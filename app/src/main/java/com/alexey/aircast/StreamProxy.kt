package com.alexey.aircast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.webkit.CookieManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * v0.8.0 — локальный HTTP-прокси на телефоне для потоков, которым нужен
 * Referer/Cookie.
 *
 * Исследование 2026-11-18: Sibnet отдаёт 403 без
 * `Referer: https://video.sibnet.ru/shell.php?videoid=…`, OK.ru —
 * подписанные URL + Referer. VLC на ТВ запускается deep link'ом, куда
 * кастомные заголовки не передать вообще, — поэтому Referer иначе не
 * доставить: прокси на телефоне ходит к CDN сам (с Referer + куками
 * WebView) и отдаёт поток телевизору. Плейлисты HLS переписываются:
 * сегменты и ключи тоже идут через прокси с тем же Referer.
 *
 * Прокси включается ТОЛЬКО для хостов из REFERER_HOSTS (данные, не
 * догадка): рабочий путь Kodik/Alloha/Voidboost идёт напрямую, как
 * раньше. Ссылки с истекающим токеном (Voidboost, OK.ru, Sibnet)
 * захватываются свежими и играют сразу — прокси их не кеширует.
 */
object StreamProxy {
    /** Хосты CDN, требующие Referer (исследование 2026-11-18). */
    private val REFERER_HOSTS = listOf("sibnet.ru", "okcdn.ru", "ok.ru")

