package com.alexey.aircast

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * VLC Cast v0.7.0 — тап по иконке = ТОЛЬКО экран-инструкция.
 *
 * По умолчанию (обычный запуск из лаунчера) здесь НЕТ ничего, кроме
 * инструкции про шаринг ссылки: ни статус-карточки, ни карточки
 * видео, ни кнопки «▶ На ТВ», ни «Диагностики» — всё это убрано по
 * слову пользователя (2026-11-18 01:46: «просто был экран с
 * инструкцией про шаринг ссылки и больше ничего»).
 *
 * Главный флоу больше не проходит через эту activity вообще: Chrome →
 * Поделиться → ShareReceiverActivity (прозрачная, без UI) →
 * CastService в фоне → уведомление. Приложение при шаринге НЕ
 * открывается.
 *
 * Эта activity оживает только по extras из уведомлений сервиса:
 *  - fallback: видео не нашлось само — видимый браузер 420 dp +
 *    список найденных видео + «▶ На ТВ» + «← Назад к странице серии»;
 *  - pairing: Companion не спарен — PIN UI (один раз);
 *  - tvpick: найдено несколько ТВ — минимальный выбор один раз.
 * Без extras — всегда только инструкция. Диагностики как экрана
 * больше нет (логи по-прежнему пишутся в файл aircast.log;
 * crash-экран сохранён).
 *
 * Пакет com.alexey.aircast НЕ меняется — обновление поверх v0.6.0
 * сохраняет SharedPreferences («aircast» + «aircast_creds»), а значит
 * спаривание Companion и запомненный ТВ переживают обновление.
 */
class MainActivity : Activity() {
    companion object {
        const val VERSION = "0.9.3.1"
        /** v0.9.1: versionCode — для проверки обновлений (сравнивается с latest.json). */
        const val VERSION_CODE = 34
        const val EXTRA_SAFE_MODE = "aircast_safe_mode"
        private const val REQ_POST_NOTIFICATIONS = 1001
        /** v0.9.3 (#27): onActivityResult — импорт файла бэкапа спаривания. */
        private const val REQUEST_BACKUP_IMPORT = 4102
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var credStore: CredStore
    private var engine: CastEngine? = null
    private var safeMode: Boolean = false
    private var currentMode: AutoCast.MainMode = AutoCast.MainMode.INSTRUCTION
    private lateinit var contentRoot: LinearLayout

    // Состояние режимов (из extras уведомления / из движка).
    private var pendingUrl: String? = null
    private var pendingEpisodeUrl: String? = null
    private var pendingPairHost: String? = null
    private var pendingPairUrl: String? = null
    private var tvPickDevices: List<AirPlayDevice> = emptyList()
    private var seedCandidates: List<VideoCandidate> = emptyList()

    // UI-рефы активного режима (nullable — режим пересоздаётся).
    private var modeHint: TextView? = null
    private var fallbackCandidateBox: LinearLayout? = null
    private var webViewHolder: LinearLayout? = null
    private var pairPinBar: EditText? = null
    private var tvPickList: LinearLayout? = null
    // v0.9.1: рефы блока «Настройка один раз» (экран-инструкция).
    private var updateStatus: TextView? = null
    private var updateUrlField: EditText? = null
    private var openSubsKeyField: EditText? = null
    private var setupPairLabel: TextView? = null
    private var updateDownloadButton: Button? = null
    // v0.9.3 (#18): статус самопроверки.
    private var selfCheckStatus: TextView? = null
    // v0.9.3: самообновление внутри приложения (DownloadManager + промпт установки).
    private var appUpdater: AppUpdater? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.mark("MainActivity.onCreate: enter")
        super.onCreate(savedInstanceState)
        CrashLog.mark("MainActivity.onCreate: super done")
        safeMode = intent.getBooleanExtra(EXTRA_SAFE_MODE, false)

        // Экран краша — ПЕРВЫМ, до любого WebView/KeyStore/NsdManager.
        if (!safeMode && CrashLog.hasCrashed(this)) {
            CrashLog.mark("MainActivity.onCreate: showing crash screen")
            try {
                setContentView(buildCrashScreen())
            } catch (e: Exception) {
                throw e
            }
            return
        }

        CrashLog.mark("MainActivity.onCreate: Log.init")
        Log.init(filesDir)
        Log.add("app", "VLC Cast $VERSION запущен (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL})${if (safeMode) " [БЕЗОПАСНЫЙ РЕЖИМ — без WebView]" else ""}")
        CrashLog.mark("MainActivity.onCreate: prefs")
        prefs = getSharedPreferences("aircast", Context.MODE_PRIVATE)
        CrashLog.mark("MainActivity.onCreate: CredentialStore (конструктор, без KeyStore)")
        credStore = try {
            CredentialStore(this)
        } catch (e: Exception) {
            Log.add("pair", "CredentialStore init failed, fallback in-memory: ${e.message}")
            MemoryCredStore()
        }

        // v0.9.3: самообновление — до рендера экрана: если загрузка
        // завершилась, пока приложения не было, промпт установки
        // показывается сразу; статус сохраняется в prefs, чтобы
        // экран-инструкция показал его после рендера.
        appUpdater = AppUpdater(this).also { it.attach { s -> setUpdateStatus(s) } }

        CrashLog.mark("MainActivity.onCreate: root")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val headerId = resources.getIdentifier("header", "drawable", packageName)
        if (headerId != 0) {
            val header = ImageView(this).apply {
                setImageResource(headerId)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(header)
        } else {
            Log.add("app", "header drawable не найден — шапка без графики")
        }
        root.addView(textView("VLC Cast $VERSION", 20f, bold = true))
        contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutContract.SCREEN_WIDTH, LayoutContract.SCREEN_HEIGHT)
        }
        root.addView(contentRoot)
        setContentView(root)

