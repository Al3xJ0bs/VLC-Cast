package com.alexey.aircast

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * v0.9.0 — pre-flight проверка потоков (авто-ретрай) + длительность
 * видео (авто-следующая серия).
 *
 * Pre-flight: перед запуском во VLC выбранный URL проверяется
 * (Range GET / GET плейлиста, редиректы вручную с Referer). Мёртвая
 * ссылка (403/404/таймаут) не запускается — движок пробует следующий
 * кандидат, а не показывает пользователю вечную загрузку на ТВ.
 *
 * Длительность: HLS — сумма #EXTINF (через вариант-плейлист, если
 * мастер); mp4 — mvhd из первых байт (Range); DASH — атрибут
 * mediaPresentationDuration. Всё с капом по времени: если длительность
 * не узналась — авто-следующая серия просто не вооружается (честно).
 *
 * Pure Kotlin, no Android imports: тестируется на JVM.
 */
object StreamProbe {
    enum class ProbeResult { OK, HTTP_ERROR, TIMEOUT, ERROR }

    /** Таймаут одного запроса pre-flight (connect/read). */
    const val PROBE_TIMEOUT_MS = 2_000

    /**
     * Проверить, играет ли URL. HLS: GET плейлиста (должен содержать
     * #EXTM3U) + Range GET первого сегмента; mp4/mpd/прочее: Range GET
     * первых байт (200/206). Редиректы — вручную, с Referer и Cookie
     * на каждом шаге (как в StreamProxy: HttpURLConnection сам Referer
     * на редирект не переносит). Range вместо HEAD: прокси на телефоне
     * HEAD не обслуживает (только GET), а Range GET проходит и напрямую,
     * и через прокси.
     */
    fun probeUrl(url: String, referer: String?, cookies: String?): ProbeResult {
        return try {
            val lower = url.toLowerCase()
            val path = lower.substringBefore('?').substringBefore('#')
            if (path.endsWith(".m3u8") || lower.contains(".m3u8")) {
                probeHls(url, referer, cookies)
            } else {
                probeRange(url, referer, cookies)
            }
        } catch (e: SocketTimeoutException) {
            ProbeResult.TIMEOUT
        } catch (e: Exception) {
            ProbeResult.ERROR
        }
    }

    private fun probeHls(url: String, referer: String?, cookies: String?): ProbeResult {
        val playlist = fetchText(url, referer, cookies, 512 * 1024) ?: return ProbeResult.HTTP_ERROR
        if (!playlist.contains("#EXTM3U")) return ProbeResult.HTTP_ERROR
        // Master: проверить вариант (первый #EXT-X-STREAM-INF), а не
        // сам мастер — мёртвый вариант мастер не выдаёт.
        val variants = hlsVariantUrls(playlist)
        if (variants.isNotEmpty()) {
            val variantUrl = VideoUrls.resolveUrl(url, variants[0])
            val variant = fetchText(variantUrl, referer, cookies, 512 * 1024) ?: return ProbeResult.HTTP_ERROR
            if (!variant.contains("#EXTM3U")) return ProbeResult.HTTP_ERROR
            return probeFirstSegment(variant, variantUrl, referer, cookies)
        }
        return probeFirstSegment(playlist, url, referer, cookies)
    }

    /** Range GET первого сегмента из media-плейлиста (или OK, если сегментов нет). */
    private fun probeFirstSegment(playlist: String, baseUrl: String, referer: String?, cookies: String?): ProbeResult {
        for (rawLine in playlist.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val segUrl = VideoUrls.resolveUrl(baseUrl, line)
            return probeRange(segUrl, referer, cookies)
        }
        return ProbeResult.OK
    }

    /** Range GET bytes=0-0: 200/206 — жив, 4xx/5xx — мёртв. */
    fun probeRange(url: String, referer: String?, cookies: String?): ProbeResult {
        return try {
            val c = openConnection(url, referer, cookies)
            c.setRequestProperty("Range", "bytes=0-0")
            c.connect()
            val code = c.responseCode
            // Тело не читаем вообще (1 байт максимум) — Range 0-0.
            runCatching { c.inputStream.close() }
            c.disconnect()
            if (code in 200..206) ProbeResult.OK else ProbeResult.HTTP_ERROR
        } catch (e: SocketTimeoutException) {
            ProbeResult.TIMEOUT
        } catch (e: Exception) {
            ProbeResult.ERROR
        }
    }

