package com.alexey.aircast

/**
 * YouTube — извлечение прямого потока через InnerTube (youtubei/v1/player).
 *
 * Исследовано 2026-11-18 по живым источникам (yt-dlp master, _base.py,
 * прочитан 2026-11-18 00:19 UTC; измерения Sep–Oct 2026):
 * - ANDROID (clientVersion 21.26.364, id 3, UA
 *   com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip)
 *   отдаёт plain url ТОЛЬКО для itag 18 (360p muxed mp4); всё остальное
 *   SABR-only (serverAbrStreamingUrl, без url). itag 22 (720p muxed)
 *   в 2026 ни один клиент plain url не отдаёт — на него не рассчитывать.
 * - VISIONOS (clientVersion 1.02, id 101, UA Safari macOS) — главный
 *   клиент конца 2026: plain url для всех форматов + hlsManifestUrl,
 *   без signatureCipher, без параметра n, без PO-токена. Требует
 *   visitorData (без него LOGIN_REQUIRED) — берём из загруженной
 *   watch-страницы (ytInitialPlayerResponse.responseContext.visitorData).
 * - API key НЕ нужен: yt-dlp и NewPipe ключ не шлют вообще, запрос
 *   аутентифицируется context.client + заголовками
 *   X-YouTube-Client-Name/Version. Ключ в _base.py master отсутствует.
 * - signatureCipher НЕ расшифровываем (base.js меняется еженедельно,
 *   плюс отдельный параметр n) — если клиент вернул только cipher,
 *   пробуем следующий клиент; если все вернули cipher/SABR — честно
 *   «YouTube: нет прямого потока».
 * - Прямой progressive URL (itag 18) — обычный подписанный HTTPS mp4,
 *   играет в VLC без Referer/Cookie; живёт ~6 ч; привязан к публичному
 *   IP (телефон и ТВ за одним NAT — ок). VISIONOS URL без параметра n —
 *   нет проблемы throttling/403 в VLC.
 * - НЕ ПРОВЕРЕНО живым тестом: примет ли VLC на tvOS googlevideo-URL
 *   по deep link и с каким UA/заголовками (проверит живой тест Алексея).
 * - Версии клиентов ротируются — сверять с yt-dlp _base.py при
 *   следующем касании этого файла.
 *
 * Pure Kotlin, no Android imports: запрос строится строкой, ответ
 * парсится regex + балансом скобок — тестируется на JVM.
 */
object YouTube {
    const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    // VISIONOS — основной клиент (plain url + hlsManifestUrl, без cipher/n/PO).
    const val VISIONOS_NAME = "VISIONOS"
    const val VISIONOS_VERSION = "1.02"
    const val VISIONOS_CLIENT_ID = "101"
    const val VISIONOS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    const val VISIONOS_DEVICE_MAKE = "Apple"
    const val VISIONOS_DEVICE_MODEL = "RealityDevice17,1"
    const val VISIONOS_OS_NAME = "visionOS"
    const val VISIONOS_OS_VERSION = "26.5.23O471"

    // ANDROID — fallback: только itag 18 с plain url.
    const val ANDROID_NAME = "ANDROID"
    const val ANDROID_VERSION = "21.26.364"
    const val ANDROID_CLIENT_ID = "3"
    const val ANDROID_USER_AGENT =
        "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
    const val ANDROID_SDK_VERSION = 30
    const val ANDROID_OS_NAME = "Android"
    const val ANDROID_OS_VERSION = "11"

    enum class Client { VISIONOS, ANDROID }

    /** videoId из watch / youtu.be / shorts / embed / live / v. */
    fun extractVideoId(url: String): String? {
        val u = url.trim()
        // watch?v=ID (v может быть не первым параметром)
        val watch = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(u)
        if (watch != null) return watch.groupValues[1]
        // youtu.be/ID, /shorts/ID, /embed/ID, /live/ID, /v/ID
        val path = Regex("/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:[?#/]|$)").find(u)
        if (path != null) return path.groupValues[1]
        val short = Regex("youtu\\.be/([A-Za-z0-9_-]{11})(?:[?#/]|$)").find(u)
        if (short != null) return short.groupValues[1]
        return null
    }

    fun isYouTubeUrl(url: String): Boolean {
        val u = url.trim().toLowerCase()
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be")
    }

