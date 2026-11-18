package com.alexey.aircast

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import kotlin.concurrent.thread

/**
 * VLC Cast v0.7.0 — авто-флоу каста, отвязанный от Activity.
 *
 * Тот же флоу, что в v0.6.0 (Chrome → share → страница в фоновом
 * WebView → перехват сети + JS-скан → авто-выбор → VLC на ТВ через
 * Companion), но хостом может быть и foreground service (CastService —
 * шаринг в фоне, приложение не открывается), и MainActivity (режимы
 * fallback / pairing / tvpick из уведомлений).
 *
 * Хост получает события через Listener и решает сам, что показать:
 * сервис — уведомления, activity — экраны. Движок никогда не трогает
 * UI напрямую и не знает, кто его хостит.
 *
 * WebView создаётся с переданным контекстом: сервис — application
 * context (WebView ни к чему не присоединён, страница грузится в
 * фоне), activity — контекст activity (WebView можно пересадить в
 * видимый контейнер фолбэка через webView()).
 *
 * ГЛАВНЫЙ РИСК (честно): headless WebView внутри Service на VM
 * проверить нельзя (нет эмулятора). Если живой тест покажет, что
 * фоновый WebView не грузит страницы/не находит видео — запасной
 * план: детект оставить в прозрачной ShareReceiverActivity (она не
 * финиширует до конца каста). Движок структурирован именно так,
 * чтобы хостом мог быть и Service, и Activity: вся работа с WebView
 * — внутри движка, хост только даёт контекст и слушает события.
 */