    /** Нужен ли прокси для этой ссылки: Referer есть и хост в списке. */
    fun needsProxy(url: String, referer: String?): Boolean {
        if (referer.isNullOrBlank()) return false
        val host = try {
            URL(url).host.toLowerCase()
        } catch (e: Exception) {
            return false
        }
        return REFERER_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** URL потока через прокси: http://<ip телефона>:<port>/stream?u=…&r=…. */
    fun proxyStreamUrl(phoneIp: String, port: Int, streamUrl: String, referer: String): String =
        "http://$phoneIp:$port/stream?u=" + URLEncoder.encode(streamUrl, "UTF-8") +
            "&r=" + URLEncoder.encode(referer, "UTF-8")

    /**
     * v0.9.0: URL дорожки субтитров через прокси — для параметра sub=
     * в vlc-x-callback-url. Путь /stream.vtt (а не /stream): у URL
     * есть расширение .vtt, чтобы VLC точно опознал формат дорожки
     * (контент тоже VTT — /stream конвертирует .srt/.ass на лету).
     * Обслуживается тем же serveStream (см. handle()).
     */
    fun proxySubtitleUrl(phoneIp: String, port: Int, trackUrl: String, referer: String): String =
        "http://$phoneIp:$port/stream.vtt?u=" + URLEncoder.encode(trackUrl, "UTF-8") +
            "&r=" + URLEncoder.encode(referer, "UTF-8")

    /**
     * v0.9.0: URL синтетического HLS-мастера с субтитрами:
     * http://<ip>:<port>/subs-master?u=…&r=…&t=…&l=…&n=… (t/l/n —
     * дорожки: URL, язык, название; повторяются по порядку).
     * Прокси отдаёт мастер, где исходный поток — единственный вариант,
     * а дорожки — группа SUBTITLES (см. Subtitles.buildSubsMaster).
     * v0.9.1: d= — запомненный язык субтитров (DEFAULT=YES в мастере).
     */
    fun proxySubsMasterUrl(phoneIp: String, port: Int, streamUrl: String, referer: String, tracks: List<SubTrack>, preferredLang: String? = null): String {
        val sb = StringBuilder("http://$phoneIp:$port/subs-master?u=")
        sb.append(URLEncoder.encode(streamUrl, "UTF-8"))
        sb.append("&r=").append(URLEncoder.encode(referer, "UTF-8"))
        if (!preferredLang.isNullOrBlank()) sb.append("&d=").append(URLEncoder.encode(preferredLang, "UTF-8"))
        for (t in tracks) {
            sb.append("&t=").append(URLEncoder.encode(t.url, "UTF-8"))
            sb.append("&l=").append(URLEncoder.encode(t.lang, "UTF-8"))
            sb.append("&n=").append(URLEncoder.encode(t.label, "UTF-8"))
        }
        return sb.toString()
    }

    /**
     * v0.9.1: URL обрезанного плейлиста для «продолжить с того же
     * места» (HLS): http://<ip>:<port>/resume?u=…&r=…&pos=<секунды>.
     * Прокси скачивает плейлист (если мастер — первый вариант),
     * обрезает до позиции (ResumePlaylist) и переписывает сегменты
     * через /stream.
     */
    fun proxyResumeUrl(phoneIp: String, port: Int, streamUrl: String, referer: String, positionSec: Long): String =
        "http://$phoneIp:$port/resume?u=" + URLEncoder.encode(streamUrl, "UTF-8") +
            "&r=" + URLEncoder.encode(referer, "UTF-8") + "&pos=$positionSec"

    /**
     * v0.9.1: URL видеофайла из Telegram/галереи через прокси:
     * http://<ip>:<port>/file<ext>?id=… (ext — из имени файла,
     * чтобы VLC опознал формат; Range поддерживается).
     */
    fun proxyFileUrl(phoneIp: String, port: Int, fileId: String, ext: String): String {
        val e = if (ext.startsWith(".")) ext else ".$ext"
        return "http://$phoneIp:$port/file$e?id=" + URLEncoder.encode(fileId, "UTF-8")
    }

    /**
     * v0.9.1: URL дорожки субтитров из памяти (OpenSubtitles:
     * скачанный .srt → VTT): http://<ip>:<port>/memory-vtt?id=….
     */
    fun proxyMemoryVttUrl(phoneIp: String, port: Int, vttId: String): String =
        "http://$phoneIp:$port/memory-vtt?id=" + URLEncoder.encode(vttId, "UTF-8")

    /** Абсолютный URL из относительного против базового (//, /, ./, ../). */
    fun resolveUrl(base: String, ref: String): String = VideoUrls.resolveUrl(base, ref)

    private val URI_ATTR_RE = Regex("""URI="([^"]+)"""")

    /**
     * Переписать HLS-плейлист: все URI (сегменты, #EXT-X-KEY, #EXT-X-MAP,
     * #EXT-X-MEDIA) — через proxyFor. Абсолютные URL тоже через прокси:
     * сегменты CDN могут требовать Referer так же, как манифест.
     */
    fun rewritePlaylist(playlist: String, baseUrl: String, proxyFor: (String) -> String): String {
        val out = StringBuilder()
        for (rawLine in playlist.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                out.append(rawLine).append("\n")
                continue
            }
            if (line.startsWith("#")) {
                val rewritten = URI_ATTR_RE.replace(line) { m ->
                    "URI=\"" + proxyFor(resolveUrl(baseUrl, m.groupValues[1])) + "\""
                }
                out.append(rewritten).append("\n")
            } else {
                out.append(proxyFor(resolveUrl(baseUrl, line))).append("\n")
            }
        }
        return out.toString()
    }

    /** Это плейлист (по Content-Type или по .m3u8 в URL). */
    fun isPlaylist(url: String, contentType: String?): Boolean {
        val ct = contentType?.toLowerCase() ?: ""
        if (ct.contains("mpegurl")) return true
        val path = url.toLowerCase().substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8")
    }
}

/** FileSource поверх ParcelFileDescriptor (content:// URI из
 *  Telegram/галереи): чтение с позиции через FileChannel, без
 *  копирования файла. Закрытие — в unregisterFileSource. */
class FdFileSource(pfd: ParcelFileDescriptor) : FileSource {
    // Стрим удерживается полем: иначе GC может финализировать
    // временный FileInputStream и закрыть fd под каналом.
    private val stream = java.io.FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel
    private val sz = channel.size()

    override fun size(): Long = sz

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || offset >= sz) return null
        return try {
            val n = minOf(length, (sz - offset).toInt())
            val buf = java.nio.ByteBuffer.allocate(n)
            var read = 0
            while (read < n) {
                val r = channel.read(buf, offset + read)
                if (r < 0) break
                read += r
            }
            if (read == 0) null else buf.array().copyOf(read)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        try { channel.close() } catch (e: Exception) { /* ignore */ }
        try { stream.close() } catch (e: Exception) { /* ignore */ }
    }
}

