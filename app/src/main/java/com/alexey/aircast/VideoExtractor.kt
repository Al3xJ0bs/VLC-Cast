package com.alexey.aircast

/**
 * Video-candidate model + URL heuristics for the in-app browser.
 *
 * Sources, in order of reliability:
 *  1. WebViewClient.shouldInterceptRequest — the real network requests the
 *     player makes (HLS segments / master .m3u8). This is the primary
 *     path: no need to reverse-engineer the player's API.
 *  2. JS scan of <video> elements and <iframe> srcs on the page.
 *  3. Manual URL entry.
 *
 * Player notes: YummyAnime does not host video itself. The episode page
 * embeds a third-party player in an <iframe> (Alloha, Kodik, ...). Its
 * video URL is fetched by the iframe's own JS with a token that expires
 * (usually hours). We never try to guess the token — we capture the URL
 * the player itself requested, and launch it in VLC on the TV
 * (Companion _launchApp); VLC fetches the video itself by that URL.
 *
 * Pure Kotlin, no Android imports: the scan-result parsing is unit-tested
 * on the JVM (tests/TestMain.kt). In particular WebView.evaluateJavascript
 * returns the JS result as a JSON-ENCODED STRING (the callback value is
 * a quoted string, not an object) — JSONObject() on it fails, which is
 * why every JS scan in v0.1.1 failed. decodeJsResult() unwraps it first.
 */
data class VideoCandidate(
    val url: String,
    val format: String,       // m3u8 / mp4 / mpd / ...
    val quality: String,      // 1080p / 720p / ? — guessed from URL
    val referer: String?,
    val source: String,       // "перехват сети" / "JS: video" / "JS: iframe" / "вручную"
    val foundAt: Long = System.currentTimeMillis(),
    /** v0.2.0: тестовое видео — каст напрямую, без прокси (чтобы отличить
     *  проблему прокси от проблемы AirPlay). */
    val directOnly: Boolean = false,
    /** v0.9.0: длительность видео в секундах, если известна из источника
     *  (YouTube InnerTube lengthSeconds). Для авто-следующей серии:
     *  если длительность известна — таймер, иначе она узнаётся после
     *  запуска (HLS #EXTINF / mp4 mvhd / DASH PT). */
    val durationSec: Long? = null,
    /** v0.9.1: аудио-дорожка/озвучка кандидата (метка {Rus}/{Eng} из
     *  Playerjs file) — для запоминания озвучки. null = одна дорожка
     *  или неизвестно. */
    val audioLabel: String? = null,
    /** v0.9.1: мастер-плейлист HLS (по URL — см. VideoUrls.
     *  isMasterPlaylist; движок может поднять флаг и по содержимому
     *  через StreamProbe.isHlsMaster, когда URL без маркера). */
    val isMaster: Boolean = VideoUrls.isMasterPlaylist(url),
    /** v0.9.2: аудио-кандидат (трек/радио, не видео). Видео в
     *  приоритете при выборе (AutoCast.orderedAttempts: аудио-пул —
     *  только если видео нет вообще). */
    val isAudio: Boolean = false,
    /** v0.9.3 (#4): частота кадров, если известна из URL
     *  (60fps/720p60) или из источника (YouTube adaptive fps).
     *  При равном счёте (формат+качество) 60fps выше (AutoCast). */
    val fps: Int? = null,
)

/** One <video> found by the JS scan. */
data class ScanVideo(val src: String, val w: Int, val h: Int)

/**
 * Decoded result of SCAN_JS.
 * v0.8.0: urls — абсолютные и //-ссылки на видео из скриптов страницы
 * (конфиги Playerjs/JWPlayer/Video.js/DPlayer/ArtPlayer/xgplayer,
 * hls.loadSource, VK files, CVH hlsUrl, OK.ru metadata — это всё просто
 * URL в тексте JS); rel — относительные пути (/v/....mp4, /hls/...m3u8,
 * JWPlayer src) — резолвятся в Kotlin против URL страницы.
 * v0.8.1: jwFeeds — JWPlayer v2 playlist/media feed URL
 * (cdn.jwplayer.com/v2/playlists|media/{id}): в них нет видео-расширения,
 * поэтому в urls они не попадают; фид скачивается отдельно и парсится
 * (VideoUrls.parseJwplayerFeed).
 * v0.9.0: links — ссылки <a> со страницы (для списка серий:
 * EpisodeList.parse); subs — дорожки субтитров (<track> + .vtt/.srt/.ass
 * в скриптах); pageTitle — title страницы (для диагностики «не найдено»).
 * v0.9.1: poster — og:image (только top doc) для BigPictureStyle
 * в уведомлении «запущено».
 * v0.9.2: audios — теги <audio> (+ <source> внутри), как videos
 * для видео: SoundCloud/Bandcamp/радио-страницы без <video>.
 */