    /**
     * v0.9.1: фрагмент "client":{...} контекста InnerTube — общий для
     * /player и /browse (плейлисты). Выделен из playerRequestJson,
     * вывод — байт в байт тот же (см. тест yt-req-visionos).
     */
    fun clientJson(client: Client, visitorData: String?): String {
        val visitor = if (!visitorData.isNullOrBlank()) {
            ""","visitorData":"${escapeJson(visitorData)}""""
        } else ""
        val inner = when (client) {
            Client.VISIONOS ->
                """"clientName":"$VISIONOS_NAME","clientVersion":"$VISIONOS_VERSION","deviceMake":"$VISIONOS_DEVICE_MAKE","deviceModel":"$VISIONOS_DEVICE_MODEL","userAgent":"$VISIONOS_USER_AGENT","osName":"$VISIONOS_OS_NAME","osVersion":"$VISIONOS_OS_VERSION","hl":"en","gl":"US"$visitor"""
            Client.ANDROID ->
                """"clientName":"$ANDROID_NAME","clientVersion":"$ANDROID_VERSION","androidSdkVersion":$ANDROID_SDK_VERSION,"userAgent":"$ANDROID_USER_AGENT","osName":"$ANDROID_OS_NAME","osVersion":"$ANDROID_OS_VERSION","hl":"en","gl":"US"$visitor"""
        }
        return """"client":{$inner}"""
    }

    /** Тело POST youtubei/v1/player для клиента. visitorData — если есть. */
    fun playerRequestJson(client: Client, videoId: String, visitorData: String?): String {
        return """{"context":{${clientJson(client, visitorData)}},"videoId":"${escapeJson(videoId)}","playbackContext":{"contentPlaybackContext":{}},"racyCheckOk":true,"contentCheckOk":true}"""
    }

    /** Заголовки POST youtubei/v1/player для клиента. */
    fun playerRequestHeaders(client: Client, visitorData: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json"
        when (client) {
            Client.VISIONOS -> {
                h["User-Agent"] = VISIONOS_USER_AGENT
                h["X-YouTube-Client-Name"] = VISIONOS_CLIENT_ID
                h["X-YouTube-Client-Version"] = VISIONOS_VERSION
            }
            Client.ANDROID -> {
                h["User-Agent"] = ANDROID_USER_AGENT
                h["X-YouTube-Client-Name"] = ANDROID_CLIENT_ID
                h["X-YouTube-Client-Version"] = ANDROID_VERSION
            }
        }
        if (!visitorData.isNullOrBlank()) h["X-Goog-Visitor-Id"] = visitorData
        return h
    }

