package com.alexey.aircast.test

import com.alexey.aircast.AirPlayDevice
import com.alexey.aircast.Bplist
import com.alexey.aircast.RememberedDevice
import com.alexey.aircast.rememberedManualDevice
import com.alexey.aircast.ChaCha20Poly1305
import com.alexey.aircast.Curve25519
import com.alexey.aircast.Hkdf
import com.alexey.aircast.AutoCast
import com.alexey.aircast.AudioCast
import com.alexey.aircast.CastEngine
import com.alexey.aircast.LayoutContract
import com.alexey.aircast.Tlv8
import com.alexey.aircast.VideoCandidate
import com.alexey.aircast.VideoUrls
import com.alexey.aircast.YouTube
import com.alexey.aircast.StreamProxy
import com.alexey.aircast.StreamProbe
import com.alexey.aircast.EpisodeList
import com.alexey.aircast.PageLink
import com.alexey.aircast.SubTrack
import com.alexey.aircast.Subtitles
import com.alexey.aircast.CompanionClient
import com.alexey.aircast.TitleCleaner
import com.alexey.aircast.YouTubePlaylist
import com.alexey.aircast.CastQueue
import com.alexey.aircast.QueueItem
import com.alexey.aircast.PlaybackMemory
import com.alexey.aircast.PlaybackStore
import com.alexey.aircast.OpenSubtitles
import com.alexey.aircast.UpdateCheck
import com.alexey.aircast.DashManifest
import com.alexey.aircast.UpdateGate
import com.alexey.aircast.FileShareRoute
import com.alexey.aircast.RangeServe
import com.alexey.aircast.TempFiles
import com.alexey.aircast.JavaFileSource
import com.alexey.aircast.MasterWait
import com.alexey.aircast.ResumePlaylist
import com.alexey.aircast.TvCheck
import com.alexey.aircast.WakeOnLan
import java.io.File

fun hex(s: String): ByteArray {
    val out = ByteArray(s.length / 2)
    for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    return out
}

fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

var failures = 0
fun check(name: String, cond: Boolean, extra: String = "") {
    if (cond) println("PASS $name") else { println("FAIL $name $extra"); failures++ }
}