/** Тот же UA, что у фонового WebView (CastEngine). */
private const val PROXY_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 16; RMX5011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/** Сервер прокси: ServerSocket на 0.0.0.0, эфемерный порт, поток на соединение. */
class StreamProxyServer(private val ctx: Context) {
    @Volatile
    var phoneIp: String? = null
        private set
    @Volatile
    var port: Int = 0
        private set
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** v0.9.1: зарегистрированные источники файлов (/file) и
     *  дорожки субтитров в памяти (/memory-vtt). */
    private val fileSources = HashMap<String, FileSource>()
    private val memoryVtts = HashMap<String, String>()

    fun registerFileSource(id: String, src: FileSource) {
        synchronized(fileSources) { fileSources[id] = src }
    }

    fun unregisterFileSource(id: String) {
        val src = synchronized(fileSources) { fileSources.remove(id) }
        src?.close()
    }

    fun registerMemoryVtt(id: String, vtt: String) {
        synchronized(memoryVtts) { memoryVtts[id] = vtt }
    }

    fun unregisterMemoryVtt(id: String) {
        synchronized(memoryVtts) { memoryVtts.remove(id) }
    }

    /** Стартовать (лениво, при первом запуске через прокси). Base URL или null. */
    @Synchronized
    fun ensureStarted(): String? {
        if (running && server != null) return baseUrl()
        return try {
            val ip = lanIp() ?: return null
            val s = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
            server = s
            port = s.localPort
            phoneIp = ip
            running = true
            acceptThread = thread(isDaemon = true, name = "vlc-proxy-accept") { acceptLoop(s) }
            Log.add("proxy", "прокси запущен: $ip:$port")
            baseUrl()
        } catch (e: Exception) {
            Log.add("proxy", "прокси НЕ запущен: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun baseUrl(): String? {
        val ip = phoneIp ?: return null
        return if (port > 0) "http://$ip:$port" else null
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        port = 0
        // v0.9.1: снять регистрации (закрыть дескрипторы файлов).
        val sources = synchronized(fileSources) { fileSources.values.toList().also { fileSources.clear() } }
        sources.forEach { runCatching { it.close() } }
        synchronized(memoryVtts) { memoryVtts.clear() }
    }

    /** IP телефона в LAN (Wi-Fi). */
    fun lanIp(): String? {
        return try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw == 0) return null
            // ipAddress — little-endian int.
            "${raw and 0xFF}.${(raw shr 8) and 0xFF}.${(raw shr 16) and 0xFF}.${(raw shr 24) and 0xFF}"
        } catch (e: Exception) {
            null
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            try {
                val sock = s.accept()
                thread(isDaemon = true, name = "vlc-proxy-conn") { handle(sock) }
            } catch (e: Exception) {
                if (running) Log.add("proxy", "accept error: ${e.message}")
                break
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeError(sock, 405)
                return
            }
            val headers = HashMap<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) headers[line!!.substring(0, idx).trim().toLowerCase()] =
                    line!!.substring(idx + 1).trim()
            }
            val target = parts[1]
            if (target.startsWith("/subs-master?")) {
                serveSubsMaster(sock, target.substringAfter('?', ""))
                return
            }
            // v0.9.1: /resume (продолжить с того же места), /file*
            // (видеофайл из Telegram/галереи), /memory-vtt (субтитры
            // OpenSubtitles из памяти).
            if (target.startsWith("/resume?")) {
                serveResume(sock, target.substringAfter('?', ""))
                return
            }
            if (target.startsWith("/file")) {
                serveFile(sock, target.substringAfter('?', ""), headers["range"], target.substringBefore('?'))
                return
            }
            if (target.startsWith("/memory-vtt?")) {
                serveMemoryVtt(sock, target.substringAfter('?', ""))
                return
            }
            // v0.9.0: /stream.vtt — дорожка субтитров для sub= (тот же
            // serveStream, расширение .vtt — только для опознания VLC).
            if (!target.startsWith("/stream?") && !target.startsWith("/stream.vtt?")) {
                writeError(sock, 404)
                return
            }
            val params = parseQueryParams(target.substringAfter('?', ""))
            val streamUrl = params["u"]?.firstOrNull() ?: run {
                writeError(sock, 400)
                return
            }
            serveStream(sock, streamUrl, params["r"]?.firstOrNull(), headers["range"])
        } catch (e: Exception) {
            Log.add("proxy", "conn error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Query-строка → карта ключ → список значений (порядок сохраняется, повторы — t/l/n дорожек). */
    private fun parseQueryParams(query: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (part in query.split('&')) {
            val i = part.indexOf('=')
            if (i < 0) continue
            val k = URLDecoder.decode(part.substring(0, i), "UTF-8")
            val v = URLDecoder.decode(part.substring(i + 1), "UTF-8")
            out.getOrPut(k) { ArrayList() }.add(v)
        }
        return out
    }

    /**
     * v0.9.1: upstream-запрос с ручными редиректами (Referer на каждом
     * шаге — как в serveStream). Возвращает подключённое соединение
     * (вызывающий обязан disconnect) или null.
     */
    private fun openUpstream(url: String, referer: String?, range: String? = null): HttpURLConnection? {
        var current = url
        for (hop in 0..5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", PROXY_CHROME_UA)
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            val cookies = runCatching { CookieManager.getInstance().getCookie(current) }.getOrNull()
            if (!cookies.isNullOrBlank()) c.setRequestProperty("Cookie", cookies)
            if (range != null) c.setRequestProperty("Range", range)
            c.connect()
            val code = c.responseCode
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) return null
                current = StreamProxy.resolveUrl(current, loc)
                continue
            }
            return c
        }
        return null
    }

    /**
     * v0.9.1: /resume?u=…&r=…&pos=<секунды> — плейлист, обрезанный
     * до запомненной позиции (ResumePlaylist.trim). Если по u —
     * мастер, берётся его первый вариант. Сегменты переписываются
     * через /stream с тем же Referer.
     */
    private fun serveResume(sock: Socket, query: String) {
        val out: OutputStream = sock.getOutputStream()
        try {
            val params = parseQueryParams(query)
            val streamUrl = params["u"]?.firstOrNull() ?: run {
                writeError(sock, 400)
                return
            }
            val referer = params["r"]?.firstOrNull() ?: ""
            val pos = params["pos"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val ip = phoneIp ?: run {
                writeError(sock, 502)
                return
            }
            var conn = openUpstream(streamUrl, referer) ?: run {
                writeError(sock, 502)
                return
            }
            if (conn.responseCode !in 200..206) {
                conn.disconnect()
                writeError(sock, 502)
                return
            }
            var base = streamUrl
            var text = readCapped(conn.inputStream, 4 * 1024 * 1024).toString(Charsets.UTF_8)
            conn.disconnect()
            if (StreamProbe.isHlsMaster(text)) {
                val variant = text.lines().map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.endsWith(".m3u8") }
                    ?: text.lines().map { it.trim() }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (variant != null) {
                    val vUrl = StreamProxy.resolveUrl(base, variant)
                    conn = openUpstream(vUrl, referer) ?: run {
                        writeError(sock, 502)
                        return
                    }
                    if (conn.responseCode !in 200..206) {
                        conn.disconnect()
                        writeError(sock, 502)
                        return
                    }
                    base = vUrl
                    text = readCapped(conn.inputStream, 4 * 1024 * 1024).toString(Charsets.UTF_8)
                    conn.disconnect()
                }
            }
            val trimmed = ResumePlaylist.trim(text, pos)
            val rewritten = StreamProxy.rewritePlaylist(trimmed.playlist, base) { abs ->
                StreamProxy.proxyStreamUrl(ip, port, abs, referer)
            }
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            val head =
                "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
            Log.add("proxy", "resume: позиция ${pos}с, сегментов ${trimmed.keptSegments}: ${VideoUrls.safeHost(base)}")
        } catch (e: Exception) {
            Log.add("proxy", "resume error: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeError(sock, 502) }
        } finally {
            runCatching { sock.close() }
        }
    }

    /**
     * v0.9.1: /file<ext>?id=… — видеофайл из Telegram/галереи
     * (зарегистрированный FileSource). Range поддерживается
     * (VLC мотает файл), Content-Type — по расширению в пути.
     */
    private fun serveFile(sock: Socket, query: String, rangeHeader: String?, path: String) {
        val out: OutputStream = sock.getOutputStream()
        try {
            val params = parseQueryParams(query)
            val id = params["id"]?.firstOrNull() ?: run {
                writeError(sock, 400)
                return
            }
            val src = synchronized(fileSources) { fileSources[id] } ?: run {
                writeError(sock, 404)
                return
            }
            val total = src.size()
            val range = RangeServe.parseRange(rangeHeader, total)
            val start = range?.start ?: 0L
            val end = range?.end ?: (total - 1)
            val length = end - start + 1
            val contentType = when {
                path.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
                path.endsWith(".webm", ignoreCase = true) -> "video/webm"
                path.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
                path.endsWith(".m4v", ignoreCase = true) -> "video/x-m4v"
                else -> "video/mp4"
            }
            val head = StringBuilder()
            if (range != null) {
                head.append("HTTP/1.1 206 Partial Content\r\n")
                head.append("Content-Range: ${range.contentRange(total)}\r\n")
            } else {
                head.append("HTTP/1.1 200 OK\r\n")
            }
            head.append("Content-Type: $contentType\r\n")
            head.append("Content-Length: $length\r\n")
            head.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            var offset = start
            var remaining = length
            val chunk = 64 * 1024
            while (remaining > 0) {
                val n = minOf(chunk.toLong(), remaining).toInt()
                val data = src.readAt(offset, n) ?: break
                out.write(data)
                offset += data.size
                remaining -= data.size
                if (data.size < n) break
            }
            out.flush()
        } catch (e: Exception) {
            Log.add("proxy", "file error: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeError(sock, 502) }
        } finally {
            runCatching { sock.close() }
        }
    }

    /**
     * v0.9.1: /memory-vtt?id=… — дорожка субтитров из памяти
     * (OpenSubtitles: скачанный .srt → VTT). Для sub= в deep link.
     */
    private fun serveMemoryVtt(sock: Socket, query: String) {
        val out: OutputStream = sock.getOutputStream()
        try {
            val params = parseQueryParams(query)
            val id = params["id"]?.firstOrNull() ?: run {
                writeError(sock, 400)
                return
            }
            val vtt = synchronized(memoryVtts) { memoryVtts[id] } ?: run {
                writeError(sock, 404)
                return
            }
            val bytes = vtt.toByteArray(Charsets.UTF_8)
            val head =
                "HTTP/1.1 200 OK\r\nContent-Type: text/vtt\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.add("proxy", "memory-vtt error: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeError(sock, 502) }
        } finally {
            runCatching { sock.close() }
        }
    }

    /**
     * v0.9.0: синтетический HLS-master с субтитрами.
     * Параметры: u — исходный поток (финальный URL запуска), r — Referer,
     * t/l/n — дорожки (URL, язык, название) в порядке.
     * Мастер не ходит к CDN вообще: исходный URL — как есть, дорожки —
     * через /stream (там же конвертация .srt/.ass в VTT).
     */
    private fun serveSubsMaster(sock: Socket, query: String) {
        val out: OutputStream = sock.getOutputStream()
        try {
            val params = parseQueryParams(query)
            val streamUrl = params["u"]?.firstOrNull() ?: run {
                writeError(sock, 400)
                return
            }
            val referer = params["r"]?.firstOrNull() ?: ""
            val urls = params["t"] ?: emptyList()
            val langs = params["l"] ?: emptyList()
            val names = params["n"] ?: emptyList()
            val preferredLang = params["d"]?.firstOrNull()
            val tracks = ArrayList<SubTrack>()
            for (i in urls.indices) {
                tracks.add(SubTrack(urls[i], langs.getOrNull(i) ?: "", names.getOrNull(i) ?: ""))
            }
            if (tracks.isEmpty()) {
                writeError(sock, 400)
                return
            }
            val ip = phoneIp ?: run {
                writeError(sock, 502)
                return
            }
            val master = Subtitles.buildSubsMaster(streamUrl, tracks, { t ->
                // v0.9.1: дорожка OpenSubtitles (memory-vtt) — уже
                // прокси-URL, без лишнего оборачивания в /stream.
                if (t.url.contains("/memory-vtt?")) t.url
                else StreamProxy.proxyStreamUrl(ip, port, t.url, referer)
            }, preferredLang)
            val bytes = master.toByteArray(Charsets.UTF_8)
            val head =
                "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
            Log.add("proxy", "мастер с субтитрами (${tracks.size} дорожек): ${VideoUrls.safeHost(streamUrl)}")
        } catch (e: Exception) {
            Log.add("proxy", "subs-master error: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeError(sock, 502) }
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun serveStream(sock: Socket, url: String, referer: String?, range: String?) {
        val out: OutputStream = sock.getOutputStream()
        var current = url
        var conn: HttpURLConnection? = null
        try {
            // Редиректы — вручную, с Referer на каждом шаге (Sibnet:
            // /v/...mp4 → 302 на подписанный CDN URL; HttpURLConnection
            // сам Referer на редирект не переносит).
            for (hop in 0..5) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", PROXY_CHROME_UA)
                if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
                val cookies = runCatching { CookieManager.getInstance().getCookie(current) }.getOrNull()
                if (!cookies.isNullOrBlank()) c.setRequestProperty("Cookie", cookies)
                if (range != null) c.setRequestProperty("Range", range)
                c.connect()
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) {
                        writeError(sock, 502)
                        return
                    }
                    current = StreamProxy.resolveUrl(current, loc)
                    continue
                }
                conn = c
                break
            }
            val c = conn ?: run {
                writeError(sock, 502)
                return
            }
            val code = c.responseCode
            if (code !in 200..206) {
                writeError(sock, 502)
                c.disconnect()
                return
            }
            // v0.9.0: дорожки субтитров (.srt/.ass) отдаются через тот же
            // /stream — конвертируются в WebVTT, чтобы VLC их понимал.
            // URL субтитров приходит сюда из мастера, построенного
            // buildSubsMaster (внутри /subs-master). Дорожки не в
            // плейлист-переписывании: subtitle — текстовый ответ, не HLS.
            if (Subtitles.isSubtitleUrl(current)) {
                val raw = try {
                    readCapped(c.inputStream, 4 * 1024 * 1024)
                } catch (e: Exception) {
                    ByteArray(0)
                }
                val text = raw.toString(Charsets.UTF_8)
                // convertIfNeeded: (Content-Type, тело) или null, если
                // конвертация не нужна (VTT — passthrough как есть).
                val converted = Subtitles.convertIfNeeded(current, text)
                val contentType = converted?.first ?: "text/vtt"
                val vtt = converted?.second ?: text
                val bytes = vtt.toByteArray(Charsets.UTF_8)
                val head =
                    "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
                Log.add("proxy", "субтитры ${current.substringAfterLast('/').take(40)} → VTT (${bytes.size} B)")
                return
            }
            val contentType = c.contentType
            if (StreamProxy.isPlaylist(current, contentType)) {
                val body = readCapped(c.inputStream, 4 * 1024 * 1024)
                val text = body.toString(Charsets.UTF_8)
                val ip = phoneIp ?: return
                val ref = referer ?: ""
                val rewritten = StreamProxy.rewritePlaylist(text, current) { abs ->
                    StreamProxy.proxyStreamUrl(ip, port, abs, ref)
                }
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                val head =
                    "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.flush()
                Log.add("proxy", "плейлист переписан (${bytes.size} Б): ${VideoUrls.safeHost(current)}")
            } else {
                // Поток (mp4/сегмент): проброс статуса и заголовков,
                // Range поддерживается (VLC мотает mp4).
                val len = c.contentLengthLong
                val head = StringBuilder()
                head.append("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\n")
                if (!contentType.isNullOrBlank()) head.append("Content-Type: $contentType\r\n")
                if (len >= 0) head.append("Content-Length: $len\r\n")
                val cr = c.getHeaderField("Content-Range")
                if (!cr.isNullOrBlank()) head.append("Content-Range: $cr\r\n")
                head.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
                out.write(head.toString().toByteArray(Charsets.UTF_8))
                c.inputStream.copyTo(out, 64 * 1024)
                out.flush()
            }
        } catch (e: Exception) {
            Log.add("proxy", "serve error ${VideoUrls.safeHost(url)}: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { writeError(sock, 502) }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readCapped(stream: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < cap) {
            val n = stream.read(buf, 0, minOf(buf.size, cap - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun writeError(sock: Socket, code: Int) {
        try {
            val body = "VLC Cast proxy: $code"
            val head =
                "HTTP/1.1 $code Error\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
            val out = sock.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (ignored: Exception) {
        }
    }
}