class CastEngine(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val credStore: CredStore,
    private val listener: Listener,
    private val safeMode: Boolean = false,
) {
    interface Listener {
        /** Строка статуса (текст уведомления / подсказка на экране). */
        fun onStatus(text: String)
        /** Список найденных видео изменился (фолбэк рисует его заново). */
        fun onCandidatesChanged(candidates: List<VideoCandidate>)
        /** Команда VLC отправлена без исключения (вердикт — на экране ТВ). */
        fun onLaunched(url: String)
        /** v0.9.0: авто-следующая серия вооружена (таймер конца текущей). */
        fun onAutoNextArmed()
        /** Запуск во VLC не удался. */
        fun onLaunchFailed(msg: String)
        /** Companion не спарен — нужен PIN (один раз). */
        fun onNeedPairing(host: String, pendingUrl: String?)
        /** Найдено несколько ТВ — нужен выбор один раз. */
        fun onNeedTvPick(devices: List<AirPlayDevice>)
        /** Discovery: текущий список найденных ТВ (для экрана выбора). */
        fun onDevicesChanged(devices: List<AirPlayDevice>)
        /** Видео само не нашлось за SEARCH_TIMEOUT_MS. */
        fun onTimeout(state: TimeoutState)
        /** Спаривание Companion прошло. */
        fun onPaired()
        /** Спаривание не удалось (или PIN не введён). */
        fun onPairingFailed(msg: String)
        /** Фатальная ошибка флоу (например, WebView не создался). */
        fun onError(msg: String)
        /** v0.9.2: ошибка файлового флоу (открыть/прокси) — по
         *  умолчанию как обычная; сервис показывает её с
         *  «Поделиться логом». */
        fun onFileError(msg: String) { onError(msg) }
        /** v0.9.1: воспроизведение закончилось (таймер) и нечего
         *  запускать дальше — сервис может остановиться. */
        fun onPlaybackEnded() {}
        /** v0.9.1: размер очереди шарингов изменился. */
        fun onQueueChanged(count: Int) {}
        /** v0.9.1: Apple TV не отвечает даже после пробуждения. */
        fun onTvUnreachable() {
            onLaunchFailed(AutoCast.notifTvUnreachableText())
        }
    }

    /** Состояние для фолбэка: что грузить и что уже найдено. */
    data class TimeoutState(
        val pageUrl: String?,
        val episodeUrl: String?,
        val candidates: List<VideoCandidate>,
    )

    companion object {
        /** Сколько ждём видео с момента загрузки страницы. */
        const val SEARCH_TIMEOUT_MS = 30_000L
        /** Пауза после первого кандидата: вдруг придёт лучший (720p+). */
        const val AUTO_LAUNCH_DELAY_MS = 2_000L
        /** Сколько раз новый кандидат может отложить авто-запуск. */
        const val MAX_LAUNCH_RESCHEDULES = 3
        /** Повторный JS-скан, пока ищем видео. */
        const val RESCAN_INTERVAL_MS = 3_000L
        /** v0.9.3 (#5): общий дедлайн пробы скорости зеркал. */
        const val MIRROR_PROBE_DEADLINE_MS = 2_500L
        /** Окно авто-поиска ТВ по mDNS. */
        const val TV_DISCOVERY_MS = 6_000L
        /** Высота видимого браузера в фолбэке. */
        const val FALLBACK_BROWSER_HEIGHT_DP = 420
        /**
         * v0.9.3 (#25): мобильный UA фонового WebView — тот же, что
         * в PROXY_CHROME_UA (StreamProbe/StreamProxy). Вынесен в
         * константу, чтобы resetFlow мог вернуть его после десктопного
         * fallback-прохода (иначе следующий флоу стартовал бы с
         * десктопным UA).
         */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 16; RMX5011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        /**
         * Авто-нажатие ▶ в плеере: после авто-открытия страницы плеера
         * пробуем запустить видео сами (Kodik отдаёт manifest только
         * после play). Безопасно: если video/button нет — ничего не
         * происходит, фолбэк покажет страницу пользователю.
         * v0.8.0: селекторы по порядку — <video>, Video.js, Plyr,
         * JWPlayer, aria/title, class/id с play (раньше был только
         * video + [class*=play], и плееры с фирменной кнопкой play
         * не запускались сами).
         * v0.8.1: добавлены фирменные селекторы из документации
         * плееров: Video.js control-bar (.vjs-play-control), JWPlayer
         * display icon (.jw-display-icon-container, CSS Skin Reference),
         * DPlayer (.dplayer-play-icon, из исходников), XGPlayer
         * (.xgplayer-start, из dist), Plyr ([data-plyr="play"]),
         * ArtPlayer (.art-control-playAndPause — выведено из имени
         * контрола playAndPause и конвенции классов art-control-*,
         * в бандле литералом НЕ подтверждено).
         */
        const val AUTO_PLAY_JS =
            "(function(){try{var v=document.querySelector('video');if(v){try{var p=v.play&&v.play();}catch(e){}return 'video.play';}var sels=['.vjs-big-play-button','.vjs-play-control','.plyr__control--overlaid','[data-plyr=\"play\"]','.jw-display-icon-container','.jw-icon-playback','.dplayer-play-icon','.art-control-playAndPause','.xgplayer-start','[aria-label*=play i]','[title*=play i]','[class*=play i]','[id*=play i]'];for(var i=0;i<sels.length;i++){var b=document.querySelector(sels[i]);if(b){b.click();return 'clicked:'+sels[i];}}return 'no-video';}catch(e){return 'err:'+e;}}())"

        /**
         * v0.9.3 (#26): активация lazy-iframe — прокрутить каждый
         * iframe в видимую область и снять loading=lazy: иначе
         * iframe с видео не грузится, пока до него не доскроллили,
         * и скан его не видит. Возвращает число прокрученных iframe
         * (для лога). Один раз за загрузку страницы (onPageFinished).
         */
        const val LAZY_IFRAME_JS =
            "(function(){try{var f=document.querySelectorAll('iframe');var n=0;for(var i=0;i<f.length;i++){try{f[i].loading='eager';f[i].scrollIntoView({block:'center'});n++;}catch(e){}}return 'scrolled:'+n;}catch(e){return 'err:'+e;}}())"

        /**
         * YouTube: visitorData + hlsManifestUrl из ytInitialPlayerResponse
         * загруженной watch-страницы (если есть). visitorData нужен для
         * InnerTube VISIONOS (без него LOGIN_REQUIRED).
         */
        const val YT_INFO_JS =
            "(function(){try{var r=window.ytInitialPlayerResponse||null;var vd='';var hls='';if(r){if(r.responseContext&&r.responseContext.visitorData)vd=r.responseContext.visitorData;if(r.streamingData&&r.streamingData.hlsManifestUrl)hls=r.streamingData.hlsManifestUrl;}if(!vd){var m=document.documentElement.innerHTML.match(/\"visitorData\":\"([^\"]+)\"/);if(m)vd=m[1];}return JSON.stringify({vd:vd,hls:hls});}catch(e){return JSON.stringify({vd:'',hls:''});}})()"

        /**
         * v0.9.2: занят ли движок — чистое решение по состоянию
         * (JVM-тесты, без ТВ): занят только поиск/запуск
         * (SEARCHING_TV / LOADING_PAGE / SEARCHING_VIDEO / LAUNCHING).
         * LAUNCHED — уже НЕ занят, даже с cap-таймером 3 ч: новый
         * шаринг прерывает текущий каст и запускается сразу (как
         * в 0.9.0). Очередь — только в окно поиска/запуска.
         */
        fun isBusyState(state: CastEngine.FlowState): Boolean =
            state == CastEngine.FlowState.SEARCHING_TV ||
            state == CastEngine.FlowState.LOADING_PAGE ||
            state == CastEngine.FlowState.SEARCHING_VIDEO ||
            state == CastEngine.FlowState.LAUNCHING
    }

    /** Состояние авто-флоу (один запуск share/открытия). */
    enum class FlowState { IDLE, SEARCHING_TV, LOADING_PAGE, SEARCHING_VIDEO, NEED_PAIRING, LAUNCHING, LAUNCHED, TIMEOUT }

    private val discovery by lazy { Discovery(ctx) }
    private val devices = LinkedHashMap<String, AirPlayDevice>()
    private var selectedDevice: AirPlayDevice? = null
    private var selectedCandidate: VideoCandidate? = null
    private val candidates = ArrayList<VideoCandidate>()
    private var currentPageUrl: String? = null
    /** Страница серии (куда возвращать из фолбэка, если авто-открыли плеер). */
    private var episodePageUrl: String? = null
    private var playerIframeUrl: String? = null

    private var flowState: FlowState = FlowState.IDLE
    private var autoArmed: Boolean = false
    private var launched: Boolean = false
    private var searchDeadlineMs: Long = 0L
    private var playerAutoOpened: Boolean = false
    private var pendingLaunchUrl: String? = null
    private var pendingLaunchReferer: String? = null
    private var launchReschedules: Int = 0
    private var launchRunnable: Runnable? = null
    private var rescanRunnable: Runnable? = null
    /** v0.9.3 (#25 + хотфикс Instagram): счётчик ресканов флоу и
     *  флаги одноразовых fallback-проходов при пустом скане. */
    private var rescanCount: Int = 0
    private var directHtmlTried: Boolean = false
    private var desktopUaTried: Boolean = false
    private var tickerRunnable: Runnable? = null
    private var tvCallback: (() -> Unit)? = null
    /** v0.9.2: fallback pre-check — что делать, если mDNS тоже
     *  никого не нашёл (тогда честный onTvUnreachable с drain). */
    private var tvFallbackNone: (() -> Unit)? = null
    private var discoveryGen: Int = 0
    private var pickDiscoveryOnly: Boolean = false
    private var pendingCompanion: CompanionClient? = null
    private var webView: WebView? = null
    private var browserBuilt: Boolean = false
    private var browserFailed: String? = null
    /** v0.8.0: YouTube — videoId из shared URL, visitorData, перебор клиентов. */
    private var youTubeVideoId: String? = null
    private var youTubeVisitorData: String? = null
    private var youTubePageLoaded: Boolean = false
    private val youTubeTried = HashSet<String>()
    private var youTubeEnded: Boolean = false
    /** v0.8.1: JWPlayer v2 feed URL, уже скачанные в этом флоу. */
    private val jwFeedsTried = HashSet<String>()
    /** v0.9.3 (#24): уже скачанные oEmbed-ссылки (один раз за флоу). */
    private val oembedTried = HashSet<String>()
    /** v0.9.3 (#1): id зарегистрированных DASH-пар (снять в resetFlow). */
    private val dashIds = HashSet<String>()
    /** v0.9.2: аудио — страница аудио-сервиса (кандидаты с неё —
     *  аудио), текущий файл — аудио, отложенный запуск — аудио,
     *  уже резолвленные SoundCloud media API URL. */
    private var audioPage: Boolean = false
    private var currentFileIsAudio: Boolean = false
    private var pendingLaunchIsAudio: Boolean = false
    private val soundCloudTried = HashSet<String>()
    /** v0.9.0: авто-ретрай — упорядоченные попытки (orderedAttempts),
     *  индекс текущей; авто-следующая — ссылки <a> со страницы, дорожки
     *  субтитров, title страницы, следующий эпизод, таймер конца серии. */
    private var retryAttempts: List<VideoCandidate> = emptyList()
    /** v0.9.3 (#5): поколение doAutoLaunch — устаревший поток пробы
     *  зеркал не трогает retryAttempts. */
    private var autoLaunchGen: Int = 0
    /** v0.9.3 (#14): установка VLC уже проверена в этом флоу. */
    private var vlcChecked: Boolean = false
    /** v0.9.3.3: запуск во VLC уже идёт (Companion-коннект в полёте).
     *  Параллельные launchVlc гонялись друг с другом (лог 07:51:04:
     *  5 запусков m78 в одну секунду → EOF/Connection reset) — один
     *  коннект за раз, дубли пропускаются. Сбрасывается в resetFlow. */
    @Volatile
    private var launchInFlight: Boolean = false
    private var retryIndex: Int = 0
    private var episodeLinks: List<PageLink> = emptyList()
    private var currentSubs: List<SubTrack> = emptyList()
    private var currentPageTitle: String = ""
    private var nextEpisodeUrl: String? = null
    private var nextTimerRunnable: Runnable? = null
    private var launchedCandidate: VideoCandidate? = null
    /** v0.9.0: последний JS-скан одной строкой — для диагностики «не найдено». */
    private var lastScanDiag: String = "сканов пока не было"
    /** v0.9.1: очередь шарингов (CastQueue), память воспроизведения
     *  (озвучка/субтитры/позиция), чистое название, постер, resume,
     *  предзагрузка следующей серии, TV pre-check, файлы. */
    private val queue = CastQueue()
    private val memory = PlaybackStore(
        get = { prefs.getString(it, null) },
        put = { k, v -> prefs.edit().putString(k, v).apply() },
        remove = { prefs.edit().remove(it).apply() },
    )
    private var currentSeriesKey: String? = null
    private var currentCleanTitle: CleanTitle? = null
    private var currentTitleOverride: String? = null
    private var currentPosterUrl: String? = null
    private var posterBytes: ByteArray? = null
    private var posterDownloading: Boolean = false
    private var resumePositionSec: Long? = null
    private var firstCandidateAtMs: Long = 0L
    /** Позиция: начало просмотра (мс), страница-ключ, база resume,
     *  полная длительность (для сохранения) и оставшаяся (для таймера). */
    private var watchStartMs: Long = 0L
    private var watchPageUrl: String? = null
    private var watchResumeBaseSec: Long = 0L
    private var watchFullDurationSec: Long? = null
    private var watchDurationSec: Long? = null
    /** Предзагрузка следующей серии: режим + найденное. */
    private var preloadMode: Boolean = false
    private var preloadUrl: String? = null
    private var preloadDeadlineMs: Long = 0L
    private var preloadRunnable: Runnable? = null
    private val preloadedCandidates = ArrayList<VideoCandidate>()
    private var preloadedSubs: List<SubTrack> = emptyList()
    private var preloadedTitle: String = ""
    private var preloadedNextUrl: String? = null
    private data class PreloadedNext(
        val url: String,
        val candidates: List<VideoCandidate>,
        val subs: List<SubTrack>,
        val title: String,
        val nextUrl: String?,
    )
    private var preloadedNext: PreloadedNext? = null
    /** OpenSubtitles: дорожки из внешней базы + id VTT в прокси. */
    private var openSubsSearched: Boolean = false
    private var openSubsVttId: String? = null
    /** Видеофайл (Telegram/галерея): id источника в прокси, fd, temp-копия. */
    private var currentFileId: String? = null
    private var currentFilePfd: android.os.ParcelFileDescriptor? = null
    private var currentFileTemp: File? = null
    /** YouTube-плейлист: id видео и индекс текущего. */
    private var youTubePlaylistIds: List<String> = emptyList()
    private var youTubePlaylistIndex: Int = 0
    /** v0.8.0: локальный прокси для Referer-потоков (Sibnet, OK.ru). */
    private val streamProxy by lazy { StreamProxyServer(ctx) }
    private val uiHandler = Handler(Looper.getMainLooper())

    private fun ui(fn: () -> Unit) = uiHandler.post(fn)

    // ---------- Входы ----------

    /**
     * Share из Chrome (или открытие ссылки): прямая ссылка / страница.
     * Возвращает false, если шаринг встал в очередь (сейчас идёт
     * другое видео) — true, если запущен сразу.
     */
    fun startShare(url: String): Boolean {
        Log.add("share", "получена ссылка: ${VideoUrls.safeHost(url)}")
        if (isBusy()) {
            queue.add(QueueItem.Url(url))
            listener.onQueueChanged(queue.size)
            listener.onStatus(AutoCast.notifQueuedText(queue.size))
            Log.add("queue", "шаринг в очереди (#${queue.size}): ${VideoUrls.safeHost(url)}")
            return false
        }
        // v0.9.2: Spotify/Apple Music — DRM, честно сразу, а не
        // молчаливое «не найдено» после 30 с поиска.
        if (AudioCast.isUnsupportedMusicService(url)) {
            Log.add("share", "DRM-сервис, каст невозможен: ${VideoUrls.safeHost(url)}")
            failFlow { listener.onError("Spotify/Apple Music: потоки зашифрованы (DRM) — во VLC их не сыграть") }
            return true
        }
        if (YouTubePlaylist.isPlaylistUrl(url)) {
            startYouTubePlaylistFlow(url)
            return true
        }
        // v0.9.2: плейлист радио (.pls/.m3u) — резолвим поток.
        if (AudioCast.isRadioPlaylistUrl(url)) {
            tryRadioPlaylist(url)
            return true
        }
        when (AutoCast.classifyUrl(url)) {
            AutoCast.ShareKind.DIRECT_AUDIO -> startDirectAudioFlow(url)
            AutoCast.ShareKind.DIRECT_VIDEO -> startDirectVideoFlow(url)
            else -> startPageFlow(url)
        }
        return true
    }

    /**
     * Занят ли движок сейчас. v0.9.2: только пока идёт поиск/запуск
     * (см. isBusyState); когда видео уже запущено (LAUNCHED) —
     * свободен: новый шаринг прерывает текущий каст, а не встаёт
     * в очередь на 3 ч cap-таймера (регрессия 0.9.1).
     */
    private fun isBusy(): Boolean = isBusyState(flowState)

    /** Прямая ссылка на видео — запускать сразу, без загрузки страницы. */
    private fun startDirectVideoFlow(url: String) {
        resetFlow()
        currentSeriesKey = PlaybackMemory.seriesKey(url)
        resumePositionSec = loadResumePosition(url)
        autoArmed = true
        flowState = FlowState.SEARCHING_VIDEO
        searchDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        listener.onStatus("Прямая ссылка на видео — запускаю…")
        addCandidate(url, "ссылка из Chrome", null)
        startTicker()
    }

    /** v0.9.2: прямая ссылка на аудио — тот же флоу, метка «аудио». */
    private fun startDirectAudioFlow(url: String) {
        resetFlow()
        currentSeriesKey = PlaybackMemory.seriesKey(url)
        resumePositionSec = loadResumePosition(url)
        autoArmed = true
        flowState = FlowState.SEARCHING_VIDEO
        searchDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        listener.onStatus("Прямая ссылка на аудио — запускаю…")
        addCandidate(url, "ссылка", null, isAudio = true)
        startTicker()
    }

    /** Страница с плеером — грузим в фоновом WebView и ждём видео. */
    private fun startPageFlow(url: String) {
        resetFlow()
        // v0.9.2: страница аудио-сервиса (SoundCloud / YouTube Music /
        // Bandcamp) — кандидаты с неё считаются аудио.
        audioPage = AudioCast.isAudioPage(url)
        episodePageUrl = url
        currentSeriesKey = PlaybackMemory.seriesKey(url)
        resumePositionSec = loadResumePosition(url)
        autoArmed = true
        flowState = FlowState.LOADING_PAGE
        searchDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        listener.onStatus("Ищу видео…")
        // v0.8.0: YouTube — InnerTube не зависит от WebView (visitorData
        // сначала пробуем взять HTTP-загрузкой youtube.com, потом из
        // страницы); запускаем параллельно с загрузкой страницы.
        if (YouTube.isYouTubeUrl(url)) {
            youTubeVideoId = YouTube.extractVideoId(url)
            if (youTubeVideoId != null) tryYouTubeInnerTube()
            else Log.add("youtube", "не удалось вытащить videoId: ${VideoUrls.safeHost(url)}")
        }
        ensureBrowser()
        goToUrl(url)
        startTicker()
    }

    /** v0.9.1: запомненная позиция для этой страницы/ссылки, или null. */
    private fun loadResumePosition(pageUrl: String): Long? {
        val pos = memory.loadPosition(pageUrl) ?: return null
        if (!PlaybackMemory.isResumable(pos)) return null
        Log.add("episodes", "продолжаю с ${pos.positionSec}с (сохранено ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(pos.savedAtMs))})")
        return pos.positionSec
    }

    /** v0.9.1: запомненная озвучка для текущего сериала, или null. */
    private fun preferredDubForCurrent(): String? {
        val key = currentSeriesKey ?: return null
        return memory.preferredDub(key)
    }

    /**
     * Режим фолбэка (MainActivity из уведомления о таймауте): страница
     * грузится в видимом браузере, найденные раньше кандидаты — уже в
     * списке, авто-запуск вооружён (если видео появится — уйдёт само).
     * Дедлайна нет: пользователь на месте, ждать можно сколько угодно.
     */
    fun startFallback(pageUrl: String?, episodeUrl: String?, seedCandidates: List<VideoCandidate>) {
        resetFlow()
        episodePageUrl = episodeUrl
        autoArmed = true
        flowState = FlowState.LOADING_PAGE
        searchDeadlineMs = Long.MAX_VALUE
        listener.onStatus("Видео само не нашлось — страница ниже, нажми ▶ в плеере")
        for (c in seedCandidates) addCandidate(c.url, c.source, c.referer, c.directOnly)
        if (pageUrl != null) {
            ensureBrowser()
            goToUrl(pageUrl)
        } else if (episodeUrl != null) {
            ensureBrowser()
            goToUrl(episodeUrl)
        }
        startTicker()
    }

    /** Сброс авто-флоу: новый share / новый запуск. */
    private fun resetFlow() {
        // v0.9.3.3: зависший флаг запуска не переживает флоу.
        launchInFlight = false
        // v0.9.1: позиция текущего видео — до сброса состояния.
        saveCurrentPosition()
        watchStartMs = 0L
        watchPageUrl = null
        watchResumeBaseSec = 0L
        watchFullDurationSec = null
        watchDurationSec = null
        // v0.9.1: видеофайл прошлого флоу — fd закрыть, temp-копию удалить.
        cleanupCurrentFile()
        // v0.9.1: дорожка OpenSubtitles прошлого флоу — снять из прокси.
        openSubsVttId?.let { streamProxy.unregisterMemoryVtt(it) }
        openSubsVttId = null
        openSubsSearched = false
        // v0.9.1: предзагрузка — остановить.
        preloadMode = false
        preloadUrl = null
        preloadRunnable?.let { uiHandler.removeCallbacks(it) }
        preloadRunnable = null
        preloadedCandidates.clear()
        preloadedSubs = emptyList()
        preloadedTitle = ""
        preloadedNextUrl = null
        preloadedNext = null
        currentSeriesKey = null
        currentCleanTitle = null
        currentTitleOverride = null
        currentPosterUrl = null
        posterBytes = null
        posterDownloading = false
        resumePositionSec = null
        firstCandidateAtMs = 0L
        youTubePlaylistIds = emptyList()
        youTubePlaylistIndex = 0
        candidates.clear()
        selectedCandidate = null
        launched = false
        launchReschedules = 0
        pendingLaunchUrl = null
        pendingLaunchReferer = null
        playerAutoOpened = false
        episodePageUrl = null
        playerIframeUrl = null
        youTubeVideoId = null
        youTubeVisitorData = null
        youTubePageLoaded = false
        youTubeEnded = false
        jwFeedsTried.clear()
        oembedTried.clear()
        // v0.9.3 (#1): снять регистрации DASH-пар прошлого флоу.
        for (id in dashIds) streamProxy.unregisterDash(id)
        dashIds.clear()
        // v0.9.2: аудио-флаги флоу.
        audioPage = false
        currentFileIsAudio = false
        pendingLaunchIsAudio = false
        soundCloudTried.clear()
        // v0.9.2: fallback pre-check прошлого флоу — снять.
        tvFallbackNone = null
        // v0.9.0: авто-следующая серия — таймер отменяется при любом
        // новом флоу (новый share, следующая серия, фолбэк).
        cancelNextTimer()
        retryAttempts = emptyList()
        retryIndex = 0
        autoLaunchGen++
        vlcChecked = false
        episodeLinks = emptyList()
        currentSubs = emptyList()
        currentPageTitle = ""
        nextEpisodeUrl = null
        launchedCandidate = null
        lastScanDiag = "сканов пока не было"
        launchRunnable?.let { uiHandler.removeCallbacks(it) }
        launchRunnable = null
        rescanRunnable?.let { uiHandler.removeCallbacks(it) }
        rescanRunnable = null
        // v0.9.3 (#25 + хотфикс Instagram): счётчик и флаги
        // fallback-проходов — сбросить; WebView вернуть на мобильный
        // UA, иначе следующий флоу стартует с десктопным.
        rescanCount = 0
        directHtmlTried = false
        if (desktopUaTried) {
            desktopUaTried = false
            try { webView?.settings?.userAgentString = MOBILE_UA } catch (ignored: Exception) {}
        }
        flowState = FlowState.IDLE
        listener.onCandidatesChanged(emptyList())
    }

    /** WebView движка — хост может пересадить его в видимый контейнер. */
    fun webView(): WebView? = webView

    fun candidates(): List<VideoCandidate> = candidates.toList()
    fun selectedCandidate(): VideoCandidate? = selectedCandidate
    fun currentPageUrl(): String? = currentPageUrl
    fun episodePageUrl(): String? = episodePageUrl
    fun hasPendingTvCallback(): Boolean = tvCallback != null

    /** v0.9.2: текущий флоу — аудио (для текста уведомления об успехе). */
    fun isAudioFlow(): Boolean = launchedCandidate?.isAudio == true || currentFileIsAudio

    /** v0.9.1: чистое название текущего видео для уведомления
     *  (TitleCleaner; если вытащить не удалось — host страницы). */
    fun currentTitle(): String {
        currentTitleOverride?.let { return it }
        val parsed = currentCleanTitle
        if (parsed?.display?.isNotBlank() == true) return parsed.display!!
        val page = currentPageUrl ?: episodePageUrl
        return if (page != null) VideoUrls.safeHost(page) else ""
    }

    /** v0.9.1: скачанный постер (og:image) для BigPictureStyle, или null. */
    fun posterBytes(): ByteArray? = posterBytes

    /** v0.9.1: размер очереди шарингов. */
    fun queueSize(): Int = queue.size

    /** v0.9.1: состояние для уведомления «идёт». */
    data class WatchingInfo(
        val title: String,
        val queueCount: Int,
        val hasNext: Boolean,
        val hasSubs: Boolean,
        val subsEnabled: Boolean,
    )

    fun watchingInfo(): WatchingInfo {
        val subsOn = subsEnabledForCurrent()
        return WatchingInfo(
            title = currentTitle(),
            queueCount = queue.size,
            hasNext = nextEpisodeUrl != null || preloadedNext != null,
            hasSubs = currentSubs.isNotEmpty(),
            subsEnabled = subsOn,
        )
    }

    /** v0.9.1: включены ли сейчас субтитры для этого сериала. */
    fun subsEnabledForCurrent(): Boolean {
        val key = currentSeriesKey ?: return currentSubs.isNotEmpty()
        return memory.subsPref(key)?.enabled ?: currentSubs.isNotEmpty()
    }

    /** v0.9.1: переключить субтитры для текущего сериала (действие
     *  в уведомлении). Действует со следующего запуска; возвращает
     *  новое состояние. */
    fun toggleSubsForCurrentSeries(): Boolean {
        val key = currentSeriesKey ?: return false
        val next = memory.toggleSubs(key, currentSubs)
        Log.add("subs", "субтитры для сериала: ${if (next) "вкл" else "выкл"} (со следующего запуска)")
        return next
    }

    /** «▶ На ТВ» в фолбэке: принудительный запуск лучшего кандидата. */
    fun launchBestNow() {
        val best = selectedCandidate ?: AutoCast.pickBest(candidates)
        if (best == null) {
            listener.onStatus("Видео пока не найдено — подожди или нажми ▶ в плеере")
            return
        }
        doAutoLaunch(force = true)
    }

    /** «← Назад к странице серии» в фолбэке. */
    fun goToEpisodePage() {
        val ep = episodePageUrl
        if (ep != null) goToUrl(ep) else listener.onStatus("Страница серии неизвестна")
    }

    // ---------- Дедлайн поиска видео ----------

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                try { checkTimeout() } catch (ignored: Exception) { }
                tickerRunnable = this
                uiHandler.postDelayed(this, 1000)
            }
        }
        tickerRunnable = r
        uiHandler.postDelayed(r, 1000)
    }

    private fun stopTicker() {
        tickerRunnable?.let { uiHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun checkTimeout() {
        if (!autoArmed || launched) return
        if (flowState != FlowState.LOADING_PAGE && flowState != FlowState.SEARCHING_VIDEO) return
        if (System.currentTimeMillis() < searchDeadlineMs) return
        stopTicker()
        Log.add("share", "таймаут: видео не нашлось за 30 с (${candidates.size} канд.)")
        // v0.9.0: диагностика «не найдено» — в лог, чтобы по кнопке
        // «Поделиться логом» было что разбирать без лишнего мусора.
        Log.add("diag", "page=${currentPageUrl ?: episodePageUrl ?: "?"}")
        if (currentPageTitle.isNotBlank()) Log.add("diag", "title=${currentPageTitle.take(120)}")
        Log.add("diag", "scan: $lastScanDiag")
        Log.add("diag", "candidates=${candidates.size}")
        // v0.9.2: IDLE + drain, а не зависший TIMEOUT без очереди.
        failFlow { listener.onTimeout(TimeoutState(currentPageUrl, episodePageUrl, candidates.toList())) }
    }

    // ---------- Браузер (фоновый WebView + перехват сети + JS-скан) ----------

    private fun ensureBrowser() {
        if (browserBuilt) return
        if (safeMode) return // в безопасном режиме WebView не создаётся вообще
        if (browserFailed != null) return
        try {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = MOBILE_UA
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (VideoUrls.isVideoUrl(url)) addCandidate(url, "перехват сети", request.requestHeaders["Referer"])
                        // v0.9.2: аудио в трафике (SoundCloud media API,
                        // Bandcamp mp3, прямые аудио) — отдельным кандидатом.
                        else if (AudioCast.isAudioUrl(url)) addCandidate(url, "перехват сети", request.requestHeaders["Referer"], isAudio = true)
                        return null
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        currentPageUrl = url
                        prefs.edit().putString("lastUrl", url).apply()
                        // v0.9.3 (#17): куки — на диск сразу после
                        // загрузки страницы: вход через fallback-браузер
                        // сохраняется (без flush() CookieManager держит
                        // куки только в памяти до смерти процесса).
                        try { CookieManager.getInstance().flush() } catch (ignored: Exception) {}
                        // v0.8.0: авто-play на ЛЮБОЙ загруженной странице,
                        // пока ищем видео (раньше — только на авто-открытой
                        // странице плеера): inline-плееры на странице серии
                        // (Playerjs/JWPlayer/Video.js) тоже должны стартовать
                        // сами. Если video/button нет — JS ничего не делает.
                        // v0.9.1: в режиме предзагрузки — тоже (иначе
                        // manifest следующей серии не перехватится).
                        if (autoArmed && (!launched || preloadMode) &&
                            (flowState == FlowState.LOADING_PAGE || flowState == FlowState.SEARCHING_VIDEO || preloadMode)
                        ) {
                            try {
                                view.evaluateJavascript(AUTO_PLAY_JS) { res ->
                                    Log.add("browser", "авто-play: $res")
                                }
                            } catch (e: Exception) {
                                Log.add("browser", "авто-play error: ${e.message}")
                            }
                        }
                        // v0.9.3 (#26): lazy-iframe — прокрутить iframe
                        // в видимую область (loading=lazy /
                        // IntersectionObserver), затем — обычный скан.
                        // Скан — в колбэке, чтобы iframe успели начать
                        // грузиться; ошибка JS — скан сразу.
                        if (autoArmed && (!launched || preloadMode) &&
                            (flowState == FlowState.LOADING_PAGE || flowState == FlowState.SEARCHING_VIDEO || preloadMode)
                        ) {
                            try {
                                view.evaluateJavascript(LAZY_IFRAME_JS) { res ->
                                    Log.add("browser", "lazy-iframe: $res")
                                    scanPageJs()
                                }
                            } catch (e: Exception) {
                                Log.add("browser", "lazy-iframe error: ${e.message}")
                                scanPageJs()
                            }
                        } else {
                            scanPageJs()
                        }
                        // v0.8.0: YouTube — visitorData + hls из страницы.
                        if (youTubeVideoId != null) {
                            youTubePageLoaded = true
                            extractYouTubeInfo(view)
                        }
                    }
                }
            }
            webView = wv
            browserBuilt = true
            Log.add("browser", "WebView создан")
        } catch (e: Throwable) {
            browserFailed = "${e.javaClass.simpleName}: ${e.message}"
            Log.add("browser", "WebView НЕ создан ($browserFailed)")
            // v0.9.2: IDLE + drain (goToUrl следом молчит — см. ниже).
            failFlow { listener.onError("Браузер (WebView) не запустился на этом телефоне: $browserFailed") }
        }
    }

    private fun goToUrl(raw: String) {
        val wv = webView
        if (wv == null) {
            // v0.9.2: если WebView не создался — ошибка уже отдана
            // в ensureBrowser; второй раз не шумим и не дреним дважды.
            if (browserFailed == null) failFlow { listener.onError("Браузер недоступен — поделись прямой ссылкой на видео") }
            return
        }
        var url = raw.trim()
        if (!url.startsWith("http")) url = "https://$url"
        currentPageUrl = url
        wv.loadUrl(url)
        Log.add("browser", "переход: $url")
    }

    private fun scanPageJs() {
        val wv = webView ?: return
        try {
            wv.evaluateJavascript(VideoUrls.SCAN_JS) { json ->
                val result = try {
                    VideoUrls.parseScanResult(json)
                } catch (e: Exception) {
                    Log.add("browser", "JS scan parse error: ${e.message} (raw=${json?.take(200)})")
                    ScanResult(emptyList(), emptyList())
                }
                for (v in result.videos) {
                    addCandidate(v.src, "JS: <video>${if (v.w > 0) " ${v.w}×${v.h}" else ""}", currentPageUrl)
                }
                // v0.9.2: теги <audio> — аудио-кандидаты (SoundCloud/
                // Bandcamp/радио-страницы без <video>).
                for (a in result.audios) {
                    addCandidate(a.src, "JS: <audio>", currentPageUrl, isAudio = true)
                }
                // v0.8.0: ссылки из скриптов страницы (конфиги плееров) и
                // относительные пути (JWPlayer src) — без нажатия play.
                // v0.8.1: Playerjs-списки качеств/аудио ([480p]url1,[720p]url2,
                // {Fr}url;{En}url, "url1 or url2") режутся на отдельные URL,
                // относительные пути резолвятся против страницы
                // (VideoUrls.resolveScriptUrl) — раньше они терялись.
                // v0.9.1: метка {Lang} сохраняется в кандидата (audioLabel)
                // для запоминания озвучки.
                for (raw in result.urls) {
                    for (part in VideoUrls.expandPlayerjsListWithLabels(raw)) {
                        val abs = VideoUrls.resolveScriptUrl(currentPageUrl, part.url) ?: continue
                        addCandidate(abs, "JS: скрипт", currentPageUrl, audioLabel = part.audioLabel)
                    }
                }
                val page = currentPageUrl
                if (page != null) {
                    for (rel in result.rel) {
                        for (part in VideoUrls.expandPlayerjsListWithLabels(rel)) {
                            addCandidate(VideoUrls.resolveUrl(page, part.url), "JS: скрипт", page, audioLabel = part.audioLabel)
                        }
                    }
                }
                // v0.9.0: ссылки <a> (список серий), дорожки субтитров
                // (<track> + .vtt/.srt/.ass в скриптах) и title — для
                // авто-следующей серии, субтитров и диагностики «не найдено».
                // v0.9.1: в режиме предзагрузки — то же, но в отдельный
                // бакет (следующая серия), а title/постер/субтитры
                // текущей — в текущий флоу + поиск OpenSubtitles.
                if (result.pageTitle.isNotBlank()) {
                    if (preloadMode) {
                        preloadedTitle = result.pageTitle
                    } else {
                        currentPageTitle = result.pageTitle
                        currentCleanTitle = TitleCleaner.parse(result.pageTitle, currentPageUrl ?: episodePageUrl ?: "")
                        searchOpenSubtitlesIfNeeded()
                    }
                }
                if (result.poster.isNotBlank() && !preloadMode) {
                    val absPoster = if (page != null) VideoUrls.resolveScriptUrl(page, result.poster) ?: result.poster else result.poster
                    if (absPoster != currentPosterUrl) {
                        currentPosterUrl = absPoster
                        maybeDownloadPoster(absPoster)
                    }
                }
                if (result.subs.isNotEmpty()) {
                    if (preloadMode) {
                        val merged = (preloadedSubs + result.subs).distinctBy { it.url }
                        if (merged.size != preloadedSubs.size) preloadedSubs = merged
                    } else {
                        val merged = (currentSubs + result.subs).distinctBy { it.url }
                        if (merged.size != currentSubs.size) {
                            currentSubs = merged
                            Log.add("subs", "дорожек найдено: ${currentSubs.size}")
                        }
                    }
                }
                if (result.links.isNotEmpty()) {
                    if (preloadMode) {
                        val pu = preloadUrl
                        if (pu != null) {
                            val nxt = EpisodeList.parse(pu, result.links)?.nextUrl
                            if (nxt != null) preloadedNextUrl = nxt
                        }
                    } else {
                        episodeLinks = result.links
                        resolveNextEpisode()
                    }
                }
                // v0.9.3 (#23): JSON-LD VideoObject — contentUrl/embedUrl.
                for (block in result.jsonld) {
                    val videoUrl = VideoUrls.parseJsonLdVideoObject(block) ?: continue
                    val abs = VideoUrls.resolveScriptUrl(currentPageUrl, videoUrl) ?: continue
                    addCandidate(abs, "JS: JSON-LD", currentPageUrl)
                }
                // v0.9.3 (#24): oEmbed — JSON по link rel=alternate
                // (скачивается отдельно, один раз за флоу на ссылку).
                for (href in result.oembed) tryOembed(href)
                // v0.9.0: последний скан одной строкой — в лог при таймауте.
                if (!preloadMode) {
                    lastScanDiag = "videos=${result.videos.size} urls=${result.urls.size} " +
                        "rel=${result.rel.size} jwfeeds=${result.jwFeeds.size} " +
                        "iframes=${result.iframes.size} links=${result.links.size} " +
                        "subs=${result.subs.size} jsonld=${result.jsonld.size} " +
                        "oembed=${result.oembed.size} " +
                        "iframeSrcs=[${result.iframes.take(5).joinToString(", ") { VideoUrls.safeHost(it) }}]"
                }
                // v0.8.1: JWPlayer v2 feed (cdn.jwplayer.com/v2/playlists|media)
                // — фид скачивается отдельно, файлы добавляются в кандидаты.
                for (feed in result.jwFeeds) tryJwplayerFeed(feed)
                for (src in result.iframes) {
                    if (VideoUrls.isPlayerIframe(src)) {
                        if (playerIframeUrl != src) {
                            playerIframeUrl = src
                            Log.add("browser", "найден плеер iframe: ${VideoUrls.safeHost(src)}")
                        }
                        // v0.1.3 (Failure A): страница плеера — НЕ видео.
                        // В кандидаты она НЕ добавляется.
                    } else if (VideoUrls.isVideoUrl(src)) {
                        addCandidate(src, "JS: iframe", currentPageUrl)
                    }
                }
                // Плеер найден, видео пока нет — открываем страницу
                // плеера сами (раньше это делал пользователь кнопкой
                // «Открыть плеер на телефоне»). На ней авто-play JS
                // попробует нажать ▶, и manifest перехватится из сети.
                // v0.9.1: в режиме предзагрузки — тоже (playerAutoOpened
                // сбрасывается в startPreload).
                if (autoArmed && (!launched || preloadMode) && playerIframeUrl != null && !playerAutoOpened &&
                    (flowState == FlowState.LOADING_PAGE || flowState == FlowState.SEARCHING_VIDEO || preloadMode)
                ) {
                    playerAutoOpened = true
                    Log.add("browser", "открываю плеер автоматически: ${VideoUrls.safeHost(playerIframeUrl!!)}")
                    webView?.loadUrl(playerIframeUrl!!)
                }
                scheduleRescan()
            }
        } catch (e: Exception) {
            Log.add("browser", "evaluateJavascript error: ${e.message}")
        }
    }

    // ---------- YouTube: InnerTube (VISIONOS → ANDROID) ----------

    /** visitorData + hls из загруженной watch-страницы (YT_INFO_JS). */
    private fun extractYouTubeInfo(view: WebView) {
        try {
            view.evaluateJavascript(YT_INFO_JS) { raw ->
                try {
                    val json = VideoUrls.decodeJsResult(raw) ?: return@evaluateJavascript
                    YouTube.parseVisitorData(json)?.let {
                        if (youTubeVisitorData != it) {
                            youTubeVisitorData = it
                            Log.add("youtube", "visitorData из страницы")
                        }
                    }
                    YouTube.parsePageHls(json)?.let {
                        addCandidate(it, "YouTube (страница)", currentPageUrl)
                    }
                    // Страница загружена — если InnerTube ещё не дал
                    // потока, добиваем перебор (с visitorData или без).
                    tryYouTubeInnerTube()
                } catch (e: Exception) {
                    Log.add("youtube", "YT_INFO parse error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.add("youtube", "YT_INFO error: ${e.message}")
        }
    }

    /**
     * InnerTube: VISIONOS (hlsManifestUrl, без cipher/n/PO) → ANDROID
     * (itag 18, 360p). Каждая комбинация клиент×visitorData — один раз.
     * Запускается параллельно с загрузкой страницы и повторно при
     * появлении visitorData. Честный конец: если оба клиента не дали
     * потока, страница загружена и кандидатов нет — "YouTube: нет
     * прямого потока" (без выдуманных обходов и decipherer'а).
     */
    private fun tryYouTubeInnerTube() {
        val videoId = youTubeVideoId ?: return
        if (youTubeEnded || launched) return
        thread {
            try {
                // visitorData: сначала из страницы, иначе HTTP-загрузка youtube.com.
                var vd = youTubeVisitorData
                if (vd == null) {
                    vd = fetchYouTubeVisitorData()
                    if (vd != null) {
                        youTubeVisitorData = vd
                        Log.add("youtube", "visitorData из HTTP-загрузки youtube.com")
                    }
                }
                for (client in listOf(YouTube.Client.VISIONOS, YouTube.Client.ANDROID)) {
                    if (youTubeEnded || launched) return@thread
                    val key = client.name + ":" + if (vd != null) "vd" else "novd"
                    val fresh = synchronized(youTubeTried) { youTubeTried.add(key) }
                    if (!fresh) continue
                    val body = postInnerTube(client, videoId, vd)
                    if (body == null) {
                        Log.add("youtube", "${client.name}: POST не удался")
                        continue
                    }
                    val parsed = YouTube.parsePlayerResponse(body)
                    Log.add("youtube", "${client.name}: playability=${parsed.playability} hls=${parsed.hlsUrl != null} muxed=${parsed.muxed.size} onlyCipher=${parsed.hasOnlyCipher}")
                    val pick = YouTube.pickStream(parsed)
                    if (pick != null) {
                        val (streamUrl, kind) = pick
                        Log.add("youtube", "${client.name}: поток найден ($kind)")
                        // v0.9.0: lengthSeconds — длительность для таймера
                        // авто-следующей серии (если это плейлист/сериал).
                        ui { addCandidate(streamUrl, "YouTube InnerTube (${client.name})", currentPageUrl ?: episodePageUrl, durationSec = parsed.lengthSeconds) }
                        return@thread
                    }
                    // v0.9.2: YouTube Music — muxed/HLS нет, только
                    // adaptive аудио (itag 141/251/140...): берём лучший
                    // аудио-only формат тем же InnerTube-ответом.
                    val audioPick = YouTube.pickAudioStream(parsed)
                    if (audioPick != null) {
                        val (streamUrl, kind) = audioPick
                        Log.add("youtube", "${client.name}: аудио-поток найден ($kind)")
                        ui { addCandidate(streamUrl, "YouTube InnerTube (${client.name})", currentPageUrl ?: episodePageUrl, durationSec = parsed.lengthSeconds, isAudio = true) }
                        return@thread
                    }
                    // v0.9.3 (#1): DASH из раздельных дорожек — когда
                    // ни HLS, ни muxed, ни аудио-only не дали потока,
                    // но есть video-only + audio-only adaptive: прокси
                    // синтезирует локальный .mpd (пара — в registerDash).
                    val dashPair = YouTube.pickDashPair(parsed)
                    if (dashPair != null) {
                        Log.add("youtube", "${client.name}: DASH-пара найдена (${dashPair.video.height ?: "?"}p${dashPair.video.fps?.let { "@$it" } ?: ""} + аудио itag ${dashPair.audio.itag})")
                        ui { addDashCandidate(dashPair, parsed.lengthSeconds) }
                        return@thread
                    }
                }
                // Оба клиента пусто — честно, только после загрузки страницы
                // (иначе WebView ещё может дать visitorData/hls).
                ui {
                    if (!youTubeEnded && !launched && candidates.isEmpty() && youTubePageLoaded) {
                        youTubeEnded = true
                        Log.add("youtube", "нет прямого потока (оба клиента)")
                        failFlow { listener.onError("YouTube: нет прямого потока") }
                    }
                }
            } catch (e: Exception) {
                Log.add("youtube", "InnerTube error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * v0.9.3 (#1): DASH-кандидат из пары YouTube adaptive (video-only +
     * audio-only). Пара регистрируется в прокси (registerDash), VLC
     * получает локальный /dash.mpd. Качество/fps — из видео-формата
     * (в URL их нет — addCandidate берёт qualityOverride/fps явно).
     * Прокси не запустился или треки не строятся — пара пропускается
     * честно (без кандидата), флоу идёт дальше как без неё.
     */
    private fun addDashCandidate(pair: YouTube.DashPair, durationSec: Long?) {
        val videoTrack = YouTube.videoDashTrack(pair.video) ?: run {
            Log.add("youtube", "DASH: видео-трек не строится — пара пропущена")
            return
        }
        val audioTrack = YouTube.audioDashTrack(pair.audio) ?: run {
            Log.add("youtube", "DASH: аудио-трек не строится — пара пропущена")
            return
        }
        val base = streamProxy.ensureStarted()
        if (base == null || streamProxy.phoneIp == null) {
            Log.add("youtube", "DASH: прокси не запустился — пара пропущена")
            return
        }
        val dashId = "dash-${System.currentTimeMillis()}"
        streamProxy.registerDash(dashId, videoTrack, audioTrack, durationSec)
        dashIds.add(dashId)
        val url = StreamProxy.proxyDashUrl(streamProxy.phoneIp!!, streamProxy.port, dashId)
        val quality = pair.video.height?.let { "${it}p" }
        addCandidate(url, "YouTube DASH", currentPageUrl ?: episodePageUrl, durationSec = durationSec, fps = pair.video.fps, qualityOverride = quality)
        Log.add("youtube", "DASH-кандидат: ${quality ?: "?"} (id=$dashId)")
    }

    /** GET https://www.youtube.com/ → visitorData из HTML (bootstrap). */
    private fun fetchYouTubeVisitorData(): String? {
        return try {
            val c = java.net.URL("https://www.youtube.com/").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", YouTube.VISIONOS_USER_AGENT)
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (c.responseCode !in 200..299) return null
            val html = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            YouTube.parseVisitorData(html)
        } catch (e: Exception) {
            Log.add("youtube", "visitorData fetch error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** POST youtubei/v1/player. Возвращает тело ответа или null. */
    private fun postInnerTube(client: YouTube.Client, videoId: String, visitorData: String?): String? {
        return try {
            val c = java.net.URL(YouTube.PLAYER_URL).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            for ((k, v) in YouTube.playerRequestHeaders(client, visitorData)) c.setRequestProperty(k, v)
            val body = YouTube.playerRequestJson(client, videoId, visitorData).toByteArray(Charsets.UTF_8)
            c.outputStream.use { it.write(body) }
            if (c.responseCode !in 200..299) {
                Log.add("youtube", "${client.name}: HTTP ${c.responseCode}")
                return null
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.add("youtube", "${client.name}: POST error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * v0.8.1: JWPlayer v2 feed (docs.jwplayer.com, "Playlists"):
     * страница может содержать только setup({playlist: "<feed URL>"}) —
     * сам файл видео в ней не виден. Скачиваем фид один раз за флоу
     * (10 с таймаут), парсим playlist[].file / sources[].file
     * (VideoUrls.parseJwplayerFeed) и добавляем в кандидаты.
     * Ошибка — просто ничего: честно, без выдуманного фолбэка.
     */
    private fun tryJwplayerFeed(feedUrl: String) {
        val fresh = synchronized(jwFeedsTried) { jwFeedsTried.add(feedUrl) }
        if (!fresh || launched) return
        thread {
            try {
                val c = java.net.URL(feedUrl).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16; RMX5011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                c.setRequestProperty("Accept", "application/json")
                if (c.responseCode !in 200..299) {
                    Log.add("browser", "JWPlayer feed HTTP ${c.responseCode}: ${VideoUrls.safeHost(feedUrl)}")
                    return@thread
                }
                val json = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val files = VideoUrls.parseJwplayerFeed(json)
                Log.add("browser", "JWPlayer feed: ${files.size} файлов")
                ui { for (u in files) addCandidate(u, "JWPlayer feed", currentPageUrl) }
            } catch (e: Exception) {
                Log.add("browser", "JWPlayer feed error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * v0.9.3 (#24): oEmbed — скачать JSON по ссылке из страницы
     * (link rel=alternate type=application/json+oembed), вытащить
     * видео-URL (VideoUrls.parseOembedVideoUrl) в кандидаты. Один
     * раз за флоу на ссылку. XML oEmbed — вне скопа (только JSON).
     */
    private fun tryOembed(href: String) {
        val fresh = synchronized(oembedTried) { oembedTried.add(href) }
        if (!fresh || launched) return
        val page = currentPageUrl ?: episodePageUrl ?: return
        val abs = VideoUrls.resolveScriptUrl(page, href) ?: run {
            Log.add("browser", "oEmbed: не резолвится href: $href")
            return
        }
        Log.add("browser", "oEmbed: $abs")
        thread {
            val json = try {
                StreamProbe.fetchText(abs, page, pageCookies(page), 256 * 1024, 8000)
            } catch (e: Exception) {
                Log.add("browser", "oEmbed error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (json == null) {
                Log.add("browser", "oEmbed: пусто (HTTP не 2xx / таймаут)")
                return@thread
            }
            val videoUrl = VideoUrls.parseOembedVideoUrl(json)
            if (videoUrl == null) {
                Log.add("browser", "oEmbed: видео-URL нет")
                return@thread
            }
            Log.add("browser", "oEmbed: видео: ${VideoUrls.safeHost(videoUrl)}")
            ui {
                if (!autoArmed || launched) return@ui
                val absVideo = VideoUrls.resolveScriptUrl(page, videoUrl) ?: return@ui
                addCandidate(absVideo, "JS: oEmbed", page)
            }
        }
    }

    /**
     * v0.9.2: SoundCloud media API — GET .../stream/hls?client_id=…
     * отдаёт JSON {"url": "<реальный плейлист .m3u8>"}. client_id —
     * из запроса самого плеера (перехват сети / JS-скан), свой не
     * нужен. Ошибка — просто ничего (честно, без выдуманного фолбэка).
     */
    private fun trySoundCloudMedia(apiUrl: String) {
        thread {
            try {
                val json = StreamProbe.fetchText(apiUrl, null, null, 64 * 1024, 10_000) ?: return@thread
                val streamUrl = AudioCast.extractSoundCloudStreamUrl(json) ?: run {
                    Log.add("audio", "SoundCloud: в ответе media API нет url: ${VideoUrls.safeHost(apiUrl)}")
                    return@thread
                }
                Log.add("audio", "SoundCloud: поток из media API: ${VideoUrls.safeHost(streamUrl)}")
                ui { addCandidate(streamUrl, "SoundCloud", currentPageUrl ?: episodePageUrl, isAudio = true) }
            } catch (e: Exception) {
                Log.add("audio", "SoundCloud media error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * v0.9.2: плейлист радио (.pls/.m3u) — текстовый файл со ссылкой
     * на поток; резолвим первую ссылку и кастим её как аудио.
     * Потока в плейлисте нет — честная ошибка, а не таймаут.
     */
    private fun tryRadioPlaylist(url: String) {
        listener.onStatus("Плейлист радио — ищу поток…")
        thread {
            val text = StreamProbe.fetchText(url, null, null, 64 * 1024, 10_000)
            val stream = text?.let { AudioCast.parseRadioPlaylist(it) }
            ui {
                if (stream == null) {
                    Log.add("audio", "в плейлисте радио нет потока: ${VideoUrls.safeHost(url)}")
                    failFlow { listener.onError("В плейлисте радио нет ссылки на поток") }
                    return@ui
                }
                Log.add("audio", "радио: поток из плейлиста: ${VideoUrls.safeHost(stream)}")
                startDirectAudioFlow(stream)
            }
        }
    }

    /** Повторный JS-скан, пока ищем видео и не вышел дедлайн. */
    private fun scheduleRescan() {
        // v0.9.1: в режиме предзагрузки — сканы до дедлайна 30 с.
        if (preloadMode) {
            if (System.currentTimeMillis() > preloadDeadlineMs) {
                finishPreload()
                return
            }
            rescanRunnable?.let { uiHandler.removeCallbacks(it) }
            val r = Runnable { scanPageJs() }
            rescanRunnable = r
            uiHandler.postDelayed(r, RESCAN_INTERVAL_MS)
            return
        }
        if (!autoArmed || launched) return
        if (flowState != FlowState.LOADING_PAGE && flowState != FlowState.SEARCHING_VIDEO) return
        rescanCount++
        // v0.9.3 (хотфикс Instagram): WebView-скан пуст — прямой
        // HTTP-fetch HTML с десктопным UA, без WebView. Instagram
        // голому WebView отдаёт страницу входа без og:video, а
        // десктопному запросу — страницу поста с og:video/video_url.
        // Для instagram.com — в первую очередь (уже на 1-м рескане,
        // ~3 с), для остальных — на 2-м (~6 с). Один раз за флоу.
        if (candidates.isEmpty() && !directHtmlTried) {
            val page = currentPageUrl ?: episodePageUrl ?: ""
            val fetchAt = if (page.contains("instagram.com")) 1 else 2
            if (rescanCount >= fetchAt) {
                directHtmlTried = true
                tryDirectHtmlFetch()
            }
        }
        // v0.9.3 (#25): десктопный UA как fallback-проход в WebView —
        // если мобильный UA видео не дал, перезагрузить ту же страницу
        // с десктопным UA. После прямого fetch, один раз за флоу;
        // дедлайн поиска (30 с) не трогаем.
        if (candidates.isEmpty() && !desktopUaTried && rescanCount >= 3) {
            desktopUaTried = true
            switchToDesktopUaAndReload()
        }
        rescanRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { scanPageJs() }
        rescanRunnable = r
        uiHandler.postDelayed(r, RESCAN_INTERVAL_MS)
    }

    /**
     * v0.9.3 (хотфикс Instagram): прямой HTTP-fetch HTML страницы с
     * десктопным UA (StreamProbe.fetchText, без WebView) и парсинг
     * og:video / og:video:secure_url / twitter:player:stream /
     * "video_url" из сырого HTML теми же паттернами, что в SCAN_JS
     * (VideoUrls.extractMetaVideoUrls). Куки страницы — как в
     * pre-flight (если пользователь залогинен через fallback-браузер,
     * Instagram отдаст пост, а не вход). Честно: если и тут страница
     * входа (нет og:video) — ничего не найдено, обычный таймаут.
     */
    private fun tryDirectHtmlFetch() {
        val page = currentPageUrl ?: episodePageUrl ?: return
        Log.add("browser", "прямой fetch HTML с десктопным UA: ${VideoUrls.safeHost(page)}")
        thread {
            val html = try {
                StreamProbe.fetchText(page, null, pageCookies(page), 2 * 1024 * 1024, 8000, DESKTOP_UA)
            } catch (e: Exception) {
                Log.add("browser", "прямой fetch error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (html == null) {
                Log.add("browser", "прямой fetch: пусто (HTTP не 2xx / таймаут)")
                return@thread
            }
            val urls = VideoUrls.extractMetaVideoUrls(html)
            Log.add("browser", "прямой fetch: og:video/video_url — ${urls.size} (HTML ${html.length} Б)")
            ui {
                if (!autoArmed || launched) return@ui
                for (u in urls) {
                    val abs = VideoUrls.resolveScriptUrl(page, u) ?: continue
                    addCandidate(abs, "HTML: og:video", page)
                }
            }
        }
    }

    /**
     * v0.9.3 (#25): перезагрузить текущую страницу в том же WebView
     * с десктопным UA. onPageFinished → авто-play → scanPageJs, как
     * при обычной загрузке; дедлайн поиска не сбрасывается.
     */
    private fun switchToDesktopUaAndReload() {
        val wv = webView ?: return
        val page = currentPageUrl ?: episodePageUrl ?: return
        Log.add("browser", "десктопный UA: перезагружаю страницу: ${VideoUrls.safeHost(page)}")
        listener.onStatus("Пробую десктопную версию страницы…")
        try {
            wv.settings.userAgentString = DESKTOP_UA
            wv.loadUrl(page)
        } catch (e: Exception) {
            Log.add("browser", "desktop UA reload error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Synchronized
    private fun addCandidate(url: String, source: String, referer: String?, directOnly: Boolean = false, durationSec: Long? = null, audioLabel: String? = null, isAudio: Boolean = false, fps: Int? = null, qualityOverride: String? = null) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
        // v0.9.2: SoundCloud media API — не поток, а JSON со ссылкой:
        // резолвим отдельно (один раз за флоу), сам API-URL в
        // кандидаты не идёт.
        if (AudioCast.isSoundCloudMediaUrl(url)) {
            val fresh = synchronized(soundCloudTried) { soundCloudTried.add(url) }
            if (fresh) trySoundCloudMedia(url)
            return
        }
        // v0.8.0: перехват сети на googlevideo ловит только DASH-сегменты
        // (videoplayback без itag 18/22): одиночный сегмент в VLC не
        // играет. Прогрессивные ссылки берутся только из InnerTube
        // (источник "YouTube InnerTube") или из прямого шаринга.
        if (source == "перехват сети" && url.toLowerCase().contains("googlevideo.com")) {
            Log.add("browser", "пропущен сегмент googlevideo (не одиночный поток): ${VideoUrls.safeHost(url)}")
            return
        }
        if (VideoUrls.isPlaceholder(url)) {
            Log.add("browser", "пропущен placeholder (не настоящее видео): ${VideoUrls.safeHost(url)}")
            return
        }
        // v0.9.3.3 (Instagram: кастится только звук): DASH-аудио по
        // efg — не видео-кандидат вообще (кроме явного аудио-флоу:
        // isAudio/audioPage — там аудио и должно быть аудио).
        // Декодированный vencode_tag — в лог для Instagram-кандидатов,
        // чтобы следующий прогон было видно сразу.
        val efgTag = VideoUrls.decodeEfgTag(url)
        if (efgTag != null && (url.contains("cdninstagram.com") || url.contains("instagram.com"))) {
            Log.add("browser", "instagram efg vencode_tag=$efgTag: ${VideoUrls.safeHost(url)}")
        }
        if (!isAudio && !audioPage && VideoUrls.isAudioOnlyEfg(url)) {
            Log.add("browser", "пропущен DASH-аудио (efg vencode_tag=$efgTag): ${VideoUrls.safeHost(url)}")
            return
        }
        // v0.9.2: аудио — в кандидаты наравне с видео (видео в
        // приоритете при выборе — AutoCast.orderedAttempts); аудио
        // определяется явно, по URL или по странице аудио-сервиса.
        val audio = isAudio || AudioCast.isAudioUrl(url) || audioPage
        // v0.1.3 (Failure A): в кандидаты — только настоящие видео/аудио-URL.
        if (!audio && !VideoUrls.isVideoUrl(url)) {
            Log.add("browser", "не видео-ссылка, в кандидаты не добавляю ($source): ${VideoUrls.safeHost(url)}")
            return
        }
        if (candidates.any { it.url == url }) return
        val format = if (audio) {
            val f = VideoUrls.formatOf(url)
            if (f == "?") "audio" else f
        } else VideoUrls.formatOf(url)
        val quality = qualityOverride ?: if (audio) AudioCast.audioQualityLabel(url) else VideoUrls.qualityOf(url)
        // v0.9.3 (#4): fps из URL (60fps/720p60) — tie-break в AutoCast;
        // явный fps от источника (YouTube adaptive) — приоритет.
        val c = VideoCandidate(url, format, quality, referer ?: currentPageUrl, source, directOnly = directOnly, durationSec = durationSec, audioLabel = audioLabel, isAudio = audio, fps = fps ?: VideoUrls.fpsOf(url))
        // v0.9.1: предзагрузка следующей серии — кандидаты в отдельный
        // бакет, без авто-запуска и без уведомления хоста.
        if (preloadMode) {
            if (preloadedCandidates.any { it.url == url }) return
            preloadedCandidates.add(c)
            if (preloadedCandidates.size > 20) preloadedCandidates.removeAt(preloadedCandidates.size - 1)
            Log.add("episodes", "предзагрузка: найдено видео [${c.format} ${c.quality}]: ${VideoUrls.safeHost(url)}")
            return
        }
        if (candidates.isEmpty()) firstCandidateAtMs = System.currentTimeMillis()
        candidates.add(0, c)
        if (candidates.size > 20) candidates.removeAt(candidates.size - 1)
        Log.add("browser", "найдено ${if (audio) "аудио" else "видео"} [${c.format} ${c.quality}] ($source): ${VideoUrls.safeHost(url)}")
        ui { listener.onCandidatesChanged(candidates.toList()) }
        // v0.9.1: master-first по содержимому — HLS-вариант без маркера
        // в URL (Twitch/Kick) может оказаться мастером; проверяем
        // текст плейлиста и поднимаем флаг, чтобы master-пул его взял.
        if (c.format.startsWith("m3u8") && !c.isMaster) checkMasterByContent(c)
        if (autoArmed && !launched) scheduleAutoLaunch()
    }

    /** v0.9.1: скачать плейлист (первые 64 КБ) и проверить,
     *  мастер ли он (#EXT-X-STREAM-INF). Тихо: не мастер — ничего. */
    private fun checkMasterByContent(c: VideoCandidate) {
        thread {
            try {
                val conn = java.net.URL(c.url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16; RMX5011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                val ref = c.referer
                if (!ref.isNullOrBlank()) conn.setRequestProperty("Referer", ref)
                val cookies = pageCookies(c.url)
                if (!cookies.isNullOrBlank()) conn.setRequestProperty("Cookie", cookies)
                if (conn.responseCode !in 200..299) return@thread
                val buf = ByteArray(64 * 1024)
                val n = conn.inputStream.read(buf)
                if (n <= 0) return@thread
                val text = buf.copyOf(n).toString(Charsets.UTF_8)
                if (StreamProbe.isHlsMaster(text)) {
                    ui {
                        val idx = candidates.indexOfFirst { it.url == c.url }
                        if (idx >= 0 && !candidates[idx].isMaster) {
                            candidates[idx] = candidates[idx].copy(isMaster = true)
                            Log.add("browser", "мастер по содержимому: ${VideoUrls.safeHost(c.url)}")
                            listener.onCandidatesChanged(candidates.toList())
                        }
                    }
                }
            } catch (e: Exception) {
                // Не мастер или не скачался — молча, это только подсказка.
            }
        }
    }

    // ---------- Авто-выбор и авто-запуск во VLC ----------

    /**
     * Дебаунс авто-запуска: ждём 2 с после первого кандидата — вдруг
     * придёт лучший (720p+). Каждый новый кандидат откладывает запуск,
     * но не больше MAX_LAUNCH_RESCHEDULES раз.
     * v0.9.1: master-first (MasterWait) — пока мастера нет, а есть
     * HLS-вариант, ждём до 8 с (задержка 4 с, до 6 переносов):
     * Twitch/Kick присылают низкий вариант раньше мастера.
     */
    private fun scheduleAutoLaunch() {
        if (!autoArmed || launched) return
        launchRunnable?.let { uiHandler.removeCallbacks(it) }
        launchReschedules++
        if (launchReschedules > MasterWait.MAX_RESCHEDULES) {
            doAutoLaunch()
            return
        }
        val hasMaster = candidates.any { it.isMaster }
        val hasHls = candidates.any { it.format.startsWith("m3u8") }
        val elapsed = if (firstCandidateAtMs == 0L) 0L else System.currentTimeMillis() - firstCandidateAtMs
        val waiting = MasterWait.shouldKeepWaiting(hasMaster, hasHls, elapsed)
        val delay = MasterWait.waitDelayMs(waiting)
        if (waiting) Log.add("browser", "жду мастер-плейлист (ещё ${MasterWait.MAX_WAIT_MS - elapsed} мс)…")
        val r = Runnable { doAutoLaunch() }
        launchRunnable = r
        uiHandler.postDelayed(r, delay)
    }

    /**
     * v0.9.0 — авто-ретрай: упорядоченные попытки (orderedAttempts,
     * тот же порядок, что pickBest) с pre-flight проверкой каждой.
     * Первый элемент — тот же, что вернул бы pickBest.
     * v0.9.1: preferredAudio — запомненная озвучка этого сериала.
     */
    private fun doAutoLaunch(force: Boolean = false) {
        if (!autoArmed && !force) return
        if (launched && !force) return
        val attempts = AutoCast.orderedAttempts(candidates, preferredAudio = preferredDubForCurrent())
        if (attempts.isEmpty()) return
        if (force) launched = false
        // v0.9.3 (#5): проба зеркал — в фоне, чтобы не блокировать UI
        // (до 2.5 с). Поколение — защита от двойного doAutoLaunch:
        // устаревший поток не трогает retryAttempts.
        val gen = ++autoLaunchGen
        thread {
            val reordered = reorderByMirrorLatency(attempts)
            ui {
                if (gen != autoLaunchGen) return@ui
                if (!autoArmed && !force) return@ui
                if (launched && !force) return@ui
                retryAttempts = reordered
                retryIndex = 0
                launchAttempt()
            }
        }
    }

    /**
     * v0.9.3 (#5): проба скорости зеркал. Когда у топ-кандидатов
     * равный счёт (тот же формат+качество, разные хосты — зеркала
     * Kodik и т.п.), меряем время первого байта (Range 0-0,
     * StreamProbe.probeFirstByteMs) и переставляем группу по
     * латентности. Пробы — параллельно, общий дедлайн ~2.5 с;
     * не дождались/нет данных — порядок как был. Только топ-группа
     * (≤3 попытки), один раз за авто-запуск. Кандидаты через
     * локальный прокси (один хост — телефон) не меряются: их не
     * различить, порядок как был.
     */
    private fun reorderByMirrorLatency(attempts: List<VideoCandidate>): List<VideoCandidate> {
        if (attempts.size < 2) return attempts
        val pref = preferredDubForCurrent()
        val topScore = AutoCast.score(attempts[0], pref)
        val tied = attempts.takeWhile { AutoCast.score(it, pref) == topScore }
        if (tied.size < 2) return attempts
        val hosts = tied.map { VideoUrls.safeHost(it.url) }.toSet()
        if (hosts.size < 2) return attempts
        val latencies = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val threads = tied.map { c ->
            thread {
                val ms = StreamProbe.probeFirstByteMs(c.url, c.referer, pageCookies(c.url))
                if (ms != null) latencies[c.url] = ms
            }
        }
        val deadline = System.currentTimeMillis() + MIRROR_PROBE_DEADLINE_MS
        for (t in threads) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            t.join(left)
        }
        if (latencies.isEmpty()) return attempts
        val sorted = tied.sortedBy { latencies[it.url] ?: Long.MAX_VALUE }
        Log.add("retry", "проба зеркал: " + sorted.joinToString(", ") { "${VideoUrls.safeHost(it.url)}=${latencies[it.url]?.let { "$it мс" } ?: "?"}" })
        return sorted + attempts.drop(tied.size)
    }

    /**
     * Одна попытка ретрая: pre-flight выбранного URL (Range GET,
     * HLS — master + первый сегмент, ≤2 с на вариант); не прошёл —
     * следующий кандидат; все исчерпаны — честное «Видео недоступно».
     */
    private fun launchAttempt() {
        // v0.9.3.3: запуски сериализованы — пока один Companion-коннект
        // в полёте, дублирующую попытку пропускаем: ретрай ведёт сам
        // летящий запуск (его catch вызывает launchAttempt), а дубль
        // только сбил бы retryIndex и устроил гонку коннектов.
        if (launchInFlight) {
            Log.add("retry", "запуск уже идёт — попытку ${retryIndex + 1} пропускаю")
            return
        }
        val c = retryAttempts.getOrNull(retryIndex)
        if (c == null) {
            Log.add("retry", "все ${retryAttempts.size} попыток исчерпаны — видео недоступно")
            // v0.9.2: IDLE + drain, а не зависший SEARCHING_VIDEO.
            failFlow { listener.onLaunchFailed("Видео недоступно — ни один источник не играет") }
            return
        }
        Log.add("retry", "попытка ${retryIndex + 1}/${retryAttempts.size}: ${VideoUrls.safeHost(c.url)}")
        thread {
            // v0.9.3 (#14): VLC установлен на ТВ? — один раз за флоу,
            // до первой попытки. false — честная ошибка «VLC не
            // установлен», без бессмысленного ретрая по кандидатам;
            // null (не удалось проверить) — запускаем как обычно.
            if (!vlcChecked) {
                vlcChecked = true
                val installed = checkVlcInstalledOnce()
                if (installed == false) {
                    Log.add("vlc", "VLC не установлен на ТВ — каст невозможен")
                    ui { failFlow { listener.onError("VLC не установлен на Apple TV — установи VLC из App Store, затем попробуй ещё раз") } }
                    return@thread
                }
                if (installed == null) Log.add("vlc", "установку VLC проверить не удалось — запускаю как обычно")
                else Log.add("vlc", "VLC установлен ✓")
            }
            val probe = StreamProbe.probeUrl(c.url, c.referer, pageCookies(c.url))
            ui {
                if (probe != StreamProbe.ProbeResult.OK) {
                    Log.add("retry", "pre-flight не прошёл ($probe): ${VideoUrls.safeHost(c.url)}")
                    retryIndex++
                    launchAttempt()
                } else {
                    launchCandidate(c)
                }
            }
        }
    }

    /**
     * v0.9.3 (#14): одноразовая проверка установки VLC (Companion
     * app_list) — true/false/null (неизвестно). Без выбранного ТВ —
     * null (проверка невозможна, не блокирует каст).
     */
    private fun checkVlcInstalledOnce(): Boolean? {
        val host = selectedDevice?.host ?: return null
        return try {
            CompanionClient(host, credStore = credStore).checkVlcInstalled()
        } catch (e: Exception) {
            Log.add("vlc", "app_list check error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---------- Авто-следующая серия (v0.9.0) ----------

    /** Следующий эпизод из ссылок <a> страницы (EpisodeList.parse). */
    private fun resolveNextEpisode() {
        // v0.9.2: аудио-страницы — не сериалы: «следующая серия» не ищется.
        if (audioPage) return
        val page = episodePageUrl ?: currentPageUrl ?: return
        val next = EpisodeList.parse(page, episodeLinks)?.nextUrl ?: return
        if (next != nextEpisodeUrl) {
            nextEpisodeUrl = next
            Log.add("episodes", "следующая серия: ${VideoUrls.safeHost(next)}")
        }
    }

    /** Cookie страницы для pre-flight/duration (Referer-потоки их требуют). */
    private fun pageCookies(url: String?): String? {
        if (url == null) return null
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * После успешного запуска: длительность текущего видео —
     * из кандидата (YouTube lengthSeconds) или из потока
     * (HLS #EXTINF / mp4 mvhd / DASH PT); если есть и следующий
     * эпизод — вооружить таймер. Длительность не узнать —
     * таймер-ограничение 3 ч (сервис живёт для позиции/очереди).
     * v0.9.1: при resume таймер — на ОСТАВШЕЕСЯ время
     * (полная длительность минус запомненная позиция); полная
     * длительность запоминается для сохранения позиции.
     */
    private fun resolveDurationAndArmNext(streamUrl: String, referer: String?) {
        val known = launchedCandidate?.durationSec
        if (known != null && known > 0) {
            watchFullDurationSec = known
            val remaining = remainingDuration(known)
            watchDurationSec = remaining
            armEndTimerFor(remaining)
            return
        }
        thread {
            val dur = StreamProbe.fetchDurationSec(streamUrl, referer, pageCookies(streamUrl))
            ui {
                if (dur != null && dur > 0) {
                    watchFullDurationSec = dur
                    val remaining = remainingDuration(dur)
                    watchDurationSec = remaining
                    armEndTimerFor(remaining)
                } else {
                    Log.add("episodes", "длительность не узнать — таймер-ограничение 3 ч")
                    armCapTimer()
                }
            }
        }
    }

    /** Оставшееся время с учётом resume-базы (не меньше минуты). */
    private fun remainingDuration(fullSec: Long): Long {
        val base = watchResumeBaseSec
        if (base <= 0) return fullSec
        return (fullSec - base).coerceAtLeast(60)
    }

    /** Конец воспроизведения: таймер на оставшееся время. */
    private fun armEndTimerFor(remainingSec: Long) {
        val next = nextEpisodeUrl
        if (next != null) armNextTimer(remainingSec, next) else armEndTimer(remainingSec)
    }

    /** Таймер конца без следующей серии (очередь/позиция/конец). */
    private fun armEndTimer(durationSec: Long) {
        cancelNextTimer()
        val delayMs = (durationSec + 20) * 1000L
        val r = Runnable { onPlaybackEnd() }
        nextTimerRunnable = r
        uiHandler.postDelayed(r, delayMs)
        Log.add("episodes", "таймер конца через ${durationSec + 20}с (следующей серии нет)")
        listener.onAutoNextArmed()
    }

    /**
     * Таймер конца серии: длительность + 20 с на старт/буфер VLC.
     * Companion/MRP о конце воспроизведения не сообщают (см. README),
     * поэтому это эвристика: пауза/перемотка с пульта ТВ таймер собьёт.
     * v0.9.1: по истечении — onPlaybackEnd: очередь → предзагруженная
     * серия → авто-следующая → конец.
     */
    private fun armNextTimer(durationSec: Long, nextUrl: String) {
        cancelNextTimer()
        val delayMs = (durationSec + 20) * 1000L
        val r = Runnable { onPlaybackEnd() }
        nextTimerRunnable = r
        uiHandler.postDelayed(r, delayMs)
        Log.add("episodes", "авто-следующая вооружена через ${durationSec + 20}с: ${VideoUrls.safeHost(nextUrl)}")
        listener.onAutoNextArmed()
    }

    /**
     * v0.9.1: таймер-ограничение 3 ч, когда длительность неизвестна.
     * Сервис живёт (позиция запоминается, очередь работает), а по
     * истечении — onPlaybackEnd, как при обычном конце.
     */
    private fun armCapTimer() {
        cancelNextTimer()
        val r = Runnable { onPlaybackEnd() }
        nextTimerRunnable = r
        uiHandler.postDelayed(r, 3 * 3600 * 1000L)
        Log.add("episodes", "таймер-ограничение 3 ч (длительность неизвестна)")
        listener.onAutoNextArmed()
    }

    /**
     * v0.9.2: запустить следующий элемент очереди, если есть.
     * Возвращает true, если что-то запущено. Используется и при
     * конце воспроизведения, и при терминальной ошибке (failFlow).
     */
    private fun drainQueue(): Boolean {
        val queued = queue.poll() ?: return false
        listener.onQueueChanged(queue.size)
        Log.add("queue", "запускаю из очереди (осталось ${queue.size})")
        when (queued) {
            is QueueItem.Url -> startShare(queued.url)
            is QueueItem.File -> startFileShare(queued.uri, queued.displayName, queued.isAudio)
        }
        return true
    }

    /**
     * v0.9.2: терминальная ошибка флоу (launch failed, onError,
     * timeout, TV unreachable): состояние — в IDLE, а не зависшее
     * SEARCHING_VIDEO без таймера, и запуск следующего элемента
     * очереди, если есть. Уведомление слушателю (notify) — только
     * если очередь пуста. Вызывать только с UI-потока (drain
     * трогает WebView через startShare/startPageFlow).
     */
    private fun failFlow(notify: () -> Unit) {
        launched = false
        cancelNextTimer()
        flowState = FlowState.IDLE
        if (!drainQueue()) notify()
    }

    /**
     * v0.9.1: конец воспроизведения (таймер). Порядок: очередь
     * шарингов → предзагруженная следующая серия → авто-следующая
     * по ссылкам → больше нечего (сервис останавливается).
     * Позиция сохраняется, досмотренное видео — очищается из памяти.
     */
    private fun onPlaybackEnd() {
        saveCurrentPosition()
        watchPageUrl?.let { memory.clearPosition(it) }
        watchStartMs = 0L
        launched = false
        cancelNextTimer()
        if (drainQueue()) return
        if (preloadMode) finishPreload()
        val pre = preloadedNext
        if (pre != null) {
            preloadedNext = null
            Log.add("episodes", "конец серии — запускаю предзагруженную следующую")
            launchPreloaded(pre)
            return
        }
        val next = nextEpisodeUrl
        if (next != null) {
            Log.add("episodes", "конец серии — запускаю следующую")
            listener.onStatus("Следующая серия…")
            startPageFlow(next)
            return
        }
        flowState = FlowState.IDLE
        listener.onPlaybackEnded()
    }

    /**
     * v0.9.1: сохранить позицию просмотра (elapsed − 15 с на старт
     * VLC; только если > 60 с от начала и > 60 с до конца, если
     * длительность известна). Абсолютная позиция = resume-база +
     * elapsed с запуска.
     */
    private fun saveCurrentPosition() {
        val start = watchStartMs
        val page = watchPageUrl ?: return
        if (start == 0L) return
        val elapsedSec = (System.currentTimeMillis() - start) / 1000 - 15
        val absolutePos = watchResumeBaseSec + elapsedSec
        val dur = watchFullDurationSec
        if (absolutePos < 60) return
        if (dur != null && absolutePos >= dur - 60) return
        memory.savePosition(page, absolutePos, dur)
        Log.add("episodes", "позиция сохранена: ${absolutePos}с / ${dur ?: "?"}с: ${VideoUrls.safeHost(page)}")
    }

    private fun cancelNextTimer() {
        nextTimerRunnable?.let { uiHandler.removeCallbacks(it) }
        nextTimerRunnable = null
    }

    /** Запуск одного кандидата: ТВ → (PIN один раз) → VLC. */
    fun launchCandidate(c: VideoCandidate) {
        selectedCandidate = c
        ui { listener.onCandidatesChanged(candidates.toList()) }
        if (selectedDevice == null) {
            listener.onStatus("Проверяю ТВ…")
            ensureTvReady { launchCandidate(c) }
            return
        }
        val dev = selectedDevice!!
        if (!credStore.has(CompanionClient.companionIdentity(dev.host))) {
            pendingLaunchUrl = c.url
            pendingLaunchReferer = c.referer
            pendingLaunchIsAudio = c.isAudio
            flowState = FlowState.NEED_PAIRING
            listener.onNeedPairing(dev.host, c.url)
            Log.add("vlc", "Companion не спарен — показываю спаривание, запуск отложен")
            return
        }
        launchVlc(c.url, c.referer, if (c.isAudio) "аудио" else "видео")
    }

    /**
     * v0.9.1: проверка ТВ заранее — до 30-секундного таймаута детекта.
     * Запомненный ТВ: TCP-проба 2 с → если не отвечает и спарен —
     * пробуждение Companion turn_on → опрос готовности до 30 с.
     * v0.9.2 (регрессия 0.9.1, каст не шёл НИОТКУДА): если probe и
     * wake не сработали — НЕ onTvUnreachable сразу, а fallback на
     * mDNS (fallbackToDiscovery): спящий ТВ после sleep-proxy виден
     * в mDNS, даже если прямой probe 7000 и Companion молчали.
     * onTvUnreachable — только если и mDNS никого не нашёл.
     * ВАЖНО: wake через Companion работает только при живом
     * Companion (connect на 49153): спящий ТВ им не разбудить —
     * он будится коннектом после mDNS (sleep proxy), а не _hidC.
     */
    private fun ensureTvReady(onReady: () -> Unit) {
        val rem = loadRemembered()
        if (rem == null) {
            ensureTv(onReady)
            return
        }
        thread {
            if (TvCheck.probe(rem.host, 7000, 2000)) {
                ui {
                    selectDevice(rememberedManualDevice(rem.host, rem))
                    onReady()
                }
                return@thread
            }
            Log.add("tv", "запомненный ТВ не отвечает — пробую разбудить: ${rem.host}")
            val paired = credStore.has(CompanionClient.companionIdentity(rem.host))
            if (paired) {
                ui { listener.onStatus("ТВ спит — бужу его…") }
                val woken = try {
                    CompanionClient(rem.host, credStore = credStore).wakeTv()
                } catch (e: Exception) {
                    Log.add("tv", "wake error: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                Log.add("tv", "wake: $woken — жду готовности до 30 с")
                val deadline = System.currentTimeMillis() + 30_000L
                var ok = false
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000)
                    if (TvCheck.probe(rem.host, 7000, 2000)) {
                        ok = true
                        break
                    }
                }
                if (ok) {
                    ui {
                        selectDevice(rememberedManualDevice(rem.host, rem))
                        onReady()
                    }
                    return@thread
                }
                Log.add("tv", "ТВ не проснулся за 30 с: ${rem.host}")
            } else {
                Log.add("tv", "ТВ не отвечает и не спарен — будить нечем")
            }
            // v0.9.2: probe+wake не сработали — fallback на mDNS,
            // а не убийство каста (см. шапку функции).
            ui { fallbackToDiscovery(onReady) }
        }
    }

    /**
     * v0.9.2: fallback pre-check — запомненный host мёртв, ищем ТВ
     * заново по mDNS. Нашли — selectDevice обновляет запомненный
     * host (saveRemembered) и onReady продолжает запуск. Никого —
     * tvFallbackNone: честный onTvUnreachable с drain очереди.
     */
    private fun fallbackToDiscovery(onReady: () -> Unit) {
        Log.add("tv", "fallback: ищу ТВ заново по mDNS")
        selectedDevice = null
        tvCallback = onReady
        tvFallbackNone = { failFlow { listener.onTvUnreachable() } }
        flowState = FlowState.SEARCHING_TV
        listener.onStatus("ТВ не отвечает — ищу его заново в сети…")
        startAutoDiscovery()
    }

    /**
     * Запуск во VLC через Companion. ВАЖНО (pyatv issue #2939, tvOS
     * 27.2): _launchApp может вернуть успех, даже если ТВ отклонил
     * запуск, — поэтому успех здесь = «команда отправлена без
     * исключения», а настоящий вердикт виден только на экране ТВ.
     * v0.8.0: Referer-потоки (Sibnet, OK.ru) — через локальный прокси
     * на телефоне: VLC по deep link'у заголовки не передаёт вообще.
     * v0.9.1: + resume (/resume, только HLS) + память субтитров
     * (вкл/выкл + язык; дорожка OpenSubtitles — прямая memory-vtt)
     * + запомненная озвучка/субтитры + cap-таймер до onLaunched.
     */
    private fun launchVlc(streamUrl: String, referer: String?, label: String) {
        val dev = selectedDevice ?: run {
            listener.onStatus("Сначала нужен ТВ")
            return
        }
        // v0.9.1: субтитры — с учётом памяти сериала (вкл/выкл + язык).
        // Запомненный язык — первым в списке (sub=) и DEFAULT=YES в мастере.
        // v0.9.2: аудио — без субтитров вообще (дорожки — только у видео).
        val audioLaunch = selectedCandidate?.isAudio == true
        val subsPref = currentSeriesKey?.let { memory.subsPref(it) }
        val subsEnabled = subsPref?.enabled ?: true
        val preferredLang = subsPref?.lang
        val effectiveSubs = if (audioLaunch) emptyList() else if (subsEnabled) {
            if (preferredLang.isNullOrBlank()) currentSubs
            else currentSubs.sortedByDescending { it.lang.equals(preferredLang, ignoreCase = true) }
        } else emptyList()
        var launchUrl = streamUrl
        // v0.9.1: продолжить с того же места — только HLS, через
        // /resume (прокси обрезает плейлист до позиции). mp4 — честное
        // ограничение: resume для него нет, запускается с начала.
        var resumeApplied = false
        val resumePos = resumePositionSec
        if (resumePos != null && resumePos > 0 && VideoUrls.formatOf(streamUrl).startsWith("m3u8")) {
            val base = streamProxy.ensureStarted()
            if (base != null && streamProxy.phoneIp != null) {
                launchUrl = StreamProxy.proxyResumeUrl(streamProxy.phoneIp!!, streamProxy.port, streamUrl, referer ?: "", resumePos)
                resumeApplied = true
                Log.add("vlc", "продолжаю с ${resumePos}с: ${VideoUrls.safeHost(streamUrl)}")
            } else {
                Log.add("vlc", "прокси не запустился — resume пропущен, запускаю с начала")
            }
        }
        // v0.9.0: sub= — первая дорожка субтитров через прокси
        // (Referer + конвертация .srt/.ass в VTT; путь /stream.vtt).
        // Параметр sub= в vlc-x-callback-url (VLC iOS 3.6.6:
        // «URL decode the subtitles parameter») — страховка к
        // HLS-мастеру с SUBTITLES-группой и единственный путь
        // субтитров для mp4. На tvOS требует живой проверки (README).
        // v0.9.1: дорожка OpenSubtitles (memory-vtt) — прямая ссылка,
        // без лишнего оборачивания в /stream.
        var subUrl: String? = null
        if (effectiveSubs.isNotEmpty()) {
            val first = effectiveSubs[0]
            subUrl = if (first.url.contains("/memory-vtt?")) {
                first.url
            } else {
                val base = streamProxy.ensureStarted()
                if (base != null && streamProxy.phoneIp != null) {
                    StreamProxy.proxySubtitleUrl(streamProxy.phoneIp!!, streamProxy.port, first.url, referer ?: "")
                } else {
                    Log.add("vlc", "прокси не запустился — sub= пропущен")
                    null
                }
            }
            if (subUrl != null) Log.add("vlc", "sub= (дорожка 1 из ${effectiveSubs.size}): ${VideoUrls.safeHost(first.url)}")
        }
        // v0.9.0: субтитры — только HLS + есть дорожки: синтетический
        // мастер с SUBTITLES-группой через локальный прокси. mp4 —
        // без мастера (только sub=, см. выше).
        var subsApplied = false
        if (effectiveSubs.isNotEmpty() && VideoUrls.formatOf(streamUrl).startsWith("m3u8")) {
            val base = streamProxy.ensureStarted()
            if (base != null && streamProxy.phoneIp != null) {
                launchUrl = StreamProxy.proxySubsMasterUrl(streamProxy.phoneIp!!, streamProxy.port, launchUrl, referer ?: "", effectiveSubs, preferredLang)
                subsApplied = true
                Log.add("vlc", "запуск $label С СУБТИТРАМИ (${effectiveSubs.size} дорожек): ${VideoUrls.safeHost(streamUrl)}")
            } else {
                Log.add("vlc", "прокси не запустился — субтитры пропущены, запускаю без них")
            }
        }
        if (!subsApplied && !resumeApplied && StreamProxy.needsProxy(streamUrl, referer)) {
            val base = streamProxy.ensureStarted()
            if (base != null && streamProxy.phoneIp != null) {
                launchUrl = StreamProxy.proxyStreamUrl(streamProxy.phoneIp!!, streamProxy.port, streamUrl, referer!!)
                Log.add("vlc", "запуск $label ЧЕРЕЗ ПРОКСИ: ${VideoUrls.safeHost(streamUrl)}")
            } else {
                Log.add("vlc", "прокси не запустился — запускаю напрямую (может не играть): ${VideoUrls.safeHost(streamUrl)}")
            }
        }
        flowState = FlowState.LAUNCHING
        listener.onStatus("Запускаю VLC на ТВ…")
        Log.add("vlc", "запуск $label: ${VideoUrls.safeHost(launchUrl)}")
        // v0.9.3.2 (Instagram: VLC не загружает найденное видео):
        // точный итоговый URL, который уходит в vlcDeepLink, —
        // ПОЛНОСТЬЮ в лог (только в лог, не в уведомление: там могут
        // быть токены). По нему — точная диагностика следующего прогона.
        Log.add("vlc", "итоговый URL во VLC: $launchUrl" + if (subUrl != null) " (sub=$subUrl)" else "")
        // v0.9.3.3: один Companion-коннект за раз (см. launchAttempt):
        // параллельные launchVlc гонялись друг с другом и роняли
        // друг друга EOF/Connection reset (лог 07:51:04).
        if (launchInFlight) {
            Log.add("vlc", "запуск уже идёт — дубль пропущен")
            return
        }
        launchInFlight = true
        thread {
            try {
                CompanionClient(dev.host, credStore = credStore).connectAndLaunchVlc(launchUrl, subUrl)
                ui {
                    // v0.9.3.3: флаг снимается здесь, в начале ui-блока,
                    // а не в finally потока: отложенный блок выполняется
                    // строго после снятия, и дубль launchAttempt между
                    // возвратом коннекта и этим блоком видит флаг живым.
                    launchInFlight = false
                    launched = true
                    launchedCandidate = selectedCandidate
                    flowState = FlowState.LAUNCHED
                    stopTicker()
                    // v0.9.1: позиция — с этого момента; cap-таймер 3 ч
                    // СИНХРОННО до onLaunched (сервис живёт для позиции/
                    // очереди, даже если длительность ещё не узнана).
                    watchStartMs = System.currentTimeMillis()
                    // v0.9.1: для файла watchPageUrl уже задан (URI);
                    // для страницы/ссылки — страница серии.
                    watchPageUrl = watchPageUrl ?: episodePageUrl ?: currentPageUrl
                    watchResumeBaseSec = if (resumeApplied) resumePos ?: 0L else 0L
                    watchFullDurationSec = null
                    watchDurationSec = null
                    armCapTimer()
                    // v0.9.1: запомнить озвучку и выбор субтитров.
                    val key = currentSeriesKey
                    if (key != null) {
                        launchedCandidate?.audioLabel?.let { memory.rememberDub(key, it) }
                        if (currentSubs.isNotEmpty()) {
                            memory.rememberSubs(key, effectiveSubs.isNotEmpty(), preferredLang ?: effectiveSubs.firstOrNull()?.lang)
                        }
                    }
                    listener.onLaunched(streamUrl)
                    Log.add("vlc", "запуск OK ($label) — подтверждение только на экране ТВ")
                    // v0.9.0: длительность + авто-следующая серия.
                    resolveDurationAndArmNext(streamUrl, referer)
                    // v0.9.1: предзагрузка следующей серии.
                    nextEpisodeUrl?.let { startPreload(it) }
                }
            } catch (e: Exception) {
                Log.add("vlc", "запуск ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
                ui {
                    // v0.9.3.3: флаг — здесь же (см. выше): ретрай из
                    // этого блока вызывает launchAttempt() уже после
                    // снятия флага.
                    launchInFlight = false
                    // v0.9.0: ошибка Companion вскоре после запуска —
                    // тоже ретрай следующим кандидатом, а не сразу ошибка.
                    val current = retryAttempts.getOrNull(retryIndex)
                    if (autoArmed && current != null && selectedCandidate == current &&
                        retryIndex < retryAttempts.size - 1
                    ) {
                        Log.add("retry", "запуск не удался (${e.message}) — следующая попытка")
                        retryIndex++
                        launchAttempt()
                    } else {
                        // v0.9.2: IDLE + drain, а не зависший SEARCHING_VIDEO.
                        failFlow { listener.onLaunchFailed("Запуск не удался: ${e.message}") }
                    }
                }
            }
        }
    }

    // ---------- v0.9.1: предзагрузка следующей серии ----------

    /**
     * Предзагрузка следующей серии: грузим её страницу в том же
     * WebView (текущее видео уже на ТВ, WebView свободен) и
     * собираем кандидатов/субтитры/title в отдельный бакет —
     * без авто-запуска. Дедлайн 30 с; по истечении — finishPreload.
     */
    private fun startPreload(nextUrl: String) {
        if (preloadMode || preloadedNext != null) return
        val wv = webView ?: return
        preloadMode = true
        preloadUrl = nextUrl
        preloadDeadlineMs = System.currentTimeMillis() + 30_000L
        preloadedCandidates.clear()
        preloadedSubs = emptyList()
        preloadedTitle = ""
        preloadedNextUrl = null
        playerAutoOpened = false
        Log.add("episodes", "предзагрузка следующей серии: ${VideoUrls.safeHost(nextUrl)}")
        val r = Runnable { finishPreload() }
        preloadRunnable = r
        uiHandler.postDelayed(r, 30_000L)
        try {
            wv.loadUrl(nextUrl)
        } catch (e: Exception) {
            Log.add("episodes", "предзагрузка: loadUrl error: ${e.message}")
            finishPreload()
        }
    }

    /** Завершить предзагрузку (дедлайн или конец серии): собрать найденное. */
    private fun finishPreload() {
        if (!preloadMode) return
        preloadMode = false
        preloadRunnable?.let { uiHandler.removeCallbacks(it) }
        preloadRunnable = null
        rescanRunnable?.let { uiHandler.removeCallbacks(it) }
        rescanRunnable = null
        val url = preloadUrl
        if (url != null && preloadedCandidates.isNotEmpty()) {
            preloadedNext = PreloadedNext(url, preloadedCandidates.toList(), preloadedSubs, preloadedTitle, preloadedNextUrl)
            Log.add("episodes", "предзагружена следующая серия: ${preloadedCandidates.size} канд., ${preloadedSubs.size} субтитров")
        } else {
            Log.add("episodes", "предзагрузка: ничего не нашлось за 30 с")
        }
    }

    /** Запуск предзагруженной серии — напрямую из бакета, без загрузки страницы. */
    private fun launchPreloaded(pre: PreloadedNext) {
        resetFlow()
        episodePageUrl = pre.url
        currentSeriesKey = PlaybackMemory.seriesKey(pre.url)
        resumePositionSec = null
        currentPageTitle = pre.title
        currentCleanTitle = if (pre.title.isNotBlank()) TitleCleaner.parse(pre.title, pre.url) else null
        currentSubs = pre.subs
        nextEpisodeUrl = pre.nextUrl
        autoArmed = true
        flowState = FlowState.SEARCHING_VIDEO
        searchDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        listener.onStatus("Следующая серия…")
        for (c in pre.candidates) {
            if (candidates.none { it.url == c.url }) candidates.add(c)
        }
        listener.onCandidatesChanged(candidates.toList())
        startTicker()
        doAutoLaunch()
    }

    // ---------- v0.9.1: постер + OpenSubtitles ----------

    /** Скачать постер (og:image) один раз за флоу, кап 2 МБ. */
    private fun maybeDownloadPoster(url: String?) {
        if (url.isNullOrBlank() || posterBytes != null || posterDownloading) return
        posterDownloading = true
        thread {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16; RMX5011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                if (c.responseCode !in 200..299) return@thread
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < 2 * 1024 * 1024) {
                    val n = c.inputStream.read(buf, 0, minOf(buf.size, 2 * 1024 * 1024 - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                if (total > 0) {
                    posterBytes = out.toByteArray()
                    Log.add("browser", "постер скачан: ${total / 1024} КБ: ${VideoUrls.safeHost(url)}")
                }
            } catch (e: Exception) {
                Log.add("browser", "постер error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * OpenSubtitles: один поиск за флоу, когда известно чистое
     * название и у страницы нет своих дорожек. Не задерживает
     * запуск: дорожка добавляется в currentSubs, если видео ещё
     * не запущено; иначе — только лог (применится в следующий раз).
     * Без API-ключа поиск пропускается (см. README).
     */
    private fun searchOpenSubtitlesIfNeeded() {
        if (openSubsSearched || launched) return
        val parsed = currentCleanTitle ?: return
        val series = parsed.series ?: return
        if (series.isBlank()) return
        val apiKey = prefs.getString("opensubtitles_api_key", null)
        if (apiKey.isNullOrBlank()) {
            Log.add("opensubs", "поиск пропущен: нет API-ключа (введи в «Настройка один раз»)")
            openSubsSearched = true
            return
        }
        if (currentSubs.isNotEmpty()) return
        openSubsSearched = true
        thread {
            val found = OpenSubtitles.search(apiKey, series, parsed.season, parsed.episode)
            if (found.isNullOrEmpty()) {
                Log.add("opensubs", "по «$series» ничего не нашлось")
                return@thread
            }
            val prefLang = currentSeriesKey?.let { memory.subsPref(it)?.lang } ?: "ru"
            val best = OpenSubtitles.pickBest(found, prefLang) ?: return@thread
            val link = OpenSubtitles.downloadLink(apiKey, best.fileId) ?: return@thread
            val srt = OpenSubtitles.fetchSubtitleText(link) ?: return@thread
            val vtt = when {
                best.fileName.endsWith(".ass", ignoreCase = true) -> Subtitles.assToVtt(srt)
                best.fileName.endsWith(".vtt", ignoreCase = true) -> srt
                else -> Subtitles.srtToVtt(srt)
            }
            ui {
                if (launched) {
                    Log.add("opensubs", "субтитры нашлись, но видео уже запущено — применятся в следующий раз")
                    return@ui
                }
                val base = streamProxy.ensureStarted()
                if (base == null || streamProxy.phoneIp == null) {
                    Log.add("opensubs", "прокси не запустился — внешние субтитры пропущены")
                    return@ui
                }
                val vttId = "os-${best.fileId}"
                streamProxy.registerMemoryVtt(vttId, vtt)
                openSubsVttId = vttId
                val url = StreamProxy.proxyMemoryVttUrl(streamProxy.phoneIp!!, streamProxy.port, vttId)
                val track = SubTrack(url, best.lang, best.fileName.take(48))
                if (currentSubs.none { it.url == url }) {
                    currentSubs = currentSubs + track
                    Log.add("opensubs", "внешние субтитры добавлены: ${best.fileName} (${best.lang})")
                }
            }
        }
    }

    // ---------- v0.9.1: шаринг видеофайлов (Telegram/галерея) ----------

    /**
     * Шаринг файла: content:// URI (fallback) или абсолютный путь
     * к копии из cacheDir/aircast-temp (основной путь с v0.9.2:
     * ShareReceiverActivity копирует файл, пока grant жив).
     * Возвращает false, если файл встал в очередь (сейчас идёт
     * другое видео).
     * v0.9.2: то же — для аудиофайлов (галерея/файловый менеджер):
     * isAudio — из MIME шаринга (надёжнее расширения в имени).
     */
    fun startFileShare(uri: String, displayName: String?, isAudio: Boolean = false): Boolean {
        if (isBusy()) {
            queue.add(QueueItem.File(uri, displayName, isAudio))
            listener.onQueueChanged(queue.size)
            listener.onStatus(AutoCast.notifQueuedText(queue.size))
            Log.add("queue", "файл в очереди (#${queue.size}): ${displayName ?: uri}")
            return false
        }
        startFileShareInternal(uri, displayName, isAudio)
        return true
    }

    private fun startFileShareInternal(uri: String, displayName: String?, isAudio: Boolean = false) {
        resetFlow()
        // v0.9.2: аудио — по MIME шаринга или по расширению имени.
        currentFileIsAudio = isAudio || AudioCast.isAudioFileName(displayName)
        autoArmed = true
        flowState = FlowState.SEARCHING_VIDEO
        searchDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        listener.onStatus(if (currentFileIsAudio) "Аудиофайл получен — запускаю…" else "Файл получен — запускаю…")
        try {
            // v0.9.2: путь к копии из ShareReceiverActivity — файл
            // читается напрямую (JavaFileSource), без openFileDescriptor:
            // grant до сервиса не доходит (баг 0.9.1 из Telegram).
            // temp — сама копия: удалится в cleanupCurrentFile.
            val opened = if (uri.startsWith("/")) {
                val f = File(uri)
                if (!f.exists() || !f.isFile) {
                    Log.add("file", "путь к копии не существует: $uri")
                    listener.onFileError("Не удалось открыть файл")
                    return
                }
                Log.add("file", "файл по пути: ${f.name}, ${f.length()} Б")
                Pair(JavaFileSource(f), f)
            } else {
                val parsedUri = Uri.parse(uri)
                openFileSource(parsedUri)
            }
            if (opened == null) {
                failFlow { listener.onFileError("Не удалось открыть файл") }
                return
            }
            val (source, tempFile) = opened
            currentFileTemp = tempFile
            val fileId = "file-${System.currentTimeMillis()}"
            val base = streamProxy.ensureStarted()
            if (base == null || streamProxy.phoneIp == null) {
                source.close()
                tempFile?.delete()
                currentFileTemp = null
                failFlow { listener.onFileError("Прокси не запустился — файл не отправить") }
                return
            }
            streamProxy.registerFileSource(fileId, source)
            currentFileId = fileId
            val ext = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
                ?: if (currentFileIsAudio) "mp3" else "mp4"
            val fileUrl = StreamProxy.proxyFileUrl(streamProxy.phoneIp!!, streamProxy.port, fileId, ext)
            // Длительность файла — для таймера конца (mp4/m4a — mvhd,
            // mp3 — Xing/CBR-оценка; остальное — cap-таймер 3 ч).
            val dur = audioFileDuration(source, ext)
            currentTitleOverride = displayName?.let { TitleCleaner.cleanFileName(it) } ?: if (currentFileIsAudio) "Аудио" else "Видео"
            // Позиция для файла — по URI (mp4 resume нет, но позиция
            // запоминается; при повторном шаринге — с начала, честно).
            watchPageUrl = uri
            addCandidate(fileUrl, if (currentFileIsAudio) "аудиофайл" else "файл из Telegram/галереи", null, durationSec = dur, isAudio = currentFileIsAudio)
            startTicker()
        } catch (e: Exception) {
            // v0.9.3.3: класс исключения — и в лог, и пользователю:
            // «Не удалось открыть файл» без класса не диагностируется.
            Log.add("file", "открытие файла error: ${e.javaClass.simpleName}: ${e.message}")
            failFlow { listener.onFileError("Не удалось открыть файл: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    /**
     * Источник файла: сначала fd (без копирования), иначе — копия
     * во временный файл (провайдер не даёт fd). Возвращает
     * (источник, temp-файл или null).
     */
    private fun openFileSource(uri: Uri): Pair<FileSource, File?>? {
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                currentFilePfd = pfd
                Pair(FdFileSource(pfd), null)
            } else {
                copyToTempFile(uri)
            }
        } catch (e: Exception) {
            Log.add("file", "openFileDescriptor error: ${e.javaClass.simpleName}: ${e.message} — пробую копию")
            try {
                copyToTempFile(uri)
            } catch (e2: Exception) {
                // v0.9.3.3: класс ошибки — в лог, чтобы следующий
                // прогон был решающим (ENOENT vs SecurityException).
                Log.add("file", "копия openInputStream error: ${e2.javaClass.simpleName}: ${e2.message}")
                null
            }
        }
    }

    private fun copyToTempFile(uri: Uri): Pair<FileSource, File?>? {
        val dir = File(ctx.cacheDir, TempFiles.TEMP_DIR_NAME).apply { mkdirs() }
        val tmp = File(dir, TempFiles.tempName())
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        Log.add("file", "файл скопирован во временный: ${tmp.length()} Б")
        return Pair(JavaFileSource(tmp), tmp)
    }

    /**
     * v0.9.2: длительность файла по расширению — для таймера конца.
     * mp4/m4a/mov — mvhd из первых 128 КБ; mp3 — Xing/CBR-оценка
     * по первым 64 КБ и размеру; остальное (flac/ogg/wav) — null
     * (cap-таймер 3 ч, честно).
     */
    private fun audioFileDuration(source: FileSource, ext: String): Long? {
        return try {
            when (ext.toLowerCase()) {
                "mp4", "m4a", "mov" -> {
                    val head = source.readAt(0, 128 * 1024) ?: return null
                    StreamProbe.mp4DurationSec(head)
                }
                "mp3" -> {
                    val head = source.readAt(0, 64 * 1024) ?: return null
                    AudioCast.mp3DurationSec(head, source.size())
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** v0.9.1: закрыть fd/источник текущего файла и удалить temp-копию. */
    private fun cleanupCurrentFile() {
        currentFileId?.let { streamProxy.unregisterFileSource(it) }
        currentFileId = null
        currentFilePfd?.let { runCatching { it.close() } }
        currentFilePfd = null
        currentFileTemp?.let { tmp ->
            if (tmp.delete()) Log.add("file", "временный файл удалён: ${tmp.name}")
        }
        currentFileTemp = null
    }

    // ---------- v0.9.1: плейлисты YouTube ----------

    /**
     * Шаринг ссылки на плейлист: /browse → список videoId,
     * первое видео — в каст, остальные — в очередь как watch-URL.
     * Только первая страница плейлиста (обычно до 100 видео).
     */
    private fun startYouTubePlaylistFlow(url: String) {
        resetFlow()
        autoArmed = true
        flowState = FlowState.LOADING_PAGE
        searchDeadlineMs = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        listener.onStatus("Плейлист YouTube — загружаю список…")
        val pid = YouTubePlaylist.extractPlaylistId(url)
        if (pid == null) {
            failFlow { listener.onError("Плейлист YouTube: не удалось вытащить id") }
            return
        }
        thread {
            var vd = youTubeVisitorData
            if (vd == null) {
                vd = fetchYouTubeVisitorData()
                if (vd != null) youTubeVisitorData = vd
            }
            val body = postBrowse(pid, vd)
            val ids = YouTubePlaylist.parseVideoIds(body)
            ui {
                if (ids.isEmpty()) {
                    failFlow { listener.onError("Плейлист YouTube: список не загрузился") }
                    return@ui
                }
                val rest = ids.drop(1).map { QueueItem.Url(YouTubePlaylist.watchUrl(it)) }
                if (rest.isNotEmpty()) {
                    queue.addAll(rest)
                    listener.onQueueChanged(queue.size)
                }
                Log.add("youtube", "плейлист: ${ids.size} видео, ${rest.size} в очереди")
                startPageFlow(YouTubePlaylist.watchUrl(ids[0]))
                youTubePlaylistIds = ids
                youTubePlaylistIndex = 0
            }
        }
    }

    /** POST youtubei/v1/browse для плейлиста. Возвращает тело или null. */
    private fun postBrowse(playlistId: String, visitorData: String?): String? {
        return try {
            val c = java.net.URL(YouTubePlaylist.BROWSE_URL).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            for ((k, v) in YouTube.playerRequestHeaders(YouTube.Client.VISIONOS, visitorData)) c.setRequestProperty(k, v)
            val body = YouTubePlaylist.browseRequestJson(playlistId, visitorData).toByteArray(Charsets.UTF_8)
            c.outputStream.use { it.write(body) }
            if (c.responseCode !in 200..299) {
                Log.add("youtube", "browse: HTTP ${c.responseCode}")
                return null
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.add("youtube", "browse error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---------- ТВ: запомненный → авто-поиск → выбор один раз ----------

    /**
     * ТВ для авто-запуска. Порядок: уже выбранный → запомненный
     * (тихо, без UI) → авто-поиск по mDNS: один найденный — берётся
     * сам, несколько — минимальный выбор один раз, ноль — статус.
     */
    private fun ensureTv(onReady: () -> Unit) {
        val dev = selectedDevice
        if (dev != null) {
            onReady()
            return
        }
        val rem = loadRemembered()
        if (rem != null) {
            selectDevice(rememberedManualDevice(rem.host, rem))
            Log.add("tv", "запомненный ТВ взят без поиска: ${rem.host}")
            onReady()
            return
        }
        if (flowState == FlowState.SEARCHING_TV) {
            tvCallback = onReady
            return
        }
        flowState = FlowState.SEARCHING_TV
        tvCallback = onReady
        listener.onStatus("Ищу ТВ в сети…")
        Log.add("tv", "авто-поиск ТВ по mDNS…")
        startAutoDiscovery()
    }

    /** ТВ по host (из уведомления о выборе): запомненные факты, иначе ручной. */
    fun setDeviceFromHost(host: String) {
        val rem = loadRemembered()
        selectDevice(rememberedManualDevice(host, rem))
    }

    /**
     * Продолжить после выбора ТВ в MainActivity: вызвать отложенный
     * колбэк (обычно повтор launchCandidate). Возвращает false, если
     * отложенного колбэка не было (сервис перезапустился — тогда
     * хост сам решает, что делать дальше, например startShare).
     */
    fun continueAfterTvPick(host: String): Boolean {
        setDeviceFromHost(host)
        val cb = tvCallback
        tvCallback = null
        tvFallbackNone = null
        if (cb == null) return false
        cb.invoke()
        return true
    }

    /** Авто-поиск только для экрана выбора ТВ (без отложенных колбэков). */
    fun startTvPickDiscovery() {
        pickDiscoveryOnly = true
        flowState = FlowState.SEARCHING_TV
        listener.onStatus("Ищу ТВ в сети…")
        startAutoDiscovery()
    }

    private fun startAutoDiscovery() {
        val gen = ++discoveryGen
        devices.clear()
        discovery.start(
            onDevice = { d ->
                ui {
                    devices[d.host] = d
                    listener.onDevicesChanged(devices.values.toList())
                }
            },
            onLost = { h ->
                ui {
                    devices.remove(h)
                    listener.onDevicesChanged(devices.values.toList())
                }
            }
        )
        uiHandler.postDelayed({
            if (gen != discoveryGen) return@postDelayed
            try { discovery.stop() } catch (ignored: Exception) { }
            finalizeDiscovery()
        }, TV_DISCOVERY_MS)
    }

    private fun finalizeDiscovery() {
        if (pickDiscoveryOnly) {
            pickDiscoveryOnly = false
            listener.onDevicesChanged(devices.values.toList())
            if (devices.isEmpty()) listener.onStatus("ТВ не найден — проверь, что ТВ включён")
            return
        }
        when {
            devices.isEmpty() -> {
                listener.onStatus("ТВ не найден — проверь, что ТВ включён")
                Log.add("tv", "авто-поиск: ТВ не найден")
                // v0.9.2: fallback pre-check — mDNS тоже пуст: честный
                // onTvUnreachable с drain очереди, а не зависший
                // SEARCHING_TV без таймера. Обычный ensureTv (без
                // fallback) — как раньше, только статус.
                val none = tvFallbackNone
                tvFallbackNone = null
                tvCallback = null
                none?.invoke()
            }
            devices.size == 1 -> {
                selectDevice(devices.values.first())
                tvFallbackNone = null
                val cb = tvCallback
                tvCallback = null
                cb?.invoke()
            }
            else -> {
                listener.onStatus("Найдено несколько ТВ — выбери один")
                listener.onNeedTvPick(devices.values.toList())
            }
        }
    }

    private fun selectDevice(dev: AirPlayDevice) {
        discoveryGen++
        try { discovery.stop() } catch (ignored: Exception) { }
        Log.add("discovery", "поиск остановлен — ТВ выбран: ${dev.name}")
        selectedDevice = dev
        prefs.edit().putString("lastDevice", dev.identity).apply()
        saveRemembered(dev)
        Log.add("app", "выбран ТВ: $dev")
    }

    // v0.2.2: last-known TV facts. Сохраняются только из реального
    // discovery / /server-info (features != 0x0) — пустой ручной ТВ
    // не должен затирать хорошие запомненные данные нулём.
    private fun saveRemembered(dev: AirPlayDevice) {
        if (dev.features == 0L) return
        prefs.edit()
            .putString("lastDeviceHost", dev.host)
            .putString("lastDeviceName", dev.name)
            .putString("lastDeviceFeatures", dev.features.toString(16))
            .putString("lastDeviceModel", dev.model)
            .putString("lastDeviceId", dev.deviceId)
            .apply()
        Log.add("app", "запомнен ТВ: ${dev.name} model=${dev.model} features=0x${dev.features.toString(16)}")
    }

    private fun loadRemembered(): RememberedDevice? {
        val host = prefs.getString("lastDeviceHost", null)
            ?: prefs.getString("lastDevice", null) ?: return null
        val features = prefs.getString("lastDeviceFeatures", null)?.toLongOrNull(16) ?: 0L
        return RememberedDevice(
            host = host,
            name = prefs.getString("lastDeviceName", null) ?: "Apple TV (вручную)",
            features = features,
            model = prefs.getString("lastDeviceModel", null) ?: "?",
            deviceId = prefs.getString("lastDeviceId", null) ?: host,
        )
    }

    // ---------- Companion: спаривание один раз ----------

    fun startPairing() {
        val dev = selectedDevice ?: run {
            ensureTv { startPairing() }
            return
        }
        listener.onStatus("Companion: показываю PIN на ТВ…")
        thread {
            try {
                val client = CompanionClient(dev.host, credStore = credStore)
                client.startPairing()
                pendingCompanion = client
                ui {
                    listener.onStatus("Companion: PIN на экране ТВ — введи его в поле и нажми «Спарить по PIN»")
                }
            } catch (e: Exception) {
                pendingCompanion = null
                ui {
                    listener.onPairingFailed("Companion: не удалось показать PIN: ${e.message}")
                }
            }
        }
    }

    fun finishPairing(pin: String) {
        val client = pendingCompanion
        val dev = selectedDevice
        if (client == null || dev == null) {
            listener.onPairingFailed("Сначала нажми «Показать PIN на ТВ»")
            return
        }
        if (pin.length < 4) {
            listener.onPairingFailed("Введи 4-значный PIN с экрана ТВ")
            return
        }
        thread {
            try {
                client.finishPairing(pin)
                pendingCompanion = null
                ui {
                    listener.onPaired()
                    Log.add("companion", "Companion спарен для ${dev.host}")
                    // Видео ждало спаривания — запускаем его само.
                    val pending = pendingLaunchUrl
                    if (pending != null) {
                        pendingLaunchUrl = null
                        val pendingRef = pendingLaunchReferer
                        pendingLaunchReferer = null
                        val pendingAudio = pendingLaunchIsAudio
                        pendingLaunchIsAudio = false
                        launchVlc(pending, pendingRef, if (pendingAudio) "аудио" else "видео")
                    }
                }
            } catch (e: Exception) {
                ui {
                    listener.onPairingFailed("Companion: спаривание не удалось: ${e.message}")
                }
            }
        }
    }

    // ---------- Жизненный цикл ----------

    fun destroy() {
        saveCurrentPosition()
        cleanupCurrentFile()
        openSubsVttId?.let { streamProxy.unregisterMemoryVtt(it) }
        openSubsVttId = null
        stopTicker()
        cancelNextTimer()
        launchRunnable?.let { uiHandler.removeCallbacks(it) }
        rescanRunnable?.let { uiHandler.removeCallbacks(it) }
        preloadRunnable?.let { uiHandler.removeCallbacks(it) }
        runCatching { discovery.stop() }
        runCatching { pendingCompanion?.close() }
        runCatching { streamProxy.stop() }
        runCatching { webView?.destroy() }
        webView = null
        browserBuilt = false
    }
}