data class ScanResult(
    val videos: List<ScanVideo> = emptyList(),
    val iframes: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val rel: List<String> = emptyList(),
    val jwFeeds: List<String> = emptyList(),
    val links: List<PageLink> = emptyList(),
    val subs: List<SubTrack> = emptyList(),
    val pageTitle: String = "",
    val poster: String = "",
    val audios: List<ScanVideo> = emptyList(),
    /** v0.9.3 (#23): сырые тексты <script type="application/ld+json">. */
    val jsonld: List<String> = emptyList(),
    /** v0.9.3 (#24): href link rel=alternate type=application/json+oembed. */
    val oembed: List<String> = emptyList(),
)

object VideoUrls {
    private val VIDEO_EXT = Regex("\\.(m3u8|mp4|mov|mpd|m4v|webm)([?#]|$)", RegexOption.IGNORE_CASE)

    /**
     * Strict "is this a real video URL" check — v0.1.3 (Failure A).
     *
     * v0.1.2 accepted too much: any URL containing "m3u8", "/video/" or
     * "videoplayback" anywhere (even in the query) counted as video, so
     * an API endpoint (/api/video/291174) and player pages became cast
     * candidates. A Kodik storage.html player page is NOT a video and
     * must never be cast or previewed.
     *
     * Accepted: a video extension (.m3u8/.mp4/.mpd/.mov/.m4v/.webm,
     * case-insensitive) in the PATH (before ?/#), including the
     * ".../720.mp4:hls:manifest.m3u8" form, or the Google videoplayback
     * endpoint (a real video stream with no extension). Player pages
     * (storage.html, kodikplayer season pages, Alloha host, any HTML
     * iframe src) return false — they are stored separately as
     * playerIframeUrl for the "Открыть плеер" button only.
     */
    fun isVideoUrl(url: String): Boolean {
        val u = url.toLowerCase()
        val path = u.substringBefore('?').substringBefore('#')
        // ".../720.mp4:hls:manifest.m3u8" — the .m3u8 is in the path, the
        // regex below catches it too; kept explicit for readability.
        if (path.contains(":hls:manifest.m3u8")) return true
        if (VIDEO_EXT.containsMatchIn(path)) return true
        if (path.contains("/videoplayback")) return true
        return false
    }

    /**
     * v0.2.0: фильтр списка кандидатов — в списке остаются ТОЛЬКО
     * настоящие видео-URL (та же строгая проверка, что в addCandidate).
     * Страницы плеера, API-эндпоинты и placeholder-клипы отбрасываются.
     */
    fun filterVideoUrls(urls: List<String>): List<String> =
        urls.filter { isVideoUrl(it) && !isPlaceholder(it) }

    fun formatOf(url: String): String {        val u = url.toLowerCase()
        return when {
            u.contains(".m3u8") -> "m3u8 (HLS)"
            u.contains(".mp4") -> "mp4"
            u.contains(".mpd") -> "mpd (DASH)"
            u.contains(".mov") -> "mov"
            u.contains(".webm") -> "webm"
            // v0.9.2: аудио-форматы (для аудио-кандидатов; видео-путь их не трогает).
            u.contains(".flac") -> "flac"
            u.contains(".mp3") -> "mp3"
            u.contains(".aac") -> "aac"
            u.contains(".m4a") -> "m4a"
            u.contains(".ogg") || u.contains(".oga") -> "ogg"
            u.contains(".wav") -> "wav"
            u.contains(".opus") -> "opus"
            u.contains(".mka") -> "mka"
            else -> "?"
        }
    }

    fun qualityOf(url: String): String {
        val u = url.toLowerCase()
        return when {
            u.contains("2160") || u.contains("4k") || u.contains("uhd") -> "2160p"
            u.contains("1440") -> "1440p"
            u.contains("1080") -> "1080p"
            u.contains("720") -> "720p"
            u.contains("480") -> "480p"
            u.contains("360") -> "360p"
            else -> "?"
        }
    }

    // v0.9.3 (#4): частота кадров из URL (60fps, 720p60, 1080p60,
    // 120fps). «1080p» без суффикса — не fps (p без цифр после).
    private val FPS_RE = Regex("""(?:(\d{2,3})fps|p(60|120|50|30|25|24)(?![0-9]))""", RegexOption.IGNORE_CASE)

