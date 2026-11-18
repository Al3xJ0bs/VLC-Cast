package com.alexey.aircast

/**
 * v0.6.0 «VLC Cast»: чистая (без Android) логика автоматики.
 *
 * Главный флоу: Chrome → Поделиться → VLC Cast → страница грузится в
 * фоновом WebView → видео находится само → VLC на ТВ запускается сам.
 * Никакого ручного ввода: ни ссылок, ни кнопок «Найти видео».
 *
 * Pure Kotlin, no Android imports — тестируется на JVM (tests/TestMain.kt).
 */
object AutoCast {
    /** Прямая ссылка на видео (запускать сразу, без загрузки страницы). */
    enum class ShareKind { DIRECT_VIDEO, DIRECT_AUDIO, PAGE, NOT_A_URL }

    private val URL_RE = Regex("https?://[^\\s\"'<>]+")

    /**
     * Первая http(s)-ссылка в тексте (то, что Chrome кладёт в
     * EXTRA_TEXT при шаринге страницы). Возвращает null, если ссылки
     * нет. Хвостовую пунктуацию (точка, запятая, скобка — бывает при
     * копипасте из мессенджера) срезает.
     */
    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL_RE.find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', ';', ':')
    }

    /** Прямая ссылка на видео/аудио — или страница с плеером. */
    fun classifyUrl(url: String): ShareKind {
        val u = url.trim()
        if (u.isEmpty() || !u.startsWith("http")) return ShareKind.NOT_A_URL
        // v0.9.2: аудио — первым (sndcdn .m3u8 — аудио, а не видео).
        if (AudioCast.isAudioUrl(u)) return ShareKind.DIRECT_AUDIO
        return if (VideoUrls.isVideoUrl(u)) ShareKind.DIRECT_VIDEO else ShareKind.PAGE
    }

    /** Классификация текста шаринга: прямая ссылка / страница / не ссылка. */
    fun classifySharedText(text: String?): ShareKind {
        val url = extractFirstUrl(text) ?: return ShareKind.NOT_A_URL
        return classifyUrl(url)
    }

    // ---------- v0.7.0: режимы MainActivity + ключи extras ----------

    /** Режимы главного экрана. По умолчанию (тап по иконке) — только инструкция. */
    enum class MainMode { INSTRUCTION, FALLBACK, PAIRING, TV_PICK }

    const val EXTRA_MODE = "vlc_cast_mode"
    const val EXTRA_URL = "vlc_cast_url"
    const val EXTRA_EPISODE_URL = "vlc_cast_episode_url"
    const val EXTRA_TV_HOST = "vlc_cast_tv_host"
    const val EXTRA_TV_HOSTS = "vlc_cast_tv_hosts"
    const val EXTRA_TV_NAMES = "vlc_cast_tv_names"
    const val EXTRA_CANDIDATES = "vlc_cast_candidates"

    const val MODE_INSTRUCTION = "instruction"
    const val MODE_FALLBACK = "fallback"
    const val MODE_PAIRING = "pairing"
    const val MODE_TV_PICK = "tvpick"

    /** Значение extra → режим. Всё неизвестное (включая null) — инструкция. */
    fun modeFromExtra(value: String?): MainMode = when (value) {
        MODE_FALLBACK -> MainMode.FALLBACK
        MODE_PAIRING -> MainMode.PAIRING
        MODE_TV_PICK -> MainMode.TV_PICK
        else -> MainMode.INSTRUCTION
    }

    // ---------- v0.7.0: тексты уведомлений фонового каста ----------

    const val NOTIF_CHANNEL_ID = "vlc_cast"

    fun notifProgressText(): String = "Отправляю видео на Apple TV…"
    fun notifSuccessText(): String = "Видео запущено на Apple TV ✓"
    fun notifNotFoundText(): String = "Видео не найдено — открыть VLC Cast"
    fun notifNeedPairingText(): String = "Нужно спарить Apple TV — открыть VLC Cast"
    fun notifNeedTvText(): String = "Выбери ТВ — открыть VLC Cast"
    fun notifLaunchFailedText(): String = "Запуск не удался — открыть VLC Cast"
    /** v0.9.0: серия идёт, авто-следующая вооружена (сервис живёт до конца серии). */
    fun notifWatchingText(): String = "Серия идёт — следующая запустится сама"
    /** v0.9.0: действие в уведомлении об ошибке. */
    fun notifShareLogText(): String = "Поделиться логом"
    /** v0.9.1: успех с чистым названием видео (TitleCleaner). */
    fun notifSuccessWithTitleText(title: String): String = "«$title» — запущено на Apple TV ✓"
    /** v0.9.1: сервис живёт — запоминает позицию просмотра. */
    fun notifWatchingPositionText(): String = "Видео идёт — позиция запоминается"
    /** v0.9.1: сервис живёт — в очереди ещё N шарингов. */
    fun notifWatchingQueueText(n: Int): String = "Видео идёт — в очереди ещё $n"
    /** v0.9.1: шаринг добавлен в очередь (сейчас идёт другое видео). */
    fun notifQueuedText(n: Int): String = "В очереди: $n — запустится после текущего видео"
    /** v0.9.1: Apple TV не отвечает — честно сразу, без 30-секундного таймаута. */
    fun notifTvUnreachableText(): String = "Apple TV не отвечает — проверь, включён ли он"
    /** v0.9.1: действие в уведомлении — переключить субтитры. */
    fun notifToggleSubsText(enabled: Boolean): String =
        if (enabled) "Субтитры: вкл" else "Субтитры: выкл"
    /** v0.9.2: аудио (прогресс и успех без названия). */
    fun notifProgressAudioText(): String = "Отправляю аудио на Apple TV…"
    fun notifSuccessAudioText(): String = "Аудио запущено на Apple TV ✓"
    /** v0.9.3.2: сервис копирует расшаренный файл (FILE_COPY). */
    fun notifCopyingFileText(): String = "Копирую файл…"

    // ---------- авто-выбор кандидата ----------

    /**
     * Ранг формата: HLS первым (адаптивный поток, у Kodik/Alloha —
     * всегда .m3u8), затем mp4, остальное — последним.
     * v0.9.2: аудио-форматы — ранг 2, как mp4 (внутри аудио-пула
     * решает качество/битрейт).
     */
    fun formatRank(format: String): Int = when {
        format.startsWith("m3u8") -> 3
        format.startsWith("mpd") -> 3 // v0.8.1: DASH .mpd — адаптивный манифест, как HLS
        format == "mp4" -> 2
        format in listOf("mp3", "aac", "flac", "ogg", "m4a", "wav", "opus", "mka", "audio") -> 2
        else -> 1
    }

    /** Ранг качества: 2160p > 1080p > 720p > … > неизвестное. */
    fun qualityRank(quality: String): Int = when (quality) {
        "2160p" -> 6
        "1440p" -> 5
        "1080p" -> 4
        "720p" -> 3
        "480p" -> 2
        "360p" -> 1
        // v0.9.2: аудио — FLAC (без потерь) выше любого битрейта.
        "FLAC" -> 6
        "320 kbps" -> 5
        "256 kbps" -> 4
        "192 kbps" -> 3
        "128 kbps" -> 2
        else -> 0
    }

    fun score(c: VideoCandidate, preferredAudio: String? = null): Int {
        var s = formatRank(c.format) * 10 + qualityRank(c.quality)
        // v0.9.1: запоминание озвучки — кандидат с той же дорожкой,
        // что в прошлый раз, получает +5 (выше любого качества).
        if (!preferredAudio.isNullOrBlank() && c.audioLabel != null &&
            c.audioLabel.equals(preferredAudio, ignoreCase = true)) s += 5
        // v0.9.3.3: диапазон байт DASH-сегмента (bytestart/byteend) —
        // не полный файл: -3, ниже любого полного файла того же
        // формата (но выше более низкого качества). Если полных
        // альтернатив нет — сегмент всё равно используется.
        if (VideoUrls.hasByteRange(c.url)) s -= 3
        return s
    }

    /**
     * v0.9.0 — порядок попыток авто-ретрая: те же годные кандидаты, что
     * в pickBest (без placeholder/directOnly), отсортированные по счёту
     * (формат×10 + качество, master HLS — отдельным пулом), без дублей,
     * не больше max. Первый элемент — тот же, что вернул бы pickBest.
     * v0.9.1: мастер-пул — по полю isMaster (а не только по URL:
     * движок поднимает флаг и по содержимому плейлиста, Twitch/Kick);
     * preferredAudio — запомненная озвучка (+5 к счёту).
     * v0.9.2: видео в приоритете — аудио-пул только если видео нет
     * вообще (страница с видео и фоновым mp3 не кастит mp3).
     */
    fun orderedAttempts(
        candidates: List<VideoCandidate>,
        max: Int = 3,
        preferredAudio: String? = null,
    ): List<VideoCandidate> {
        val usable = candidates.filter { !VideoUrls.isPlaceholder(it.url) && !it.directOnly }
        if (usable.isEmpty()) return emptyList()
        // v0.9.3.3 (страховка к фильтру в addCandidate): DASH-аудио
        // по efg — не видео-пул, даже если просочилось мимо фильтра
        // (прямая ссылка и т.п.). Явный аудио-пул это не трогает.
        val video = usable.filter { !it.isAudio && !VideoUrls.isAudioOnlyEfg(it.url) }
        val audio = usable.filter { it.isAudio }
        val base = if (video.isNotEmpty()) video else audio
        val masters = base.filter { it.format.startsWith("m3u8") && it.isMaster }
        val pool = if (masters.isNotEmpty()) masters else base
        // v0.9.3 (#4): при равном счёте (формат+качество) — 60fps выше.
        // Для HLS-мастеров fps неважен (VLC сам выбирает rendition),
        // но маркеров fps в URL мастеров обычно и нет.
        return pool.distinctBy { it.url }
            .sortedWith(compareByDescending<VideoCandidate> { score(it, preferredAudio) }.thenByDescending { it.fps ?: 0 })
            .take(max)
    }

    /**
     * Лучший кандидат из найденных: HLS приоритет, затем большее
     * разрешение. Служебные URL (placeholder-клипы, тестовые видео)
     * пропускаются. При равенстве счёта — первый в списке (список
     * кандидатов — от новых к старым, так что это самый свежий).
     * v0.8.0 (Aniboom): если среди HLS есть master-плейлист — берётся
     * он, а не одиночный вариант: у Aniboom fMP4 аудио лежит в
     * отдельной дорожке, и bare variant играет без звука.
     * Возвращает null, если годных кандидатов нет.
     * v0.9.1: preferredAudio — запомненная озвучка.
     */
    fun pickBest(candidates: List<VideoCandidate>, preferredAudio: String? = null): VideoCandidate? {
        return orderedAttempts(candidates, 1, preferredAudio).firstOrNull()
    }
}