        renderModeFromIntent(intent)
        CrashLog.mark("MainActivity.onCreate: done")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderModeFromIntent(intent)
    }

    // ---------- Режимы ----------

    private fun renderModeFromIntent(intent: Intent?) {
        val mode = AutoCast.modeFromExtra(intent?.getStringExtra(AutoCast.EXTRA_MODE))
        pendingUrl = intent?.getStringExtra(AutoCast.EXTRA_URL)
        pendingEpisodeUrl = intent?.getStringExtra(AutoCast.EXTRA_EPISODE_URL)
        pendingPairHost = intent?.getStringExtra(AutoCast.EXTRA_TV_HOST)
        pendingPairUrl = pendingUrl
        val hosts = intent?.getStringArrayListExtra(AutoCast.EXTRA_TV_HOSTS)
        val names = intent?.getStringArrayListExtra(AutoCast.EXTRA_TV_NAMES)
        tvPickDevices = if (hosts != null && names != null) {
            hosts.zip(names).map { (h, n) -> AirPlayDevice(n, h, 7000, emptyMap(), 0L, "?", h) }
        } else emptyList()
        seedCandidates = intent?.getStringArrayListExtra(AutoCast.EXTRA_CANDIDATES)
            ?.map { VideoCandidate(it, VideoUrls.formatOf(it), VideoUrls.qualityOf(it), null, "найдено ранее") }
            ?: emptyList()
        // Фолбэк-уведомление сервис уже отсоединил от foreground —
        // при открытии приложения снимаем его сами.
        if (mode == AutoCast.MainMode.FALLBACK) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(CastService.NOTIF_ID)
        }
        renderMode(mode)
        if (mode == AutoCast.MainMode.INSTRUCTION) requestNotificationPermissionOnce()
    }

    private fun renderMode(mode: AutoCast.MainMode) {
        currentMode = mode
        modeHint = null
        fallbackCandidateBox = null
        webViewHolder = null
        pairPinBar = null
        tvPickList = null
        updateStatus = null
        updateUrlField = null
        openSubsKeyField = null
        setupPairLabel = null
        updateDownloadButton = null
        contentRoot.removeAllViews()
        when (mode) {
            AutoCast.MainMode.INSTRUCTION -> buildInstruction()
            AutoCast.MainMode.FALLBACK -> buildFallback()
            AutoCast.MainMode.PAIRING -> buildPairing()
            AutoCast.MainMode.TV_PICK -> buildTvPick()
        }
    }

    /** Тап по иконке: только инструкция про шаринг, больше ничего. */
    private fun buildInstruction() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
        }
        scroll.addView(root)
        root.addView(stepCard("🌐", "Откройте страницу с видео в Chrome или другом приложении",
            "Аниме, фильм — любая страница с плеером."))
        root.addView(stepCard("📤", "Нажмите «Поделиться» → VLC Cast",
            "В меню шаринга Chrome выберите VLC Cast."))
        root.addView(stepCard("📺", "Видео само запустится на Apple TV",
            "Ничего больше нажимать не нужно — видео найдётся само."))
        // v0.9.2: музыка — тоже шарингом: трек или аудиофайл.
        root.addView(stepCard("🎵", "Музыка — тоже: поделись ссылкой на трек или аудиофайлом",
            "SoundCloud, YouTube Music, радио, Bandcamp — обложка и название из метаданных."))
        root.addView(textView("При шаринге приложение не открывается: всё происходит в фоне, а на экране появится уведомление.", 14f))
        // v0.9.3 (#20): «Что нового» — один раз на новую версию.
        if ((prefs.getString("last_seen_version", "") ?: "") != VERSION) {
            root.addView(buildWhatsNewCard())
        }
        root.addView(buildSetupBlock())
        root.addView(textView("VLC Cast $VERSION", 13f))
        contentRoot.addView(scroll)
        // v0.9.1: проверка обновлений — раз в сутки, тихо, в фоне.
        maybeAutoCheckUpdates()
    }

    /**
     * v0.9.3 (#20): карточка «Что нового» — один раз на новую версию.
     * Кнопка «Понятно» записывает версию и перерисовывает экран
     * (карточка исчезает до следующего обновления).
     */
    private fun buildWhatsNewCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            setBackgroundColor(Color.parseColor("#1E3A2F"))
        }
        card.addView(textView("Что нового в $VERSION", 17f, bold = true))
        card.addView(textView("• Самообновление внутри приложения: «Скачать и установить» качает APK и сразу предлагает установку, без браузера.\n• Проверка VLC на ТВ перед запуском — честная ошибка, если VLC не установлен.\n• DASH из раздельных дорожек YouTube, 60fps в приоритете, проба скорости зеркал.\n• Куки и вход на сайте, самопроверка, JSON-LD/oEmbed, десктопный UA, lazy-iframe, бэкап спаривания.\n• Исправлено: шаринг видеофайла из Telegram (был молчаливый сбой) и Instagram (не находилось видео).", 14f))
        card.addView(button("Понятно") {
            prefs.edit().putString("last_seen_version", VERSION).apply()
            renderMode(AutoCast.MainMode.INSTRUCTION)
        })
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12)
        }
        wrap.addView(card)
        return wrap
    }

    /**
     * v0.9.1: блок «Настройка один раз» — VLC на Apple TV +
     * спаривание по PIN один раз + URL проверки обновлений +
     * API-ключ OpenSubtitles (для внешних субтитров).
     */
    private fun buildSetupBlock(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }
        root.addView(textView("Настройка один раз", 17f, bold = true))
        root.addView(textView("На Apple TV должен быть установлен VLC. Один раз нужно найти приставку и спарить её по PIN-коду — дальше видео запускаются сами.", 14f))
        val pairLabel = textView(if (isPaired()) "ТВ спарен ✓" else "ТВ пока не спарен", 15f, bold = true)
        setupPairLabel = pairLabel
        root.addView(pairLabel)
        if (!isPaired()) {
            root.addView(bigButton("Спарить Apple TV") {
                renderMode(AutoCast.MainMode.PAIRING)
                ensureEngine().startPairing()
            })
        }
        // v0.9.3 (#18): самопроверка — ТВ доступен? спарен? VLC установлен?
        root.addView(textView("Самопроверка", 15f, bold = true))
        val selfStatus = textView(selfCheckText(), 14f)
        selfCheckStatus = selfStatus
        root.addView(selfStatus)
        root.addView(button("Проверить") { runSelfCheck() })
        root.addView(textView("Проверка обновлений", 15f, bold = true))
        val status = textView(updateStatusText(), 14f)
        updateStatus = status
        root.addView(status)
        root.addView(button("Проверить обновления") { checkUpdates(manual = true) })
        val urlField = EditText(this).apply {
            // v0.9.2: поле предзаполнено источником по умолчанию
            // (GitHub Al3xJ0bs/VLC-Cast); пользователь может изменить.
            setHint("URL latest.json (по умолчанию — GitHub Al3xJ0bs/VLC-Cast)")
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(updateCheckUrl())
        }
        updateUrlField = urlField
        root.addView(urlField)
        root.addView(textView("Субтитры из внешних баз (OpenSubtitles)", 15f, bold = true))
        root.addView(textView("Бесплатный consumer-ключ: регистрация на opensubtitles.com → API. Без ключа внешние субтитры не ищутся, свои дорожки со страницы работают как раньше.", 14f))
        val keyField = EditText(this).apply {
            setHint("API-ключ OpenSubtitles")
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("opensubtitles_api_key", "") ?: "")
        }
        openSubsKeyField = keyField
        root.addView(keyField)
        root.addView(bigButton("Сохранить настройки") {
            prefs.edit()
                .putString("update_check_url", updateUrlField?.text?.toString()?.trim() ?: "")
                .putString("opensubtitles_api_key", openSubsKeyField?.text?.toString()?.trim() ?: "")
                .apply()
            toast("Настройки сохранены")
            updateStatus?.text = updateStatusText()
        })
        // v0.9.3 (#27): бэкап спаривания — экспорт/импорт без повторного PIN.
        root.addView(textView("Бэкап спаривания", 15f, bold = true))
        root.addView(textView("Экспорт — в файл aircast-pairing-backup.txt (поделись им с собой). Файл содержит секреты спаривания: храни в надёжном месте и удали после восстановления.", 14f))
        root.addView(button("Экспорт спаривания") { exportPairingBackup() })
        root.addView(button("Импорт спаривания") { importPairingBackup() })
        // v0.9.3 (#18): авто-самопроверка один раз на версию.
        if ((prefs.getString("selfcheck_done_for", "") ?: "") != VERSION_CODE.toString()) {
            runSelfCheck()
        }
        return root
    }

    /** Текст самопроверки (без сети — по сохранённому состоянию). */
    private fun selfCheckText(): String {
        val last = prefs.getString("selfcheck_last_result", "") ?: ""
        return if (last.isBlank()) "Не проверялось. Нажми «Проверить»." else last
    }

    /**
     * v0.9.3 (#18): самопроверка — три состояния на проверку
     * (OK/FAIL/UNKNOWN): ТВ доступен (TCP 7000), спарен (учётка
     * Companion есть), VLC установлен (app_list — только если
     * спарен, иначе UNKNOWN). Флаг версии ставится сразу, чтобы
     * повторный рендер экрана не запускал проверку заново.
     */
    private fun runSelfCheck() {
        prefs.edit().putString("selfcheck_done_for", VERSION_CODE.toString()).apply()
        selfCheckStatus?.text = "Проверяю…"
        Thread {
            val host = prefs.getString("lastDeviceHost", null) ?: prefs.getString("lastDevice", null)
            val lines = ArrayList<String>()
            if (host.isNullOrBlank()) {
                lines.add("ТВ: ? (не запомнен — спарь один раз)")
                lines.add("Спаривание: ✗ (ТВ не спарен)")
                lines.add("VLC: ? (нужно спаривание)")
            } else {
                val reachable = try { TvCheck.probe(host, 7000, 2000) } catch (e: Exception) { false }
                lines.add(if (reachable) "ТВ: ✓ доступен ($host)" else "ТВ: ✗ не отвечает ($host)")
                val paired = isPaired()
                lines.add(if (paired) "Спаривание: ✓ ТВ спарен" else "Спаривание: ✗ ТВ не спарен")
                if (!paired) {
                    lines.add("VLC: ? (нужно спаривание)")
                } else {
                    val vlc = try { CompanionClient(host, credStore = credStore).checkVlcInstalled() } catch (e: Exception) { null }
                    lines.add(when (vlc) {
                        true -> "VLC: ✓ установлен"
                        false -> "VLC: ✗ не установлен — установи VLC из App Store"
                        null -> "VLC: ? (не удалось проверить)"
                    })
                }
            }
            val result = lines.joinToString("\n")
            prefs.edit().putString("selfcheck_last_result", result).apply()
            Log.add("app", "самопроверка:\n$result")
            runOnUiThread { selfCheckStatus?.text = result }
        }.start()
    }

    /**
     * v0.9.3 (#27): экспорт спаривания — запомненный ТВ: Companion-
     * учётка (companion:<host>) и HAP-учётка старых версий (<host>),
     * если есть. Файл — filesDir/aircast-pairing-backup.txt, дальше —
     * системный share sheet (LogFileProvider отдаёт его read-only).
     */
    private fun exportPairingBackup() {
        val host = prefs.getString("lastDeviceHost", null) ?: prefs.getString("lastDevice", null)
        val entries = ArrayList<Pair<String, String>>()
        if (!host.isNullOrBlank()) {
            for (identity in listOf(CompanionClient.companionIdentity(host), host)) {
                val creds = try { credStore.load(identity) } catch (e: Exception) { null }
                if (creds != null) entries.add(identity to creds.serialize())
            }
        }
        if (entries.isEmpty()) {
            toast("Нечего экспортировать — ТВ не спарен")
            return
        }
        try {
            val file = java.io.File(filesDir, PairingBackup.BACKUP_NAME)
            file.writeText(PairingBackup.exportAll(entries))
            Log.add("pair", "бэкап спаривания: ${entries.size} учёток → ${file.name}")
            val uri = LogFileProvider.uriForBackup(this)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Сохранить бэкап спаривания"))
        } catch (e: Exception) {
            Log.add("pair", "экспорт бэкапа error: ${e.javaClass.simpleName}: ${e.message}")
            toast("Не удалось сохранить бэкап: ${e.message}")
        }
    }

    /** v0.9.3 (#27): импорт спаривания — выбор файла бэкапа (текст). */
    private fun importPairingBackup() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/plain"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, "Выбери файл бэкапа"), REQUEST_BACKUP_IMPORT)
        } catch (e: Exception) {
            toast("Не удалось открыть выбор файла: ${e.message}")
        }
    }

    /** Спарен ли запомненный ТВ (Companion-учётка есть в credStore). */
    private fun isPaired(): Boolean {
        val host = prefs.getString("lastDeviceHost", null) ?: prefs.getString("lastDevice", null)
        if (host.isNullOrBlank()) return false
        return try {
            credStore.has(CompanionClient.companionIdentity(host))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v0.9.2: действующий URL источника обновлений — сохранённый
     * пользователем, а если поле пустое (в т.ч. у обновившихся
     * с v0.9.1, где URL не задавался) — по умолчанию GitHub
     * Al3xJ0bs/VLC-Cast. Пустое поле в настройках = назад к умолчанию.
     */
    private fun updateCheckUrl(): String {
        val saved = prefs.getString("update_check_url", null)?.trim()
        return if (saved.isNullOrBlank()) UpdateCheck.DEFAULT_UPDATE_URL else saved
    }

    /** Текст статуса обновлений (без сети — по сохранённому состоянию). */
    private fun updateStatusText(): String {
        val last = prefs.getString("update_last_result", "") ?: ""
        return if (last.isBlank()) "Источник обновлений: GitHub (Al3xJ0bs/VLC-Cast). Нажми «Проверить обновления»." else last
    }

    /** Проверка обновлений: вручную по кнопке или раз в сутки при запуске. */
    private fun checkUpdates(manual: Boolean) {
        // Поле на экране — приоритет (пользователь мог поменять URL,
        // не сохраняя); пустое поле = источник по умолчанию.
        val fieldUrl = updateUrlField?.text?.toString()?.trim()
        val url = if (fieldUrl.isNullOrBlank()) updateCheckUrl() else fieldUrl
        updateStatus?.text = "Проверяю обновления…"
        Thread {
            val fetched = UpdateCheck.fetchLatestResult(url)
            val latest = (fetched as? UpdateCheck.FetchResult.Ok)?.latest
            val result = when (fetched) {
                // v0.9.3: 404 — файла latest.json нет по этому URL:
                // честно «источник недоступен», а не «проверьте сеть».
                is UpdateCheck.FetchResult.Unavailable -> "Источник обновлений недоступен (файл latest.json не найден). Попробуй позже."
                is UpdateCheck.FetchResult.Error -> "Не удалось проверить обновления (сеть или URL). Попробуй ещё раз."
                is UpdateCheck.FetchResult.Ok ->
                    if (UpdateCheck.isNewer(VERSION_CODE, fetched.latest)) "Доступна версия ${fetched.latest.versionName} (код ${fetched.latest.versionCode}) — нажми «Скачать и установить» ниже."
                    else "У вас последняя версия ($VERSION)."
            }
            prefs.edit()
                .putString("update_last_result", result)
                .putLong("update_checked_at", System.currentTimeMillis())
                .apply()
            runOnUiThread {
                updateStatus?.text = result
                if (latest != null && UpdateCheck.isNewer(VERSION_CODE, latest) && latest.url.isNotBlank()) {
                    // v0.9.3: кнопка «Скачать и установить» — скачивание
                    // и установка ВНУТРИ приложения (AppUpdater), без браузера.
                    updateDownloadButton?.let { (it.parent as? LinearLayout)?.removeView(it) }
                    val dl = button("Скачать и установить ${latest.versionName}") {
                        startUpdateFlow(latest)
                    }
                    updateDownloadButton = dl
                    (updateStatus?.parent as? LinearLayout)?.addView(dl)
                }
            }
        }.start()
    }

    /** Статус обновлений — на экран и в prefs (экран пересоздаётся). */
    private fun setUpdateStatus(s: String) {
        updateStatus?.text = s
        prefs.edit().putString("update_last_result", s).apply()
    }

    /**
     * v0.9.3: самообновление внутри приложения. Гейт — UpdateGate:
     * PROCEED — качаем сразу; OPEN_SETTINGS — один раз в настройки
     * установки из неизвестных источников (pending URL запомнен,
     * после возврата скачивание начнётся само); DENIED — честный
     * текст, без гонок по кругу.
     */
    private fun startUpdateFlow(latest: UpdateCheck.Latest) {
        val updater = appUpdater ?: return
        when (updater.gateDecision()) {
            UpdateGate.Decision.PROCEED -> {
                updater.rememberPending(latest.url, latest.versionName)
                if (!updater.startDownload(latest.url, latest.versionName) { s -> setUpdateStatus(s) }) {
                    setUpdateStatus("Не удалось начать скачивание. Проверь сеть и попробуй ещё раз.")
                }
            }
            UpdateGate.Decision.OPEN_SETTINGS -> {
                updater.rememberPending(latest.url, latest.versionName)
                toast("Разреши установку из неизвестных источников — после этого скачивание начнётся само")
                setUpdateStatus("Нужен доступ к установке из неизвестных источников — открываю настройки…")
                updater.openInstallSettings()
            }
            UpdateGate.Decision.DENIED -> {
                setUpdateStatus("Установка из неизвестных источников запрещена. Разреши её в настройках (VLC Cast → установка неизвестных приложений), затем нажми «Скачать и установить» ещё раз.")
            }
        }
    }

    /** Авто-проверка раз в сутки (источник — по умолчанию GitHub). */
    private fun maybeAutoCheckUpdates() {
        val last = prefs.getLong("update_checked_at", 0)
        if (System.currentTimeMillis() - last < 24 * 3600 * 1000L) return
        checkUpdates(manual = false)
    }

    private fun stepCard(emoji: String, title: String, sub: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 14, 16, 14)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        val icon = textView(emoji, 28f)
        card.addView(icon)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 0, 0)
        }
        texts.addView(textView(title, 17f, bold = true))
        texts.addView(textView(sub, 14f))
        card.addView(texts)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12)
        }
        wrap.addView(card)
        return wrap
    }

    // ---------- Режим фолбэка ----------

    private fun buildFallback() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
        }
        scroll.addView(root)
        val hint = textView("Видео само не нашлось — страница ниже, нажми ▶ в плеере", 15f, bold = true)
        modeHint = hint
        root.addView(hint)
        root.addView(smallLink("← Назад к странице серии") { engine?.goToEpisodePage() })
        // Контейнер браузера: в фолбэке — 420 dp, браузер виден, и в
        // плеере можно нажать ▶ вручную (движок перехватит manifest).
        webViewHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(CastEngine.FALLBACK_BROWSER_HEIGHT_DP)
            )
        }
        root.addView(webViewHolder)
        root.addView(textView("Найденные видео:", 15f, bold = true))
        fallbackCandidateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(fallbackCandidateBox)
        root.addView(bigButton("▶ На ТВ") { engine?.launchBestNow() })
        // v0.9.3 (#17): вход на сайте — куки уже сохраняются при каждой
        // загрузке страницы (flush в onPageFinished); кнопка — ручное
        // сохранение после логина в видимом браузере.
        root.addView(button("Сохранить вход (куки)") {
            try {
                android.webkit.CookieManager.getInstance().flush()
                toast("Вход сохранён — куки записаны")
            } catch (e: Exception) {
                toast("Не удалось сохранить куки: ${e.message}")
            }
        })
        contentRoot.addView(scroll)

        val eng = ensureEngine()
        eng.startFallback(pendingUrl, pendingEpisodeUrl, seedCandidates)
        // WebView движка — в видимый контейнер, на весь контейнер.
        eng.webView()?.let { wv ->
            webViewHolder?.removeAllViews()
            webViewHolder?.addView(wv, LinearLayout.LayoutParams(LayoutContract.WEBVIEW_WIDTH, LayoutContract.WEBVIEW_HEIGHT))
        }
        renderFallbackCandidates(eng.candidates())
    }

    /** Список найденных видео — тап = запустить это видео. */
    private fun renderFallbackCandidates(list: List<VideoCandidate>) {
        val box = fallbackCandidateBox ?: return
        box.removeAllViews()
        if (list.isEmpty()) {
            box.addView(textView("Пока ничего не найдено. Открой серию и нажми ▶ в плеере — ссылка перехватится из сети.", 14f))
            return
        }
        for (c in list) {
            val isSel = c == engine?.selectedCandidate()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundColor(Color.parseColor(if (isSel) "#1E3A2F" else "#1E1E1E"))
            }
            val title = textView(
                (if (isSel) "✓ " else "○ ") + if (c.quality == "?") c.format else "${c.quality} · ${c.format}",
                17f, bold = true)
            if (isSel) title.setTextColor(Color.parseColor("#4CAF50"))
            card.addView(title)
            card.addView(textView(VideoUrls.safeHost(c.url), 13f))
            card.setOnClickListener { engine?.launchCandidate(c) }
            box.addView(card)
        }
    }

    // ---------- Режим спаривания ----------

    private fun buildPairing() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
        }
        scroll.addView(root)
        val hint = textView("Companion не спарен — нужен PIN один раз. После спаривания видео запустится само.", 15f, bold = true)
        modeHint = hint
        root.addView(hint)
        root.addView(bigButton("1. Показать PIN на ТВ") { engine?.startPairing() })
        val pinBar = EditText(this).apply {
            setHint("PIN с экрана ТВ")
            textSize = 18f
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        pairPinBar = pinBar
        root.addView(pinBar)
        root.addView(bigButton("2. Спарить по PIN") {
            engine?.finishPairing(pinBar.text.toString().trim())
        })
        contentRoot.addView(scroll)

        val eng = ensureEngine()
        // Флоу теперь ведёт activity: сервис, ждавший PIN, больше не нужен.
        startService(CastService.cancelIntent(this))
        val host = pendingPairHost
        if (host != null) eng.setDeviceFromHost(host)
    }

    // ---------- Режим выбора ТВ ----------

    private fun buildTvPick() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)
        }
        scroll.addView(root)
        val hint = textView("Найдено несколько ТВ — выбери один:", 15f, bold = true)
        modeHint = hint
        root.addView(hint)
        tvPickList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tvPickList)
        contentRoot.addView(scroll)

        if (tvPickDevices.isNotEmpty()) {
            renderTvPickList(tvPickDevices)
        } else {
            // Сервис умер раньше, чем передал список — ищем сами.
            ensureEngine().startTvPickDiscovery()
        }
    }

    private fun renderTvPickList(devices: List<AirPlayDevice>) {
        val list = tvPickList ?: return
        list.removeAllViews()
        if (devices.isEmpty()) {
            list.addView(textView("ТВ не найден — проверь, что ТВ включён.", 14f))
            return
        }
        for (d in devices) {
            list.addView(button("${d.name} • ${d.model}") {
                // Выбор уходит обратно в сервис: он продолжит флоу.
                startService(CastService.tvPickedIntent(this, d.host, pendingUrl))
                toast("ТВ выбран: ${d.name}")
                finish()
            })
        }
    }

    // ---------- Движок + слушатель ----------

    private fun ensureEngine(): CastEngine {
        var eng = engine
        if (eng == null) {
            eng = CastEngine(this, prefs, credStore, activityListener, safeMode)
            engine = eng
        }
        return eng
    }

    private val activityListener = object : CastEngine.Listener {
        override fun onStatus(text: String) {
            runOnUiThread { modeHint?.text = text }
        }

        override fun onCandidatesChanged(candidates: List<VideoCandidate>) {
            runOnUiThread {
                if (currentMode == AutoCast.MainMode.FALLBACK) renderFallbackCandidates(candidates)
            }
        }

        override fun onLaunched(url: String) {
            runOnUiThread {
                toast("Команда VLC отправлена — смотри на ТВ")
                renderMode(AutoCast.MainMode.INSTRUCTION)
            }
        }

        /** v0.9.0: в activity авто-следующая не показывается отдельно —
         *  уведомление сервиса («Серия идёт…») уже всё сказало. */
        override fun onAutoNextArmed() {
            Log.add("app", "MainActivity: авто-следующая вооружена")
        }

        override fun onLaunchFailed(msg: String) {
            runOnUiThread { modeHint?.text = msg }
        }

        override fun onNeedPairing(host: String, pendingUrl: String?) {
            runOnUiThread {
                pendingPairHost = host
                this@MainActivity.pendingPairUrl = pendingUrl
                renderMode(AutoCast.MainMode.PAIRING)
            }
        }

        override fun onNeedTvPick(devices: List<AirPlayDevice>) {
            runOnUiThread {
                tvPickDevices = devices
                renderMode(AutoCast.MainMode.TV_PICK)
            }
        }

        override fun onDevicesChanged(devices: List<AirPlayDevice>) {
            runOnUiThread {
                if (currentMode == AutoCast.MainMode.TV_PICK) {
                    tvPickDevices = devices
                    renderTvPickList(devices)
                }
            }
        }

        override fun onTimeout(state: CastEngine.TimeoutState) {
            // В фолбэке дедлайна нет; в остальных режимах таймаут
            // означает «видео не нашлось» — показываем фолбэк.
            runOnUiThread {
                pendingUrl = state.pageUrl
                pendingEpisodeUrl = state.episodeUrl
                seedCandidates = state.candidates
                renderMode(AutoCast.MainMode.FALLBACK)
            }
        }

        override fun onPaired() {
            // v0.9.1: если спарили из блока «Настройка один раз» —
            // перерисовать инструкцию (обновить «ТВ спарен ✓»).
            runOnUiThread {
                if (currentMode == AutoCast.MainMode.INSTRUCTION) renderMode(AutoCast.MainMode.INSTRUCTION)
                else modeHint?.text = "Companion спарен ✓"
            }
        }

        override fun onPairingFailed(msg: String) {
            runOnUiThread { modeHint?.text = msg }
        }

        override fun onError(msg: String) {
            runOnUiThread { modeHint?.text = msg }
        }
    }

    // ---------- Разрешение на уведомления (Android 13+) ----------

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (prefs.getBoolean("notifAsked", false)) return
        prefs.edit().putBoolean("notifAsked", true).apply()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    // ---------- Экран краша ----------

    private fun buildCrashScreen(): View {
        val crashText = try { CrashLog.readCrashLog(this) } catch (e: Exception) { "(не читается: ${e.message})" }
        val lastMarker = try { CrashLog.lastMarker(this) } catch (e: Exception) { "(?)" }
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(16, 16, 16, 16)
        }
        scroll.addView(root)
        root.addView(textView("VLC Cast $VERSION — при прошлом запуске был вылет", 17f, bold = true))
        root.addView(textView("Причина пока неизвестна — этот лог и нужен, чтобы её найти. Ниже — стек падения и последний шаг запуска (куда дошёл до вылета).", 14f))
        root.addView(textView("Последний шаг запуска: $lastMarker", 14f, bold = true))
        val logBox = TextView(this).apply {
            text = crashText.take(8000)
            textSize = 12f
            setTextColor(Color.parseColor("#F2F2F2"))
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }
        root.addView(logBox)
        root.addView(button("Поделиться логом") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VLC Cast crash log $VERSION")
                putExtra(Intent.EXTRA_TEXT, "Последний шаг: $lastMarker\n\n$crashText")
            }
            startActivity(Intent.createChooser(send, "Отправить лог VLC Cast"))
        })
        root.addView(button("Копировать лог") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("vlccast-crash", "Последний шаг: $lastMarker\n\n$crashText"))
            toast("Лог скопирован")
        })
        root.addView(button("Продолжить в безопасном режиме (без WebView)") {
            CrashLog.clearCrashed(this)
            restartWithSafeMode(true)
        })
        root.addView(button("Запустить как обычно") {
            CrashLog.clearCrashed(this)
            restartWithSafeMode(false)
        })
        return scroll
    }

    private fun restartWithSafeMode(safe: Boolean) {
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_SAFE_MODE, safe)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(i)
        finish()
    }

    // ---------- UI-хелперы ----------

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun textView(text: String, size: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F2F2F2"))
            setPadding(8, 4, 8, 4)
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            setOnClickListener { onClick() }
        }

    private fun bigButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#FF7A00"))
            setTextColor(Color.WHITE)
            minHeight = dpToPx(64)
            setPadding(16, 12, 16, 12)
            setOnClickListener { onClick() }
        }

    private fun smallLink(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#FF7A00"))
            setOnClickListener { onClick() }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        appUpdater?.detach()
        runCatching { engine?.destroy() }
        super.onDestroy()
    }

    /**
     * v0.9.3: возврат из настроек установки из неизвестных источников.
     * Разрешили — скачивание pending-обновления начинается само;
     * нет — честный статус, повторный тап по кнопке покажет его же.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // v0.9.3 (#27): импорт файла бэкапа спаривания.
        if (requestCode == REQUEST_BACKUP_IMPORT) {
            if (resultCode != Activity.RESULT_OK || data?.data == null) return
            val uri = data.data!!
            Thread {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) {
                    Log.add("pair", "чтение бэкапа error: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
                val parsed = PairingBackup.parse(text)
                var restored = 0
                for (e in parsed.entries) {
                    try {
                        credStore.save(e.identity, e.creds)
                        restored++
                    } catch (ex: Exception) {
                        Log.add("pair", "сохранение учётки ${e.identity} error: ${ex.message}")
                    }
                }
                Log.add("pair", "импорт бэкапа: восстановлено $restored, пропущено ${parsed.skipped}")
                runOnUiThread {
                    if (restored > 0) {
                        toast("Восстановлено учёток: $restored — PIN не нужен")
                        setupPairLabel?.text = if (isPaired()) "ТВ спарен ✓" else "ТВ пока не спарен"
                    } else {
                        toast("В файле нет валидных учёток (пропущено: ${parsed.skipped})")
                    }
                }
            }.start()
            return
        }
        if (requestCode != AppUpdater.REQUEST_INSTALL_SETTINGS) return
        val updater = appUpdater ?: return
        if (updater.canRequestInstalls()) {
            updater.resetSettingsShown()
            val url = updater.pendingUrl()
            val name = updater.pendingName()
            if (url.isNullOrBlank()) {
                setUpdateStatus("Разрешение дано. Нажми «Проверить обновления» и «Скачать и установить» ещё раз.")
                return
            }
            setUpdateStatus("Разрешение дано — скачиваю $name…")
            updater.startDownload(url, name ?: "") { s -> setUpdateStatus(s) }
        } else {
            setUpdateStatus("Установка из неизвестных источников не разрешена. Разреши её в настройках (VLC Cast → установка неизвестных приложений), затем нажми «Скачать и установить» ещё раз.")
        }
    }
}