    /** Частота кадров из маркеров URL, или null (неизвестна). */
    fun fpsOf(url: String): Int? {
        val m = FPS_RE.find(url.toLowerCase()) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    fun isKodik(url: String): Boolean {
        val u = url.toLowerCase()
        return u.contains("kodik") || u.contains("kodik.") || u.contains("/serial/") && u.contains("kodik")
    }

    /**
     * Generic player-iframe detection. The episode page embeds its player
     * (Alloha, Kodik, ...) in an <iframe>; the iframe src is the player
     * page, not a video file. Covers: alloha, kodik, /player, /embed,
     * embed. hosts, player. hosts, /serial/ (Kodik serial pages),
     * v0.8.0: Sibnet shell.php, VK video_ext.php, OK.ru videoembed,
     * AnimeGO cdn-iframe, Voidboost, Aniboom.
     * Deliberately does NOT match static/slider iframes.
     */
    fun isPlayerIframe(url: String): Boolean {
        val u = url.toLowerCase()
        if (isKodik(url)) return true
        return u.contains("alloha") ||
            u.contains("/player") ||
            u.contains("/embed") ||
            u.contains("embed.") ||
            u.contains("player.") ||
            u.contains("/serial/") ||
            u.contains("shell.php") ||
            u.contains("video_ext.php") ||
            u.contains("videoembed") ||
            u.contains("cdn-iframe") ||
            u.contains("voidboost") ||
            u.contains("aniboom")
    }

    /**
     * Master HLS playlist (multi-variant) vs single-variant manifest:
     * Aniboom fMP4 keeps audio in a separate rendition — a bare variant
     * URL plays silent, only the master is playable. Kodik's
     * ".../720.mp4:hls:manifest.m3u8" is a single-variant master, not
     * a multi-variant one (no "master"/"playlist" in the path).
     * v0.8.1: "index.m3u8" без маркера качества в пути — тоже master
     * (так называют мастер-плейлист многие HLS-энкодеры); а вот
     * ".../720p/index.m3u8" — вариант в папке качества, НЕ master.
     */
    fun isMasterPlaylist(url: String): Boolean {
        val p = url.toLowerCase().substringBefore('?').substringBefore('#')
        if (p.contains("master") || p.endsWith("playlist.m3u8")) return true
        if (p.endsWith("index.m3u8")) {
            val qualityMarkers = listOf("2160", "1440", "1080", "720", "480", "360", "4k")
            if (qualityMarkers.none { p.contains(it) }) return true
        }
        return false
    }

    /**
     * Placeholder videos that are not real content — e.g. the blank clip
     * some players load (plyr.io blank.mp4). Filtered out of
     * "Найдено видео" so it does not pollute the candidate list.
     */
    fun isPlaceholder(url: String): Boolean {
        val u = url.toLowerCase()
        val file = u.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return file == "blank.mp4" || file == "blank.webm" || file == "blank.m3u8"
    }

    /**
     * Абсолютный URL из относительного против базового (//, /, ./, ../).
     * Для относительных путей из скриптов страницы (JWPlayer src и т.п.).
     */
    fun resolveUrl(base: String, ref: String): String {
        return try {
            java.net.URL(java.net.URL(base), ref).toString()
        } catch (e: Exception) {
            ref
        }
    }

    /**
     * v0.8.1: абсолютный URL из сырого значения скрипта страницы.
     * В отличие от normalizeScriptUrl в CastEngine (v0.8.0, только
     * http(s)/protocol-relative) — относительные пути (/v/..., ./, ../)
     * тоже резолвятся против URL страницы: data-атрибуты (data-src и
     * т.п.) часто относительные, и раньше они молча терялись.
     * Возвращает null для мусора (javascript:, blob:, data:, пустое).
     */
    fun resolveScriptUrl(pageUrl: String?, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("//")) return "https:$t"
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return t
        if (pageUrl != null && (t.startsWith("/") || t.startsWith("./") || t.startsWith("../"))) {
            return resolveUrl(pageUrl, t)
        }
        return null
    }

    /**
     * v0.8.1: распарсить Playerjs-строку file (docs playerjs.com,
     * "Выбор качества" / "Fallback sources"): список качеств через
     * запятую с метками [480p]/[720p] (или ((480p)) в DLE-шорткатах),
     * аудиодорожки через ";" с метками {French}/{English}, fallback
     * "url1 or url2", балансировка "url1 and url2", суффикс [seek:N],
     * разделитель групп [<<<...]. Возвращает отдельные URL-строки
     * (могут быть // или относительными — резолвятся вызывающим).
     * Разделение по "," / ";" — ТОЛЬКО на границе элемента Playerjs
     * (следующий сегмент начинается с [, {, ((, // или http): запятая
     * внутри query обычного URL ничего не ломает.
     *
     * v0.9.1: expandPlayerjsListWithLabels — то же, но сохраняет
     * аудиодорожку {Lang} (для запоминания озвучки);
     * expandPlayerjsList делегирует сюда и возвращает только URL —
     * поведение идентично прежнему.
     */
    data class PlayerjsPart(val url: String, val audioLabel: String?)

    fun expandPlayerjsList(raw: String): List<String> =
        expandPlayerjsListWithLabels(raw).map { it.url }