    private fun escapeJson(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    // ---------- Разбор ответа /player ----------

    /** Один muxed формат (streamingData.formats — по определению muxed). */
    data class MuxedFormat(val itag: Int, val url: String?, val hasCipher: Boolean)

    /**
     * v0.9.2: один adaptive-формат (streamingData.adaptiveFormats).
     * Для YouTube Music (music.youtube.com) muxed-форматов нет вообще —
     * только adaptive: видео-only и аудио-only (mimeType audio/...).
     * itag аудио: 141 (m4a 256k), 251 (opus 160k), 140 (m4a 128k),
     * 171 (webm 128k), 250/249 (opus 70k/50k), 139 (m4a 48k).
     */
    data class AudioFormat(
        val itag: Int,
        val url: String?,
        val mimeType: String?,
        val hasCipher: Boolean,
        /** v0.9.3 (#1): initRange/indexRange для DASH-пары (SegmentBase). */
        val initStart: Long? = null,
        val initEnd: Long? = null,
        val indexStart: Long? = null,
        val indexEnd: Long? = null,
        /** v0.9.3 (#1): битрейт (для bandwidth в MPD). */
        val bitrate: Long? = null,
    )

    /**
     * v0.9.3 (#1): один adaptive-формат video-only (streamingData.
     * adaptiveFormats, mimeType video/...). Пара video+audio — для
     * синтеза локального .mpd (DashManifest), когда ни HLS, ни muxed
     * не дали одиночного потока.
     */
    data class VideoAdaptiveFormat(
        val itag: Int,
        val url: String?,
        val mimeType: String?,
        val hasCipher: Boolean,
        val width: Int? = null,
        val height: Int? = null,
        val fps: Int? = null,
        val bitrate: Long? = null,
        val initStart: Long? = null,
        val initEnd: Long? = null,
        val indexStart: Long? = null,
        val indexEnd: Long? = null,
    )

    /** v0.9.3 (#1): пара для синтеза DASH (видео + аудио). */
    data class DashPair(val video: VideoAdaptiveFormat, val audio: AudioFormat)

    data class PlayerParse(
        val hlsUrl: String?,
        val dashUrl: String?,
        val muxed: List<MuxedFormat>,
        /** Все форматы с cipher и ни одного plain url. */
        val hasOnlyCipher: Boolean,
        /** playabilityStatus.status (OK / LOGIN_REQUIRED / UNPLAYABLE / ...). */
        val playability: String?,
        /** v0.9.0: videoDetails.lengthSeconds — длительность для
         *  авто-следующей серии (таймер конца воспроизведения). */
        val lengthSeconds: Long? = null,
        /** v0.9.2: аудио-only adaptive-форматы (YouTube Music). */
        val audio: List<AudioFormat> = emptyList(),
        /** v0.9.3 (#1): видео-only adaptive-форматы (для DASH-пары). */
        val video: List<VideoAdaptiveFormat> = emptyList(),
    )

    private val HLS_RE = Regex(""""hlsManifestUrl"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val DASH_RE = Regex(""""dashManifestUrl"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val VISITOR_RE = Regex(""""visitorData"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val LENGTH_RE = Regex(""""lengthSeconds"\s*:\s*"(\d+)"""")
    private val PLAYABILITY_RE = Regex(""""playabilityStatus"\s*:\s*\{\s*"status"\s*:\s*"([^"]+)"""")
    private val FORMAT_OBJ_RE = Regex("""\{[^{}]*"itag"\s*:\s*(\d+)[^{}]*\}""")
    private val URL_IN_OBJ_RE = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val MIME_IN_OBJ_RE = Regex(""""mimeType"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val ITAG_IN_OBJ_RE = Regex(""""itag"\s*:\s*(\d+)""")
    private val CIPHER_IN_OBJ_RE = Regex(""""signatureCipher"\s*:""")
    /** v0.9.3 (#1): поля adaptive-форматов для DASH-пары. */
    private val WIDTH_RE = Regex(""""width"\s*:\s*(\d+)""")
    private val HEIGHT_RE = Regex(""""height"\s*:\s*(\d+)""")
    private val FPS_RE = Regex(""""fps"\s*:\s*(\d+)""")
    private val BITRATE_RE = Regex(""""bitrate"\s*:\s*(\d+)""")
    private val CODECS_RE = Regex("""codecs="([^"]+)"""")

    /** initRange/indexRange объекта adaptive-формата (start,end) или null,null. */
    private fun rangeOf(obj: String, name: String): Pair<Long?, Long?> {
        val m = Regex(String.format("\"%s\"\\s*:\\s*\\{\\s*\"start\"\\s*:\\s*\"(\\d+)\"\\s*,\\s*\"end\"\\s*:\\s*\"(\\d+)\"\\s*\\}", name)).find(obj) ?: return null to null
        return m.groupValues[1].toLongOrNull() to m.groupValues[2].toLongOrNull()
    }

    /** visitorData из HTML/JSON watch-страницы (ytInitialPlayerResponse). */
    fun parseVisitorData(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = VISITOR_RE.find(text) ?: return null
        val v = unescape(m.groupValues[1])
        return if (v.isBlank()) null else v
    }

    /** hlsManifestUrl из ytInitialPlayerResponse watch-страницы (если есть). */
    fun parsePageHls(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = HLS_RE.find(text) ?: return null
        val v = unescape(m.groupValues[1])
        return if (v.isBlank()) null else v
    }

    /** Разбор JSON ответа /player. Никогда не бросает исключение. */
    fun parsePlayerResponse(json: String?): PlayerParse {
        if (json.isNullOrBlank()) return PlayerParse(null, null, emptyList(), false, null)
        val playability = PLAYABILITY_RE.find(json)?.groupValues?.get(1)
        val lengthSeconds = LENGTH_RE.find(json)?.groupValues?.get(1)?.toLongOrNull()
        // streamingData — область поиска форматов (вне её "url" — это
        // превью и прочие ссылки, не потоки).
        val sd = extractStreamingData(json) ?: json
        val hls = HLS_RE.find(sd)?.groupValues?.get(1)?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        val dash = DASH_RE.find(sd)?.groupValues?.get(1)?.let { unescape(it) }?.takeIf { it.isNotBlank() }
        val muxed = ArrayList<MuxedFormat>()
        val formatsArr = extractArray(sd, "\"formats\"")
        if (formatsArr != null) {
            for (m in FORMAT_OBJ_RE.findAll(formatsArr)) {
                val obj = m.value
                val itag = m.groupValues[1].toIntOrNull() ?: continue
                val url = URL_IN_OBJ_RE.find(obj)?.groupValues?.get(1)?.let { unescape(it) }
                val hasCipher = CIPHER_IN_OBJ_RE.containsMatchIn(obj)
                muxed.add(MuxedFormat(itag, url?.takeIf { it.isNotBlank() }, hasCipher))
            }
        }
        val hasOnlyCipher = muxed.isNotEmpty() && muxed.none { it.url != null }
        // v0.9.2: adaptiveFormats — для YouTube Music там только аудио
        // (и видео-only). Объекты с вложенными initRange/indexRange —
        // FORMAT_OBJ_RE их не берёт, поэтому режем массив на объекты
        // по балансу скобок (splitTopObjects) и берём только audio/*.
        // v0.9.3 (#1): video-only — тоже, с width/height/fps/bitrate
        // и initRange/indexRange (для синтеза локального .mpd).
        val audio = ArrayList<AudioFormat>()
        val video = ArrayList<VideoAdaptiveFormat>()
        val adaptiveArr = extractArray(sd, "\"adaptiveFormats\"")
        if (adaptiveArr != null) {
            for (obj in splitTopObjects(adaptiveArr)) {
                val itag = ITAG_IN_OBJ_RE.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val url = URL_IN_OBJ_RE.find(obj)?.groupValues?.get(1)?.let { unescape(it) }
                val mime = MIME_IN_OBJ_RE.find(obj)?.groupValues?.get(1)?.let { unescape(it) }
                val hasCipher = CIPHER_IN_OBJ_RE.containsMatchIn(obj)
                val (initStart, initEnd) = rangeOf(obj, "initRange")
                val (indexStart, indexEnd) = rangeOf(obj, "indexRange")
                if (mime?.contains("audio/") == true) {
                    val bitrate = BITRATE_RE.find(obj)?.groupValues?.get(1)?.toLongOrNull()
                    audio.add(AudioFormat(itag, url?.takeIf { it.isNotBlank() }, mime, hasCipher, initStart, initEnd, indexStart, indexEnd, bitrate))
                } else if (mime?.contains("video/") == true) {
                    val width = WIDTH_RE.find(obj)?.groupValues?.get(1)?.toIntOrNull()
                    val height = HEIGHT_RE.find(obj)?.groupValues?.get(1)?.toIntOrNull()
                    val fps = FPS_RE.find(obj)?.groupValues?.get(1)?.toIntOrNull()
                    val bitrate = BITRATE_RE.find(obj)?.groupValues?.get(1)?.toLongOrNull()
                    video.add(VideoAdaptiveFormat(itag, url?.takeIf { it.isNotBlank() }, mime, hasCipher, width, height, fps, bitrate, initStart, initEnd, indexStart, indexEnd))
                }
            }
        }
        return PlayerParse(hls, dash, muxed, hasOnlyCipher, playability, lengthSeconds, audio, video)
    }

    /** Лучший одиночный поток для VLC: HLS, затем лучший muxed mp4, затем DASH. */
    fun pickStream(parse: PlayerParse): Pair<String, String>? {
        if (parse.hlsUrl != null) return parse.hlsUrl to "hls"
        val best = muxedSorted(parse.muxed).firstOrNull { it.url != null }
        if (best?.url != null) return best.url to "mp4"
        if (parse.dashUrl != null) return parse.dashUrl to "mpd"
        return null
    }

    /**
     * v0.9.2: лучший аудио-only поток (YouTube Music): по рангу itag.
     * Вызывается, когда pickStream пуст (нет HLS/muxed/DASH) — у
     * музыкальных треков muxed-форматов не бывает вообще.
     */
    fun pickAudioStream(parse: PlayerParse): Pair<String, String>? {
        val best = parse.audio.filter { it.url != null }.sortedByDescending { audioItagRank(it.itag) }.firstOrNull()
        return best?.url?.let { it to "audio" }
    }

    /** Ранг аудио-itag: 141 (m4a 256k) > 251 (opus 160k) > 140 (m4a 128k) > 171 > 250/249 > 139. */
    fun audioItagRank(itag: Int): Int = when (itag) {
        141 -> 5
        251 -> 4
        140 -> 3
        171 -> 2
        250 -> 1
        249 -> 1
        139 -> 0
        else -> 0
    }

    /**
     * v0.9.3 (#1): пара для синтеза локального .mpd — лучшее видео-only
     * (по высоте, затем fps, затем битрейт) + лучшее аудио-only
     * (по рангу itag). Требуется plain url у обоих (cipher — не
     * расшифровываем, как и везде) и initRange у обоих (без него
     * DashManifest.build вернёт null). Null — пары нет.
     */
    fun pickDashPair(parse: PlayerParse): DashPair? {
        val v = parse.video.filter { it.url != null && !it.hasCipher }
            .sortedWith(compareByDescending<VideoAdaptiveFormat> { it.height ?: 0 }.thenByDescending { it.fps ?: 0 }.thenByDescending { it.bitrate ?: 0L })
            .firstOrNull() ?: return null
        val a = parse.audio.filter { it.url != null && !it.hasCipher }
            .sortedByDescending { audioItagRank(it.itag) }
            .firstOrNull() ?: return null
        if (v.initStart == null || v.initEnd == null) return null
        if (a.initStart == null || a.initEnd == null) return null
        return DashPair(v, a)
    }

    /** mimeType до ';' ("video/mp4") — для AdaptationSet. */
    fun mimeBase(mimeType: String?): String =
        mimeType?.substringBefore(';')?.trim() ?: ""

    /** codecs="..." из mimeType — для AdaptationSet. */
    fun codecsOf(mimeType: String?): String =
        mimeType?.let { CODECS_RE.find(it)?.groupValues?.get(1) } ?: ""

    /** DashManifest.Track из видео-формата пары. */
    fun videoDashTrack(v: VideoAdaptiveFormat): DashManifest.Track? {
        val url = v.url ?: return null
        return DashManifest.Track(url, mimeBase(v.mimeType), codecsOf(v.mimeType), v.bitrate ?: 0L, v.width, v.height, v.fps, v.initStart, v.initEnd, v.indexStart, v.indexEnd)
    }

    /** DashManifest.Track из аудио-формата пары. */
    fun audioDashTrack(a: AudioFormat): DashManifest.Track? {
        val url = a.url ?: return null
        return DashManifest.Track(url, mimeBase(a.mimeType), codecsOf(a.mimeType), a.bitrate ?: 0L, null, null, null, a.initStart, a.initEnd, a.indexStart, a.indexEnd)
    }

    /**
     * v0.9.2: объекты верхнего уровня из JSON-массива (баланс скобок,
     * строки учитываются). Для adaptiveFormats: объекты с вложенными
     * initRange/indexRange — плоский regex их не режет.
     */
    fun splitTopObjects(arrayText: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inStr = false
        var start = -1
        var i = 0
        while (i < arrayText.length) {
            val c = arrayText[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(arrayText.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            i++
        }
        return out
    }

    /** Muxed форматы с plain url, по убыванию качества (itag → ранг). */
    fun muxedSorted(muxed: List<MuxedFormat>): List<MuxedFormat> =
        muxed.filter { it.url != null }.sortedByDescending { itagRank(it.itag) }

    /** Ранг muxed itag: 38 (2160p) > 37 (1080p) > 22 (720p) > 59 (480p) > 18 (360p) > 43. */
    fun itagRank(itag: Int): Int = when (itag) {
        38 -> 6
        37 -> 5
        22 -> 4
        59 -> 3
        18 -> 2
        43 -> 1
        else -> 0
    }

    /** Подстрока объекта "streamingData":{...} с балансом скобок, или null. */
    fun extractStreamingData(json: String): String? {
        val idx = json.indexOf("\"streamingData\"")
        if (idx < 0) return null
        val open = json.indexOf('{', idx)
        if (open < 0) return null
        return balanced(json, open, '{', '}')
    }

    /** Подстрока массива "key":[...] с балансом скобок, или null. */
    fun extractArray(json: String, keyQuoted: String): String? {
        val idx = json.indexOf(keyQuoted)
        if (idx < 0) return null
        val open = json.indexOf('[', idx)
        if (open < 0) return null
        return balanced(json, open, '[', ']')
    }

    /** Сбалансированный фрагмент от открывающей скобки (строки учитываются). */
    fun balanced(text: String, openIdx: Int, open: Char, close: Char): String? {
        var depth = 0
        var inStr = false
        var i = openIdx
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(openIdx, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /** Unescape JSON-строки (\u0026, \/, \\, \" и т.д.). */
    fun unescape(token: String): String =
        VideoUrls.decodeJsResult("\"$token\"") ?: token
}