fun main() {
    cryptoTests()

    // --- filterVideoUrls (v0.2.0): в списке — только настоящие видео ---
    val mixed = listOf(
        "https://cdn.example.test/hls/master.m3u8?token=abc",
        "https://kodik.example.test/storage.html",
        "https://cdn.example.test/v/720.mp4?token=abc",
        "https://cdn.plyr.io/static/blank.mp4",
        "https://api.example.test/api/video/291174",
        "https://alloha.example.test/?token_movie=abc",
    )
    val onlyVideo = VideoUrls.filterVideoUrls(mixed)
    check("filter-video-only", onlyVideo.size == 2 && onlyVideo.all { VideoUrls.isVideoUrl(it) }, onlyVideo.toString())

    // --- Remembered/manual TV (v0.2.2): ручной ТВ хранит model/features ---
    val rem = RememberedDevice("192.168.1.10", "Apple TV 4k", 0x3c177fde4a7fdfd5L, "Apple TV 4K", "AA:BB:CC:DD:EE:FF")
    check("remembered-known", rem.known)
    val remDev = rememberedManualDevice("192.168.1.10", rem)
    check("remembered-manual-same-ip", remDev.features == 0x3c177fde4a7fdfd5L && remDev.model == "Apple TV 4K" && remDev.name == "Apple TV 4k" && remDev.supportsV1 && remDev.supportsV2, remDev.toString())
    check("remembered-manual-other-ip", rememberedManualDevice("192.168.1.99", rem).let { it.features == 0L && it.model == "?" && it.name == "Apple TV (вручную)" })
    check("remembered-manual-none", rememberedManualDevice("192.168.1.10", null).let { it.features == 0L && it.model == "?" })
    check("remembered-manual-zero", rememberedManualDevice("192.168.1.10", RememberedDevice("192.168.1.10", "Apple TV (вручную)", 0L, "?", "192.168.1.10")).features == 0L)
    check("remembered-from", RememberedDevice.from(AirPlayDevice("Apple TV 4k", "192.168.1.10", 7000, emptyMap(), 0x3c177fde4a7fdfd5L, "Apple TV 4K", "AA:BB:CC:DD:EE:FF")).known)
    check("remembered-from-zero", !RememberedDevice.from(AirPlayDevice("Apple TV (вручную)", "192.168.1.10", 7000, emptyMap(), 0L, "?", "192.168.1.10")).known)

    // --- Версия в шапке = версия сборки (v0.3.3) ---
    // v0.3.1: шапка показывала 0.3.0-command при коде 0.3.1 — VERSION
    // не был поднят. build.sh теперь берёт --version-name из
    // MainActivity.VERSION (единственный источник истины), поэтому
    // тест проверяет: (а) VERSION равен строке этого релиза;
    // (б) build.sh выводит version-name из MainActivity, а не хардкод;
    // (в) --version-code равен 34. MainActivity не компилируется в
    // JVM-тестах (Android-классы) — читаем исходник как текст.
    val mainActivitySrc = File("app/src/main/java/com/alexey/aircast/MainActivity.kt").readText()
    val shownVersion = Regex("const val VERSION = \"([^\"]+)\"").find(mainActivitySrc)?.groupValues?.get(1)
    check("version-label", shownVersion == "0.9.3.5", "VERSION=$shownVersion")
    val buildSh = File("build.sh").readText()
    check("version-build-derives-name", buildSh.contains("const val VERSION") && !Regex("--version-name 0\\.3").containsMatchIn(buildSh),
        "build.sh must derive --version-name from MainActivity.VERSION, not hardcode it")
    check("version-code-38", buildSh.contains("--version-code 38"), "no --version-code 38 in build.sh")
    check("version-code-const-38", Regex("const val VERSION_CODE = 38").containsMatchIn(mainActivitySrc),
        "MainActivity.VERSION_CODE must be 38 (update check compares against it)")
    // v0.9.3.1: краш при запуске — двухаргументный registerReceiver
    // на Android 13+ бросает SecurityException. Статическая проверка
    // исходника AppUpdater: флаг обязан быть, двухаргументной
    // формы — не быть. JVM-тест сам вызов framework не поймает.
    // v0.9.3.5: флаг обязан быть RECEIVER_EXPORTED, а не
    // NOT_EXPORTED: NOT_EXPORTED из 0.9.3.1 молча отрезал доставку
    // системного ACTION_DOWNLOAD_COMPLETE (DownloadManager — другой
    // UID), и установка после загрузки не начиналась.
    val appUpdaterSrc = File("app/src/main/java/com/alexey/aircast/AppUpdater.kt").readText()
    check("updater-receiver-flag", appUpdaterSrc.contains("registerReceiver(r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)") &&
        !appUpdaterSrc.contains("registerReceiver(r, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))"),
        "AppUpdater must register the download receiver with RECEIVER_EXPORTED (NOT_EXPORTED blocks the system broadcast)")
    // v0.9.3.3 (Telegram: «Не удалось открыть файл»): копия файла —
    // В САМОЙ ShareReceiverActivity (полупрозрачная тема, живёт до
    // конца копии). Дизайн 0.9.3.2 (только форвард + finish() сразу,
    // копия в сервисе) отменён живым логом: grant до сервиса не
    // доходит (ENOENT). Статическая проверка: в activity есть копия
    // с таймаутом, прогресс на экране, старт сервиса с готовым путём
    // + finish() — после копии, а не в onCreate. Тема — Translucent,
    // а не NoDisplay (см. v0933-manifest-share-translucent ниже).
    val shareReceiverSrc = File("app/src/main/java/com/alexey/aircast/ShareReceiverActivity.kt").readText()
    check("share-receiver-copy-0933", shareReceiverSrc.contains("fun copySharedFile") &&
        shareReceiverSrc.contains("copySharedFileWithTimeout") &&
        shareReceiverSrc.contains("CastService.filePathCastIntent") &&
        shareReceiverSrc.contains("setContentView") &&
        shareReceiverSrc.contains("Копирую файл") &&
        shareReceiverSrc.contains("CastService.fileCopyIntent"),
        "ShareReceiverActivity must copy the file itself (translucent, lives until the copy ends), then start the service with the path; FILE_COPY stays as fallback")
    // v0.9.3.3: запуски во VLC сериализованы (лог 07:51:04: 5
    // параллельных launchVlc → EOF/Connection reset). Статическая
    // проверка: флаг launchInFlight, гарды в launchAttempt/launchVlc,
    // снятие в ui-блоках исхода, сброс в resetFlow.
    val engineSrc0933 = File("app/src/main/java/com/alexey/aircast/CastEngine.kt").readText()
    check("launch-serialized", engineSrc0933.contains("launchInFlight") &&
        engineSrc0933.contains("if (launchInFlight)") &&
        engineSrc0933.contains("launchInFlight = true") &&
        engineSrc0933.contains("launchInFlight = false"),
        "launchAttempt/launchVlc must serialize Companion launches (launchInFlight guard)")
    // --- v0.6.0: зеркало, мастер и ручной ввод удалены; share intent ---
    // Зеркало («вообще не интересно», его слово 2026-11-10) и мастер
    // 1-2-3 удалены из приложения полностью. Эти проверки фиксируют,
    // что в MainActivity не осталось ни вызовов зеркала, ни мастера,
    // ни ручного ввода ссылок: любой их возврат — регрессия.
    check("v060-no-mirror-calls",
        !mainActivitySrc.contains("MirrorService(") && !mainActivitySrc.contains("startMirror(") &&
        !mainActivitySrc.contains("stopMirror(") && !mainActivitySrc.contains("MirrorClient"),
        "mirror calls in MainActivity")
    check("v060-no-wizard",
        !mainActivitySrc.contains("WizardStep") && !mainActivitySrc.contains("WizardFlow") &&
        !mainActivitySrc.contains("advanceToStep(") && !mainActivitySrc.contains("goToWizardStep("),
        "wizard leftovers in MainActivity")
    check("v060-no-manual-input",
        !mainActivitySrc.contains("TEST_VIDEOS") && !mainActivitySrc.contains("addManualUrl") &&
        !mainActivitySrc.contains("showTestVideos") && !mainActivitySrc.contains("AspectMode"),
        "manual input / test videos / aspect leftovers in MainActivity")
    check("v060-no-hap-pairing",
        !mainActivitySrc.contains("HapPairing") && !mainActivitySrc.contains("startPairSetup") &&
        !mainActivitySrc.contains("finishPairSetup"),
        "AirPlay HAP pairing was only for mirror/direct cast — must be gone")
    // Переименование: label, app_name, шапка лога.
    val manifest = File("app/src/main/AndroidManifest.xml").readText()
    check("v060-manifest-label", manifest.contains("android:label=\"VLC Cast\""),
        "manifest label is not 'VLC Cast'")
    // --- v0.7.0: share — в ShareReceiverActivity, MainActivity — только лаунчер ---
    // Share идёт в прозрачную ShareReceiverActivity (v0.9.3.3:
    // Theme.Translucent.NoTitleBar — ветка файла копирует в живой
    // activity; раньше — Theme.NoDisplay): для ссылок она стартует
    // CastService и сразу finish(), приложение при шаринге не
    // открывается. MainActivity — только LAUNCHER (тап по
    // иконке = экран-инструкция); режимы fallback/pairing/tvpick —
    // только по extras из уведомлений сервиса.
    val mainSection = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore("</activity>")
    val shareSection = manifest.substringAfter("android:name=\".ShareReceiverActivity\"").substringBefore("</activity>")
    check("v070-manifest-share-receiver",
        shareSection.contains("android.intent.action.SEND") && shareSection.contains("text/plain") &&
        shareSection.contains("android.intent.action.VIEW") && shareSection.contains("android:scheme=\"http\"") &&
        shareSection.contains("android:scheme=\"https\""),
        "SEND/VIEW intent-filters must live on ShareReceiverActivity")
    // v0.9.3.3: тема ShareReceiverActivity — Translucent, НЕ
    // NoDisplay: ветка файла копирует в живой activity (полупрозрачный
    // экран с прогрессом), а NoDisplay обязана finish() в onCreate.
    // Для ссылок поведение то же: старт сервиса + finish() сразу,
    // окна не видно (контента нет, фон прозрачный).
    check("v0933-manifest-share-translucent",
        shareSection.contains("Theme.Translucent.NoTitleBar") && !shareSection.contains("Theme.NoDisplay") &&
        shareSection.contains("excludeFromRecents=\"true\""),
        "ShareReceiverActivity must be Translucent (not NoDisplay) + excludeFromRecents")
    check("v070-manifest-main-launcher-only",
        mainSection.contains("android.intent.action.MAIN") && mainSection.contains("android.intent.category.LAUNCHER") &&
        !mainSection.contains("android.intent.action.SEND") && !mainSection.contains("android.intent.action.VIEW"),
        "MainActivity must have ONLY the LAUNCHER filter — share goes to ShareReceiverActivity")
    check("v070-manifest-single-top", manifest.contains("android:launchMode=\"singleTop\""),
        "singleTop missing — уведомление при открытом приложении не придёт в onNewIntent")
    check("v070-manifest-cast-service",
        manifest.contains("android:name=\".CastService\"") && manifest.contains("foregroundServiceType=\"dataSync\""),
        "CastService with foregroundServiceType dataSync missing")
    check("v070-manifest-fgs-permissions",
        manifest.contains("android.permission.FOREGROUND_SERVICE\"") &&
        manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC\"") &&
        manifest.contains("android.permission.POST_NOTIFICATIONS\""),
        "FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS missing")
    check("v060-manifest-no-mirror",
        !manifest.contains("MirrorService") && !manifest.contains("DumpProvider") &&
        !manifest.contains("FOREGROUND_SERVICE_MEDIA_PROJECTION"),
        "mirror service/provider/permissions left in manifest")
    check("v060-manifest-package", manifest.contains("package=\"com.alexey.aircast\""),
        "package must stay com.alexey.aircast (update-over-install)")
    val stringsXml = File("app/src/main/res/values/strings.xml").readText()
    check("v060-strings-app-name", stringsXml.contains("<string name=\"app_name\">VLC Cast</string>"),
        "app_name is not 'VLC Cast'")
    // BipBop HLS — контрольный URL живёт в CompanionClient (мок-тест
    // проверяет deep link на нём); TEST_VIDEOS удалён с кнопкой.
    val companionSrc = File("app/src/main/java/com/alexey/aircast/CompanionClient.kt").readText()
    check("v060-companion-bipbop", companionSrc.contains("https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"),
        "BipBop test HLS missing from CompanionClient")
    check("v060-no-direct-cast",
        !mainActivitySrc.contains("AirPlayVideoClient") && !mainActivitySrc.contains("HlsProxy"),
        "dead direct cast must not return")

    // --- v0.7.0: раскладка — один contentRoot + фолбэк-браузер 420 dp ---
    // Диагностики как экрана больше нет (логи пишутся в файл, экран
    // краша сохранён). Тап по иконке — только инструкция из 3 шагов
    // шаринга; MainActivity не обрабатывает share и не грузит
    // последнюю страницу при запуске.
    val mainSrc = File("app/src/main/java/com/alexey/aircast/MainActivity.kt").readText()
    val engineSrc = File("app/src/main/java/com/alexey/aircast/CastEngine.kt").readText()
    check("layout-contract-values",
        LayoutContract.SCREEN_WIDTH == -1 && LayoutContract.SCREEN_HEIGHT == -1 &&
        LayoutContract.WEBVIEW_WIDTH == -1 && LayoutContract.WEBVIEW_HEIGHT == -1,
        "contract must be MATCH_PARENT(-1) screens/webview")
    check("layout-engine-fallback-height",
        engineSrc.contains("FALLBACK_BROWSER_HEIGHT_DP = 420"),
        "CastEngine.FALLBACK_BROWSER_HEIGHT_DP must be 420")
    check("layout-src-fallback-height",
        mainSrc.contains("dpToPx(CastEngine.FALLBACK_BROWSER_HEIGHT_DP)"),
        "fallback browser must be 420dp from CastEngine.FALLBACK_BROWSER_HEIGHT_DP")
    check("layout-src-webview-explicit",
        mainSrc.contains("webViewHolder?.addView(wv, LinearLayout.LayoutParams(LayoutContract.WEBVIEW_WIDTH, LayoutContract.WEBVIEW_HEIGHT))"),
        "WebView must be added to the holder with explicit MATCH_PARENT params")
    check("layout-src-no-diag",
        !mainSrc.contains("buildDiagScreen") && !mainSrc.contains("checkServerInfo") &&
        !mainSrc.contains("Диагностика и журнал"),
        "Diagnostics screen is gone in v0.7.0 (logs still go to file)")
    check("layout-src-instruction-only",
        mainSrc.contains("Откройте страницу с видео в Chrome или другом приложении") &&
        mainSrc.contains("Нажмите «Поделиться»") && mainSrc.contains("VLC Cast") &&
        mainSrc.contains("Видео само запустится на Apple TV"),
        "instruction screen must have the 3 share steps")
    check("layout-src-no-share-handling",
        !mainSrc.contains("handleIntent") && !mainSrc.contains("android.intent.action.SEND") &&
        !mainSrc.contains("startPageFlow(prefs.getString(\"lastUrl\""),
        "MainActivity must not handle share intents or auto-load the last URL on launch")

    // --- v0.6.0: AutoCast — share intent и авто-выбор кандидата ---
    check("autocast-extract-url",
        AutoCast.extractFirstUrl("Смотри https://old.yummyani.me/catalog/item/rozario-vampir-1 — классное аниме") ==
            "https://old.yummyani.me/catalog/item/rozario-vampir-1")
    check("autocast-extract-none", AutoCast.extractFirstUrl("просто текст без ссылки") == null)
    check("autocast-extract-blank", AutoCast.extractFirstUrl(null) == null)
    check("autocast-extract-trailing-punct",
        AutoCast.extractFirstUrl("глянь (https://example.test/a.mp4).") == "https://example.test/a.mp4")
    check("autocast-classify-direct-mp4",
        AutoCast.classifyUrl("https://cdn.example.test/v/720.mp4?token=abc") == AutoCast.ShareKind.DIRECT_VIDEO)
    check("autocast-classify-direct-m3u8",
        AutoCast.classifyUrl("https://cdn.example.test/hls/master.m3u8") == AutoCast.ShareKind.DIRECT_VIDEO)
    check("autocast-classify-page",
        AutoCast.classifyUrl("https://old.yummyani.me/catalog/item/rozario-vampir-1") == AutoCast.ShareKind.PAGE)
    check("autocast-classify-not-url",
        AutoCast.classifyUrl("просто текст") == AutoCast.ShareKind.NOT_A_URL)
    check("autocast-classify-shared-direct",
        AutoCast.classifySharedText("https://cdn.example.test/v/720.mp4") == AutoCast.ShareKind.DIRECT_VIDEO)
    check("autocast-classify-shared-not-url",
        AutoCast.classifySharedText("привет, как дела?") == AutoCast.ShareKind.NOT_A_URL)
    val pickMixed = listOf(
        VideoCandidate("https://cdn.example.test/v/720.mp4", "mp4", "720p", null, "тест"),
        VideoCandidate("https://cdn.example.test/hls/master.m3u8", "m3u8", "480p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест"),
    )
    check("autocast-pick-hls-first",
        AutoCast.pickBest(pickMixed)?.url == "https://cdn.example.test/hls/master.m3u8",
        "HLS must beat mp4 even at lower resolution: ${AutoCast.pickBest(pickMixed)}")
    val pickMp4Only = listOf(
        VideoCandidate("https://cdn.example.test/v/480.mp4", "mp4", "480p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/720.mp4", "mp4", "720p", null, "тест"),
    )
    check("autocast-pick-higher-res",
        AutoCast.pickBest(pickMp4Only)?.url == "https://cdn.example.test/v/1080.mp4",
        "1080p must beat 720p/480p among mp4: ${AutoCast.pickBest(pickMp4Only)}")
    val pickWithJunk = listOf(
        VideoCandidate("https://cdn.plyr.io/static/blank.mp4", "mp4", "?", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/720.mp4", "mp4", "720p", null, "тест"),
    )
    check("autocast-pick-skips-placeholder",
        AutoCast.pickBest(pickWithJunk)?.url == "https://cdn.example.test/v/720.mp4",
        "placeholder blank.mp4 must be skipped: ${AutoCast.pickBest(pickWithJunk)}")
    val pickTestOnly = listOf(
        VideoCandidate("https://example.test/t.mp4", "mp4", "1080p", null, "тест", directOnly = true),
    )
    check("autocast-pick-skips-direct-only",
        AutoCast.pickBest(pickTestOnly) == null,
        "directOnly (test) candidates must be skipped")
    check("autocast-pick-empty", AutoCast.pickBest(emptyList()) == null)

    // --- v0.7.0: режимы MainActivity, тексты уведомлений, новые компоненты ---
    check("mode-instruction-default",
        AutoCast.modeFromExtra(null) == AutoCast.MainMode.INSTRUCTION &&
        AutoCast.modeFromExtra("") == AutoCast.MainMode.INSTRUCTION &&
        AutoCast.modeFromExtra("bogus") == AutoCast.MainMode.INSTRUCTION &&
        AutoCast.modeFromExtra("instruction") == AutoCast.MainMode.INSTRUCTION)
    check("mode-fallback", AutoCast.modeFromExtra("fallback") == AutoCast.MainMode.FALLBACK)
    check("mode-pairing", AutoCast.modeFromExtra("pairing") == AutoCast.MainMode.PAIRING)
    check("mode-tv-pick", AutoCast.modeFromExtra("tvpick") == AutoCast.MainMode.TV_PICK)
    check("notif-texts",
        AutoCast.notifProgressText() == "Отправляю видео на Apple TV…" &&
        AutoCast.notifSuccessText() == "Видео запущено на Apple TV ✓" &&
        AutoCast.notifNotFoundText() == "Видео не найдено — открыть VLC Cast" &&
        AutoCast.notifNeedPairingText() == "Нужно спарить Apple TV — открыть VLC Cast" &&
        AutoCast.notifNeedTvText() == "Выбери ТВ — открыть VLC Cast" &&
        AutoCast.notifLaunchFailedText() == "Запуск не удался — открыть VLC Cast")
    val receiverSrc = File("app/src/main/java/com/alexey/aircast/ShareReceiverActivity.kt").readText()
    check("receiver-foreground-service",
        receiverSrc.contains("startForegroundService") && receiverSrc.contains("finish()") &&
        receiverSrc.contains("ACTION_SEND") && receiverSrc.contains("extractFirstUrl"),
        "ShareReceiverActivity must start CastService as foreground and finish immediately")
    val serviceSrc = File("app/src/main/java/com/alexey/aircast/CastService.kt").readText()
    check("service-foreground",
        serviceSrc.contains("startForeground(NOTIF_ID") && serviceSrc.contains("STOP_FOREGROUND_DETACH") &&
        serviceSrc.contains("ACTION_TV_PICKED") && serviceSrc.contains("ACTION_CANCEL"),
        "CastService must go foreground, detach on end, handle TV_PICKED/CANCEL")
    check("engine-host-agnostic",
        engineSrc.contains("class CastEngine") && engineSrc.contains("fun startShare") &&
        engineSrc.contains("fun startFallback") && engineSrc.contains("fun startPairing") &&
        engineSrc.contains("fun finishPairing") && engineSrc.contains("fun destroy") &&
        engineSrc.contains("fun continueAfterTvPick") && engineSrc.contains("interface Listener"),
        "CastEngine must expose startShare/startFallback/pairing/destroy/continueAfterTvPick + Listener")

    // --- v0.8.0: YouTube — InnerTube (VISIONOS primary, ANDROID fallback) ---
    check("yt-video-id-watch",
        YouTube.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ")
    check("yt-video-id-watch-params",
        YouTube.extractVideoId("https://www.youtube.com/watch?list=PLx&t=42s&v=dQw4w9WgXcQ") == "dQw4w9WgXcQ")
    check("yt-video-id-short",
        YouTube.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc") == "dQw4w9WgXcQ")
    check("yt-video-id-shorts",
        YouTube.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ") == "dQw4w9WgXcQ")
    check("yt-video-id-embed",
        YouTube.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ") == "dQw4w9WgXcQ")
    check("yt-video-id-live",
        YouTube.extractVideoId("https://www.youtube.com/live/dQw4w9WgXcQ") == "dQw4w9WgXcQ")
    check("yt-video-id-none",
        YouTube.extractVideoId("https://www.youtube.com/feed/subscriptions") == null &&
        YouTube.extractVideoId("https://old.yummyani.me/catalog/item/x") == null)
    check("yt-is-youtube",
        YouTube.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ") &&
        YouTube.isYouTubeUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ") &&
        YouTube.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ") &&
        YouTube.isYouTubeUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ") &&
        !YouTube.isYouTubeUrl("https://www.youtubetomp3.example/watch?v=dQw4w9WgXcQ") &&
        !YouTube.isYouTubeUrl("https://example.test/watch?v=dQw4w9WgXcQ"))
    val ytReqV = YouTube.playerRequestJson(YouTube.Client.VISIONOS, "dQw4w9WgXcQ", "CgtQWEJCYVRzQV9nby1R")
    check("yt-req-visionos",
        ytReqV.contains("\"clientName\":\"VISIONOS\"") && ytReqV.contains("\"clientVersion\":\"1.02\"") &&
        ytReqV.contains("\"deviceModel\":\"RealityDevice17,1\"") &&
        ytReqV.contains("\"videoId\":\"dQw4w9WgXcQ\"") &&
        ytReqV.contains("\"visitorData\":\"CgtQWEJCYVRzQV9nby1R\"") &&
        ytReqV.contains("\"racyCheckOk\":true") && ytReqV.contains("\"contentCheckOk\":true"),
        ytReqV.take(200))
    check("yt-req-visionos-no-visitor",
        !YouTube.playerRequestJson(YouTube.Client.VISIONOS, "dQw4w9WgXcQ", null).contains("visitorData"))
    val ytReqA = YouTube.playerRequestJson(YouTube.Client.ANDROID, "dQw4w9WgXcQ", null)
    check("yt-req-android",
        ytReqA.contains("\"clientName\":\"ANDROID\"") && ytReqA.contains("\"clientVersion\":\"21.26.364\"") &&
        ytReqA.contains("\"androidSdkVersion\":30") &&
        ytReqA.contains("\"userAgent\":\"com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip\""),
        ytReqA.take(200))
    val ytHeadersV = YouTube.playerRequestHeaders(YouTube.Client.VISIONOS, "CgtQWEJCYVRzQV9nby1R")
    check("yt-headers-visionos",
        ytHeadersV["X-YouTube-Client-Name"] == "101" && ytHeadersV["X-YouTube-Client-Version"] == "1.02" &&
        ytHeadersV["X-Goog-Visitor-Id"] == "CgtQWEJCYVRzQV9nby1R" &&
        ytHeadersV["Content-Type"] == "application/json")
    check("yt-headers-android-no-visitor",
        YouTube.playerRequestHeaders(YouTube.Client.ANDROID, null)["X-YouTube-Client-Name"] == "3" &&
        !YouTube.playerRequestHeaders(YouTube.Client.ANDROID, null).containsKey("X-Goog-Visitor-Id"))
    // Фикстура: ответ VISIONOS — hlsManifestUrl + muxed 18 (url) / 22 (cipher) / 37 (url).
    val ytVisionos = "{\"responseContext\":{\"visitorData\":\"CgtQWEJCYVRzQV9nby1R\"},\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"hlsManifestUrl\":\"https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1795000000/ei/abc/ip/1.2.3.4/id/xyz.1/itag/0/source/youtube/file/index.m3u8\",\"formats\":[{\"itag\":18,\"mimeType\":\"video/mp4\",\"bitrate\":500000,\"width\":640,\"height\":360,\"url\":\"https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1795000000\\u0026ei=abc\\u0026ip=1.2.3.4\\u0026id=xyz\\u0026itag=18\\u0026source=youtube\"},{\"itag\":22,\"mimeType\":\"video/mp4\",\"bitrate\":2000000,\"width\":1280,\"height\":720,\"signatureCipher\":\"s=ABC123&sp=sig&url=https%3A%2F%2Frr1---sn-abc.googlevideo.com%2Fvideoplayback%3Fexpire%3D1%26itag%3D22\"},{\"itag\":37,\"mimeType\":\"video/mp4\",\"bitrate\":4000000,\"width\":1920,\"height\":1080,\"url\":\"https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1795000000\\u0026ei=abc\\u0026ip=1.2.3.4\\u0026id=xyz\\u0026itag=37\\u0026source=youtube\"}],\"adaptiveFormats\":[{\"itag\":137,\"mimeType\":\"video/mp4\",\"url\":\"https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1\\u0026itag=137\"}]}}"
    val ytParsed = YouTube.parsePlayerResponse(ytVisionos)
    check("yt-parse-hls",
        ytParsed.hlsUrl == "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1795000000/ei/abc/ip/1.2.3.4/id/xyz.1/itag/0/source/youtube/file/index.m3u8",
        ytParsed.hlsUrl ?: "null")
    check("yt-parse-muxed-only",
        ytParsed.muxed.size == 3 && ytParsed.muxed.map { it.itag }.toSet() == setOf(18, 22, 37),
        "adaptive itag 137 must not be in formats: ${ytParsed.muxed}")
    check("yt-parse-cipher-flag",
        ytParsed.muxed.first { it.itag == 22 }.hasCipher &&
        ytParsed.muxed.first { it.itag == 22 }.url == null &&
        ytParsed.muxed.first { it.itag == 18 }.url?.contains("itag=18") == true &&
        !ytParsed.hasOnlyCipher)
    check("yt-parse-unescape",
        ytParsed.muxed.first { it.itag == 18 }.url?.contains("&ei=abc&ip=1.2.3.4") == true,
        "\\\\u0026 must become &: ${ytParsed.muxed.first { it.itag == 18 }.url}")
    check("yt-pick-hls-first",
        YouTube.pickStream(ytParsed)?.first == ytParsed.hlsUrl && YouTube.pickStream(ytParsed)?.second == "hls")
    check("yt-parse-playability",
        ytParsed.playability == "OK" && YouTube.parseVisitorData(ytVisionos) == "CgtQWEJCYVRzQV9nby1R")
    // Фикстура: ответ ANDROID — без hls, только itag 18 с url.
    val ytAndroid = "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"formats\":[{\"itag\":18,\"mimeType\":\"video/mp4\",\"url\":\"https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1795000000\\u0026itag=18\"}]}}"
    val ytParsedA = YouTube.parsePlayerResponse(ytAndroid)
    check("yt-android-pick-18",
        YouTube.pickStream(ytParsedA)?.first?.contains("itag=18") == true &&
        YouTube.pickStream(ytParsedA)?.second == "mp4")
    // Фикстура: только cipher — честный ноль.
    val ytCipher = "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"formats\":[{\"itag\":22,\"signatureCipher\":\"s=ABC&sp=sig&url=https%3A%2F%2Fx\"},{\"itag\":18,\"signatureCipher\":\"s=DEF&sp=sig&url=https%3A%2F%2Fx\"}]}}"
    val ytParsedC = YouTube.parsePlayerResponse(ytCipher)
    check("yt-cipher-only",
        ytParsedC.hasOnlyCipher && YouTube.pickStream(ytParsedC) == null)
    // Фикстура: SABR-only — ни форматов, ни hls.
    val ytSabr = "{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"adaptiveFormats\":[{\"itag\":137,\"serverAbrStreamingUrl\":\"https://example.test/sabr\"}]}}"
    check("yt-sabr-empty",
        YouTube.parsePlayerResponse(ytSabr).let { it.hlsUrl == null && it.muxed.isEmpty() && YouTube.pickStream(it) == null })
    check("yt-itag-rank",
        YouTube.itagRank(37) > YouTube.itagRank(22) && YouTube.itagRank(22) > YouTube.itagRank(18) &&
        YouTube.muxedSorted(ytParsed.muxed).first().itag == 37)

    // --- v0.8.0: универсальные плееры — JS-скан v2, master-плейлисты, прокси ---
    check("player-iframe-sibnet",
        VideoUrls.isPlayerIframe("https://video.sibnet.ru/shell.php?videoid=123456"))
    check("player-iframe-vk",
        VideoUrls.isPlayerIframe("https://vk.com/video_ext.php?oid=1&id=2"))
    check("player-iframe-ok",
        VideoUrls.isPlayerIframe("https://ok.ru/videoembed/123456"))
    check("player-iframe-cvh",
        VideoUrls.isPlayerIframe("https://cdn.example.test/cdn-iframe/123/dub/1/1"))
    check("player-iframe-voidboost",
        VideoUrls.isPlayerIframe("https://voidboost.example.test/embed/abc"))
    check("player-iframe-aniboom",
        VideoUrls.isPlayerIframe("https://aniboom.example.test/embed/abc"))
    check("player-iframe-catalog-false",
        !VideoUrls.isPlayerIframe("https://example.test/catalog/item/x") &&
        !VideoUrls.isPlayerIframe("https://example.test/news/123"))
    check("master-playlist-yes",
        VideoUrls.isMasterPlaylist("https://cdn.example.test/hls/master.m3u8") &&
        VideoUrls.isMasterPlaylist("https://cdn.example.test/hls/playlist.m3u8"))
    check("master-playlist-no",
        !VideoUrls.isMasterPlaylist("https://cdn.example.test/720.mp4:hls:manifest.m3u8") &&
        !VideoUrls.isMasterPlaylist("https://cdn.example.test/hls/720p/index.m3u8") &&
        !VideoUrls.isMasterPlaylist("https://cdn.example.test/v/720.mp4"))
    check("resolve-url-abs-path",
        VideoUrls.resolveUrl("https://video.sibnet.ru/shell.php?videoid=1", "/v/abc/123.mp4") ==
            "https://video.sibnet.ru/v/abc/123.mp4")
    check("resolve-url-protocol-relative",
        VideoUrls.resolveUrl("https://example.test/page", "//cdn.example.test/x.m3u8") ==
            "https://cdn.example.test/x.m3u8")
    check("resolve-url-relative",
        VideoUrls.resolveUrl("https://example.test/a/b/page.html", "../hls/x.m3u8") ==
            "https://example.test/a/hls/x.m3u8")
    // Фикстура SCAN_JS v2: video + iframe + urls (Playerjs file, JWPlayer
    // sources, hls.loadSource) + rel (JWPlayer src) — как отдаёт JS.
    val scanV2 = "\"{\\\"videos\\\":[{\\\"src\\\":\\\"https://cdn.example.test/v/720.mp4\\\",\\\"w\\\":1280,\\\"h\\\":720}],\\\"iframes\\\":[\\\"https://kodikplayer.com/season/1/abc/720p\\\"],\\\"urls\\\":[\\\"https://cdn.example.test/hls/master.m3u8\\\",\\\"//cdn2.example.test/stream.m3u8\\\"],\\\"rel\\\":[\\\"/v/token123/456.mp4\\\"],\\\"url\\\":\\\"https://example.test/page\\\",\\\"title\\\":\\\"t\\\"}\""
    val parsedV2 = VideoUrls.parseScanResult(scanV2)
    check("scan-v2-videos-iframes",
        parsedV2.videos.size == 1 && parsedV2.videos[0].src == "https://cdn.example.test/v/720.mp4" &&
        parsedV2.iframes.size == 1 && parsedV2.iframes[0].contains("kodikplayer"),
        parsedV2.toString())
    check("scan-v2-urls",
        parsedV2.urls.size == 2 && parsedV2.urls[0] == "https://cdn.example.test/hls/master.m3u8" &&
        parsedV2.urls[1] == "//cdn2.example.test/stream.m3u8",
        parsedV2.urls.toString())
    check("scan-v2-rel",
        parsedV2.rel.size == 1 && parsedV2.rel[0] == "/v/token123/456.mp4",
        parsedV2.rel.toString())
    // Aniboom: master побеждает вариант (иначе — видео без звука).
    val pickAniboom = listOf(
        VideoCandidate("https://cdn.example.test/hls/720p/index.m3u8", "m3u8", "720p", null, "тест"),
        VideoCandidate("https://cdn.example.test/hls/master.m3u8", "m3u8", "?", null, "тест"),
    )
    check("pick-master-first",
        AutoCast.pickBest(pickAniboom)?.url == "https://cdn.example.test/hls/master.m3u8",
        "master playlist must beat a variant: ${AutoCast.pickBest(pickAniboom)}")
    // Kodik: master'а нет — обычный ранжир (регрессия).
    val pickKodik = listOf(
        VideoCandidate("https://p14.solodcdn.com/s/m/abc/720.mp4:hls:manifest.m3u8", "m3u8", "720p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест"),
    )
    check("pick-kodik-regression",
        AutoCast.pickBest(pickKodik)?.url?.contains("solodcdn") == true,
        "Kodik HLS must still beat mp4: ${AutoCast.pickBest(pickKodik)}")
    // Прокси: только Referer-хосты и только с Referer.
    check("proxy-sibnet",
        StreamProxy.needsProxy("https://dv97.sibnet.ru/v/abc/1.mp4?st=x&e=123", "https://video.sibnet.ru/shell.php?videoid=1"))
    check("proxy-okcdn",
        StreamProxy.needsProxy("https://vd423.okcdn.ru/video.m3u8?expires=1&sig=x", "https://ok.ru/video/123"))
    check("proxy-no-referer",
        !StreamProxy.needsProxy("https://dv97.sibnet.ru/v/abc/1.mp4", null) &&
        !StreamProxy.needsProxy("https://dv97.sibnet.ru/v/abc/1.mp4", ""))
    check("proxy-kodik-direct",
        !StreamProxy.needsProxy("https://p14.solodcdn.com/s/m/abc/720.mp4:hls:manifest.m3u8", "https://old.yummyani.me/catalog/item/x") &&
        !StreamProxy.needsProxy("https://stream.voidboost.cc/abc/720.mp4:hls:manifest.m3u8", "https://rezka.ag/film/1"))
    check("proxy-url-build",
        StreamProxy.proxyStreamUrl("192.168.1.20", 54321, "https://dv97.sibnet.ru/v/abc/1.mp4?st=x&e=1", "https://video.sibnet.ru/shell.php?videoid=1") ==
            "http://192.168.1.20:54321/stream?u=https%3A%2F%2Fdv97.sibnet.ru%2Fv%2Fabc%2F1.mp4%3Fst%3Dx%26e%3D1&r=https%3A%2F%2Fvideo.sibnet.ru%2Fshell.php%3Fvideoid%3D1")
    val proxyForTest: (String) -> String = { abs -> "http://192.168.1.20:54321/stream?u=" + java.net.URLEncoder.encode(abs, "UTF-8") + "&r=R" }
    val playlistIn = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a1\",URI=\"audio/index.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"a1\"\n720p/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=800000,AUDIO=\"a1\"\n480p/index.m3u8\n"
    val playlistOut = StreamProxy.rewritePlaylist(playlistIn, "https://cdn.example.test/hls/master.m3u8", proxyForTest)
    check("proxy-rewrite-segments",
        playlistOut.contains("u=https%3A%2F%2Fcdn.example.test%2Fhls%2F720p%2Findex.m3u8") &&
        playlistOut.contains("u=https%3A%2F%2Fcdn.example.test%2Fhls%2F480p%2Findex.m3u8") &&
        playlistOut.contains("URI=\"http://192.168.1.20:54321/stream?u=https%3A%2F%2Fcdn.example.test%2Fhls%2Faudio%2Findex.m3u8"),
        playlistOut)
    val playlistKey = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\nseg0.ts\nhttps://cdn.example.test/abs/seg1.ts\n"
    val playlistKeyOut = StreamProxy.rewritePlaylist(playlistKey, "https://cdn.example.test/hls/index.m3u8", proxyForTest)
    check("proxy-rewrite-key",
        playlistKeyOut.contains("URI=\"http://192.168.1.20:54321/stream?u=https%3A%2F%2Fcdn.example.test%2Fhls%2Fkey.bin") &&
        playlistKeyOut.contains("u=https%3A%2F%2Fcdn.example.test%2Fhls%2Fseg0.ts") &&
        playlistKeyOut.contains("u=https%3A%2F%2Fcdn.example.test%2Fabs%2Fseg1.ts"),
        playlistKeyOut)
    check("proxy-is-playlist",
        StreamProxy.isPlaylist("https://cdn.example.test/hls/index.m3u8", "application/vnd.apple.mpegurl") &&
        StreamProxy.isPlaylist("https://cdn.example.test/hls/index.m3u8", null) &&
        !StreamProxy.isPlaylist("https://cdn.example.test/v/720.mp4", "video/mp4"))
    // Движок v0.8.0: авто-play селекторы, JS-скан v2, YouTube, прокси.
    check("engine-autoplay-selectors",
        engineSrc.contains(".vjs-big-play-button") && engineSrc.contains(".plyr__control--overlaid") &&
        engineSrc.contains(".jw-icon-playback") && engineSrc.contains("[aria-label*=play i]"),
        "AUTO_PLAY_JS must try Video.js/Plyr/JWPlayer/aria selectors")
    check("engine-scan-v2",
        File("app/src/main/java/com/alexey/aircast/VideoExtractor.kt").readText().contains("contentDocument") &&
        File("app/src/main/java/com/alexey/aircast/VideoExtractor.kt").readText().contains("data-url") &&
        File("app/src/main/java/com/alexey/aircast/VideoExtractor.kt").readText().contains("outerHTML"),
        "SCAN_JS must scan scripts/data-attrs/same-origin iframes")
    check("engine-youtube",
        engineSrc.contains("tryYouTubeInnerTube") && engineSrc.contains("YT_INFO_JS") &&
        engineSrc.contains("YouTube: нет прямого потока") &&
        engineSrc.contains("postInnerTube") && engineSrc.contains("fetchYouTubeVisitorData"),
        "CastEngine must run the InnerTube flow (VISIONOS→ANDROID) with an honest no-stream end")
    check("engine-youtube-guard",
        engineSrc.contains("googlevideo.com") && engineSrc.contains("пропущен сегмент"),
        "network interception must skip YouTube DASH segments")
    check("engine-proxy",
        engineSrc.contains("StreamProxy.needsProxy") && engineSrc.contains("proxyStreamUrl") &&
        engineSrc.contains("streamProxy.ensureStarted") && engineSrc.contains("streamProxy.stop()"),
        "CastEngine must route Referer streams through StreamProxy and stop it on destroy")

    // --- v0.8.1: generic player parsing (docs/demos only, no sites) ---
    // Playerjs file-списки (playerjs.com/docs: качества [480p], аудио {Fr},
    // fallback "or", балансировка "and", DLE ((480p)), [seek:N], группы [<<<]).
    check("playerjs-split-labels",
        VideoUrls.expandPlayerjsList("[480p]//cdn.example.test/v/480.mp4,[720p]//cdn.example.test/v/720.mp4") ==
        listOf("//cdn.example.test/v/480.mp4", "//cdn.example.test/v/720.mp4"))
    check("playerjs-split-audio",
        VideoUrls.expandPlayerjsList("{French}//cdn.example.test/v/fr.mp4;{English}//cdn.example.test/v/en.mp4") ==
        listOf("//cdn.example.test/v/fr.mp4", "//cdn.example.test/v/en.mp4"))
    check("playerjs-split-or-and",
        VideoUrls.expandPlayerjsList("//cdn.example.test/v/master.m3u8 or //cdn.example.test/v/fallback.mp4") ==
        listOf("//cdn.example.test/v/master.m3u8", "//cdn.example.test/v/fallback.mp4") &&
        VideoUrls.expandPlayerjsList("//cdn.example.test/v/a.mp4 and //cdn.example.test/v/b.mp4") ==
        listOf("//cdn.example.test/v/a.mp4", "//cdn.example.test/v/b.mp4"))
    check("playerjs-split-dle",
        VideoUrls.expandPlayerjsList("((480p))//cdn.example.test/v/480.mp4,((720p))//cdn.example.test/v/720.mp4") ==
        listOf("//cdn.example.test/v/480.mp4", "//cdn.example.test/v/720.mp4"))
    check("playerjs-split-seek",
        VideoUrls.expandPlayerjsList("https://cdn.example.test/v/720.mp4[seek:90]") ==
        listOf("https://cdn.example.test/v/720.mp4"))
    check("playerjs-split-comma-in-query",
        VideoUrls.expandPlayerjsList("https://cdn.example.test/v/x.mp4?a=1,2") ==
        listOf("https://cdn.example.test/v/x.mp4?a=1,2"))
    check("playerjs-split-group",
        VideoUrls.expandPlayerjsList("[480p]//cdn.example.test/v/480.mp4,[720p]//cdn.example.test/v/720.mp4,[<<<First section]") ==
        listOf("//cdn.example.test/v/480.mp4", "//cdn.example.test/v/720.mp4"))
    check("playerjs-split-plain",
        VideoUrls.expandPlayerjsList("https://cdn.example.test/v/plain.mp4") ==
        listOf("https://cdn.example.test/v/plain.mp4"))
    check("playerjs-split-multi-label",
        VideoUrls.expandPlayerjsList("[720p]{French}//cdn.example.test/v/720fr.mp4") ==
        listOf("//cdn.example.test/v/720fr.mp4"))
    // Фикстура playerjs_config.html: 9 конфигов из документации.
    val pjsHtml = File("tests/fixtures/playerjs_config.html").readText()
    val pjsFiles = Regex("""file:"([^"]+)"""").findAll(pjsHtml).map { it.groupValues[1] }.toList()
    check("playerjs-fixture-count", pjsFiles.size == 9, pjsFiles.size.toString())
    check("playerjs-fixture-expand",
        VideoUrls.expandPlayerjsList(pjsFiles[0]) == listOf("//cdn.example.test/v/480.mp4", "//cdn.example.test/v/720.mp4") &&
        VideoUrls.expandPlayerjsList(pjsFiles[1]) == listOf("//cdn.example.test/v/fr.mp4", "//cdn.example.test/v/en.mp4") &&
        VideoUrls.expandPlayerjsList(pjsFiles[2]).size == 2 &&
        VideoUrls.expandPlayerjsList(pjsFiles[3]).size == 2 &&
        VideoUrls.expandPlayerjsList(pjsFiles[4]).size == 2 &&
        VideoUrls.expandPlayerjsList(pjsFiles[5]) == listOf("https://cdn.example.test/v/720.mp4") &&
        VideoUrls.expandPlayerjsList(pjsFiles[6]) == listOf("https://cdn.example.test/v/x.mp4?a=1,2") &&
        VideoUrls.expandPlayerjsList(pjsFiles[7]) == listOf("https://cdn.example.test/v/plain.mp4") &&
        VideoUrls.expandPlayerjsList(pjsFiles[8]) == listOf("//cdn.example.test/v/720fr.mp4"),
        pjsFiles.toString())
    // Резолв сырых значений скриптов против URL страницы.
    check("resolve-script-url",
        VideoUrls.resolveScriptUrl("https://site.example.test/page/1", "https://cdn.example.test/v/720.mp4") == "https://cdn.example.test/v/720.mp4" &&
        VideoUrls.resolveScriptUrl("https://site.example.test/page/1", "//cdn.example.test/v/720.mp4") == "https://cdn.example.test/v/720.mp4" &&
        VideoUrls.resolveScriptUrl("https://site.example.test/page/1", "/v/720.mp4") == "https://site.example.test/v/720.mp4" &&
        VideoUrls.resolveScriptUrl("https://site.example.test/page/sub/1", "../v/720.mp4") == "https://site.example.test/page/v/720.mp4" &&
        VideoUrls.resolveScriptUrl("https://site.example.test/page/1", "./v/720.mp4") == "https://site.example.test/page/v/720.mp4" &&
        VideoUrls.resolveScriptUrl("https://site.example.test/page/1", "javascript:void(0)") == null &&
        VideoUrls.resolveScriptUrl("https://site.example.test/page/1", "") == null &&
        VideoUrls.resolveScriptUrl(null, "/v/720.mp4") == null)
    // Master-плейлист: index.m3u8 без маркера качества — master,
    // а в папке качества (720p/index.m3u8) — вариант.
    check("master-index",
        VideoUrls.isMasterPlaylist("https://cdn.example.test/hls/index.m3u8") &&
        !VideoUrls.isMasterPlaylist("https://cdn.example.test/hls/720p/index.m3u8") &&
        !VideoUrls.isMasterPlaylist("https://cdn.example.test/hls/1080/index.m3u8"))
    // DASH .mpd — адаптивный манифест, ранг как у HLS.
    check("formatrank-mpd",
        AutoCast.formatRank("mpd (DASH)") == 3 && AutoCast.formatRank("m3u8") == 3 &&
        AutoCast.formatRank("mp4") == 2)
    check("pick-mpd-beats-mp4",
        AutoCast.pickBest(listOf(
            VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест"),
            VideoCandidate("https://cdn.example.test/v/manifest.mpd", "mpd (DASH)", "720p", null, "тест")
        ))?.url == "https://cdn.example.test/v/manifest.mpd")
    // JWPlayer v2 feed: фикстуры + парсинг результата SCAN_JS.
    val jwFeedJson = File("tests/fixtures/jwplayer_feed.json").readText()
    check("jwfeed-parse",
        VideoUrls.parseJwplayerFeed(jwFeedJson) == listOf(
            "https://cdn.example.test/jw/master.m3u8",
            "https://cdn.example.test/jw/720.mp4",
            "https://cdn.example.test/jw/480.mp4",
            "https://cdn.example.test/jw/second.m3u8"),
        VideoUrls.parseJwplayerFeed(jwFeedJson).toString())
    val jwSetupHtml = File("tests/fixtures/jwplayer_setup.html").readText()
    check("jwfeed-extract",
        VideoUrls.extractJwplayerFeedUrls(jwSetupHtml) == listOf(
            "https://cdn.jwplayer.com/v2/playlists/AbC123xYz",
            "https://cdn.jwplayer.com/v2/media/Zz987654"),
        VideoUrls.extractJwplayerFeedUrls(jwSetupHtml).toString())
    check("jwfeed-not-video",
        !VideoUrls.isVideoUrl("https://cdn.jwplayer.com/v2/playlists/AbC123xYz"))
    val scanJw = "\"{\\\"videos\\\":[],\\\"iframes\\\":[],\\\"urls\\\":[],\\\"rel\\\":[],\\\"jwfeeds\\\":[\\\"https://cdn.jwplayer.com/v2/playlists/AbC123xYz\\\"],\\\"url\\\":\\\"https://example.test/page\\\",\\\"title\\\":\\\"t\\\"}\""
    val parsedJw = VideoUrls.parseScanResult(scanJw)
    check("scan-jwfeeds-parse",
        parsedJw.jwFeeds.size == 1 && parsedJw.jwFeeds[0] == "https://cdn.jwplayer.com/v2/playlists/AbC123xYz",
        parsedJw.toString())
    // SCAN_JS v0.8.1: нормализация (\/, \u002F, &amp;), %3A%2F%2F,
    // новые data-атрибуты, jwfeeds.
    val extractorSrc = File("app/src/main/java/com/alexey/aircast/VideoExtractor.kt").readText()
    check("scan-js-v081",
        extractorSrc.contains("u002f") && extractorSrc.contains("&amp;") &&
        extractorSrc.contains("%3A%2F%2F") && extractorSrc.contains("jwfeeds") &&
        extractorSrc.contains("data-stream") && extractorSrc.contains("data-file-url") &&
        extractorSrc.contains("data-hls-url"),
        "SCAN_JS must normalize escaped URLs, decode %3A%2F%2F, read extra data attrs and jwfeeds")
    // AUTO_PLAY_JS v0.8.1: фирменные селекторы из документации плееров.
    check("engine-autoplay-v081",
        engineSrc.contains(".vjs-play-control") && engineSrc.contains(".jw-display-icon-container") &&
        engineSrc.contains(".dplayer-play-icon") && engineSrc.contains(".art-control-playAndPause") &&
        engineSrc.contains(".xgplayer-start") && engineSrc.contains("data-plyr"),
        "AUTO_PLAY_JS must try Video.js/JWPlayer/DPlayer/ArtPlayer/XGPlayer/Plyr play controls")
    // CastEngine: JWPlayer feed — один раз за флоу, 10 с таймаут.
    check("engine-jwfeed",
        engineSrc.contains("tryJwplayerFeed") && engineSrc.contains("jwFeedsTried") &&
        engineSrc.contains("parseJwplayerFeed") && engineSrc.contains("JWPlayer feed"),
        "CastEngine must fetch JWPlayer v2 feeds once per flow")
    // Reference-фикстура: канонические конфиги 11 плееров из документации.
    val refHtml = File("tests/fixtures/players_reference.html").readText()
    val refUrls = Regex("""https?://[^\s"'<>\\]+""").findAll(refHtml).map { it.value }.toList()
    check("players-reference-markers",
        listOf("jwplayer(", "videojs(", "new Plyr", "new DPlayer", "new Artplayer", "new Player(",
            "new Playerjs", "hls.loadSource", "dashjs.MediaPlayer", "new shaka.Player", "<video").all { refHtml.contains(it) })
    check("players-reference-urls",
        listOf(
            "https://cdn.example.test/jw/master.m3u8",
            "https://cdn.example.test/vjs/master.m3u8",
            "https://cdn.example.test/plyr/720.mp4",
            "https://cdn.example.test/dplayer/720.mp4",
            "https://cdn.example.test/art/720.mp4",
            "https://cdn.example.test/xg/720.mp4",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
            "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
            "https://cdn.example.test/html5/720.mp4"
        ).all { it in refUrls } && refUrls.filter { VideoUrls.isVideoUrl(it) }.size >= 10,
        refUrls.toString())

    // --- v0.9.0: авто-ретрай, авто-следующая серия, лог, субтитры ---
    // StreamProbe: длительность HLS — сумма #EXTINF.
    val hlsMedia = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6.0,\nseg2.ts\n#EXT-X-ENDLIST\n"
    check("probe-hls-duration", StreamProbe.hlsDurationSec(hlsMedia) == 18L, StreamProbe.hlsDurationSec(hlsMedia).toString())
    check("probe-hls-duration-none", StreamProbe.hlsDurationSec("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv720.m3u8\n") == null)
    val hlsMaster = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720\nv720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=854x480\nv480.m3u8\n"
    check("probe-hls-master", StreamProbe.isHlsMaster(hlsMaster) && !StreamProbe.isHlsMaster(hlsMedia))
    check("probe-hls-variants", StreamProbe.hlsVariantUrls(hlsMaster) == listOf("v720.m3u8", "v480.m3u8"), StreamProbe.hlsVariantUrls(hlsMaster).toString())
    // mp4 mvhd v0: timescale 1000, duration 150000 -> 150 с.
    // ftyp(24) + moov(8 + mvhd 108); mvhd body: version(4) ctime(4)
    // mtime(4) timescale@+12 duration@+16.
    val mp4 = ByteArray(24 + 8 + 108)
    fun be32(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v shr 24).toByte(); arr[off + 1] = (v shr 16).toByte()
        arr[off + 2] = (v shr 8).toByte(); arr[off + 3] = v.toByte()
    }
    be32(mp4, 0, 24); "ftyp".toByteArray(Charsets.US_ASCII).copyInto(mp4, 4)
    be32(mp4, 24, 116); "moov".toByteArray(Charsets.US_ASCII).copyInto(mp4, 28)
    be32(mp4, 32, 108); "mvhd".toByteArray(Charsets.US_ASCII).copyInto(mp4, 36)
    be32(mp4, 52, 1000); be32(mp4, 56, 150000)
    check("probe-mp4-duration", StreamProbe.mp4DurationSec(mp4) == 150L, StreamProbe.mp4DurationSec(mp4).toString())
    check("probe-mp4-none", StreamProbe.mp4DurationSec(ByteArray(64)) == null)
    // DASH mediaPresentationDuration.
    check("probe-dash-duration", StreamProbe.dashDurationSec("<MPD mediaPresentationDuration=\"PT2M30S\">") == 150L)
    check("probe-dash-duration-h", StreamProbe.dashDurationSec("<MPD mediaPresentationDuration=\"PT1H2M3.5S\">") == 3723L)
    check("probe-dash-none", StreamProbe.dashDurationSec("<MPD>") == null)
    // probeUrl/fetchDurationSec против локального HTTP-сервера (без сети наружу).
    val probeServer = localHttpServer(mapOf(
        "/master.m3u8" to (200 to hlsMaster),
        "/v720.m3u8" to (200 to hlsMedia),
        "/seg0.ts" to (200 to "TS"),
    ))
    val probePort = probeServer.first
    try {
        check("probe-hls-ok", StreamProbe.probeUrl("http://127.0.0.1:$probePort/master.m3u8", null, null) == StreamProbe.ProbeResult.OK)
        check("probe-404", StreamProbe.probeUrl("http://127.0.0.1:$probePort/nope.m3u8", null, null) == StreamProbe.ProbeResult.HTTP_ERROR)
        check("probe-duration-hls", StreamProbe.fetchDurationSec("http://127.0.0.1:$probePort/master.m3u8", null, null) == 18L)
        check("probe-duration-404", StreamProbe.fetchDurationSec("http://127.0.0.1:$probePort/nope.m3u8", null, null) == null)
    } finally {
        probeServer.second.close()
    }
    check("probe-refused", StreamProbe.probeUrl("http://127.0.0.1:1/x.mp4", null, null) == StreamProbe.ProbeResult.ERROR)
    // Subtitles: SRT/ASS -> VTT, мастер с дорожками.
    val srt = "1\n00:00:01,000 --> 00:00:04,500\nПривет, Бусик\n\n2\n00:00:05,000 --> 00:00:07,000\nВторая строка\n"
    val vtt = Subtitles.srtToVtt(srt)
    check("subs-srt-vtt", vtt.startsWith("WEBVTT") && vtt.contains("00:00:01.000 --> 00:00:04.500") && vtt.contains("Привет, Бусик"), vtt)
    val ass = "[Script Info]\nTitle: test\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Привет, {\\i1}Бусик{\\i0}\n"
    val assVtt = Subtitles.assToVtt(ass)
    check("subs-ass-vtt", assVtt.startsWith("WEBVTT") && assVtt.contains("00:00:01.000 --> 00:00:04.000") && assVtt.contains("Привет, Бусик") && !assVtt.contains("{\\i1}"), assVtt)
    check("subs-ass-time", Subtitles.assTimeToVtt("0:01:02.50") == "00:01:02.500" && Subtitles.assTimeToVtt("bad") == null)
    check("subs-is-url", Subtitles.isSubtitleUrl("https://cdn.example.test/subs/ru.srt") && Subtitles.isSubtitleUrl("https://cdn.example.test/subs/ru.vtt?x=1") && Subtitles.isSubtitleUrl("https://cdn.example.test/subs/ru.ass") && !Subtitles.isSubtitleUrl("https://cdn.example.test/v/720.mp4"))
    check("subs-convert-vtt-null", Subtitles.convertIfNeeded("https://cdn.example.test/subs/ru.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n") == null)
    check("subs-convert-srt", Subtitles.convertIfNeeded("https://cdn.example.test/subs/ru.srt", srt)?.first == "text/vtt" && Subtitles.convertIfNeeded("https://cdn.example.test/subs/ru.srt", srt)?.second?.startsWith("WEBVTT") == true)
    val subsMaster = Subtitles.buildSubsMaster(
        "https://cdn.example.test/v/master.m3u8",
        listOf(SubTrack("https://cdn.example.test/subs/ru.srt", "ru", "Русские"), SubTrack("https://cdn.example.test/subs/en.vtt", "en", "English")),
        proxyTrack = { t -> "http://192.168.1.20:8080/stream?u=" + t.url }
    )
    check("subs-master", subsMaster.contains("#EXT-X-MEDIA:TYPE=SUBTITLES") && subsMaster.contains("LANGUAGE=\"ru\"") && subsMaster.contains("NAME=\"Русские\"") && subsMaster.contains("NAME=\"English\"") && subsMaster.contains("SUBTITLES=\"subs\"") && subsMaster.contains("https://cdn.example.test/v/master.m3u8"), subsMaster)
    // EpisodeList: якорь «Следующая серия», шаблон с номером, нет списка, последняя серия, ?ep=.
    val epNext = EpisodeList.parse(
        "https://serial.example.test/show/s1e5",
        listOf(PageLink("https://serial.example.test/show/s1e4", "Серия 4"), PageLink("https://serial.example.test/show/s1e6", "Следующая серия"))
    )
    check("ep-next-anchor", epNext?.nextUrl == "https://serial.example.test/show/s1e6" && epNext?.total == 0, epNext.toString())
    val epNum = EpisodeList.parse(
        "https://serial.example.test/show/5",
        listOf(PageLink("/show/4", "4"), PageLink("/show/5", "5"), PageLink("/show/6", "6"), PageLink("/show/7", "7"))
    )
    check("ep-numbered", epNum?.nextUrl == "https://serial.example.test/show/6" && epNum?.total == 4, epNum.toString())
    val epLast = EpisodeList.parse(
        "https://serial.example.test/show/7",
        listOf(PageLink("/show/4", "4"), PageLink("/show/5", "5"), PageLink("/show/6", "6"), PageLink("/show/7", "7"))
    )
    check("ep-last", epLast?.nextUrl == null && epLast?.total == 4, epLast.toString())
    val epNone = EpisodeList.parse(
        "https://film.example.test/movie/123",
        listOf(PageLink("/catalog", "Каталог"), PageLink("/about", "О нас"), PageLink("/movie/123/trailer", "Трейлер"))
    )
    check("ep-none", epNone == null, epNone.toString())
    val epQuery = EpisodeList.parse(
        "https://serial.example.test/watch?ep=5",
        listOf(PageLink("/watch?ep=4", "4"), PageLink("/watch?ep=5", "5"), PageLink("/watch?ep=6", "6"))
    )
    check("ep-query", epQuery?.nextUrl == "https://serial.example.test/watch?ep=6", epQuery.toString())
    // AutoCast.orderedAttempts: порядок = pickBest первым, без дублей, max.
    val retryCands = listOf(
        VideoCandidate("https://cdn.example.test/v/480.mp4", "mp4", "480p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/720p.m3u8", "m3u8", "720p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/720.mp4", "mp4", "720p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/480.mp4", "mp4", "480p", null, "тест")
    )
    val attempts = AutoCast.orderedAttempts(retryCands)
    check("retry-order", attempts.map { it.url } == listOf("https://cdn.example.test/v/720p.m3u8", "https://cdn.example.test/v/720.mp4", "https://cdn.example.test/v/480.mp4"), attempts.map { it.url }.toString())
    check("retry-first-is-pickbest", AutoCast.pickBest(retryCands)?.url == attempts[0].url)
    check("retry-max", AutoCast.orderedAttempts(retryCands, 2).size == 2)
    check("retry-empty", AutoCast.orderedAttempts(emptyList()).isEmpty())
    check("retry-master-pool", AutoCast.orderedAttempts(listOf(
        VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/master.m3u8", "m3u8", "720p", null, "тест")
    )).map { it.url } == listOf("https://cdn.example.test/v/master.m3u8"))
    // YouTube lengthSeconds -> длительность для таймера авто-следующей.
    val ytParsed090 = YouTube.parsePlayerResponse("{\"playabilityStatus\":{\"status\":\"OK\"},\"videoDetails\":{\"lengthSeconds\":\"150\"},\"streamingData\":{\"hlsManifestUrl\":\"https://manifest.googlevideo.com/x.m3u8\"}}")
    check("youtube-length", ytParsed090.lengthSeconds == 150L && ytParsed090.hlsUrl != null, ytParsed090.toString())
    // SCAN_JS v0.9.0: ссылки серий, субтитры, title.
    val scanV090 = "\"{\\\"videos\\\":[],\\\"iframes\\\":[\\\"https://player.example.test/e/123\\\"],\\\"urls\\\":[],\\\"rel\\\":[],\\\"jwfeeds\\\":[],\\\"links\\\":[{\\\"href\\\":\\\"/show/6\\\",\\\"text\\\":\\\"Следующая серия\\\"},{\\\"href\\\":\\\"/show/5\\\",\\\"text\\\":\\\"5\\\"}],\\\"subs\\\":[{\\\"src\\\":\\\"https://cdn.example.test/subs/ru.srt\\\",\\\"lang\\\":\\\"ru\\\",\\\"label\\\":\\\"Русские\\\"}],\\\"url\\\":\\\"https://serial.example.test/show/5\\\",\\\"title\\\":\\\"Сериал — 5 серия\\\"}\""
    val parsedV090 = VideoUrls.parseScanResult(scanV090)
    check("scan-links-subs-title",
        parsedV090.links.size == 2 && parsedV090.links[0].href == "/show/6" &&
        parsedV090.subs.size == 1 && parsedV090.subs[0].lang == "ru" && parsedV090.subs[0].url.endsWith("ru.srt") &&
        parsedV090.pageTitle == "Сериал — 5 серия" && parsedV090.iframes.size == 1, parsedV090.toString())
    check("scan-js-v090",
        extractorSrc.contains("track[kind=\"subtitles\"]") && extractorSrc.contains(".vtt") &&
        extractorSrc.contains("links.push") && extractorSrc.contains("top.subs"),
        "SCAN_JS must collect episode links and subtitle tracks")
    // CastEngine v0.9.0: ретрай с pre-flight, таймер следующей серии, мастер с субтитрами.
    check("engine-retry-v090",
        engineSrc.contains("launchAttempt") && engineSrc.contains("orderedAttempts") &&
        engineSrc.contains("StreamProbe.probeUrl") && engineSrc.contains("onAutoNextArmed") &&
        engineSrc.contains("armNextTimer") && engineSrc.contains("resolveNextEpisode") &&
        engineSrc.contains("proxySubsMasterUrl") && engineSrc.contains("resolveDurationAndArmNext"),
        "CastEngine must retry with pre-flight, arm next-episode timer, launch with subs master")
    // v0.9.0: sub= — внешний файл субтитров в vlc-x-callback-url
    // (VLC iOS 3.6.6: «URL decode the subtitles parameter»;
    // сторонние интеграции передают &sub=<encoded> для VLC/Infuse).
    val deepNoSub = CompanionClient.vlcDeepLink("https://cdn.example.test/v/720.mp4")
    check("vlc-link-no-sub", deepNoSub == "vlc-x-callback://x-callback-url/stream?url=https%3A%2F%2Fcdn.example.test%2Fv%2F720.mp4", deepNoSub)
    val deepSub = CompanionClient.vlcDeepLink(
        "https://cdn.example.test/v/720.mp4",
        "http://192.168.1.20:8080/stream.vtt?u=https%3A%2F%2Fcdn.example.test%2Fsubs%2Fru.srt&r="
    )
    check("vlc-link-sub", deepSub.startsWith(deepNoSub + "&sub=") && deepSub.contains("stream.vtt"), deepSub)
    val subProxy = StreamProxy.proxySubtitleUrl("192.168.1.20", 8080, "https://cdn.example.test/subs/ru.srt", "https://serial.example.test/show/5")
    check("proxy-subtitle-url", subProxy.startsWith("http://192.168.1.20:8080/stream.vtt?u=") && subProxy.contains("ru.srt"), subProxy)
    check("engine-sub-v090",
        engineSrc.contains("proxySubtitleUrl") && engineSrc.contains("connectAndLaunchVlc(launchUrl, subUrl)") &&
        engineSrc.contains("sub= (дорожка"),
        "CastEngine must pass the first subtitle track as sub= to VLC")
    val proxySrc = File("app/src/main/java/com/alexey/aircast/StreamProxy.kt").readText()
    check("proxy-stream-vtt-v090",
        proxySrc.contains("proxySubtitleUrl") && proxySrc.contains("/stream.vtt?"),
        "StreamProxy must serve subtitle tracks at /stream.vtt for sub=")
    // CastService v0.9.0: «Поделиться логом» в «не найдено», сервис живёт при вооружённой авто-следующей.
    val serviceSrc090 = File("app/src/main/java/com/alexey/aircast/CastService.kt").readText()
    check("service-log-share-v090",
        serviceSrc090.contains("notifShareLogText") && serviceSrc090.contains("LogShareActivity") &&
        serviceSrc090.contains("notifWatchingText") && serviceSrc090.contains("autoNextArmed"),
        "CastService must offer log share on not-found and keep service alive while next episode is armed")
    // Манифест v0.9.0: LogShareActivity + LogFileProvider.
    val manifestSrc = File("app/src/main/AndroidManifest.xml").readText()
    check("manifest-log-provider-v090",
        manifestSrc.contains("LogShareActivity") && manifestSrc.contains("LogFileProvider") &&
        manifestSrc.contains("com.alexey.aircast.logfile"),
        "manifest must declare LogShareActivity and LogFileProvider")

    // --- v0.9.1: обновления, настройка, озвучка, resume, плейлисты, названия, ТВ, очередь, субтитры, файлы, Instagram, Twitch/Kick ---
    // TitleCleaner: мусор, сайт, сезон/серия, S02E05, год, пустое, имя файла.
    val tc1 = TitleCleaner.parse("Розаріо вампір 1 сезон 5 серія дивитись онлайн | YummyAnime", "https://yummyani.me/show/5")
    check("title-clean-series", tc1.display == "Розаріо вампір — 1 сезон, 5 серия" && tc1.series == "Розаріо вампір" && tc1.season == 1 && tc1.episode == 5, tc1.toString())
    val tc2 = TitleCleaner.parse("Сериал — 5 серия | Сайт", "https://serial.example.test/show/5")
    check("title-clean-episode", tc2.display == "Сериал — 5 серия" && tc2.episode == 5 && tc2.season == null, tc2.toString())
    val tc3 = TitleCleaner.parse("Breaking Bad S02E05", "https://serial.example.test/breaking-bad")
    check("title-clean-sxxexx", tc3.display == "Breaking Bad — 2 сезон, 5 серия" && tc3.season == 2 && tc3.episode == 5, tc3.toString())
    val tc4 = TitleCleaner.parse("Фильм (2021) смотреть онлайн", "https://film.example.test/movie/123")
    check("title-clean-film-year", tc4.display == "Фильм (2021)" && tc4.series == "Фильм (2021)" && tc4.episode == null && TitleCleaner.yearOf(tc4.series ?: "") == 2021, tc4.toString())
    val tc4b = TitleCleaner.parse("Сериал", "https://serial.example.test/watch?ep=5")
    check("title-clean-url-ep", tc4b.display == "Сериал — 5 серия" && tc4b.episode == 5, tc4b.toString())
    val tc4c = TitleCleaner.parse("Сериал", "https://serial.example.test/show/s1e5")
    check("title-clean-url-sxxexx", tc4c.display == "Сериал — 1 сезон, 5 серия" && tc4c.season == 1 && tc4c.episode == 5, tc4c.toString())
    val tc5 = TitleCleaner.parse("", "https://example.test/x")
    check("title-clean-empty", tc5.display == null && tc5.series == null && tc5.season == null && tc5.episode == null, tc5.toString())
    check("title-clean-file", TitleCleaner.cleanFileName("Serial.S01E05.1080p.WEB-DL.mkv") == "Serial S01E05", TitleCleaner.cleanFileName("Serial.S01E05.1080p.WEB-DL.mkv"))
    check("title-clean-file-plain", TitleCleaner.cleanFileName("video_20261118_123456.mp4") == "video 20261118 123456", TitleCleaner.cleanFileName("video_20261118_123456.mp4"))
    // YouTubePlaylist: id, browse-запрос, парсинг фикстуры (только playlistVideoRenderer, дубли — один раз).
    check("ytpl-extract", YouTubePlaylist.extractPlaylistId("https://www.youtube.com/playlist?list=PL1234567890abcdef") == "PL1234567890abcdef" &&
        YouTubePlaylist.extractPlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef") == "PL1234567890abcdef" &&
        YouTubePlaylist.extractPlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == null &&
        YouTubePlaylist.isPlaylistUrl("https://music.youtube.com/playlist?list=PL1234567890abcdef"))
    val browseJson = YouTubePlaylist.browseRequestJson("PL1234567890abcdef", "CgtQWABCD")
    check("ytpl-browse-req", browseJson.contains("\"browseId\":\"VLPL1234567890abcdef\"") && browseJson.contains("\"clientName\":\"VISIONOS\"") && browseJson.contains("\"visitorData\":\"CgtQWABCD\""), browseJson)
    val playlistJson = File("tests/fixtures/youtube_playlist.json").readText()
    check("ytpl-parse", YouTubePlaylist.parseVideoIds(playlistJson) == listOf("dQw4w9WgXcQ", "9bZkp7q19f0", "JGwWNGJdvx8"), YouTubePlaylist.parseVideoIds(playlistJson).toString())
    check("ytpl-parse-empty", YouTubePlaylist.parseVideoIds(null).isEmpty() && YouTubePlaylist.parseVideoIds("").isEmpty() && YouTubePlaylist.parseVideoIds("{}").isEmpty())
    check("ytpl-watch-url", YouTubePlaylist.watchUrl("dQw4w9WgXcQ") == "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    // CastQueue: FIFO, файлы, очистка.
    val q = CastQueue()
    check("queue-fifo", q.add(QueueItem.Url("https://a.example.test/1")) == 1 && q.add(QueueItem.File("content://x/2", "b.mp4")) == 2 && q.size == 2 &&
        q.poll() == QueueItem.Url("https://a.example.test/1") && q.poll() == QueueItem.File("content://x/2", "b.mp4") && q.poll() == null && q.isEmpty())
    q.add(QueueItem.Url("https://a.example.test/1"))
    q.add(QueueItem.Url("https://a.example.test/2"))
    q.clear()
    check("queue-clear", q.isEmpty() && q.size == 0)
    // PlaybackMemory: ключ сериала, хэш, позиция, субтитры, хранилище на HashMap.
    check("mem-series-key", PlaybackMemory.seriesKey("https://serial.example.test/show/5") == PlaybackMemory.seriesKey("https://serial.example.test/show/6") &&
        PlaybackMemory.seriesKey("https://serial.example.test/show/5") != PlaybackMemory.seriesKey("https://other.example.test/show/5") &&
        PlaybackMemory.seriesKey("https://serial.example.test/show/5") != PlaybackMemory.seriesKey("https://serial.example.test/film/5"))
    check("mem-hash", PlaybackMemory.hashKey("abc") == PlaybackMemory.hashKey("abc") && PlaybackMemory.hashKey("abc").length == 16 && PlaybackMemory.hashKey("abc") != PlaybackMemory.hashKey("abd"))
    check("mem-position-codec", PlaybackMemory.decodePosition(PlaybackMemory.encodePosition(900, 1500, 1700000000000)) == PlaybackMemory.Position(900, 1500, 1700000000000) &&
        PlaybackMemory.decodePosition("garbage") == null && PlaybackMemory.decodePosition("0|1500|1") == null)
    check("mem-resumable", PlaybackMemory.isResumable(PlaybackMemory.Position(70, 1500, 1)) && !PlaybackMemory.isResumable(PlaybackMemory.Position(30, 1500, 1)) &&
        !PlaybackMemory.isResumable(PlaybackMemory.Position(1450, 1500, 1)) && PlaybackMemory.isResumable(PlaybackMemory.Position(70, null, 1)))
    check("mem-subs-codec", PlaybackMemory.decodeSubs(PlaybackMemory.encodeSubs(true, "ru")) == PlaybackMemory.SubsPref(true, "ru") &&
        PlaybackMemory.decodeSubs(PlaybackMemory.encodeSubs(false, null)) == PlaybackMemory.SubsPref(false, null) &&
        PlaybackMemory.decodeSubs("2|ru") == null)
    val memMap = HashMap<String, String>()
    val store = PlaybackStore({ memMap[it] }, { k, v -> memMap[k] = v }, { memMap.remove(it) })
    val skey = PlaybackMemory.seriesKey("https://serial.example.test/show/5")
    store.rememberDub(skey, "Rus")
    check("mem-store-dub", store.preferredDub(skey) == "Rus" && store.preferredDub(PlaybackMemory.seriesKey("https://serial.example.test/show/6")) == "Rus")
    store.rememberSubs(skey, true, "ru")
    check("mem-store-subs-toggle", store.toggleSubs(skey, listOf(SubTrack("u", "ru", "Русские"))) == false && store.subsPref(skey) == PlaybackMemory.SubsPref(false, "ru") &&
        store.toggleSubs(skey, listOf(SubTrack("u", "ru", "Русские"))) == true)
    val purl = "https://serial.example.test/show/5"
    store.savePosition(purl, 30, 1500)
    check("mem-store-pos-short", store.loadPosition(purl) == null)
    store.savePosition(purl, 900, 1500)
    check("mem-store-pos", store.loadPosition(purl)?.positionSec == 900L)
    store.clearPosition(purl)
    check("mem-store-pos-clear", store.loadPosition(purl) == null)
    // OpenSubtitles: парсинг фикстуры (сортировка по download_count), выбор, ссылки.
    val osJson = File("tests/fixtures/opensubtitles_search.json").readText()
    val osFound = OpenSubtitles.parseSearchResponse(osJson)
    check("opensubs-parse", osFound.size == 3 && osFound[0].fileId == 9003L && osFound[0].lang == "en" && osFound[0].downloadCount == 98700L &&
        osFound[1].fileId == 9001L && osFound[1].lang == "ru" && osFound[1].fileName == "Show.S01E05.RUS.srt" &&
        osFound[2].fileId == 9004L, osFound.toString())
    check("opensubs-parse-empty", OpenSubtitles.parseSearchResponse(null).isEmpty() && OpenSubtitles.parseSearchResponse("garbage").isEmpty())
    check("opensubs-pick", OpenSubtitles.pickBest(osFound, "ru")?.fileId == 9001L && OpenSubtitles.pickBest(osFound, null)?.fileId == 9003L &&
        OpenSubtitles.pickBest(osFound, "fr")?.fileId == 9003L && OpenSubtitles.pickBest(emptyList(), "ru") == null)
    check("opensubs-download-link", OpenSubtitles.parseDownloadResponse("{\"link\":\"https://dl.opensubtitles.com/x.srt\",\"file_name\":\"x.srt\"}") == "https://dl.opensubtitles.com/x.srt" &&
        OpenSubtitles.parseDownloadResponse("garbage") == null)
    val osUrl = OpenSubtitles.searchUrl("Show", 1, 5)
    check("opensubs-search-url", osUrl.startsWith("https://api.opensubtitles.com/api/v1/subtitles?query=Show") && osUrl.contains("season_number=1") && osUrl.contains("episode_number=5") && osUrl.contains("languages=ru%2Cen"), osUrl)
    check("opensubs-download-req", OpenSubtitles.downloadRequestJson(9001) == "{\"file_id\":9001}")
    // SCAN_JS v0.9.1: og:video / video_url / og:image (Instagram) — парсинг + исходник JS.
    val scanOg = "\"{\\\"videos\\\":[],\\\"iframes\\\":[],\\\"urls\\\":[\\\"https://scontent.cdninstagram.com/v/x.mp4\\\"],\\\"rel\\\":[],\\\"jwfeeds\\\":[],\\\"links\\\":[],\\\"subs\\\":[],\\\"url\\\":\\\"https://www.instagram.com/p/ABC/\\\",\\\"title\\\":\\\"Cool clip\\\",\\\"poster\\\":\\\"https://scontent.cdninstagram.com/v/poster.jpg\\\"}\""
    val parsedOg = VideoUrls.parseScanResult(scanOg)
    check("scan-ogvideo", parsedOg.urls == listOf("https://scontent.cdninstagram.com/v/x.mp4") && parsedOg.poster == "https://scontent.cdninstagram.com/v/poster.jpg", parsedOg.toString())
    check("scan-js-ogvideo",
        extractorSrc.contains("og:video") && extractorSrc.contains("og:video:secure_url") &&
        extractorSrc.contains("twitter:player:stream") && extractorSrc.contains("video_url") &&
        extractorSrc.contains("og:image") && extractorSrc.contains("top.poster"),
        "SCAN_JS must collect og:video / video_url / og:image")
    val ogHtml = File("tests/fixtures/ogvideo_page.html").readText()
    check("fixture-ogvideo-page", ogHtml.contains("og:video") && ogHtml.contains("og:video:secure_url") && ogHtml.contains("twitter:player:stream") &&
        ogHtml.contains("video_url") && ogHtml.contains("og:image") && !ogHtml.contains("<video"), "ogvideo fixture must be an Instagram-like page without <video>")
    // MasterWait: ждать мастера до 8 с, задержка 4 с / 2 с.
    check("master-wait", MasterWait.shouldKeepWaiting(false, true, 1000) && !MasterWait.shouldKeepWaiting(false, true, 9000) &&
        !MasterWait.shouldKeepWaiting(true, true, 1000) && !MasterWait.shouldKeepWaiting(false, false, 1000) &&
        MasterWait.waitDelayMs(true) == 4000L && MasterWait.waitDelayMs(false) == 2000L &&
        MasterWait.MAX_WAIT_MS == 8000L && MasterWait.MAX_RESCHEDULES == 6)
    // Twitch/Kick: мастер по содержимому, вариант — нет; isMaster — поле кандидата.
    val twitchMaster = File("tests/fixtures/twitch_master.m3u8").readText()
    val twitchVariant = File("tests/fixtures/twitch_variant.m3u8").readText()
    check("twitch-master-content", StreamProbe.isHlsMaster(twitchMaster) && !StreamProbe.isHlsMaster(twitchVariant) &&
        StreamProbe.hlsVariantUrls(twitchMaster).size == 3)
    val twitchCands = listOf(
        VideoCandidate("https://video-weaver.fra02.hls.ttvnw.net/v1/playlist/xyz.m3u8", "m3u8", "720p", null, "перехват сети", isMaster = true),
        VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест")
    )
    check("master-pool-field", AutoCast.orderedAttempts(twitchCands).map { it.url } == listOf("https://video-weaver.fra02.hls.ttvnw.net/v1/playlist/xyz.m3u8"))
    check("candidate-ismaster-default", VideoCandidate("https://cdn.example.test/v/master.m3u8", "m3u8", "720p", null, "тест").isMaster &&
        !VideoCandidate("https://cdn.example.test/v/720p/index.m3u8", "m3u8", "720p", null, "тест").isMaster)
    // orderedAttempts с запомненной озвучкой (+5 — выше качества).
    val dubCands = listOf(
        VideoCandidate("https://cdn.example.test/v/1080.mp4", "mp4", "1080p", null, "тест"),
        VideoCandidate("https://cdn.example.test/v/rus.m3u8", "m3u8", "720p", null, "тест", audioLabel = "Rus"),
        VideoCandidate("https://cdn.example.test/v/eng.m3u8", "m3u8", "720p", null, "тест", audioLabel = "Eng")
    )
    check("ordered-dub", AutoCast.orderedAttempts(dubCands, preferredAudio = "Eng")[0].url == "https://cdn.example.test/v/eng.m3u8" &&
        AutoCast.pickBest(dubCands, preferredAudio = "Rus")?.url == "https://cdn.example.test/v/rus.m3u8" &&
        AutoCast.pickBest(dubCands)?.url == "https://cdn.example.test/v/rus.m3u8")
    // Playerjs: метки {Lang} сохраняются; обычный список — как раньше.
    val pjParts = VideoUrls.expandPlayerjsListWithLabels("[720p]{Rus}https://cdn.example.test/a.m3u8;{Eng}https://cdn.example.test/b.m3u8")
    check("playerjs-labels", pjParts == listOf(VideoUrls.PlayerjsPart("https://cdn.example.test/a.m3u8", "Rus"), VideoUrls.PlayerjsPart("https://cdn.example.test/b.m3u8", "Eng")), pjParts.toString())
    check("playerjs-list-same", VideoUrls.expandPlayerjsList("[720p]{Rus}https://cdn.example.test/a.m3u8;{Eng}https://cdn.example.test/b.m3u8") == listOf("https://cdn.example.test/a.m3u8", "https://cdn.example.test/b.m3u8"))
    // Subtitles: запомненный язык — DEFAULT=YES только у своей дорожки.
    val subsMasterPref = Subtitles.buildSubsMaster(
        "https://cdn.example.test/v/master.m3u8",
        listOf(SubTrack("https://cdn.example.test/subs/ru.srt", "ru", "Русские"), SubTrack("https://cdn.example.test/subs/en.vtt", "en", "English")),
        { t -> "http://192.168.1.20:8080/stream?u=" + t.url },
        "ru"
    )
    check("subs-master-pref-lang", subsMasterPref.contains("LANGUAGE=\"ru\",DEFAULT=YES") && !subsMasterPref.contains("LANGUAGE=\"en\",DEFAULT=YES"), subsMasterPref)
    // RangeServe: байтовые диапазоны для /file.
    check("range-full", RangeServe.parseRange("bytes=0-99", 1000)?.let { it.start == 0L && it.end == 99L && it.status == 206 && it.length == 100L && it.contentRange(1000) == "bytes 0-99/1000" } == true)
    check("range-open", RangeServe.parseRange("bytes=500-", 1000)?.let { it.start == 500L && it.end == 999L } == true)
    check("range-suffix", RangeServe.parseRange("bytes=-200", 1000)?.let { it.start == 800L && it.end == 999L } == true)
    check("range-clamp", RangeServe.parseRange("bytes=0-5000", 1000)?.let { it.start == 0L && it.end == 999L } == true)
    check("range-invalid", RangeServe.parseRange(null, 1000) == null && RangeServe.parseRange("bytes=1000-2000", 1000) == null &&
        RangeServe.parseRange("garbage", 1000) == null && RangeServe.parseRange("bytes=-0", 1000) == null && RangeServe.parseRange("bytes=5-2", 1000) == null)
    // TempFiles: чистка stale, свежие и чужие файлы — на месте; JavaFileSource — чтение с позиции.
    val tempDir = java.io.File("build/test-tmp").apply { mkdirs() }
    val oldTmp = java.io.File(tempDir, "aircast-1000.tmp").apply { writeText("old") ; setLastModified(System.currentTimeMillis() - 2 * 3600 * 1000) }
    val freshTmp = java.io.File(tempDir, "aircast-2000.tmp").apply { writeText("fresh") }
    val otherFile = java.io.File(tempDir, "other.txt").apply { writeText("other"); setLastModified(System.currentTimeMillis() - 2 * 3600 * 1000) }
    check("temp-cleanup", TempFiles.cleanup(tempDir) == 1 && !oldTmp.exists() && freshTmp.exists() && otherFile.exists())
    check("temp-cleanup-all", TempFiles.cleanup(tempDir, 0) == 1 && !freshTmp.exists() && otherFile.exists())
    val jf = java.io.File(tempDir, "sample.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)) }
    val jfs = JavaFileSource(jf)
    check("java-file-source", jfs.size() == 10L && jfs.readAt(0, 4)?.toList() == listOf<Byte>(0, 1, 2, 3) && jfs.readAt(8, 4)?.toList() == listOf<Byte>(8, 9) && jfs.readAt(10, 1) == null)
    jfs.close()
    // UpdateCheck: парсинг latest.json, сравнение кодов.
    check("update-parse", UpdateCheck.parseLatest("{\"versionCode\":32,\"versionName\":\"0.9.2\",\"url\":\"https://example.test/v092.apk\"}") == UpdateCheck.Latest(32, "0.9.2", "https://example.test/v092.apk") &&
        UpdateCheck.parseLatest("{\"versionName\":\"0.9.2\"}") == null && UpdateCheck.parseLatest("garbage") == null && UpdateCheck.parseLatest(null) == null)
    check("update-newer", UpdateCheck.isNewer(32, UpdateCheck.Latest(33, "0.9.3", "u")) && !UpdateCheck.isNewer(32, UpdateCheck.Latest(32, "0.9.2", "u")) &&
        !UpdateCheck.isNewer(32, UpdateCheck.Latest(31, "0.9.1", "u")) && !UpdateCheck.isNewer(32, null))
    // v0.9.3: UpdateGate — ветвление разрешения на установку
    // (canRequestPackageInstalls / уже отправляли в настройки).
    check("update-gate", UpdateGate.decide(true, false) == UpdateGate.Decision.PROCEED &&
        UpdateGate.decide(true, true) == UpdateGate.Decision.PROCEED &&
        UpdateGate.decide(false, false) == UpdateGate.Decision.OPEN_SETTINGS &&
        UpdateGate.decide(false, true) == UpdateGate.Decision.DENIED)
    // v0.9.3.2 (краш шаринга из Telegram): FileShareRoute — копия
    // файла теперь в сервисе (FILE_COPY); двухшаговая схема
    // FILE_WAIT/FILE_DELIVER удалена.
    check("fileshare-route", FileShareRoute.resolve(FileShareRoute.ACTION_FILE_COPY, null, "content://telegram/x.mp4", null) == FileShareRoute.Route.COPY_FILE &&
        FileShareRoute.resolve(FileShareRoute.ACTION_FILE_COPY, null, null, null) == FileShareRoute.Route.NONE &&
        FileShareRoute.resolve("com.alexey.aircast.action.CAST", "/cache/x.mp4", null, null) == FileShareRoute.Route.DELIVER_PATH &&
        FileShareRoute.resolve("com.alexey.aircast.action.CAST", null, "content://telegram/x.mp4", null) == FileShareRoute.Route.FILE_URI &&
        FileShareRoute.resolve("com.alexey.aircast.action.CAST", null, null, "https://example.test/p") == FileShareRoute.Route.URL &&
        FileShareRoute.resolve("com.alexey.aircast.action.CAST", null, null, null) == FileShareRoute.Route.NONE)
    // v0.9.3.2 (Instagram через прокси): CDN Instagram — в прокси;
    // без Referer — как раньше, напрямую; чужие хосты — напрямую.
    val igRef = "https://www.instagram.com/p/xyz/"
    check("proxy-instagram-hosts", StreamProxy.needsProxy("https://scontent-hel3-1.cdninstagram.com/v/t50.mp4", igRef) &&
        StreamProxy.needsProxy("https://scontent-iev1-1.xx.fbcdn.net/v.mp4", igRef) &&
        StreamProxy.needsProxy("https://www.instagram.com/p/xyz/media.mp4", igRef) &&
        !StreamProxy.needsProxy("https://scontent-hel3-1.cdninstagram.com/v/t50.mp4", null) &&
        !StreamProxy.needsProxy("https://example.test/v.mp4", igRef) &&
        StreamProxy.needsProxy("https://video.sibnet.ru/v/1.mp4", "https://video.sibnet.ru/shell.php?videoid=1"))
    // v0.9.3.3 (Instagram: кастится только звук): efg-тесты — в
    // отдельной функции ниже (kotlinc 1.4 падает с "wrong bytecode
    // generated", если этот блок остаётся внутри гигантского main()).
    efgTests0933()
    // v0.9.3.4 (Wake-on-LAN): WoL-тесты — тоже в отдельной функции
    // ниже, по той же причине (лимит кодогена на main()).
    wolTests0934()
    // v0.9.3.5 (самообновление: скачивает, но установку не начинает):
    // тесты фикса — в отдельной функции ниже, по той же причине.
    updaterTests0935()
    val igHtml = """<html><head><meta property="og:video" content="https://cdn.example.test/v.mp4"><meta content="https://cdn.example.test/v2.mp4" property="og:video:secure_url"><meta name="twitter:player:stream" content="https://cdn.example.test/v3.mp4?x=1&amp;y=2"><script>{"video_url":"https:\/\/cdn.example.test\/v4.mp4"}</script></head></html>"""
    check("meta-video-urls", VideoUrls.extractMetaVideoUrls(igHtml) == listOf(
        "https://cdn.example.test/v.mp4", "https://cdn.example.test/v2.mp4",
        "https://cdn.example.test/v3.mp4?x=1&y=2", "https://cdn.example.test/v4.mp4"))
    // v0.9.3: video_url с экранированными слешами (\/) — как в сыром
    // HTML Instagram; не-URL video_url — не кандидат.
    check("meta-video-urls-escaped", VideoUrls.extractMetaVideoUrls("""<script>{"video_url":"https:\/\/cdn.example.test\/v.mp4"}</script>""") == listOf("https://cdn.example.test/v.mp4"))
    check("meta-video-urls-not-url", VideoUrls.extractMetaVideoUrls("""<script>{"video_url":"12345"}</script>""").isEmpty())
    check("meta-video-urls-login", VideoUrls.extractMetaVideoUrls("<html><head><title>Instagram</title></head><body>Войдите</body></html>").isEmpty())
    check("meta-video-urls-blank", VideoUrls.extractMetaVideoUrls("<meta property=\"og:video\" content=\"\">").isEmpty() &&
        VideoUrls.extractMetaVideoUrls("<meta property=\"og:video\" content=\"blob:https://x/1\">").isEmpty())
    // ResumePlaylist: обрезка до позиции (фикстура: 6 сегментов, KEY+MAP+ENDLIST).
    val hlsResume = File("tests/fixtures/hls_resume.m3u8").readText()
    val trimmed = ResumePlaylist.trim(hlsResume, 15)
    check("resume-trim", trimmed.keptSegments == 4 && trimmed.skippedSegments == 2 &&
        trimmed.playlist.contains("seg102.ts") && trimmed.playlist.contains("seg105.ts") &&
        !trimmed.playlist.contains("seg100.ts") && !trimmed.playlist.contains("seg101.ts") &&
        trimmed.playlist.contains("#EXT-X-MEDIA-SEQUENCE:102") && trimmed.playlist.contains("#EXT-X-TARGETDURATION:6") &&
        trimmed.playlist.contains("#EXT-X-KEY:METHOD=AES-128") && trimmed.playlist.contains("#EXT-X-MAP:URI=\"init.mp4\"") &&
        trimmed.playlist.contains("#EXT-X-ENDLIST"), trimmed.playlist)
    check("resume-trim-edges", ResumePlaylist.trim(hlsResume, 0).playlist == hlsResume &&
        ResumePlaylist.trim(hlsResume, 100).playlist == hlsResume && ResumePlaylist.trim("", 10).playlist == "" &&
        ResumePlaylist.trim(hlsResume, 6).skippedSegments == 1)
    // TvCheck: локальный сервер — доступен, закрытый порт — нет.
    val tvServer = java.net.ServerSocket(0)
    try {
        check("tvcheck-open", TvCheck.probe("127.0.0.1", tvServer.localPort, 2000))
    } finally {
        tvServer.close()
    }
    check("tvcheck-closed", !TvCheck.probe("127.0.0.1", tvServer.localPort, 500) && !TvCheck.probe("127.0.0.1", 1, 500))
    // Прокси v0.9.1: URL /resume, /file, /memory-vtt; маршруты и Range в исходнике.
    check("proxy-resume-url", StreamProxy.proxyResumeUrl("192.168.1.20", 8080, "https://cdn.example.test/v/master.m3u8", "https://serial.example.test/show/5", 900).startsWith("http://192.168.1.20:8080/resume?u=") &&
        StreamProxy.proxyResumeUrl("192.168.1.20", 8080, "https://cdn.example.test/v/master.m3u8", "https://serial.example.test/show/5", 900).contains("pos=900"))
    check("proxy-file-url", StreamProxy.proxyFileUrl("192.168.1.20", 8080, "file-123", "mp4") == "http://192.168.1.20:8080/file.mp4?id=file-123")
    check("proxy-memory-vtt-url", StreamProxy.proxyMemoryVttUrl("192.168.1.20", 8080, "os-9001") == "http://192.168.1.20:8080/memory-vtt?id=os-9001")
    check("proxy-subs-master-pref", StreamProxy.proxySubsMasterUrl("192.168.1.20", 8080, "https://cdn.example.test/v/master.m3u8", "https://serial.example.test/show/5", listOf(SubTrack("u", "ru", "Русские")), "ru").contains("&d=ru"))
    check("proxy-v091-routes",
        proxySrc.contains("/resume?") && proxySrc.contains("/memory-vtt?") && proxySrc.contains("target.startsWith(\"/file\")") &&
        proxySrc.contains("RangeServe.parseRange") && proxySrc.contains("class FdFileSource") &&
        proxySrc.contains("registerFileSource") && proxySrc.contains("registerMemoryVtt") &&
        proxySrc.contains("preferredLang") && proxySrc.contains("ResumePlaylist.trim"),
        "StreamProxy must serve /resume, /file (Range), /memory-vtt")
    // CompanionClient v0.9.1: пробуждение ТВ (HID Wake, _hidC 13).
    check("companion-wake",
        companionSrc.contains("fun wakeTv") && companionSrc.contains("\"_hidC\"") && companionSrc.contains("\"_hidC\" to 13") &&
        companionSrc.contains("\"_hBtS\" to 2"),
        "CompanionClient must wake the TV via _hidC (HID Wake 13), not WoL")
    // CastEngine v0.9.1: очередь, файлы, pre-check, preload, OpenSubtitles, память, cap-таймер.
    check("engine-v091",
        engineSrc.contains("CastQueue") && engineSrc.contains("fun startShare") &&
        engineSrc.contains("fun startFileShare") && engineSrc.contains("ensureTvReady") &&
        engineSrc.contains("TvCheck.probe") && engineSrc.contains("wakeTv()") &&
        engineSrc.contains("onPlaybackEnded") && engineSrc.contains("onQueueChanged") &&
        engineSrc.contains("armCapTimer") && engineSrc.contains("startPreload") &&
        engineSrc.contains("searchOpenSubtitlesIfNeeded") && engineSrc.contains("YouTubePlaylist") &&
        engineSrc.contains("TitleCleaner") && engineSrc.contains("MasterWait") &&
        engineSrc.contains("PlaybackStore") &&
        engineSrc.contains("proxyResumeUrl") && engineSrc.contains("toggleSubsForCurrentSeries") &&
        engineSrc.contains("fun currentTitle") && engineSrc.contains("fun posterBytes") &&
        engineSrc.contains("fun watchingInfo") && engineSrc.contains("onTvUnreachable") &&
        engineSrc.contains("cleanupCurrentFile") && engineSrc.contains("notifQueuedText"),
        "CastEngine must have queue, file share, TV pre-check, preload, OpenSubtitles, memory, cap timer")
    // CastService v0.9.1: переключение субтитров, название+постер, очередь, конец, файлы.
    check("service-v091",
        serviceSrc090.contains("ACTION_TOGGLE_SUBS") && serviceSrc090.contains("fileCastIntent") &&
        serviceSrc090.contains("notifSuccessWithTitleText") && serviceSrc090.contains("BigPictureStyle") &&
        serviceSrc090.contains("onPlaybackEnded") && serviceSrc090.contains("TempFiles.cleanup") &&
        serviceSrc090.contains("notifWatchingQueueText") && serviceSrc090.contains("notifTvUnreachableText") &&
        serviceSrc090.contains("watchingInfo") && serviceSrc090.contains("FLAG_GRANT_READ_URI_PERMISSION"),
        "CastService must toggle subs, show title+poster, handle queue/end/files")
    // MainActivity v0.9.3.2 (чистка экрана, слово Алексея): блок
    // «Настройка один раз» убран — только компактные строки статуса:
    // спаривание, самопроверка, обновления.
    check("main-status-v0932",
        mainSrc.contains("ТВ спарен ✓") && mainSrc.contains("Спарить Apple TV") &&
        mainSrc.contains("Самопроверка") && mainSrc.contains("runSelfCheck") &&
        mainSrc.contains("DEFAULT_UPDATE_URL") && mainSrc.contains("autoChecksStarted"),
        "MainActivity must have the compact status block (pairing + self-check + updates)")
    // Манифест v0.9.1: ACTION_SEND с video/* — на ShareReceiverActivity.
    check("manifest-video-share",
        shareSection.contains("video/*") && shareSection.contains("android.intent.action.SEND"),
        "manifest must declare ACTION_SEND video/* on ShareReceiverActivity")
    // ShareReceiverActivity v0.9.3.3: видеофайл → копия В ACTIVITY
    // (полупрозрачная, живёт до конца копии) → filePathCastIntent
    // с готовым путём; fileCopyIntent — только fallback при ошибке
    // копии (сервисная копия + URI-путь движка).
    val shareSrc = File("app/src/main/java/com/alexey/aircast/ShareReceiverActivity.kt").readText()
    check("share-receiver-file",
        shareSrc.contains("EXTRA_STREAM") && shareSrc.contains("video/") && shareSrc.contains("filePathCastIntent") &&
        shareSrc.contains("fileCopyIntent") && shareSrc.contains("OpenableColumns.DISPLAY_NAME"),
        "ShareReceiverActivity must copy video/* files itself and start the service with the path (fileCopyIntent is fallback only)")
    // Уведомления v0.9.1: тексты.
    check("notif-texts-v091",
        AutoCast.notifSuccessWithTitleText("Розаріо вампір — 1 сезон, 5 серия") == "«Розаріо вампір — 1 сезон, 5 серия» — запущено на Apple TV ✓" &&
        AutoCast.notifWatchingPositionText() == "Видео идёт — позиция запоминается" &&
        AutoCast.notifWatchingQueueText(2) == "Видео идёт — в очереди ещё 2" &&
        AutoCast.notifQueuedText(1) == "В очереди: 1 — запустится после текущего видео" &&
        AutoCast.notifTvUnreachableText() == "Apple TV не отвечает — проверь, включён ли он" &&
        AutoCast.notifToggleSubsText(true) == "Субтитры: вкл" && AutoCast.notifToggleSubsText(false) == "Субтитры: выкл")

    // --- v0.9.2: каст музыки (аудио-детект, SoundCloud/Bandcamp/радио, YouTube Music, файлы, прокси) ---
    check("audio-url-ext",
        AudioCast.isAudioUrl("https://cdn.example.com/track.mp3") &&
        AudioCast.isAudioUrl("https://cdn.example.com/track.aac") &&
        AudioCast.isAudioUrl("https://cdn.example.com/track.flac") &&
        AudioCast.isAudioUrl("https://cdn.example.com/track.ogg") &&
        AudioCast.isAudioUrl("https://cdn.example.com/track.m4a") &&
        AudioCast.isAudioUrl("https://cdn.example.com/track.wav") &&
        AudioCast.isAudioUrl("https://cdn.example.com/track.opus"))
    check("audio-url-not-video",
        !AudioCast.isAudioUrl("https://cdn.example.com/video.mp4") &&
        !AudioCast.isAudioUrl("https://cdn.example.com/stream.m3u8") &&
        !AudioCast.isAudioUrl("https://soundcloud.com/artist/track") &&
        !AudioCast.isAudioUrl("https://www.youtube.com/watch?v=abc"))
    check("audio-url-services",
        AudioCast.isAudioUrl("https://api-v2.soundcloud.com/media/soundcloud:tracks:123456/stream/hls?client_id=abc123") &&
        AudioCast.isAudioUrl("https://cf-hls-media.sndcdn.com/media/12345/playlist.m3u8") &&
        AudioCast.isAudioUrl("https://t4.bcbits.com/stream/abcdef/mp3-128/1234567890?p=0"))
    check("audio-page",
        AudioCast.isAudioPage("https://soundcloud.com/artist/track") &&
        AudioCast.isAudioPage("https://music.youtube.com/watch?v=abc") &&
        AudioCast.isAudioPage("https://artist.bandcamp.com/album/test") &&
        !AudioCast.isAudioPage("https://www.youtube.com/watch?v=abc") &&
        !AudioCast.isAudioPage("https://www.anilibria.tv/release/x.html"))
    check("audio-unsupported",
        AudioCast.isUnsupportedMusicService("https://open.spotify.com/track/abc") &&
        AudioCast.isUnsupportedMusicService("https://music.apple.com/us/album/x/123") &&
        !AudioCast.isUnsupportedMusicService("https://soundcloud.com/artist/track"))
    check("audio-format-of",
        VideoUrls.formatOf("https://x/track.mp3") == "mp3" &&
        VideoUrls.formatOf("https://x/track.flac") == "flac" &&
        VideoUrls.formatOf("https://x/track.m4a") == "m4a" &&
        VideoUrls.formatOf("https://x/video.mp4") == "mp4")
    check("audio-quality-label",
        AudioCast.audioQualityLabel("https://x/track.flac") == "FLAC" &&
        AudioCast.audioQualityLabel("https://x/track.mp3?bitrate=320") == "320 kbps" &&
        AudioCast.audioQualityLabel("https://x/track.mp3") == "?")
    check("audio-format-rank",
        AutoCast.formatRank("mp3") == 2 && AutoCast.formatRank("flac") == 2 &&
        AutoCast.qualityRank("FLAC") == 6 && AutoCast.qualityRank("320 kbps") == 5 &&
        AutoCast.qualityRank("128 kbps") == 2)
    check("audio-classify",
        AutoCast.classifyUrl("https://cdn.example.com/track.mp3") == AutoCast.ShareKind.DIRECT_AUDIO &&
        AutoCast.classifyUrl("https://cdn.example.com/video.mp4") == AutoCast.ShareKind.DIRECT_VIDEO &&
        AutoCast.classifyUrl("https://soundcloud.com/artist/track") == AutoCast.ShareKind.PAGE)
    // Видео в приоритете: смешанный список — первым видео; только аудио — лучшее аудио.
    val mixedAudio = listOf(
        VideoCandidate("https://x/a.mp3", "mp3", "128 kbps", "p", "t", isAudio = true),
        VideoCandidate("https://x/v.mp4", "mp4", "720p", "p", "t"))
    check("audio-pick-video-first",
        AutoCast.orderedAttempts(mixedAudio).firstOrNull()?.url == "https://x/v.mp4")
    val audioOnly = listOf(
        VideoCandidate("https://x/a128.mp3", "mp3", "128 kbps", "p", "t", isAudio = true),
        VideoCandidate("https://x/a320.mp3", "mp3", "320 kbps", "p", "t", isAudio = true))
    check("audio-pick-audio-only",
        AutoCast.orderedAttempts(audioOnly).firstOrNull()?.url == "https://x/a320.mp3")
    check("audio-notif-texts",
        AutoCast.notifProgressAudioText() == "Отправляю аудио на Apple TV…" &&
        AutoCast.notifSuccessAudioText() == "Аудио запущено на Apple TV ✓")
    // YouTube Music: adaptive audio из фикстуры (itag 141 — лучший).
    val ymJson = File("tests/fixtures/youtube_music_player.json").readText()
    val ym = YouTube.parsePlayerResponse(ymJson)
    check("ytmusic-audio-parse",
        ym.audio.size == 3 && ym.audio.any { it.itag == 141 } && ym.audio.any { it.itag == 251 } && ym.audio.any { it.itag == 140 })
    check("ytmusic-pick-audio",
        YouTube.pickAudioStream(ym)?.first?.contains("itag=141") == true)
    val videoOnlyJson = """{"streamingData":{"adaptiveFormats":[{"bitrate":2000000,"itag":22,"mimeType":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"","url":"https://rr1.googlevideo.com/videoplayback?itag=22"}]},"videoDetails":{"lengthSeconds":"100","title":"v"}}"""
    check("ytmusic-pick-audio-null", YouTube.pickAudioStream(YouTube.parsePlayerResponse(videoOnlyJson)) == null)
    // SCAN_JS v0.9.2: <audio>, SoundCloud media, Bandcamp — исходник + парсинг.
    check("scanjs-audio",
        extractorSrc.contains("querySelectorAll('audio')") &&
        extractorSrc.contains("api-v2.soundcloud.com/media") &&
        extractorSrc.contains("bcbits.com/stream") &&
        extractorSrc.contains("audios: top.audios"))
    val scanAudio = VideoUrls.parseScanResult("""{"videos":[],"audios":[{"src":"https://example.com/track.mp3","w":0,"h":0}],"iframes":[],"urls":[],"rel":[],"jwfeeds":[],"links":[],"subs":[],"url":"https://soundcloud.com/x","title":"t","poster":""}""")
    check("scan-audios-parse", scanAudio.audios.size == 1 && scanAudio.audios[0].src.endsWith("track.mp3"))
    // SoundCloud: извлечение потока из JSON media API.
    check("soundcloud-extract",
        AudioCast.extractSoundCloudStreamUrl("""{"url":"https://cf-hls-media.sndcdn.com/media/123/playlist.m3u8"}""") == "https://cf-hls-media.sndcdn.com/media/123/playlist.m3u8" &&
        AudioCast.extractSoundCloudStreamUrl("{}") == null &&
        AudioCast.extractSoundCloudStreamUrl("garbage") == null)
    // Радио: .pls и .m3u из фикстур.
    val pls = File("tests/fixtures/radio.pls").readText()
    val radioM3u = File("tests/fixtures/radio.m3u").readText()
    check("radio-pls",
        AudioCast.isRadioPlaylistUrl("https://x/radio.pls") &&
        AudioCast.parseRadioPlaylist(pls) == "https://stream.example-radio.fm/live.mp3")
    check("radio-m3u",
        AudioCast.isRadioPlaylistUrl("https://x/list.m3u") &&
        AudioCast.parseRadioPlaylist(radioM3u) == "https://stream.example-radio.fm/live.aac" &&
        AudioCast.parseRadioPlaylist("garbage, no urls") == null)
    // mp3-длительность: синтетический CBR 128k/44100 (417 Б/фрейм).
    val cbr = ByteArray(384000)
    var cbrOff = 0
    while (cbrOff + 417 <= cbr.size) {
        cbr[cbrOff] = 0xFF.toByte(); cbr[cbrOff + 1] = 0xFB.toByte(); cbr[cbrOff + 2] = 0x90.toByte(); cbr[cbrOff + 3] = 0
        cbrOff += 417
    }
    check("mp3-duration-cbr", AudioCast.mp3DurationSec(cbr, 384000L) == 24L)
    val xing = ByteArray(600)
    xing[0] = 0xFF.toByte(); xing[1] = 0xFB.toByte(); xing[2] = 0x90.toByte(); xing[3] = 0
    "Xing".toByteArray(Charsets.US_ASCII).copyInto(xing, 36)
    xing[43] = 1 // flags = 0x00000001 (есть счётчик фреймов)
    xing[47] = 100 // frames = 100
    check("mp3-duration-xing", AudioCast.mp3DurationSec(xing, 600L) == 2L)
    check("mp3-duration-none", AudioCast.mp3DurationSec(ByteArray(1000) { 0x55 }, 1000L) == null)
    check("mp3-frame-header",
        AudioCast.parseMp3FrameHeader(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0), 0)?.let { it.bitrate == 128 && it.sampleRate == 44100 && it.version == 1 } == true)
    check("audio-file-name",
        AudioCast.isAudioFileName("song.mp3") && AudioCast.isAudioFileName("track.FLAC") &&
        !AudioCast.isAudioFileName("video.mp4") && !AudioCast.isAudioFileName(null))
    // Прокси: аудио Content-Type в исходнике.
    check("proxy-audio-types",
        proxySrc.contains("audio/mpeg") && proxySrc.contains("audio/flac") && proxySrc.contains("audio/ogg") &&
        proxySrc.contains("audio/mp4") && proxySrc.contains("audio/wav") && proxySrc.contains("audio/opus"))
    // Манифест: ACTION_SEND audio/* на ShareReceiverActivity.
    check("manifest-audio-share", shareSection.contains("audio/*"))
    // ShareReceiverActivity v0.9.3.3: audio/* → копия в activity +
    // filePathCastIntent с isAudio; fileCopyIntent — fallback.
    check("share-receiver-audio",
        shareSrc.contains("audio/") && shareSrc.contains("filePathCastIntent") && shareSrc.contains("fileCopyIntent") && shareSrc.contains("isAudio"))
    // CastEngine: аудио-ветки в исходнике.
    check("engine-audio",
        engineSrc.contains("trySoundCloudMedia") && engineSrc.contains("tryRadioPlaylist") &&
        engineSrc.contains("isAudioFlow") && engineSrc.contains("startDirectAudioFlow") &&
        engineSrc.contains("pendingLaunchIsAudio") && engineSrc.contains("audioPage"))
    // CastService: флаг isAudio в fileCastIntent + аудио-тексты.
    check("service-audio",
        serviceSrc.contains("EXTRA_FILE_IS_AUDIO") && serviceSrc.contains("notifSuccessAudioText") &&
        serviceSrc.contains("notifProgressAudioText"))
    // v0.9.3.3: копия файла — в СЕРВИСЕ осталась только как fallback
    // (основная — в ShareReceiverActivity); grant до сервиса не
    // доходит (ENOENT, лог 07:51:32), поэтому primary — activity.
    check("service-copies-file",
        serviceSrc.contains("copySharedFile") && serviceSrc.contains("openInputStream") &&
        serviceSrc.contains("TempFiles.tempName") && serviceSrc.contains("fileCopyIntent") &&
        serviceSrc.contains("скопирован в сервисе") && serviceSrc.contains("FILE_COPY"))
    check("service-file-path",
        serviceSrc.contains("EXTRA_FILE_PATH") && serviceSrc.contains("filePathCastIntent") &&
        serviceSrc.contains("onFileError") && serviceSrc.contains("shareLog = true"))
    check("engine-file-path",
        engineSrc.contains("JavaFileSource(f)") && engineSrc.contains("onFileError") &&
        engineSrc.contains("startsWith(\"/\")"))
    // MainActivity: карточка музыки на экране-инструкции.
    check("main-music-card", mainActivitySrc.contains("🎵") && mainActivitySrc.contains("SoundCloud"))
    // StreamProbe: ветка .mp3 + fetchMp3DurationSec.
    val probeSrc = File("app/src/main/java/com/alexey/aircast/StreamProbe.kt").readText()
    check("probe-mp3", probeSrc.contains("fetchMp3DurationSec") && probeSrc.contains(".mp3"))
    // Фикстуры аудио: SoundCloud/Bandcamp/HLS-аудио.
    check("audio-fixtures",
        File("tests/fixtures/soundcloud_page.html").readText().contains("api-v2.soundcloud.com/media") &&
        File("tests/fixtures/bandcamp_page.html").readText().contains("bcbits.com/stream") &&
        File("tests/fixtures/audio_hls.m3u8").readText().contains("cf-hls-media.sndcdn.com"))
    // --- v0.9.2 (БАГ 0.9.1 №2): pre-check fallback, isBusy, drain при ошибке ---
    // isBusyState — чистое решение по состоянию (JVM, без ТВ).
    check("busy-states",
        CastEngine.isBusyState(CastEngine.FlowState.SEARCHING_TV) &&
        CastEngine.isBusyState(CastEngine.FlowState.LOADING_PAGE) &&
        CastEngine.isBusyState(CastEngine.FlowState.SEARCHING_VIDEO) &&
        CastEngine.isBusyState(CastEngine.FlowState.LAUNCHING))
    check("busy-launched-free",
        !CastEngine.isBusyState(CastEngine.FlowState.LAUNCHED) &&
        !CastEngine.isBusyState(CastEngine.FlowState.IDLE) &&
        !CastEngine.isBusyState(CastEngine.FlowState.TIMEOUT) &&
        !CastEngine.isBusyState(CastEngine.FlowState.NEED_PAIRING))
    // isBusy в исходнике — только через isBusyState (без launched && timer).
    check("engine-busy-no-cap",
        engineSrc.contains("isBusyState(flowState)") && !engineSrc.contains("launched && nextTimerRunnable"))
    // failFlow + drainQueue: есть, и все терминальные ветки через них.
    check("engine-failflow",
        engineSrc.contains("private fun failFlow") && engineSrc.contains("private fun drainQueue") &&
        engineSrc.contains("failFlow { listener.onLaunchFailed") &&
        engineSrc.contains("failFlow { listener.onTimeout") &&
        engineSrc.contains("failFlow { listener.onError") &&
        engineSrc.contains("failFlow { listener.onFileError"))
    // Pre-check fallback: probe+wake → mDNS, onTvUnreachable только если mDNS пуст.
    check("engine-tv-fallback",
        engineSrc.contains("fallbackToDiscovery") && engineSrc.contains("tvFallbackNone") &&
        engineSrc.contains("fallback: ищу ТВ заново по mDNS") &&
        engineSrc.contains("failFlow { listener.onTvUnreachable() }") &&
        engineSrc.contains("sleep proxy"))
    // Wake-комментарий: Companion живёт только при живом Companion.
    val companionClientSrc = File("app/src/main/java/com/alexey/aircast/CompanionClient.kt").readText()
    check("wake-sleep-proxy-note",
        companionClientSrc.contains("sleep proxy") && companionClientSrc.contains("только при живом"))
    // Уведомление «Apple TV недоступен» — с «Поделиться логом».
    check("service-tv-unreachable-log",
        serviceSrc.contains("notifTvUnreachableText(), AutoCast.MODE_INSTRUCTION, shareLog = true"))
    // --- v0.9.2: источник обновлений по умолчанию (GitHub Al3xJ0bs/VLC-Cast) ---
    check("update-default-url",
        UpdateCheck.DEFAULT_UPDATE_URL == "https://raw.githubusercontent.com/Al3xJ0bs/VLC-Cast/main/latest.json")
    // fetchLatestResult против локального сервера: 200 OK / 404 / мусор / обрыв.
    val (updPort, updServer) = localHttpServer(mapOf(
        "/latest.json" to (200 to """{"versionCode":33,"versionName":"0.9.3","url":"https://example.test/v093.apk"}"""),
        "/bad.json" to (200 to "garbage, not json")))
    val updBase = "http://127.0.0.1:$updPort"
    check("update-fetch-ok",
        UpdateCheck.fetchLatestResult("$updBase/latest.json") == UpdateCheck.FetchResult.Ok(UpdateCheck.Latest(33, "0.9.3", "https://example.test/v093.apk")))
    check("update-fetch-wrapper",
        UpdateCheck.fetchLatest("$updBase/latest.json") == UpdateCheck.Latest(33, "0.9.3", "https://example.test/v093.apk"))
    check("update-fetch-404",
        UpdateCheck.fetchLatestResult("$updBase/nope.json") == UpdateCheck.FetchResult.Unavailable)
    check("update-fetch-bad",
        UpdateCheck.fetchLatestResult("$updBase/bad.json") == UpdateCheck.FetchResult.Error)
    updServer.close()
    check("update-fetch-refused",
        UpdateCheck.fetchLatestResult("$updBase/latest.json") == UpdateCheck.FetchResult.Error)
    // MainActivity v0.9.3.2: источник обновлений — всегда вшитый
    // DEFAULT_UPDATE_URL; функция updateCheckUrl() удалена вместе
    // с полем ввода URL; 404 — честно «источник недоступен».
    check("main-update-default",
        mainSrc.contains("UpdateCheck.DEFAULT_UPDATE_URL") &&
        mainSrc.contains("источник недоступен") &&
        !mainSrc.contains("updateCheckUrl()"))

    // --- v0.9.3: DASH, 60fps, проба зеркал, VLC-check, JSON-LD, oEmbed, бэкап ---
    // #1: DashManifest — структура, SegmentBase, кодексы, PT, null без initRange.
    val dashV = DashManifest.Track("https://example.test/v.mp4", "video/mp4", "avc1.640028", 4_000_000L, 1920, 1080, 60, 0L, 712L, 713L, 1536L)
    val dashA = DashManifest.Track("https://example.test/a.m4a", "audio/mp4", "mp4a.40.2", 128_000L, null, null, null, 0L, 672L, 673L, 1450L)
    val mpd = DashManifest.build(dashV, dashA, 3723L)
    check("dash-build", mpd != null && mpd.contains("<MPD") && mpd.contains("mediaPresentationDuration=\"PT1H2M3S\"") &&
        mpd.contains("minBufferTime=\"PT1.5S\"") && mpd.contains("isoff-on-demand:2011") &&
        mpd.contains("mimeType=\"video/mp4\"") && mpd.contains("codecs=\"avc1.640028\"") &&
        mpd.contains("width=\"1920\"") && mpd.contains("height=\"1080\"") && mpd.contains("frameRate=\"60\"") &&
        mpd.contains("<Initialization range=\"0-712\"/>") && mpd.contains("indexRange=\"713-1536\"") &&
        mpd.contains("mimeType=\"audio/mp4\"") && mpd.contains("<BaseURL>https://example.test/v.mp4</BaseURL>") &&
        mpd.contains("bandwidth=\"4000000\""))
    check("dash-pt", DashManifest.ptDuration(3723L) == "PT1H2M3S" && DashManifest.ptDuration(90L) == "PT1M30S" &&
        DashManifest.ptDuration(45L) == "PT45S" && DashManifest.ptDuration(3600L) == "PT1H0S")
    check("dash-no-init", DashManifest.build(dashV.copy(initStart = null), dashA, 100L) == null &&
        DashManifest.build(dashV, dashA.copy(initEnd = null), 100L) == null)
    check("dash-no-duration", DashManifest.build(dashV, dashA, null)?.contains("mediaPresentationDuration") == false)
    // #1: pickDashPair — лучшее видео-only (высота, fps, битрейт) + аудио по рангу itag.
    val dashParse = YouTube.PlayerParse(null, null, emptyList(), false, "OK", 3723L,
        audio = listOf(
            YouTube.AudioFormat(140, "https://example.test/a140.m4a", "audio/mp4; codecs=\"mp4a.40.2\"", false, 0L, 672L, 673L, 1450L, 131072L),
            YouTube.AudioFormat(251, "https://example.test/a251.webm", "audio/webm; codecs=\"opus\"", false, 0L, 600L, 601L, 1200L, 160000L)),
        video = listOf(
            YouTube.VideoAdaptiveFormat(136, "https://example.test/v136.mp4", "video/mp4; codecs=\"avc1.4d401f\"", false, 1280, 720, 30, 2_000_000L, 0L, 712L, 713L, 1536L),
            YouTube.VideoAdaptiveFormat(137, "https://example.test/v137.mp4", "video/mp4; codecs=\"avc1.640028\"", false, 1920, 1080, 60, 8_000_000L, 0L, 712L, 713L, 1536L),
            YouTube.VideoAdaptiveFormat(298, "https://example.test/v298.mp4", "video/mp4; codecs=\"avc1.640028\"", false, 1920, 1080, 30, 6_000_000L, 0L, 712L, 713L, 1536L),
            YouTube.VideoAdaptiveFormat(299, null, "video/mp4; codecs=\"avc1.640028\"", true, 1920, 1080, 60, 12_000_000L, 0L, 712L, 713L, 1536L)))
    val dashPair = YouTube.pickDashPair(dashParse)
    check("dash-pair", dashPair != null && dashPair.video.itag == 137 && dashPair.audio.itag == 251)
    check("dash-pair-no-init", YouTube.pickDashPair(dashParse.copy(video = listOf(
        YouTube.VideoAdaptiveFormat(137, "https://example.test/v137.mp4", "video/mp4", false, 1920, 1080, 60, 8_000_000L, null, null, null, null)))) == null)
    check("dash-pair-cipher-only", YouTube.pickDashPair(dashParse.copy(video = listOf(
        YouTube.VideoAdaptiveFormat(137, null, "video/mp4", true, 1920, 1080, 60, 8_000_000L, 0L, 712L, 713L, 1536L)))) == null)
    val pairMpd = dashPair?.let { DashManifest.build(YouTube.videoDashTrack(it.video)!!, YouTube.audioDashTrack(it.audio)!!, dashParse.lengthSeconds) }
    check("dash-pair-mpd", pairMpd != null && pairMpd.contains("avc1.640028") && pairMpd.contains("opus") && pairMpd.contains("PT1H2M3S"))
    // #4: fpsOf + tie-break в orderedAttempts (равный score — 60fps выше).
    check("fps-of", VideoUrls.fpsOf("https://cdn.test/v_1080p60.mp4") == 60 &&
        VideoUrls.fpsOf("https://cdn.test/v-720p60fps.mp4") == 60 &&
        VideoUrls.fpsOf("https://cdn.test/v_1080p.mp4") == null &&
        VideoUrls.fpsOf("https://cdn.test/v_1080p30.mp4") == 30 &&
        VideoUrls.fpsOf("https://cdn.test/v_1080p120.mp4") == 120)
    val c30 = VideoCandidate("https://a.test/v1080p30.mp4", "mp4", "1080p", "https://a.test/", "test", fps = 30)
    val c60 = VideoCandidate("https://b.test/v1080p60.mp4", "mp4", "1080p", "https://b.test/", "test", fps = 60)
    val fpsAttempts = AutoCast.orderedAttempts(listOf(c30, c60), 2)
    check("fps-tiebreak", fpsAttempts.size == 2 && fpsAttempts[0].url == c60.url)
    // #5: probeFirstByteMs — локальный сервер: 200 → число мс, 404 → null.
    val (mpProbePort, mpProbeServer) = localHttpServer(mapOf("/v.mp4" to (200 to "x")))
    val probeMs = StreamProbe.probeFirstByteMs("http://127.0.0.1:$mpProbePort/v.mp4", null, null)
    check("probe-first-byte", probeMs != null && probeMs >= 0)
    check("probe-first-byte-404", StreamProbe.probeFirstByteMs("http://127.0.0.1:$mpProbePort/nope.mp4", null, null) == null)
    mpProbeServer.close()
    // #14: isVlcInApps — bundle id с vlc или имя VLC.
    check("vlc-in-apps", CompanionClient.isVlcInApps(mapOf("com.apple.tv" to "TV", "org.videolan.vlc-ios" to "VLC")) &&
        CompanionClient.isVlcInApps(mapOf("com.example.app" to "VLC")) &&
        !CompanionClient.isVlcInApps(mapOf("com.apple.tv" to "TV", "com.netflix.Netflix" to "Netflix")) &&
        !CompanionClient.isVlcInApps(emptyMap()))
    // #23: JSON-LD VideoObject — contentUrl, @graph, не-видео, страница плеера, экранирование.
    check("jsonld-content", VideoUrls.parseJsonLdVideoObject("""{"@context":"https://schema.org","@type":"VideoObject","name":"x","contentUrl":"https://cdn.test/v.mp4"}""") == "https://cdn.test/v.mp4")
    check("jsonld-graph", VideoUrls.parseJsonLdVideoObject("""{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"VideoObject","embedUrl":"https://cdn.test/v.m3u8"}]}""") == "https://cdn.test/v.m3u8")
    check("jsonld-not-video", VideoUrls.parseJsonLdVideoObject("""{"@type":"Article","contentUrl":"https://cdn.test/v.mp4"}""") == null)
    check("jsonld-player-page", VideoUrls.parseJsonLdVideoObject("""{"@type":"VideoObject","embedUrl":"https://site.test/watch/123"}""") == null)
    check("jsonld-escaped", VideoUrls.parseJsonLdVideoObject("""{"@type":"VideoObject","contentUrl":"https:\/\/cdn.test\/v.mp4"}""") == "https://cdn.test/v.mp4")
    // #23/#24: parseJsonStringArray — скобки и кавычки внутри строк.
    val jsa = VideoUrls.parseJsonStringArray("""{"jsonld":["{\"@type\":\"VideoObject\"}","a]b"],"oembed":["https://x.test/o.json"]}""", "jsonld")
    check("json-string-array", jsa.size == 2 && jsa[0] == """{"@type":"VideoObject"}""" && jsa[1] == "a]b")
    check("json-string-array-missing", VideoUrls.parseJsonStringArray("""{"a":[]}""", "jsonld").isEmpty())
    // #24: oEmbed — url, iframe src из html, type=photo → null, без type — как video.
    check("oembed-url", VideoUrls.parseOembedVideoUrl("""{"type":"video","url":"https://cdn.test/v.mp4","html":"<iframe src=\"https://cdn.test/other.mp4\"></iframe>"}""") == "https://cdn.test/v.mp4")
    check("oembed-html", VideoUrls.parseOembedVideoUrl("""{"type":"video","html":"<iframe width=\"640\" src=\"https://cdn.test/v.m3u8\"></iframe>"}""") == "https://cdn.test/v.m3u8")
    check("oembed-photo", VideoUrls.parseOembedVideoUrl("""{"type":"photo","url":"https://cdn.test/v.mp4"}""") == null)
    check("oembed-no-type", VideoUrls.parseOembedVideoUrl("""{"url":"https://cdn.test/v.mp4"}""") == "https://cdn.test/v.mp4")
    // #17/#18/#20: UI на месте (текстовые маркеры в MainActivity).
    check("ui-093", mainSrc.contains("Сохранить вход (куки)") &&
        mainSrc.contains("Самопроверка") && mainSrc.contains("runSelfCheck") &&
        mainSrc.contains("Что нового в \$VERSION") && mainSrc.contains("last_seen_version"))
    // v0.9.3.2 (чистка главного экрана): самопроверка и обновления —
    // авто при запуске; кнопок проверки, поля URL, ключа OpenSubtitles
    // и бэкапа спаривания на главном экране больше нет.
    // v0.9.3.4: «Поделиться логом» с главного экрана тоже убрана
    // (слово Алексея) — её наличие тут больше не проверяется,
    // см. ui-0934-no-log-button-main ниже.
    check("ui-0932-cleanup", mainSrc.contains("autoChecksStarted") && mainSrc.contains("DEFAULT_UPDATE_URL") &&
        !mainSrc.contains("button(\"Экспорт спаривания\")") &&
        !mainSrc.contains("button(\"Импорт спаривания\")") &&
        !mainSrc.contains("REQUEST_BACKUP_IMPORT") &&
        !mainSrc.contains("PairingBackup") &&
        !mainSrc.contains("button(\"Проверить обновления\")") &&
        !mainSrc.contains("button(\"Проверить\")") &&
        !mainSrc.contains("bigButton(\"Сохранить настройки\")") &&
        !mainSrc.contains("update_check_url") &&
        !mainSrc.contains("opensubtitles_api_key"))

    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TESTS FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}

// v0.9.3.3 (Instagram: кастится только звук): efg из живого лога
// 07:51:04 (рилс instagram.com/reel/Df1cvoMx59o/): m78 — DASH-аудио
// (vencode_tag ...dash_ln_heaac_vbr3_audio + bytestart/byteend),
// m86 — настоящее 720p видео (xpv_progressive...dash_baseline_1_v1).
// Проба зеркал предпочла аудио по латентности — теперь аудио-efg
// исключается из видео-кандидатов вообще, а byte-range понижается.
// Отдельная функция, а не блок в main(): kotlinc 1.4 падает с
// "wrong bytecode generated", если этот блок остаётся внутри
// гигантского main().
fun efgTests0933() {
    val igAudioEfg = "eyJ2ZW5jb2RlX3RhZyI6ImlnLXhwdmRzLmNsaXBzLmlnd3d3LUMzLmRhc2hfbG5faGVhYWNfdmJyM19hdWRpbyIsInZpZGVvX2lkIjpudWxsLCJvaWxfdXJsZ2VuX2FwcF9pZCI6OTM2NjE5NzQzMzkyNDU5LCJjbGllbnRfbmFtZSI6ImlnIiwieHB2X2Fzc2V0X2lkIjoxNzk4NzMwNjMxMzkyNjk4LCJhc3NldF9hZ2VfZGF5cyI6MCwidmlfdXNlY2FzZV9pZCI6MTAwOTksImR1cmF0aW9uX3MiOjExLCJiaXRyYXRlIjo2MjY2NiwidXJsZ2VuX3NvdXJjZSI6Ind3dyJ9"
    val igVideoEfg = "eyJ2ZW5jb2RlX3RhZyI6Inhwdl9wcm9ncmVzc2l2ZS5JTlNUQUdSQU0uQ0xJUFMuQzMuNzIwLmRhc2hfYmFzZWxpbmVfMV92MSIsInhwdl9hc3NldF9pZCI6MTc5ODczMDYzMTM5MjY5OCwiYXNzZXRfYWdlX2RheXMiOjAsInZpX3VzZWNhc2VfaWQiOjEwMDk5LCJkdXJhdGlvbl9zIjoxMSwidXJsZ2VuX3NvdXJjZSI6Ind3dyJ9"
    val igAudioUrl = "https://scontent-iev1-1.cdninstagram.com/o1/v/t2/f2/m78/AQPzo2zC.mp4?efg=$igAudioEfg&bytestart=928&byteend=18341"
    val igVideoUrl = "https://scontent-iev1-1.cdninstagram.com/o1/v/t2/f2/m86/AQPzwfs2.mp4?efg=$igVideoEfg"
    check("efg-decode-audio", VideoUrls.decodeEfgTag(igAudioUrl) == "ig-xpvds.clips.igwww-C3.dash_ln_heaac_vbr3_audio",
        VideoUrls.decodeEfgTag(igAudioUrl) ?: "null")
    check("efg-decode-video", VideoUrls.decodeEfgTag(igVideoUrl) == "xpv_progressive.INSTAGRAM.CLIPS.C3.720.dash_baseline_1_v1",
        VideoUrls.decodeEfgTag(igVideoUrl) ?: "null")
    check("efg-audio-only", VideoUrls.isAudioOnlyEfg(igAudioUrl) && !VideoUrls.isAudioOnlyEfg(igVideoUrl),
        "audio efg must be audio-only, video efg must not")
    check("efg-no-efg", !VideoUrls.isAudioOnlyEfg("https://example.test/v.mp4") &&
        VideoUrls.decodeEfgTag("https://example.test/v.mp4") == null &&
        !VideoUrls.isAudioOnlyEfg("https://example.test/v.mp4?efg=!!!not-base64!!!"),
        "no efg / garbage efg must not be audio-only")
    check("byterange-detect", VideoUrls.hasByteRange(igAudioUrl) && !VideoUrls.hasByteRange(igVideoUrl),
        "bytestart/byteend must mark a byte-range URL")
    check("byterange-score", AutoCast.score(VideoCandidate(igAudioUrl, "mp4", "720p", null, "t")) <
        AutoCast.score(VideoCandidate(igVideoUrl, "mp4", "720p", null, "t")),
        "byte-range segment must score below the full file of the same format/quality")
    // Страховка в orderedAttempts: аудио-efg с БОЛЬШИМ качеством
    // (2160p) всё равно не попадает в видео-пул — первым идёт видео.
    check("ordered-efg-filter", AutoCast.orderedAttempts(listOf(
        VideoCandidate(igAudioUrl, "mp4", "2160p", null, "t"),
        VideoCandidate(igVideoUrl, "mp4", "720p", null, "t"))).firstOrNull()?.url == igVideoUrl,
        "audio-efg must not be in the video pool even with higher quality")
    // Явный аудио-флоу (isAudio) efg-фильтр не трогает.
    check("ordered-efg-audio-kept", AutoCast.orderedAttempts(listOf(
        VideoCandidate(igAudioUrl, "mp4", "720p", null, "t", isAudio = true))).firstOrNull()?.url == igAudioUrl,
        "explicit audio flow must keep its candidate")
}

/** Локальный HTTP-сервер для probe-тестов: path → (код, тело). */
fun localHttpServer(responses: Map<String, Pair<Int, String>>): Pair<Int, java.net.ServerSocket> {
    val server = java.net.ServerSocket(0)
    val t = Thread {
        try {
            while (!server.isClosed) {
                val sock = server.accept()
                try {
                    val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                    val reqLine = reader.readLine() ?: ""
                    var line: String?
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) { }
                    val path = reqLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
                    val (code, body) = responses[path] ?: (404 to "not found")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val status = if (code == 200) "OK" else "Not Found"
                    val head = "HTTP/1.1 $code $status\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                    sock.getOutputStream().write(bytes)
                    sock.getOutputStream().flush()
                } catch (ignored: Exception) { } finally { runCatching { sock.close() } }
            }
        } catch (ignored: Exception) { }
    }
    t.isDaemon = true
    t.start()
    return server.localPort to server
}

/** Ручной bplist00: {"eventPort": <int 0xC0A1, 2 байта>} — как прислал ТВ.
 *  obj0 @8: dict D1 01 02; obj1 @11: "eventPort"; obj2 @21: int 0x11 C0 A1;
 *  offset table @24: 08 0B 15; trailer: offsetSize=1, refSize=1, 3 объекта. */
fun bplist2ByteInt(): ByteArray {
    val out = ArrayList<Byte>()
    fun b(vararg xs: Int) { for (x in xs) out.add(x.toByte()) }
    fun s(str: String) { str.toByteArray(Charsets.US_ASCII).forEach { out.add(it) } }
    fun longBE(v: Long) { for (i in 7 downTo 0) out.add(((v shr (8 * i)) and 0xFF).toByte()) }
    s("bplist00")
    b(0xD1, 0x01, 0x02)
    b(0x59); s("eventPort")
    b(0x11, 0xC0, 0xA1)
    b(0x08, 0x0B, 0x15)
    b(0, 0, 0, 0, 0, 0, 1, 1)
    longBE(3); longBE(0); longBE(24)
    return out.toByteArray()
}


// Крипто-тесты (HKDF/ChaCha/X25519/Ed25519/TLV8/bplist) —
// вынесены из main(): kotlinc 1.4 падает с "wrong bytecode
// generated", когда main() перерастает лимит кодогена.
fun cryptoTests() {
// --- HKDF-SHA512, RFC 5869 test case 1 ---
val ikm = ByteArray(22) { 0x0b }
val salt = ByteArray(13) { it.toByte() }
val info = ByteArray(10) { (0xf0 + it).toByte() }
val okm = Hkdf.expand(salt, info, ikm, 42)
check("hkdf-rfc5869", hex(okm) == "832390086cda71fb47625bb5ceb168e4c8e26a1a16ed34d9fc7fe92c1481579338da362cb8d9f925d7cb", hex(okm))

// --- ChaCha20-Poly1305, RFC 8439 A.5 ---
val key = ByteArray(32) { (0x80 + it).toByte() }
val nonce = hex("070000004041424344454647")
val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray(Charsets.US_ASCII)
val aad = hex("50515253c0c1c2c3c4c5c6c7")
val cipher = ChaCha20Poly1305(key, key)
val ct = cipher.encrypt(pt, nonce = nonce, aad = aad)
check("chacha20-poly1305-rfc8439", hex(ct) == "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691", hex(ct))
val back = ChaCha20Poly1305(key, key).decrypt(ct, nonce = nonce, aad = aad)
check("chacha20-roundtrip", back.contentEquals(pt))

// --- X25519, RFC 7748 5.2 ---
val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
val shared = Curve25519.x25519(alicePriv, bobPub)
check("x25519-rfc7748", hex(shared) == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(shared))

// --- Ed25519, RFC 8032 test 1 key + python-computed signature for msg af82 ---
val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
val pub = Curve25519.ed25519PublicKey(seed)
check("ed25519-pubkey", hex(pub) == "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hex(pub))
val msg = hex("af82")
val sig = Curve25519.ed25519Sign(seed, msg)
check("ed25519-sign", hex(sig) == "ed652fda879f268e71658f05cb7c0d620ae3998ab7d9a7535719f9a63cbdbcafffd2e8b9ff03a42a61a26a230353db43ddadfcd7b820a87bce90e90a5a44d400", hex(sig))
check("ed25519-verify", Curve25519.ed25519Verify(sig, pub, msg))
check("ed25519-verify-tamper", !Curve25519.ed25519Verify(sig, pub, hex("af83")))

// --- TLV8 round-trip ---
val tlv = Tlv8().apply {
    add(0x00, byteArrayOf(0))
    add(0x01, "hello".toByteArray())
    add(0x02, ByteArray(300) { 0xAB.toByte() })
}.encode()
val decoded = Tlv8.decode(tlv)
check("tlv8", decoded[1]?.contentEquals("hello".toByteArray()) == true && decoded[2]?.size == 300)

// --- bplist: decode a Python plistlib fixture, then round-trip ---
val fixture = File("tests/fixture.bplist").readBytes()
val parsed = Bplist.decode(fixture) as Map<*, *>
check("bplist-py-decode", parsed["model"] == "AppleTV5,3" && (parsed["features"] as Long) == 0x1234567890L && parsed["flag"] == true, Bplist.describe(parsed))
val nested = parsed["nested"] as Map<*, *>
check("bplist-py-nested", nested["arr"] == listOf(1L, 2L, 3L) && (nested["data"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3, 4)))
check("bplist-py-unicode", parsed["title"] == "Привет")

val myDoc: Map<String, Any?> = mapOf(
    "Content-Location" to "http://example.local/video.mp4",
    "Start-Position-Seconds" to 12.5,
    "streamType" to 1,
    "rate" to 1.0,
    "ok" to true,
    "blob" to byteArrayOf(9, 8, 7),
    "nested" to mapOf("x" to listOf("a", "b"))
)
val encoded = Bplist.encode(myDoc)
File("tests/kotlin.bplist").writeBytes(encoded)
val reparsed = Bplist.decode(encoded) as Map<*, *>
check("bplist-kt-roundtrip", reparsed["Content-Location"] == "http://example.local/video.mp4" && reparsed["Start-Position-Seconds"] == 12.5 && reparsed["streamType"] == 1L && reparsed["ok"] == true, Bplist.describe(reparsed))

// --- JS scan decoding (v0.1.2): evaluateJavascript returns the JS
// result as a JSON-encoded STRING (quoted, inner quotes escaped).
// A direct JSONObject() on it failed on every scan in v0.1.1.
// The quoted value below is exactly what the WebView callback gets
// for a page with one <video> and one player <iframe>.
val quoted = """"{\"videos\":[{\"src\":\"https://example.test/v.mp4\",\"w\":1280,\"h\":720}],\"iframes\":[\"https://player.example.test/?t=abc\",\"https://static.example.test/slider/\"],\"url\":\"https://example.test/\",\"title\":\"T\"}""""
val jsDecoded = VideoUrls.decodeJsResult(quoted)
check("js-decode-quoted", jsDecoded != null && jsDecoded.startsWith("{\"videos\""), jsDecoded?.take(40) ?: "null")
val jsParsed = VideoUrls.parseScanResult(quoted)
check("js-parse-videos", jsParsed.videos.size == 1 && jsParsed.videos[0].src == "https://example.test/v.mp4" && jsParsed.videos[0].w == 1280 && jsParsed.videos[0].h == 720, jsParsed.toString())
check("js-parse-iframes", jsParsed.iframes.size == 2 && jsParsed.iframes[0] == "https://player.example.test/?t=abc", jsParsed.iframes.toString())
val plain = """{"videos":[{"src":"https://example.test/a.mp4","w":0,"h":0}],"iframes":[],"url":"https://example.test/","title":"T"}"""
val jsParsedPlain = VideoUrls.parseScanResult(plain)
check("js-decode-plain", jsParsedPlain.videos.size == 1 && jsParsedPlain.videos[0].src == "https://example.test/a.mp4", jsParsedPlain.toString())
check("js-decode-null", VideoUrls.parseScanResult("null") == com.alexey.aircast.ScanResult(emptyList(), emptyList()))
check("js-decode-empty", VideoUrls.parseScanResult("") == com.alexey.aircast.ScanResult(emptyList(), emptyList()))
check("js-decode-null-input", VideoUrls.parseScanResult(null) == com.alexey.aircast.ScanResult(emptyList(), emptyList()))
// \uXXXX escapes inside the quoted string (e.g. Cyrillic titles).
val quotedUni = """"{\"videos\":[],\"iframes\":[],\"url\":\"https://example.test/\",\"title\":\"\u0420\u043e\u0437\u0430\"}""""
check("js-decode-unicode", VideoUrls.decodeJsResult(quotedUni)?.contains("Роза") == true, VideoUrls.decodeJsResult(quotedUni) ?: "null")

// --- Player-iframe detection (v0.1.2): Alloha/Kodik/player/embed ---
check("isPlayer-alloha", VideoUrls.isPlayerIframe("https://alloha.example.test/?token_movie=abc&translation=10"))
check("isPlayer-kodik", VideoUrls.isPlayerIframe("https://kodik.info/serial/123"))
check("isPlayer-embed", VideoUrls.isPlayerIframe("https://example.test/embed/456"))
check("isPlayer-slider-false", !VideoUrls.isPlayerIframe("https://static.example.test/slider/"))
check("isPlayer-page-false", !VideoUrls.isPlayerIframe("https://example.test/catalog/item/x"))

// --- Placeholder filter (v0.1.2) ---
check("isPlaceholder-blank", VideoUrls.isPlaceholder("https://cdn.plyr.io/static/blank.mp4"))
check("isPlaceholder-real", !VideoUrls.isPlaceholder("https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"))
check("isPlaceholder-m3u8", !VideoUrls.isPlaceholder("https://example.test/master.m3u8"))
// Token URLs must never be logged whole: safeHost strips the query.
check("safeHost-strips-query", VideoUrls.safeHost("https://player.example.test/?token_movie=abc&translation=10") == "player.example.test/", VideoUrls.safeHost("https://player.example.test/?token_movie=abc"))

// --- Candidate filtering (v0.1.3, Failure A): only real video URLs ---
// Accepted: .m3u8/.mp4/.mpd/.mov/.m4v/.webm in the PATH (case-insensitive),
// the ".../720.mp4:hls:manifest.m3u8" form, Google videoplayback.
check("isVideo-m3u8", VideoUrls.isVideoUrl("https://cdn.example.test/hls/master.m3u8?token=abc"))
check("isVideo-mp4", VideoUrls.isVideoUrl("https://cdn.example.test/v/720.mp4?token=abc"))
check("isVideo-m3u8-upper", VideoUrls.isVideoUrl("https://cdn.example.test/MASTER.M3U8"))
check("isVideo-hls-form", VideoUrls.isVideoUrl("https://cdn.example.test/720.mp4:hls:manifest.m3u8"))
check("isVideo-hls-form-token", VideoUrls.isVideoUrl("https://cdn.example.test/720.mp4:hls:manifest.m3u8?token=abc"))
check("isVideo-mpd", VideoUrls.isVideoUrl("https://cdn.example.test/manifest.mpd"))
check("isVideo-mov", VideoUrls.isVideoUrl("https://cdn.example.test/clip.mov"))
check("isVideo-m4v", VideoUrls.isVideoUrl("https://cdn.example.test/clip.m4v"))
check("isVideo-webm", VideoUrls.isVideoUrl("https://cdn.example.test/clip.webm"))
check("isVideo-videoplayback", VideoUrls.isVideoUrl("https://rr1---sn.example.test/videoplayback?expire=1&id=abc"))
// Rejected: player pages, API endpoints, substring traps.
check("notVideo-storage", !VideoUrls.isVideoUrl("https://kodik.example.test/storage.html"))
check("notVideo-api-video", !VideoUrls.isVideoUrl("https://api.example.test/api/video/291174"))
check("notVideo-kodik-season", !VideoUrls.isVideoUrl("https://kodikplayer.com/season/123/720p"))
check("notVideo-alloha", !VideoUrls.isVideoUrl("https://alloha.example.test/?token_movie=abc&translation=10"))
check("notVideo-m3u8-substring", !VideoUrls.isVideoUrl("https://example.test/m3u8player/page.html"))
check("notVideo-query-mp4", !VideoUrls.isVideoUrl("https://example.test/page?file=video.mp4"))
check("notVideo-page", !VideoUrls.isVideoUrl("https://example.test/catalog/item/x"))

// --- Pair-Verify M3 cross-check vs pyatv (v0.1.3, Failure B) ---
// Expected values computed INDEPENDENTLY in Python (tests/m3_oracle.py)
// with pyatv's hkdf_expand + cryptography, following pyatv verify1:
//   device_info = clientPub + clientId + serverPub
//   signature = Ed25519_sign(clientSeed, device_info)
// Inputs: client X25519 priv = 01..20, server X25519 priv = 21..40,
// client Ed seed = RFC 8032 test 1, clientId = "TestClient01".
// The v0.1.2 (wrong) order serverPub + clientId + clientPub gives a
// DIFFERENT signature (8697da5e...) — the two must not be confused.
val m3ClientPriv = ByteArray(32) { (it + 1).toByte() }
val m3ServerPriv = ByteArray(32) { (it + 33).toByte() }
val m3Seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
val m3ClientId = "TestClient01".toByteArray(Charsets.UTF_8)
val m3ClientPub = Curve25519.x25519Public(m3ClientPriv)
val m3ServerPub = Curve25519.x25519Public(m3ServerPriv)
check("m3-client-pub", hex(m3ClientPub) == "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c", hex(m3ClientPub))
check("m3-server-pub", hex(m3ServerPub) == "5869aff450549732cbaaed5e5df9b30a6da31cb0e5742bad5ad4a1a768f1a67b", hex(m3ServerPub))
val m3Shared = Curve25519.x25519(m3ClientPriv, m3ServerPub)
check("m3-shared", hex(m3Shared) == "a84dc7c3c8f058b1b2dc4cd1e9b5dc0a7987f88b6a9564cde3391fc421159e77", hex(m3Shared))
val m3SessionKey = Hkdf.expand("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", m3Shared)
check("m3-session-key", hex(m3SessionKey) == "e4aad66348fe8cf67cdb4cf290fd218d96d907aa46fe8c6ad0759f23cea7033f", hex(m3SessionKey))
val m3Sig = Curve25519.ed25519Sign(m3Seed, m3ClientPub + m3ClientId + m3ServerPub)
check("m3-pyatv-crosscheck", hex(m3Sig) == "03aa417c466f66a7d921943392bbf4df38fa439ac055cdb47a00bc2410093b722eee30b7eaa45a86fe1e8f44dc6160df47df2823d7eb608a5283525fe734e80c", hex(m3Sig))
val m3SigWrongOrder = Curve25519.ed25519Sign(m3Seed, m3ServerPub + m3ClientId + m3ClientPub)
check("m3-wrong-order-differs", hex(m3SigWrongOrder) == "8697da5ed1058177e31600974d61dfa81ba678ceb2379d595cd55430b084c74d9b7d3b5e89bc174ae86171d062dbfcc9abd3d68d6f925ccd5bf441ebb9ef5e0e", hex(m3SigWrongOrder))
check("m3-ltpk-sanity", hex(Curve25519.ed25519PublicKey(m3Seed)) == "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

// --- bplist 2-byte int (v0.2.0, eventPort -81759) ---
// Живой факт: SETUP вернул eventPort, который v0.1.3 читал как -81759.
// Причина — двойное знаковое расширение в readIntBE для 2-байтовых
// int (см. Bplist.kt). Ручной bplist: словарь {"eventPort": <int 0xC0A1
// в 2 байта>} — ровно то, что прислал ТВ. Правильное знаковое чтение:
// -16223 (порт как uint16 восстанавливает eventPortFromBplist).
val evDoc = Bplist.decode(bplist2ByteInt()) as Map<*, *>
check("bplist-2byte-int-signed", evDoc["eventPort"] == -16223L, evDoc.toString())
// Отрицательные int переживают round-trip (1-байтовые и 4-байтовые).
val negDoc: Map<String, Any?> = mapOf("minus5" to -5, "minus1" to -1, "big" to 49313)
val negReparsed = Bplist.decode(Bplist.encode(negDoc)) as Map<*, *>
check("bplist-negative-roundtrip", negReparsed["minus5"] == -5L && negReparsed["minus1"] == -1L && negReparsed["big"] == 49313L, Bplist.describe(negReparsed))
}

// v0.9.3.4: фейковый UDP-сокет для теста sendMagicPacket —
// песочница блокирует любой реальный UDP-send, поэтому тест
// проверяет через него пакет/адрес/порт/повторы.
class FakeUdpSocket : java.net.DatagramSocket() {
    val sent = mutableListOf<java.net.DatagramPacket>()
    var broadcastSet: Boolean? = null
    override fun setBroadcast(on: Boolean) { broadcastSet = on }
    override fun send(p: java.net.DatagramPacket) {
        sent.add(java.net.DatagramPacket(p.data.copyOf(p.length), p.length, p.address, p.port))
    }
}

// v0.9.3.4 (Wake-on-LAN: пробуждение спящего Apple TV не работало —
// был только Companion turn_on, а спящий ТВ не слушает 49153).
// Отдельная функция: kotlinc 1.4 падает с "wrong bytecode
// generated" на гигантском main() (см. efgTests0933/cryptoTests).
fun wolTests0934() {
    val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    val packet = WakeOnLan.buildMagicPacket(mac)
    check("wol-packet-size", packet.size == 102, "size=${packet.size}")
    check("wol-packet-sync", packet.take(6).all { it == 0xFF.toByte() }, packet.take(6).toString())
    var repeatsOk = true
    for (r in 0 until 16) {
        if (!packet.copyOfRange(6 + r * 6, 6 + r * 6 + 6).contentEquals(mac)) repeatsOk = false
    }
    check("wol-packet-repeat", repeatsOk, "MAC must repeat 16x after the 6xFF sync")
    check("wol-parse-mac", WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF")?.contentEquals(mac) == true &&
        WakeOnLan.parseMac("aa:bb:cc:dd:ee:ff")?.contentEquals(mac) == true &&
        WakeOnLan.parseMac("AA-BB-CC-DD-EE-FF")?.contentEquals(mac) == true,
        "colon/dash, upper/lower must parse")
    check("wol-parse-mac-bad", WakeOnLan.parseMac(null) == null &&
        WakeOnLan.parseMac("aa:bb:cc:dd:ee") == null &&
        WakeOnLan.parseMac("zz:bb:cc:dd:ee:ff") == null &&
        WakeOnLan.parseMac("aa:bb:cc:dd:ee:ff:00") == null &&
        WakeOnLan.parseMac("not-a-mac") == null,
        "short/bad-hex/7-octets/garbage must be null")
    check("wol-format-mac", WakeOnLan.formatMac(mac) == "aa:bb:cc:dd:ee:ff")
    check("wol-mask", WakeOnLan.maskMac("aa:bb:cc:dd:ee:ff") == "aa:bb:..:..:ee:ff" &&
        WakeOnLan.maskMac("мусор") == "?",
        WakeOnLan.maskMac("aa:bb:cc:dd:ee:ff"))
    val arp = "IP address       HW type     Flags       HW address            Mask     Device\n" +
        "192.168.1.10     0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0\n" +
        "192.168.1.20     0x1         0x2         11:22:33:44:55:66     *        wlan0\n" +
        "192.168.1.1      0x1         0x0         00:00:00:00:00:00     *        wlan0\n"
    check("wol-arp-parse", WakeOnLan.parseArpTable(arp, "192.168.1.10") == "aa:bb:cc:dd:ee:ff" &&
        WakeOnLan.parseArpTable(arp, "192.168.1.20") == "11:22:33:44:55:66",
        "must find the TV MAC by IP")
    check("wol-arp-missing", WakeOnLan.parseArpTable(arp, "192.168.1.99") == null &&
        WakeOnLan.parseArpTable(arp, "192.168.1.1") == null,
        "absent IP and incomplete (zero-MAC) entry must be null")
    val arpFile = java.io.File.createTempFile("arp0934", ".txt").apply { writeText(arp); deleteOnExit() }
    check("wol-arp-read-file", WakeOnLan.readArpMac("192.168.1.10", arpFile.absolutePath) == "aa:bb:cc:dd:ee:ff" &&
        WakeOnLan.readArpMac("192.168.1.10", "/nonexistent-arp-0934") == null,
        "readArpMac must read a file path and return null on missing file")
    check("wol-broadcast", WakeOnLan.broadcastFor("192.168.1.10") == "192.168.1.255" &&
        WakeOnLan.broadcastFor("192.168.1.10", 16) == "192.168.255.255" &&
        WakeOnLan.broadcastFor("192.168.1.10", 25) == "192.168.1.127" &&
        WakeOnLan.broadcastFor("192.168.1.10", 32) == "192.168.1.10",
        "broadcast must follow the prefix")
    check("wol-broadcast-bad", WakeOnLan.broadcastFor("мусор") == "255.255.255.255" &&
        WakeOnLan.broadcastFor("192.168.1.999") == "255.255.255.255",
        "invalid IP must fall back to limited broadcast")
    // Песочница блокирует ЛЮБОЙ UDP-send (даже unicast на loopback:
    // IOException: Operation not permitted — проверено отдельным
    // Java-репродуктором). Поэтому реальный сокет здесь не проверить:
    // sendMagicPacket принимает фабрику сокетов (test seam), а тест
    // гоняет фейковый сокет и проверяет пакет/адрес/порт/повторы/
    // паузы; плюс реальный сокет в песочнице обязан вернуть 0,
    // а не бросить исключение.
    val fake = FakeUdpSocket()
    var sleeps = 0
    val sent3 = WakeOnLan.sendMagicPacket(mac, "127.0.0.1", port = 9, repeats = 3, intervalMs = 100,
        sleep = { sleeps++ }, createSocket = { fake })
    check("wol-send-fake", sent3 == 3 && fake.sent.size == 3 &&
        fake.broadcastSet == true && sleeps == 2 &&
        fake.sent.all { it.data.contentEquals(packet) && it.address.hostAddress == "127.0.0.1" && it.port == 9 },
        "sent=$sent3 sleeps=$sleeps broadcast=${fake.broadcastSet}")
    check("wol-send-blocked-returns-0",
        WakeOnLan.sendMagicPacket(mac, "127.0.0.1", port = 9, repeats = 1, intervalMs = 0) == 0,
        "blocked UDP send must return 0, not throw")
    // Статика: порядок пробуждения в ensureTvReady — WoL ДО turn_on,
    // MAC кэшируется в saveRemembered и читается в loadRemembered.
    val engineSrc = File("app/src/main/java/com/alexey/aircast/CastEngine.kt").readText()
    val readyFn = engineSrc.substringAfter("private fun ensureTvReady").substringBefore("private fun fallbackToDiscovery")
    check("wol-order-0934", readyFn.contains("sendWol(") && readyFn.contains(".wakeTv()") &&
        readyFn.indexOf("sendWol(") < readyFn.indexOf(".wakeTv()") &&
        readyFn.contains("waitTvReady(rem.host, 30_000L)"),
        "ensureTvReady must send WoL first, wait 30s, only then turn_on")
    check("wol-mac-cached", engineSrc.contains("putString(\"lastDeviceMac\"") &&
        engineSrc.contains("getString(\"lastDeviceMac\"") &&
        engineSrc.contains("fun cacheTvMac("),
        "MAC must be cached in saveRemembered and read in loadRemembered")
    // Статика: кнопка «Поделиться логом» убрана с главного экрана
    // (слово Алексея 13:34) — в MainActivity она осталась только на
    // экране краша; в уведомлении «Видео не найдено» — как раньше.
    val mainSrc = File("app/src/main/java/com/alexey/aircast/MainActivity.kt").readText()
    val logButtons = Regex("button\\(\"Поделиться логом\"\\)").findAll(mainSrc).toList()
    check("ui-0934-no-log-button-main", logButtons.size == 1 &&
        mainSrc.indexOf("private fun buildCrashScreen") < logButtons[0].range.first,
        "main screen must have no share-log button; only the crash screen keeps one")
    val serviceSrc = File("app/src/main/java/com/alexey/aircast/CastService.kt").readText()
    check("ui-0934-log-button-notif", serviceSrc.contains("shareLog = true"),
        "the «Видео не найдено» notification must still offer share-log")
}

// v0.9.3.5 (самообновление: скачивает APK, но установку само не
// начинает). Отдельная функция: kotlinc 1.4 падает с "wrong bytecode
// generated" на гигантском main() (см. efgTests0933/wolTests0934).
// JVM-тесты вызовы Android framework не исполняют — статические
// проверки исходников: receiver, intent установки, уведомление,
// pending-install, manifest-receiver.
fun updaterTests0935() {
    val manifestSrc = File("app/src/main/AndroidManifest.xml").readText()
    val installSrc = File("app/src/main/java/com/alexey/aircast/UpdateInstall.kt").readText()
    val receiverSrc = File("app/src/main/java/com/alexey/aircast/UpdateDownloadReceiver.kt").readText()
    val updaterSrc = File("app/src/main/java/com/alexey/aircast/AppUpdater.kt").readText()
    val mainSrc = File("app/src/main/java/com/alexey/aircast/MainActivity.kt").readText()
    // 1. Manifest-receiver объявлен: exported, фильтр DOWNLOAD_COMPLETE.
    check("updater-manifest-receiver", manifestSrc.contains("android:name=\".UpdateDownloadReceiver\"") &&
        manifestSrc.contains("android:exported=\"true\"") &&
        manifestSrc.contains("android.intent.action.DOWNLOAD_COMPLETE"),
        "manifest must declare UpdateDownloadReceiver exported with DOWNLOAD_COMPLETE filter")
    // 2. Manifest-receiver вызывает общую логику завершения загрузки.
    check("updater-manifest-handles", receiverSrc.contains("UpdateInstall.handleDownloadFinished(context.applicationContext, id, null)") &&
        receiverSrc.contains("DownloadManager.ACTION_DOWNLOAD_COMPLETE"),
        "UpdateDownloadReceiver must delegate to UpdateInstall.handleDownloadFinished")
    // 3. Activity-receiver тоже делегирует в ту же общую логику.
    check("updater-activity-delegates", updaterSrc.contains("UpdateInstall.handleDownloadFinished(activity, id, statusCallback)"),
        "AppUpdater.handleDownloadFinished must delegate to UpdateInstall")
    // 4. Install-интент: ACTION_VIEW + APK MIME + grant + NEW_TASK.
    check("updater-install-intent", installSrc.contains("Intent(Intent.ACTION_VIEW)") &&
        installSrc.contains("\"application/vnd.android.package-archive\"") &&
        installSrc.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION") &&
        installSrc.contains("Intent.FLAG_ACTIVITY_NEW_TASK"),
        "promptInstall must build ACTION_VIEW + apk mime + grant + NEW_TASK")
    // 5. Доставка из фона — уведомление с PendingIntent (BAL-exempt),
    // на переднем плане — прямой промпт.
    check("updater-deliver-split", installSrc.contains("fun deliverInstall(ctx: Context, file: File)") &&
        installSrc.contains("isAppForeground(ctx)") &&
        installSrc.contains("postInstallReadyNotification(ctx, file)"),
        "deliverInstall must prompt in foreground and notify in background")
    // 6. Уведомление: PendingIntent.getActivity + IMMUTABLE + install-интент.
    check("updater-notif-pending-intent", installSrc.contains("PendingIntent.getActivity(") &&
        installSrc.contains("PendingIntent.FLAG_IMMUTABLE") &&
        installSrc.contains("fun postInstallReadyNotification"),
        "install-ready notification must carry a PendingIntent with the install intent")
    // 7. Проверка foreground — IMPORTANCE_FOREGROUND.
    check("updater-foreground-check", installSrc.contains("ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND") &&
        installSrc.contains("fun isAppForeground(ctx: Context)"),
        "isAppForeground must check IMPORTANCE_FOREGROUND")
    // 8. Pending-install: флаг, attach проверяет его до чистки stale,
    // onActivityResult ставит установку без повторного скачивания.
    check("updater-pending-install", installSrc.contains("PREF_PENDING_INSTALL") &&
        updaterSrc.contains("prefs.getBoolean(UpdateInstall.PREF_PENDING_INSTALL, false)") &&
        updaterSrc.contains("fun handlePendingInstall()") &&
        mainSrc.contains("PREF_PENDING_INSTALL") &&
        mainSrc.contains("updater.promptInstall(pendingFile)"),
        "pending-install flag must survive download and resume install after settings without re-download")
    // 9. Провал загрузки из фона — честное уведомление, а не тишина.
    check("updater-failed-notif", installSrc.contains("fun postUpdateFailedNotification") &&
        installSrc.contains("DownloadManager.STATUS_FAILED"),
        "failed download must notify in background")
    // 10. Новые файлы реально компилируются в run_tests.sh.
    val runTestsSh = File("tests/run_tests.sh").readText()
    check("updater-tests-compile", runTestsSh.contains("UpdateInstall.kt") && runTestsSh.contains("UpdateDownloadReceiver.kt"),
        "run_tests.sh must compile UpdateInstall.kt and UpdateDownloadReceiver.kt")
}