    fun expandPlayerjsListWithLabels(raw: String): List<PlayerjsPart> {
        val out = ArrayList<PlayerjsPart>()
        // Fallback/balancing: "url1 or url2", "url1 and url2" (с пробелами —
        // в самих URL пробелов не бывает).
        val chunks = raw.split(Regex("\\s+(?i:or|and)\\s+"))
        for (chunk in chunks) {
            var start = 0
            var i = 0
            while (i < chunk.length) {
                val c = chunk[i]
                if (c == ',' || c == ';') {
                    var j = i + 1
                    while (j < chunk.length && chunk[j] == ' ') j++
                    val rest = chunk.substring(j)
                    val boundary = rest.startsWith("[") || rest.startsWith("{") ||
                        rest.startsWith("((") || rest.startsWith("//") ||
                        rest.startsWith("http://", ignoreCase = true) ||
                        rest.startsWith("https://", ignoreCase = true)
                    if (boundary) {
                        out.add(cleanPlayerjsPartWithLabel(chunk.substring(start, i)))
                        start = j
                        i = j
                        continue
                    }
                }
                i++
            }
            out.add(cleanPlayerjsPartWithLabel(chunk.substring(start)))
        }
        return out.filter { it.url.isNotEmpty() }
    }

    private fun cleanPlayerjsPartWithLabel(part: String): PlayerjsPart {
        var s = part.trim().trim('"', '\'')
        var audio: String? = null
        // Ведущие метки — возможно несколько подряд: [720p]{French}url.
        while (true) {
            val label = Regex("^\\[[^\\]]*\\]").find(s)
            if (label != null) { s = s.substring(label.value.length).trim(); continue }
            val audioM = Regex("^\\{[^}]*\\}").find(s)
            if (audioM != null) {
                if (audio == null) audio = audioM.value.substring(1, audioM.value.length - 1).trim()
                s = s.substring(audioM.value.length).trim(); continue
            }
            val dle = Regex("^\\(\\([^)]*\\)\\)").find(s)
            if (dle != null) { s = s.substring(dle.value.length).trim(); continue }
            break
        }
        // Суффикс [seek:N] в конце URL.
        s = Regex("\\[seek:[^\\]]*\\]$").replace(s, "")
        return PlayerjsPart(s.trim().trim('"', '\''), audio?.takeIf { it.isNotBlank() })
    }