    /**
     * v0.9.3 (#5): время первого байта (Range 0-0), мс, или null
     * (ошибка/таймаут/не 2xx). Для пробы скорости зеркал: чем меньше,
     * тем быстрее зеркало отвечает. Читается ровно 1 байт.
     */
    fun probeFirstByteMs(url: String, referer: String?, cookies: String?): Long? {
        return try {
            val start = System.nanoTime()
            val c = openConnection(url, referer, cookies)
            c.setRequestProperty("Range", "bytes=0-0")
            c.connect()
            val code = c.responseCode
            if (code !in 200..206) {
                c.disconnect()
                return null
            }
            c.inputStream.use { it.read() }
            c.disconnect()
            (System.nanoTime() - start) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }

    /**
     * GET текста с капом по размеру. Редиректы — вручную (до 5),
     * с Referer/Cookie на каждом шаге. null — HTTP не 2xx / таймаут /
     * ошибка. Тело читается полностью только до капа.
     */
    fun fetchText(url: String, referer: String?, cookies: String?, capBytes: Int = 1024 * 1024, timeoutMs: Int = 3000, userAgent: String = PROXY_CHROME_UA): String? {
        val bytes = fetchBytes(url, referer, cookies, capBytes, timeoutMs, null, userAgent) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    /** Range GET первых байт (для mvhd mp4). null — HTTP не 2xx/206 / ошибка. */
    fun fetchRange(url: String, referer: String?, cookies: String?, start: Long, len: Int, timeoutMs: Int = 3000): ByteArray? {
        return fetchBytes(url, referer, cookies, len, timeoutMs, "bytes=$start-${start + len - 1}")
    }

    private fun fetchBytes(url: String, referer: String?, cookies: String?, capBytes: Int, timeoutMs: Int, range: String?, userAgent: String = PROXY_CHROME_UA): ByteArray? {
        var current = url
        var hops = 0
        while (hops <= 5) {
            val c: HttpURLConnection
            try {
                c = openConnection(current, referer, cookies, timeoutMs, userAgent)
                if (range != null) c.setRequestProperty("Range", range)
                c.connect()
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank()) return null
                    current = VideoUrls.resolveUrl(current, loc)
                    hops++
                    continue
                }
                if (code !in 200..206) {
                    c.disconnect()
                    return null
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                val stream = c.inputStream
                while (total < capBytes) {
                    val n = stream.read(buf, 0, minOf(buf.size, capBytes - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                stream.close()
                c.disconnect()
                return out.toByteArray()
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun openConnection(url: String, referer: String?, cookies: String?, timeoutMs: Int = PROBE_TIMEOUT_MS, userAgent: String = PROXY_CHROME_UA): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", userAgent)
        if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
        if (!cookies.isNullOrBlank()) c.setRequestProperty("Cookie", cookies)
        return c
    }

    /** Длительность HLS media-плейлиста: сумма #EXTINF (секунды). null — не плейлист. */
    fun hlsDurationSec(playlist: String): Long? {
        var sum = 0.0
        var found = false
        for (rawLine in playlist.lines()) {
            val line = rawLine.trim()
            if (!line.startsWith("#EXTINF:")) continue
            val dur = line.substringAfter("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: continue
            sum += dur
            found = true
        }
        return if (found) sum.toLong() else null
    }

    /** Variant URI из master-плейлиста (строки после #EXT-X-STREAM-INF). */
    fun hlsVariantUrls(playlist: String): List<String> {
        val out = ArrayList<String>()
        var expectUri = false
        for (rawLine in playlist.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                expectUri = true
                continue
            }
            if (expectUri) {
                expectUri = false
                if (line.isNotEmpty() && !line.startsWith("#")) out.add(line)
            }
        }
        return out
    }

    /** Это master-плейлист (есть #EXT-X-STREAM-INF). */
    fun isHlsMaster(playlist: String): Boolean =
        playlist.lines().any { it.trim().startsWith("#EXT-X-STREAM-INF") }

    /**
     * Длительность mp4 по первым байтам файла: ищем box "mvhd".
     * v0: timescale u32 @ +12, duration u32 @ +16 (от начала тела box);
     * v1: timescale u32 @ +20, duration u64 @ +24. null — mvhd не найден.
     */
    fun mp4DurationSec(bytes: ByteArray): Long? {
        var i = 0
        while (i + 8 <= bytes.size) {
            if (bytes[i + 4] == 'm'.toByte() && bytes[i + 5] == 'v'.toByte() &&
                bytes[i + 6] == 'h'.toByte() && bytes[i + 7] == 'd'.toByte()
            ) {
                val body = i + 8
                if (body >= bytes.size) return null
                val version = bytes[body].toInt() and 0xFF
                return try {
                    if (version == 1) {
                        if (body + 32 > bytes.size) return null
                        val timescale = readU32(bytes, body + 20)
                        val duration = readU64(bytes, body + 24)
                        if (timescale <= 0) null else duration / timescale
                    } else {
                        if (body + 20 > bytes.size) return null
                        val timescale = readU32(bytes, body + 12)
                        val duration = readU32(bytes, body + 16)
                        if (timescale <= 0) null else duration / timescale
                    }
                } catch (e: Exception) {
                    null
                }
            }
            i++
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 0..3) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 0..7) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return v
    }

    /**
     * Длительность DASH по mediaPresentationDuration="PT1H2M3.5S".
     * null — атрибут не найден / не парсится.
     */
    fun dashDurationSec(mpd: String): Long? {
        val m = Regex("""mediaPresentationDuration\s*=\s*"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?"""").find(mpd) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: 0L
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 3600 + min * 60 + sec.toLong()
        return if (total > 0) total else null
    }

    /**
     * Длительность видео по URL (для авто-следующей серии): HLS —
     * через вариант-плейлист, mp4 — mvhd, mpd — PT-атрибут.
     * null — не узналась (тогда авто-следующая серия не вооружается).
     */
    fun fetchDurationSec(url: String, referer: String?, cookies: String?, timeoutMs: Int = 4000): Long? {
        return try {
            val lower = url.toLowerCase()
            val path = lower.substringBefore('?').substringBefore('#')
            when {
                path.endsWith(".m3u8") || lower.contains(".m3u8") -> {
                    val playlist = fetchText(url, referer, cookies, 512 * 1024, timeoutMs) ?: return null
                    if (isHlsMaster(playlist)) {
                        val variants = hlsVariantUrls(playlist)
                        if (variants.isEmpty()) return null
                        val variant = fetchText(VideoUrls.resolveUrl(url, variants[0]), referer, cookies, 512 * 1024, timeoutMs)
                            ?: return null
                        hlsDurationSec(variant)
                    } else {
                        hlsDurationSec(playlist)
                    }
                }
                path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".m4a") -> {
                    val head = fetchRange(url, referer, cookies, 0, 128 * 1024, timeoutMs) ?: return null
                    mp4DurationSec(head)
                }
                // v0.9.2: mp3 — Xing/CBR-оценка по первым 16 КБ и
                // общему размеру (см. fetchMp3DurationSec).
                path.endsWith(".mp3") -> fetchMp3DurationSec(url, referer, cookies, timeoutMs)
                path.endsWith(".mpd") -> {
                    val mpd = fetchText(url, referer, cookies, 512 * 1024, timeoutMs) ?: return null
                    dashDurationSec(mpd)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v0.9.2: длительность mp3-ссылки без скачивания всего файла:
     * Range 0–16383 + общий размер из Content-Range/Content-Length →
     * AudioCast.mp3DurationSec (Xing или CBR-оценка). Ошибка сети —
     * null, честно.
     */
    fun fetchMp3DurationSec(url: String, referer: String? = null, cookies: String? = null, timeoutMs: Int = 4000): Long? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Range", "bytes=0-16383")
            conn.setRequestProperty("User-Agent", PROXY_CHROME_UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            if (cookies != null) conn.setRequestProperty("Cookie", cookies)
            conn.connect()
            if (conn.responseCode !in listOf(200, 206)) return null
            val head = conn.inputStream.readBytes()
            // Без общего размера CBR-оценка невозможна (Xing — тоже
            // из head, но размер нужен сигнатуре) — null, честно.
            val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?: return null
            AudioCast.mp3DurationSec(head, total)
        } catch (e: Exception) {
            null
        }
    }
}

/** Тот же UA, что у фонового WebView (CastEngine) и прокси. */
private const val PROXY_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 16; RMX5011) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/**
 * v0.9.3 (#25 + хотфикс Instagram): десктопный Chrome UA для
 * fallback-проходов — прямой HTTP-fetch HTML и перезагрузка страницы
 * в WebView, когда мобильный UA видео не дал. Instagram голому
 * WebView с мобильным UA отдаёт страницу входа без og:video.
 */
const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
