package com.alexey.aircast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/**
 * VLC Cast v0.7.0 — фоновый каст.
 *
 * ShareReceiverActivity стартует этот сервис с URL из Chrome; дальше
 * всё происходит в фоне: CastEngine грузит страницу в фоновом WebView,
 * находит видео и запускает VLC на ТВ. Приложение при этом НЕ
 * открывается — пользователь остаётся в Chrome, а на экране висит
 * только уведомление «Отправляю видео на Apple TV…».
 *
 * Успех — уведомление «Видео запущено на Apple TV ✓», сервис
 * останавливается, уведомление снимается через 6 с (если процесс
 * доживёт; иначе остаётся свайпаемым). Таймаут / нужен PIN / несколько
 * ТВ / ошибка запуска — уведомление с действием «Открыть VLC Cast»,
 * которое ведёт в MainActivity в нужном режиме; уведомление остаётся,
 * пока пользователь не откроет приложение или не смахнёт его.
 *
 * Повторный share во время работы заменяет текущую задачу новой.
 * Companion не спарен / несколько ТВ: сервис ждёт действия
 * пользователя до 5 минут, потом останавливается сам.
 */
class CastService : Service() {
    companion object {
        const val ACTION_CAST = "com.alexey.aircast.action.CAST"
        const val ACTION_CANCEL = "com.alexey.aircast.action.CANCEL"
        const val ACTION_TV_PICKED = "com.alexey.aircast.action.TV_PICKED"
        /** v0.9.1: переключить субтитры (действие в уведомлении «идёт»). */
        const val ACTION_TOGGLE_SUBS = "com.alexey.aircast.action.TOGGLE_SUBS"
        /** v0.9.1: имя видеофайла для показа (extra, рядом с data URI). */
        const val EXTRA_FILE_NAME = "vlc_cast_file_name"
        /** v0.9.2: путь к копии файла в cacheDir/aircast-temp
         *  (ShareReceiverActivity скопировала content URI, пока
         *  grant был жив). Без data URI — сервису grant не нужен. */
        const val EXTRA_FILE_PATH = "vlc_cast_file_path"
        /** v0.9.2: файл — аудио (MIME audio), а не видео. */
        const val EXTRA_FILE_IS_AUDIO = "vlc_cast_file_is_audio"
        const val NOTIF_ID = 1001

        /** Сколько ждём действия пользователя (PIN / выбор ТВ). */
        private const val WAIT_TIMEOUT_MS = 5 * 60 * 1000L
        /** Через сколько снять уведомление об успехе. */
        private const val SUCCESS_HIDE_MS = 6_000L

        fun castIntent(ctx: Context, url: String): Intent =
            Intent(ctx, CastService::class.java).apply {
                action = ACTION_CAST
                putExtra(AutoCast.EXTRA_URL, url)
            }

        /**
         * v0.9.1: каст видеофайла (Telegram/галерея). URI — в data
         * интента (а не в extra): только так FLAG_GRANT_READ_URI_
         * PERMISSION передаёт сервису право чтения content:// URI.
         */
        fun fileCastIntent(ctx: Context, uri: android.net.Uri, name: String?, isAudio: Boolean = false): Intent =
            Intent(ctx, CastService::class.java).apply {
                action = ACTION_CAST
                data = uri
                if (name != null) putExtra(EXTRA_FILE_NAME, name)
                putExtra(EXTRA_FILE_IS_AUDIO, isAudio)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        /**
         * v0.9.2: каст копии файла по пути (ShareReceiverActivity
         * скопировала content:// URI, пока grant был жив — баг
         * 0.9.1 «Не удалось открыть файл» из Telegram). Без data
         * URI: сервису grant не нужен вообще, движок читает файл
         * напрямую (JavaFileSource).
         */
        fun filePathCastIntent(ctx: Context, path: String, name: String?, isAudio: Boolean = false): Intent =
            Intent(ctx, CastService::class.java).apply {
                action = ACTION_CAST
                putExtra(EXTRA_FILE_PATH, path)
                if (name != null) putExtra(EXTRA_FILE_NAME, name)
                putExtra(EXTRA_FILE_IS_AUDIO, isAudio)
            }

        /**
         * v0.9.3 (хотфикс 0.9.2 — тишина из Telegram): сервис стартует
         * СИНХРОННО в onCreate ShareReceiverActivity, ДО копии файла.
         * Сразу показывает «Копирую файл…» и ждёт путь вторым интентом.
         * Без data/extra — только имя файла для уведомления.
         */
        fun fileWaitIntent(ctx: Context, name: String?, isAudio: Boolean = false): Intent =
            Intent(ctx, CastService::class.java).apply {
                action = FileShareRoute.ACTION_FILE_WAIT
                if (name != null) putExtra(EXTRA_FILE_NAME, name)
                putExtra(EXTRA_FILE_IS_AUDIO, isAudio)
            }

        /**
         * v0.9.3: доставка пути к копии в уже работающий (foreground)
         * сервис. Ограничение Android 12+ на старт из фона не действует.
         */
        fun fileDeliverIntent(ctx: Context, path: String, name: String?, isAudio: Boolean = false): Intent =
            Intent(ctx, CastService::class.java).apply {
                action = FileShareRoute.ACTION_FILE_DELIVER
                putExtra(EXTRA_FILE_PATH, path)
                if (name != null) putExtra(EXTRA_FILE_NAME, name)
                putExtra(EXTRA_FILE_IS_AUDIO, isAudio)
            }

        fun cancelIntent(ctx: Context): Intent =
            Intent(ctx, CastService::class.java).apply { action = ACTION_CANCEL }

        fun tvPickedIntent(ctx: Context, host: String, url: String?): Intent =
            Intent(ctx, CastService::class.java).apply {
                action = ACTION_TV_PICKED
                putExtra(AutoCast.EXTRA_TV_HOST, host)
                if (url != null) putExtra(AutoCast.EXTRA_URL, url)
            }
    }

    private lateinit var engine: CastEngine
    private var currentUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var waitRunnable: Runnable? = null
    private var hideRunnable: Runnable? = null
    /** v0.9.0: авто-следующая серия вооружена — сервис живёт до конца серии. */
    private var autoNextArmed: Boolean = false

    private fun nm(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    override fun onCreate() {
        super.onCreate()
        Log.init(filesDir)
        createNotificationChannel()
        val prefs = getSharedPreferences("aircast", Context.MODE_PRIVATE)
        val credStore: CredStore = try {
            CredentialStore(this)
        } catch (e: Exception) {
            Log.add("pair", "CredentialStore init failed, fallback in-memory: ${e.message}")
            MemoryCredStore()
        }
        engine = CastEngine(applicationContext, prefs, credStore, serviceListener)
        // v0.9.1: чистка stale-временных файлов (копии для каста).
        val cleaned = TempFiles.cleanup(File(cacheDir, TempFiles.TEMP_DIR_NAME))
        if (cleaned > 0) Log.add("file", "удалено stale временных файлов: $cleaned")
        Log.add("app", "CastService создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.add("app", "CastService: отмена по запросу activity")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_SUBS -> {
                // v0.9.1: переключить субтитры для текущего сериала.
                val enabled = engine.toggleSubsForCurrentSeries()
                Log.add("subs", "субтитры переключены: ${if (enabled) "вкл" else "выкл"} (со следующего запуска)")
                if (autoNextArmed) nm().notify(NOTIF_ID, watchingNotification())
                return START_NOT_STICKY
            }
            ACTION_TV_PICKED -> {
                val host = intent.getStringExtra(AutoCast.EXTRA_TV_HOST)
                    ?: return START_NOT_STICKY
                val url = intent.getStringExtra(AutoCast.EXTRA_URL)
                currentUrl = url
                cancelWaitTimer()
                startForeground(NOTIF_ID, progressNotification("Запускаю видео…"))
                val continued = engine.continueAfterTvPick(host)
                if (!continued && url != null) engine.startShare(url)
                Log.add("tv", "CastService: ТВ выбран в activity: $host (continued=$continued)")
                return START_NOT_STICKY
            }
            // v0.9.3 (хотфикс 0.9.2 — тишина из Telegram): сервис
            // стартовал синхронно в onCreate activity, ДО копии файла.
            // Сразу foreground + «Копирую файл…», ждёт путь вторым
            // интентом. Страховка: если activity умрёт до доставки
            // пути — сервис сам остановится через 5 мин, а не висит
            // вечно с «Копирую файл…».
            FileShareRoute.ACTION_FILE_WAIT -> {
                val name = intent.getStringExtra(EXTRA_FILE_NAME)
                val isAudio = intent.getBooleanExtra(EXTRA_FILE_IS_AUDIO, false)
                Log.add("service", "FILE_WAIT: жду копию файла (${name ?: "без имени"}, аудио=$isAudio)")
                armWaitTimer()
                startForeground(NOTIF_ID, progressNotification(AutoCast.notifCopyingFileText()))
                return START_NOT_STICKY
            }
            // v0.9.3: доставка пути к копии в уже работающий
            // (foreground) сервис — ограничение Android 12+ на старт
            // из фона не действует.
            FileShareRoute.ACTION_FILE_DELIVER -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                    ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_FILE_NAME)
                val isAudio = intent.getBooleanExtra(EXTRA_FILE_IS_AUDIO, false)
                Log.add("service", "FILE_DELIVER: путь к копии: $filePath (аудио=$isAudio)")
                cancelWaitTimer()
                currentUrl = null
                if (!autoNextArmed) {
                    startForeground(NOTIF_ID, progressNotification(if (isAudio) AutoCast.notifProgressAudioText() else "Файл получен — запускаю…"))
                }
                engine.startFileShare(filePath, name, isAudio)
                return START_NOT_STICKY
            }
            else -> {
                // v0.9.3: маршрутизация — через чистую FileShareRoute
                // (JVM-тесты); порядок веток — как раньше: путь, URI, URL.
                val filePath = intent?.getStringExtra(EXTRA_FILE_PATH)
                val fileUri = intent?.data
                val url = intent?.getStringExtra(AutoCast.EXTRA_URL)
                when (FileShareRoute.resolve(intent?.action, filePath, fileUri?.toString(), url)) {
                    FileShareRoute.Route.DELIVER_PATH -> {
                        val name = intent?.getStringExtra(EXTRA_FILE_NAME)
                        val isAudio = intent?.getBooleanExtra(EXTRA_FILE_IS_AUDIO, false) ?: false
                        Log.add("service", "ACTION_CAST по пути: $filePath (аудио=$isAudio)")
                        cancelWaitTimer()
                        currentUrl = null
                        if (!autoNextArmed) {
                            startForeground(NOTIF_ID, progressNotification(if (isAudio) AutoCast.notifProgressAudioText() else "Файл получен — запускаю…"))
                        }
                        engine.startFileShare(filePath!!, name, isAudio)
                        return START_NOT_STICKY
                    }
                    FileShareRoute.Route.FILE_URI -> {
                        // v0.9.1: шаринг видеофайла (data URI из
                        // Telegram/галереи). Если идёт видео — файл
                        // встанет в очередь: не трогаем уведомление
                        // «идёт» (startForeground его заменил бы).
                        val name = intent?.getStringExtra(EXTRA_FILE_NAME)
                        val isAudio = intent?.getBooleanExtra(EXTRA_FILE_IS_AUDIO, false) ?: false
                        cancelWaitTimer()
                        currentUrl = null
                        if (!autoNextArmed) {
                            startForeground(NOTIF_ID, progressNotification(if (isAudio) AutoCast.notifProgressAudioText() else "Файл получен — запускаю…"))
                        }
                        engine.startFileShare(fileUri.toString(), name, isAudio)
                        return START_NOT_STICKY
                    }
                    FileShareRoute.Route.URL -> {
                        cancelWaitTimer()
                        currentUrl = url
                        // v0.9.1: если идёт видео — шаринг встал в
                        // очередь: не трогаем armed-флаг и
                        // уведомление «идёт».
                        if (autoNextArmed) {
                            engine.startShare(url!!)
                            return START_NOT_STICKY
                        }
                        cancelHideTimer()
                        autoNextArmed = false
                        startForeground(NOTIF_ID, progressNotification(AutoCast.notifProgressText()))
                        engine.startShare(url!!)
                        return START_NOT_STICKY
                    }
                    else -> return START_NOT_STICKY
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelWaitTimer()
        cancelHideTimer()
        runCatching { engine.destroy() }
        super.onDestroy()
    }

    // ---------- Уведомления ----------

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            AutoCast.NOTIF_CHANNEL_ID,
            "VLC Cast",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        ch.description = "Статус отправки видео на Apple TV"
        nm().createNotificationChannel(ch)
    }

    private fun smallIcon(): Int {
        val id = resources.getIdentifier("ic_launcher", "mipmap", packageName)
        return if (id != 0) id else android.R.drawable.ic_media_play
    }

    /** PendingIntent в MainActivity в заданном режиме. */
    private fun modeIntent(mode: String, configure: Intent.() -> Unit = {}): PendingIntent {
        // setClassName строкой, а не MainActivity::class.java: MainActivity
        // ссылается на CrashLog (Java) и не компилируется в JVM-тестах.
        val i = Intent().setClassName("com.alexey.aircast", "com.alexey.aircast.MainActivity").apply {
            putExtra(AutoCast.EXTRA_MODE, mode)
            configure()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, mode.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun progressNotification(text: String): Notification =
        Notification.Builder(this, AutoCast.NOTIF_CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle("VLC Cast")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(modeIntent(AutoCast.MODE_INSTRUCTION))
            .build()

    private fun actionNotification(text: String, mode: String, shareLog: Boolean = false, configure: Intent.() -> Unit): Notification {
        val pi = modeIntent(mode, configure)
        val b = Notification.Builder(this, AutoCast.NOTIF_CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle("VLC Cast")
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "Открыть VLC Cast", pi)
        // v0.9.0: «Поделиться логом» — в уведомлении «Видео не найдено».
        if (shareLog) b.addAction(0, AutoCast.notifShareLogText(), shareLogPendingIntent())
        return b.build()
    }

    /** PendingIntent в LogShareActivity (прозрачная, открывает share sheet лога). */
    private fun shareLogPendingIntent(): PendingIntent {
        val i = Intent().setClassName("com.alexey.aircast", "com.alexey.aircast.LogShareActivity").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            this, 9001, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** v0.9.1: серия идёт, авто-следующая вооружена — уведомление с «Отменить».
     *  Текст — из состояния движка: очередь / следующая серия / позиция.
     *  Если у текущего видео есть субтитры — действие «Субтитры: вкл/выкл». */
    private fun watchingNotification(): Notification {
        val cancelPi = PendingIntent.getService(
            this, 9002, cancelIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val info = engine.watchingInfo()
        val text = when {
            info.queueCount > 0 -> AutoCast.notifWatchingQueueText(info.queueCount)
            info.hasNext -> AutoCast.notifWatchingText()
            else -> AutoCast.notifWatchingPositionText()
        }
        val b = Notification.Builder(this, AutoCast.NOTIF_CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle("VLC Cast")
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(modeIntent(AutoCast.MODE_INSTRUCTION))
            .addAction(0, "Отменить", cancelPi)
        if (info.hasSubs) {
            val subsPi = PendingIntent.getService(
                this, 9003,
                Intent(this, CastService::class.java).apply { action = ACTION_TOGGLE_SUBS },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, AutoCast.notifToggleSubsText(info.subsEnabled), subsPi)
        }
        return b.build()
    }

    private fun updateProgress(text: String) {
        // v0.9.1: пока идёт видео, статус не заменяет уведомление «идёт» —
        // оно обновляется само (очередь и т.п.).
        if (autoNextArmed) nm().notify(NOTIF_ID, watchingNotification())
        else nm().notify(NOTIF_ID, progressNotification(text))
    }

    private fun cancelWaitTimer() {
        waitRunnable?.let { mainHandler.removeCallbacks(it) }
        waitRunnable = null
    }

    private fun cancelHideTimer() {
        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun armWaitTimer() {
        cancelWaitTimer()
        val r = Runnable {
            Log.add("app", "CastService: 5 мин без действия — останавливаюсь")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        waitRunnable = r
        mainHandler.postDelayed(r, WAIT_TIMEOUT_MS)
    }

    /** Успех: уведомление об успехе (с чистым названием и постером),
     *  сервис — стоп, уведомление снять через 6 с. Если авто-следующая
     *  (или cap-таймер) вооружена — через 10 с уведомление меняется
     *  на «идёт», сервис остаётся до конца видео. */
    private fun onLaunched() {
        cancelWaitTimer()
        val title = engine.currentTitle()
        // v0.9.2: аудио без названия — «Аудио запущено…», а не «Видео…».
        val text = if (title.isNotBlank()) AutoCast.notifSuccessWithTitleText(title)
            else if (engine.isAudioFlow()) AutoCast.notifSuccessAudioText()
            else AutoCast.notifSuccessText()
        val b = Notification.Builder(this, AutoCast.NOTIF_CHANNEL_ID)
            .setSmallIcon(smallIcon())
            .setContentTitle("VLC Cast")
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(modeIntent(AutoCast.MODE_INSTRUCTION))
        // v0.9.1: постер (og:image) — BigPictureStyle, если скачался.
        engine.posterBytes()?.let { bytes ->
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bmp != null) b.setStyle(Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(text))
        }
        nm().notify(NOTIF_ID, b.build())
        stopForeground(STOP_FOREGROUND_DETACH)
        // v0.9.0: авто-следующая может вооружиться чуть позже (длительность
        // из потока узнаётся сетевым запросом, до ~4 с) — не останавливаемся
        // сразу: ждём до 10 с, onAutoNextArmed отменит остановку.
        // v0.9.1: cap-таймер вооружён синхронно до onLaunched, поэтому
        // autoNextArmed уже true — через 10 с показываем «идёт».
        val r = Runnable {
            if (autoNextArmed) {
                nm().notify(NOTIF_ID, watchingNotification())
                return@Runnable
            }
            stopSelf()
            nm().cancel(NOTIF_ID)
        }
        hideRunnable = r
        mainHandler.postDelayed(r, SUCCESS_HIDE_MS + 4_000L)
    }

    /**
     * Конец флоу с действием для пользователя: уведомление остаётся
     * (отсоединённое от foreground), сервис останавливается.
     */
    private fun endWithAction(text: String, mode: String, shareLog: Boolean = false, configure: Intent.() -> Unit) {
        cancelWaitTimer()
        nm().notify(NOTIF_ID, actionNotification(text, mode, shareLog, configure))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    // ---------- Слушатель движка ----------

    private val serviceListener = object : CastEngine.Listener {
        override fun onStatus(text: String) = updateProgress(text)

        override fun onCandidatesChanged(candidates: List<VideoCandidate>) {
            // Сервису список не нужен — только лог уже есть в движке.
        }

        override fun onLaunched(url: String) = onLaunched()

        /** v0.9.0: авто-следующая вооружена — сервис живёт до конца серии. */
        override fun onAutoNextArmed() {
            autoNextArmed = true
            cancelHideTimer()
            nm().notify(NOTIF_ID, watchingNotification())
            Log.add("app", "CastService: авто-следующая вооружена, сервис остаётся до конца серии")
        }

        /** v0.9.1: воспроизведение закончилось, нечего запускать — стоп. */
        override fun onPlaybackEnded() {
            autoNextArmed = false
            cancelHideTimer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            nm().cancel(NOTIF_ID)
            Log.add("app", "CastService: воспроизведение закончилось, останавливаюсь")
        }

        /** v0.9.1: очередь изменилась — обновить уведомление «идёт». */
        override fun onQueueChanged(count: Int) {
            if (autoNextArmed) nm().notify(NOTIF_ID, watchingNotification())
        }

        /** v0.9.1: Apple TV не отвечает даже после пробуждения.
         *  v0.9.2: с «Поделиться логом» — как «Видео не найдено»:
         *  pre-check умер до запуска, без лога не разобрать. */
        override fun onTvUnreachable() {
            endWithAction(AutoCast.notifTvUnreachableText(), AutoCast.MODE_INSTRUCTION, shareLog = true) {}
        }

        override fun onLaunchFailed(msg: String) {
            val st = engine.let {
                CastEngine.TimeoutState(it.currentPageUrl(), it.episodePageUrl(), it.candidates())
            }
            endWithAction(AutoCast.notifLaunchFailedText(), AutoCast.MODE_FALLBACK) {
                if (st.pageUrl != null) putExtra(AutoCast.EXTRA_URL, st.pageUrl)
                if (st.episodeUrl != null) putExtra(AutoCast.EXTRA_EPISODE_URL, st.episodeUrl)
                putStringArrayListExtra(AutoCast.EXTRA_CANDIDATES, ArrayList(st.candidates.map { it.url }))
            }
        }

        override fun onNeedPairing(host: String, pendingUrl: String?) {
            armWaitTimer()
            nm().notify(
                NOTIF_ID,
                actionNotification(AutoCast.notifNeedPairingText(), AutoCast.MODE_PAIRING) {
                    putExtra(AutoCast.EXTRA_TV_HOST, host)
                    if (pendingUrl != null) putExtra(AutoCast.EXTRA_URL, pendingUrl)
                }
            )
        }

        override fun onNeedTvPick(devices: List<AirPlayDevice>) {
            armWaitTimer()
            nm().notify(
                NOTIF_ID,
                actionNotification(AutoCast.notifNeedTvText(), AutoCast.MODE_TV_PICK) {
                    if (currentUrl != null) putExtra(AutoCast.EXTRA_URL, currentUrl)
                    putStringArrayListExtra(AutoCast.EXTRA_TV_HOSTS, ArrayList(devices.map { it.host }))
                    putStringArrayListExtra(AutoCast.EXTRA_TV_NAMES, ArrayList(devices.map { it.name }))
                }
            )
        }

        override fun onDevicesChanged(devices: List<AirPlayDevice>) {
            // Экран выбора живёт в activity; сервису промежуточные списки не нужны.
        }

        override fun onTimeout(state: CastEngine.TimeoutState) {
            // v0.9.0: в уведомлении «Видео не найдено» — «Поделиться логом».
            endWithAction(AutoCast.notifNotFoundText(), AutoCast.MODE_FALLBACK, shareLog = true) {
                if (state.pageUrl != null) putExtra(AutoCast.EXTRA_URL, state.pageUrl)
                if (state.episodeUrl != null) putExtra(AutoCast.EXTRA_EPISODE_URL, state.episodeUrl)
                putStringArrayListExtra(AutoCast.EXTRA_CANDIDATES, ArrayList(state.candidates.map { it.url }))
            }
        }

        override fun onPaired() {
            // Сервис спаривание не выполняет — это делает activity.
        }

        override fun onPairingFailed(msg: String) {
            // Сервис спаривание не выполняет — это делает activity.
        }

        override fun onError(msg: String) {
            endWithAction(msg, AutoCast.MODE_INSTRUCTION) {}
        }

        /** v0.9.2: ошибка файла — с «Поделиться логом»,
         *  как в «Видео не найдено» (диагностика без телефона). */
        override fun onFileError(msg: String) {
            endWithAction(msg, AutoCast.MODE_INSTRUCTION, shareLog = true) {}
        }
    }
}