    private val JW_FEED_RE = Regex("""(?:https?:)?//cdn\.jwplayer\.com/v2/(?:playlists|media)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)

    /**
     * v0.8.1: JWPlayer v2 feed URL из текста страницы (docs.jwplayer.com,
     * "Playlists": playlist property может быть URL JSON/RSS фида вида
     * https://cdn.jwplayer.com/v2/playlists/{id} — в нём нет
     * видео-расширения, поэтому generic regex его не ловит).
     */
    fun extractJwplayerFeedUrls(html: String): List<String> {
        return JW_FEED_RE.findAll(html).map {
            val m = it.value
            if (m.startsWith("//")) "https:$m" else m
        }.distinct().toList()
    }

    private val JW_FILE_RE = Regex(""""file"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * v0.8.1: файлы из JWPlayer v2 feed JSON: playlist[].file и
     * playlist[].sources[].file. Возвращает только настоящие
     * видео-URL (isVideoUrl); постеры (image) и прочее — мимо.
     */
    fun parseJwplayerFeed(json: String): List<String> {
        return JW_FILE_RE.findAll(json).mapNotNull {
            val u = decodeJsResult("\"${it.groupValues[1]}\"") ?: it.groupValues[1]
            if (u.isNotBlank() && isVideoUrl(u)) u else null
        }.distinct().toList()
    }

    /**
     * Host + path of a URL, WITHOUT the query string. For logs: player
     * iframe URLs carry tokens in the query — never print them whole.
     */
    fun safeHost(url: String): String {
        return try {
            val noScheme = url.substringAfter("://")
            val hostAndPath = noScheme.substringBefore('?').substringBefore('#')
            hostAndPath
        } catch (e: Exception) {
            url.take(60)
        }
    }

    // ---------- JS scan result decoding ----------

    /**
     * Unwrap the value WebView.evaluateJavascript hands to its callback.
     * The JS returns JSON.stringify({...}), so the callback receives a
     * JSON-encoded STRING: the whole object wrapped in quotes, with all
     * inner quotes backslash-escaped. Returns the decoded inner JSON
     * text, or null for null/empty input. A plain (unquoted) JSON object
     * is returned as-is.
     */
    fun decodeJsResult(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim()
        if (t.isEmpty() || t == "null") return null
        if (!t.startsWith("\"")) return t
        // Parse a JSON string literal: "...." with \" \\ \/ \n \t \uXXXX escapes.
        val out = StringBuilder()
        var i = 1
        while (i < t.length) {
            val c = t[i]
            if (c == '"') break // unescaped closing quote
            if (c == '\\' && i + 1 < t.length) {
                val e = t[i + 1]
                when (e) {
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    '/' -> { out.append('/'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < t.length) {
                            val code = t.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) out.append(code.toChar())
                            i += 6
                        } else i += 2
                    }
                    else -> { out.append(e); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Unescape the inside of a JSON string token (no surrounding quotes). */
    private fun unescapeJsonString(inner: String): String {
        // Reuse the literal parser by wrapping the content in quotes.
        return decodeJsResult("\"$inner\"") ?: inner
    }

    // v0.9.3 (хотфикс Instagram): те же паттерны, что SCAN_JS ищет в
    // живом DOM — meta og:video / og:video:secure_url / og:video:url /
    // twitter:player:stream (content до или после property) и
    // "video_url" из JSON страницы (как re6 в SCAN_JS).
    private val META_VIDEO_A = Regex("""<meta[^>]+(?:property|name)\s*=\s*["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["'][^>]+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val META_VIDEO_B = Regex("""<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["'](?:og:video(?::secure_url|:url)?|twitter:player:stream)["']""", RegexOption.IGNORE_CASE)
    private val VIDEO_URL_JSON = Regex(""""video_url"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * v0.9.3 (хотфикс Instagram): видео-URL из СЫРОГО HTML — без
     * WebView. Instagram голому WebView отдаёт страницу входа без
     * og:video, а прямому HTTP-fetch с десктопным UA — страницу поста
     * с ними. Возвращает сырые значения (могут быть //... — резолвит
     * вызывающий через resolveScriptUrl); &amp; → &, \/ → /.
     * Порядок — как в документе (не по группам паттернов).
     * \"video_url\" из JSON — строка целиком (в сыром HTML слеши
     * экранированы как \/, SCAN_JS re6 с буквальным // их не ловит);
     * после unescape — только URL-форма (http(s):// или //).
     * Никогда не бросает: на любом сбое — то, что успели собрать.
     */
    fun extractMetaVideoUrls(html: String): List<String> {
        val found = ArrayList<Pair<Int, String>>()
        fun clean(raw: String): String? {
            val v = raw.replace("&amp;", "&").replace("\\/", "/").trim()
            if (v.isBlank() || v.startsWith("blob:") || v.startsWith("data:")) return null
            return v
        }
        try {
            for (m in META_VIDEO_A.findAll(html)) clean(m.groupValues[1])?.let { found.add(m.range.first to it) }
            for (m in META_VIDEO_B.findAll(html)) clean(m.groupValues[1])?.let { found.add(m.range.first to it) }
            for (m in VIDEO_URL_JSON.findAll(html)) {
                val v = try { unescapeJsonString(m.groupValues[1]) } catch (e: Exception) { m.groupValues[1] }
                val c = clean(v)
                if (c != null && (c.startsWith("http://") || c.startsWith("https://") || c.startsWith("//"))) found.add(m.range.first to c)
            }
        } catch (e: Exception) {
            // Никогда не бросает — вернётся то, что успели собрать.
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private val VIDEO_OBJ = Regex("""\{\s*"src"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"w"\s*:\s*(\d+)\s*,\s*"h"\s*:\s*(\d+)\s*\}""")
    private val AUDIOS_ARR = Regex(""""audios"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val IFRAMES_ARR = Regex(""""iframes"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val URLS_ARR = Regex(""""urls"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val REL_ARR = Regex(""""rel"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val JWFEEDS_ARR = Regex(""""jwfeeds"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val LINKS_ARR = Regex(""""links"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val LINK_OBJ = Regex("""\{\s*"href"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")
    private val SUBS_ARR = Regex(""""subs"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val SUB_OBJ = Regex("""\{\s*"src"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"lang"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"label"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")
    private val TITLE_VAL = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val POSTER_VAL = Regex(""""poster"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val QUOTED = Regex(""""((?:[^"\\]|\\.)*)"""")

    /**
     * Parse a SCAN_JS result (raw evaluateJavascript callback value) into
     * videos + iframes + script URLs + relative URLs + JWPlayer feeds +
     * episode links + subtitle tracks + page title.
     * Never throws: on any failure returns what could be extracted
     * (possibly empty lists).
     */
    fun parseScanResult(raw: String?): ScanResult {
        val json = decodeJsResult(raw) ?: return ScanResult()
        val videos = ArrayList<ScanVideo>()
        try {
            for (m in VIDEO_OBJ.findAll(json)) {
                val src = unescapeJsonString(m.groupValues[1])
                if (src.isNotEmpty()) {
                    videos.add(ScanVideo(src, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0))
                }
            }
        } catch (ignored: Exception) {
        }
        // v0.9.2: теги <audio> — та же форма объекта {src,w,h} (w/h = 0).
        val audios = ArrayList<ScanVideo>()
        try {
            val arr = AUDIOS_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in VIDEO_OBJ.findAll(arr)) {
                val src = unescapeJsonString(m.groupValues[1])
                if (src.isNotEmpty()) audios.add(ScanVideo(src, 0, 0))
            }
        } catch (ignored: Exception) {
        }
        val frames = ArrayList<String>()
        try {
            val arr = IFRAMES_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in QUOTED.findAll(arr)) {
                val src = unescapeJsonString(m.groupValues[1])
                if (src.isNotEmpty()) frames.add(src)
            }
        } catch (ignored: Exception) {
        }
        val urls = ArrayList<String>()
        try {
            val arr = URLS_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in QUOTED.findAll(arr)) {
                val src = unescapeJsonString(m.groupValues[1])
                if (src.isNotEmpty()) urls.add(src)
            }
        } catch (ignored: Exception) {
        }
        val rel = ArrayList<String>()
        try {
            val arr = REL_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in QUOTED.findAll(arr)) {
                val src = unescapeJsonString(m.groupValues[1])
                if (src.isNotEmpty()) rel.add(src)
            }
        } catch (ignored: Exception) {
        }
        val jwFeeds = ArrayList<String>()
        try {
            val arr = JWFEEDS_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in QUOTED.findAll(arr)) {
                val src = unescapeJsonString(m.groupValues[1])
                if (src.isNotEmpty()) jwFeeds.add(src)
            }
        } catch (ignored: Exception) {
        }
        val links = ArrayList<PageLink>()
        try {
            val arr = LINKS_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in LINK_OBJ.findAll(arr)) {
                val href = unescapeJsonString(m.groupValues[1])
                val text = unescapeJsonString(m.groupValues[2])
                if (href.isNotEmpty()) links.add(PageLink(href, text))
            }
        } catch (ignored: Exception) {
        }
        val subs = ArrayList<SubTrack>()
        try {
            val arr = SUBS_ARR.find(json)?.groupValues?.get(1) ?: ""
            for (m in SUB_OBJ.findAll(arr)) {
                val src = unescapeJsonString(m.groupValues[1])
                val lang = unescapeJsonString(m.groupValues[2])
                val label = unescapeJsonString(m.groupValues[3])
                if (src.isNotEmpty()) subs.add(SubTrack(src, lang, label))
            }
        } catch (ignored: Exception) {
        }
        var title = ""
        try {
            title = TITLE_VAL.find(json)?.groupValues?.get(1)?.let { unescapeJsonString(it) } ?: ""
        } catch (ignored: Exception) {
        }
        var poster = ""
        try {
            poster = POSTER_VAL.find(json)?.groupValues?.get(1)?.let { unescapeJsonString(it) } ?: ""
        } catch (ignored: Exception) {
        }
        // v0.9.3 (#23/#24): jsonld/oembed — массив JSON-строк; внутри
        // блоков есть неэкранированные [ ] (например @graph), поэтому
        // обычный regex массива (.*?) оборвался бы на первой ] —
        // парсим строки вручную (parseJsonStringArray).
        val jsonld = try { parseJsonStringArray(json, "jsonld") } catch (ignored: Exception) { emptyList() }
        val oembed = try { parseJsonStringArray(json, "oembed") } catch (ignored: Exception) { emptyList() }
        return ScanResult(videos, frames, urls, rel, jwFeeds, links, subs, title, poster, audios, jsonld, oembed)
    }

    /**
     * v0.9.3 (#23/#24): массив JSON-строк по ключу ("jsonld": ["..."]).
     * Ручной парсер: внутри строк бывают [ ] и экранированные кавычки —
     * regex с нежадным (.*?) ломается. Никогда не бросает.
     */
    fun parseJsonStringArray(json: String, key: String): List<String> {
        val out = ArrayList<String>()
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return out
        var i = json.indexOf('[', keyIdx)
        if (i < 0) return out
        i++
        while (i < json.length) {
            while (i < json.length && (json[i] == ' ' || json[i] == '\n' || json[i] == '\r' || json[i] == '\t' || json[i] == ',')) i++
            if (i >= json.length) break
            if (json[i] == ']') break
            if (json[i] != '"') { i++; continue }
            val sb = StringBuilder()
            i++
            var closed = false
            while (i < json.length) {
                val c = json[i]
                if (c == '\\' && i + 1 < json.length) {
                    sb.append(c); sb.append(json[i + 1]); i += 2
                } else if (c == '"') { i++; closed = true; break }
                else { sb.append(c); i++ }
            }
            if (closed) out.add(unescapeJsonString(sb.toString()))
        }
        return out
    }

    private val JSONLD_CONTENT_URL = Regex(""""contentUrl"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val JSONLD_EMBED_URL = Regex(""""embedUrl"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * v0.9.3 (#23): видео-URL из блока JSON-LD. Блок должен упоминать
     * VideoObject (иначе — не видеоразметка). @graph обрабатывается
     * без структурного парсинга — regex ищут по всему тексту блока.
     * contentUrl — предпочтительно (прямой файл); embedUrl — только
     * если он сам видео-URL (страница плеера — не видео).
     * contentUrl массивом (["u1","u2"]) — не поддерживается честно
     * (в разметке почти всегда строка).
     */
    fun parseJsonLdVideoObject(jsonld: String): String? {
        if (!jsonld.contains("VideoObject")) return null
        for (m in JSONLD_CONTENT_URL.findAll(jsonld)) {
            val u = try { unescapeJsonString(m.groupValues[1]) } catch (e: Exception) { m.groupValues[1] }
            if (u.isNotBlank() && isVideoUrl(u)) return u
        }
        for (m in JSONLD_EMBED_URL.findAll(jsonld)) {
            val u = try { unescapeJsonString(m.groupValues[1]) } catch (e: Exception) { m.groupValues[1] }
            if (u.isNotBlank() && isVideoUrl(u)) return u
        }
        return null
    }

    private val OEMBED_TYPE = Regex(""""type"\s*:\s*"([^"]*)"""")
    private val OEMBED_URL = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val OEMBED_HTML = Regex(""""html"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val OEMBED_IFRAME_SRC = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * v0.9.3 (#24): видео-URL из oEmbed JSON. type=photo/link — не
     * видео (null); type=video или без type — сначала "url" (если сам
     * видео-URL), затем src из "html" (iframe). XML oEmbed — вне скопа
     * (SCAN_JS собирает только application/json+oembed).
     */
    fun parseOembedVideoUrl(json: String): String? {
        val type = OEMBED_TYPE.find(json)?.groupValues?.get(1) ?: ""
        if (type.isNotEmpty() && type != "video") return null
        val url = OEMBED_URL.find(json)?.groupValues?.get(1)?.let {
            try { unescapeJsonString(it) } catch (e: Exception) { it }
        }
        if (!url.isNullOrBlank() && isVideoUrl(url)) return url
        val html = OEMBED_HTML.find(json)?.groupValues?.get(1)?.let {
            try { unescapeJsonString(it) } catch (e: Exception) { it }
        }
        if (!html.isNullOrBlank()) {
            val src = OEMBED_IFRAME_SRC.find(html)?.groupValues?.get(1)
            if (!src.isNullOrBlank() && isVideoUrl(src)) return src
        }
        return null
    }

    /**
     * JS run in the page: collects <video> tags, <iframe> srcs, video URLs
     * from scripts (player configs) and data-attributes, and relative
     * video paths. Same-origin iframes are scanned inside too
     * (cross-origin throws — skipped silently). v0.8.0: раньше был
     * только <video> + <iframe>, поэтому плееры без авто-play
     * (Playerjs/JWPlayer/Video.js на малых сайтах, Sibnet, VK, CVH,
     * OK.ru) не находились вообще.
     * v0.8.1: нормализация HTML перед regex (JSON-экранированные
     * \/, \u002F и &amp; в outerHTML — иначе URL с такими слешами
     * терялись), процентно-закодированные URL (https%3A%2F%2F...),
     * больше data-атрибутов (data-stream/data-source/data-playlist/
     * data-mp4/data-m3u8/data-video-url/data-hls-url/data-file-url),
     * JWPlayer v2 feed URL (jwfeeds — фид скачивается и парсится
     * в Kotlin, VideoUrls.parseJwplayerFeed), относительные пути
     * из Playerjs-списков (re2 видит ","/"["/"{"/";" как разделители).
     * v0.9.0: + ссылки <a> (список серий, только top doc, кап 300) и
     * дорожки субтитров (<track kind=subtitles/captions> + .vtt/.srt/.ass
     * в скриптах; same-origin iframe — тоже).
     * v0.9.1: + meta-теги og:video / og:video:secure_url / og:video:url /
     * twitter:player:stream и "video_url" из JSON страницы (Instagram:
     * видео есть в meta/JSON, <video> на странице нет) + og:image
     * (постер, только top doc).
     * v0.9.2: + теги <audio> (audios, та же форма {src,w,h}, w/h=0),
     * аудио-расширения в re/re2 (.mp3/.aac/.flac/.ogg/.oga/.m4a/.wav/
     * .opus), SoundCloud media API (api-v2.soundcloud.com/media/… —
     * без расширения, резолвится в Kotlin) и Bandcamp
     * (tN.bcbits.com/stream/… — mp3-128 без .mp3 в пути).
     */
    const val SCAN_JS = """(function(){
      function collect(doc){
        var out={videos:[],audios:[],urls:[],rel:[],jwfeeds:[],subs:[],poster:'',jsonld:[],oembed:[]};
        doc.querySelectorAll('video').forEach(function(v){
          out.videos.push({src: v.currentSrc || v.src || '', w: v.videoWidth||0, h: v.videoHeight||0});
          v.querySelectorAll('source').forEach(function(s){ out.videos.push({src: s.src||'', w:0, h:0}); });
        });
        doc.querySelectorAll('audio').forEach(function(a){
          out.audios.push({src: a.currentSrc || a.src || '', w:0, h:0});
          a.querySelectorAll('source').forEach(function(s){ out.audios.push({src: s.src||'', w:0, h:0}); });
        });
        doc.querySelectorAll('track[kind="subtitles"],track[kind="captions"]').forEach(function(t){
          out.subs.push({src: t.src||'', lang: t.srclang||'', label: t.label||''});
        });
        doc.querySelectorAll('meta[property="og:video"],meta[property="og:video:secure_url"],meta[property="og:video:url"],meta[name="twitter:player:stream"]').forEach(function(m){
          var c=m.getAttribute('content'); if(c) out.urls.push(c);
        });
        var ogImg=doc.querySelector('meta[property="og:image"]');
        if(ogImg) out.poster=ogImg.getAttribute('content')||'';
        // v0.9.3 (#23): JSON-LD VideoObject — сырые тексты скриптов
        // (contentUrl/embedUrl парсятся в Kotlin).
        doc.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){
          if(s.textContent) out.jsonld.push(s.textContent);
        });
        // v0.9.3 (#24): oEmbed — ссылка на JSON (только JSON, не XML).
        doc.querySelectorAll('link[rel="alternate"][type="application/json+oembed"]').forEach(function(l){
          if(l.href) out.oembed.push(l.href);
        });
        doc.querySelectorAll('[data-url],[data-file],[data-video],[data-src],[data-hls],[data-stream],[data-source],[data-playlist],[data-mp4],[data-m3u8],[data-video-url],[data-hls-url],[data-file-url]').forEach(function(el){
          ['data-url','data-file','data-video','data-src','data-hls','data-stream','data-source','data-playlist','data-mp4','data-m3u8','data-video-url','data-hls-url','data-file-url'].forEach(function(a){
            var val=el.getAttribute(a); if(val) out.urls.push(val);
          });
        });
        var html=doc.documentElement.outerHTML.replace(/\\\//g,'/').replace(/\\u002f/gi,'/').replace(/&amp;/g,'&');
        var re=/(?:https?:)?\/\/[^\s"'<>\\]+\.(?:m3u8|mp4|mpd|m4v|mov|webm|mp3|aac|flac|ogg|oga|m4a|wav|opus)(?:[?#][^\s"'<>\\]*)?/gi;
        var m; while((m=re.exec(html))!==null){ out.urls.push(m[0]); }
        var re2=/(?:["'\s=,\[{;])(\/[^\s"'<>\\]+\.(?:m3u8|mp4|mpd|m4v|mov|webm|mp3|aac|flac|ogg|oga|m4a|wav|opus)(?:[?#][^\s"'<>\\]*)?)/g;
        while((m=re2.exec(html))!==null){ out.rel.push(m[1]); }
        var re3=/(https?%3A%2F%2F[^\s"'<>\\]+)/gi;
        while((m=re3.exec(html))!==null){ try{ out.urls.push(decodeURIComponent(m[1])); }catch(e){} }
        var re4=/(?:https?:)?\/\/cdn\.jwplayer\.com\/v2\/(?:playlists|media)\/[A-Za-z0-9_-]+/gi;
        while((m=re4.exec(html))!==null){ var f=m[0]; out.jwfeeds.push(f.indexOf('http')===0?f:'https:'+f); }
        var re5=/(?:https?:)?\/\/[^\s"'<>\\]+\.(?:vtt|srt|ass)(?:[?#][^\s"'<>\\]*)?/gi;
        while((m=re5.exec(html))!==null){ out.subs.push({src:m[0], lang:'', label:''}); }
        var re6=/"video_url"\s*:\s*"((?:https?:)?\/\/[^"\\]+)"/g;
        while((m=re6.exec(html))!==null){ out.urls.push(m[1]); }
        var re7=/(?:https?:)?\/\/api-v2\.soundcloud\.com\/media\/[^\s"'<>\\]+/gi;
        while((m=re7.exec(html))!==null){ out.urls.push(m[0]); }
        var re8=/(?:https?:)?\/\/t\d+\.bcbits\.com\/stream\/[^\s"'<>\\]+/gi;
        while((m=re8.exec(html))!==null){ out.urls.push(m[0]); }
        return out;
      }
      var top=collect(document);
      var frames = [];
      document.querySelectorAll('iframe').forEach(function(f){ if(f.src) frames.push(f.src); });
      document.querySelectorAll('iframe').forEach(function(f){
        try{
          var d=f.contentDocument; if(!d) return;
          var r=collect(d);
          top.videos=top.videos.concat(r.videos);
          top.audios=top.audios.concat(r.audios);
          top.urls=top.urls.concat(r.urls);
          top.rel=top.rel.concat(r.rel);
          top.jwfeeds=top.jwfeeds.concat(r.jwfeeds);
          top.subs=top.subs.concat(r.subs);
          top.jsonld=top.jsonld.concat(r.jsonld);
          top.oembed=top.oembed.concat(r.oembed);
        }catch(e){}
      });
      var links=[];
      document.querySelectorAll('a[href]').forEach(function(a){
        if(links.length>=300) return;
        links.push({href: a.getAttribute('href')||'', text: (a.textContent||'').trim().slice(0,80)});
      });
      return JSON.stringify({videos: top.videos, audios: top.audios, iframes: frames, urls: top.urls, rel: top.rel, jwfeeds: top.jwfeeds, links: links, subs: top.subs, url: location.href, title: document.title, poster: top.poster, jsonld: top.jsonld, oembed: top.oembed});
    })()"""
}
