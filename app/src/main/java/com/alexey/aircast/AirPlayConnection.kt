package com.alexey.aircast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Parsed HTTP/RTSP response. */
data class AirPlayResponse(val statusCode: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * Single TCP connection to an AirPlay receiver (port 7000) carrying HTTP
 * (pairing, /server-info, /play, /playback-info) and RTSP (SETUP/RECORD/...)
 * requests. After HAP pair-verify, [enableEncryption] installs a [HapSession]
 * so all subsequent traffic is ChaCha20-Poly1305 framed.
 *
 * Transport logic adapted from CenturyPlay's AirPlay2Connection (AGPL-3.0),
 * which mirrors pyatv's support/http.py + support/rtsp.py.
 */
class AirPlayConnection(
    private val host: String,
    private val port: Int,
    private val senderName: String = "AirCast",
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    var localIp: String = "0.0.0.0"
        private set

    private var session: HapSession? = null
    private var plaintext = ByteArray(0)
    private var readPos = 0
    private var cseq = 0

    val sessionId: Int = SecureRandom().nextInt(1 shl 30) + 1
    val dacpId: String = "%016X".format(SecureRandom().nextLong())
    val activeRemote: String = "%08X".format(SecureRandom().nextInt())

    val isEncrypted: Boolean get() = session != null

    fun connect(timeoutMs: Int = 10000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        output = s.getOutputStream()
        input = s.getInputStream()
        localIp = s.localAddress.hostAddress ?: "0.0.0.0"
        Log.add("airplay", "TCP подключено к $host:$port (локальный IP $localIp)")
    }

    fun setReadTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    fun reopen(timeoutMs: Int = 10000) {
        close()
        session = null
        plaintext = ByteArray(0)
        readPos = 0
        cseq = 0
        connect(timeoutMs)
    }

    fun enableEncryption(outKey: ByteArray, inKey: ByteArray) {
        session = HapSession(outKey, inKey)
        Log.add("airplay", "HAP-шифрование канала включено")
    }

    /** Plain HTTP request (used before pairing: /server-info, /pair-*). */
    fun http(method: String, uri: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): AirPlayResponse {
        val hdrs = LinkedHashMap<String, String>()
        hdrs["User-Agent"] = USER_AGENT
        hdrs.putAll(headers)
        return send(method, uri, "HTTP/1.1", hdrs, body)
    }

    fun get(uri: String, headers: Map<String, String> = emptyMap()): AirPlayResponse =
        http("GET", uri, headers)

    fun post(uri: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): AirPlayResponse =
        http("POST", uri, headers, body)

    /**
     * Plain RTSP request: CSeq + User-Agent only, no DACP-ID /
     * Active-Remote / Client-Instance. Used by the mirroring client,
     * which follows doubletake (SETUP / RECORD / TEARDOWN / /feedback /
     * /fp-setup carry exactly these headers). The direct-cast path keeps
     * using exchange().
     */
    fun rtsp(
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
    ): AirPlayResponse {
        val hdrs = LinkedHashMap<String, String>()
        hdrs["CSeq"] = (cseq++).toString()
        hdrs["User-Agent"] = USER_AGENT
        if (contentType != null) hdrs["Content-Type"] = contentType
        hdrs.putAll(headers)
        return send(method, uri, "RTSP/1.0", hdrs, body)
    }

    /** RTSP exchange (adds CSeq, DACP-ID, Active-Remote, Client-Instance). */
    fun exchange(method: String, uri: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, contentType: String? = null): AirPlayResponse {
        val hdrs = LinkedHashMap<String, String>()
        hdrs["CSeq"] = (cseq++).toString()
        hdrs["DACP-ID"] = dacpId
        hdrs["Active-Remote"] = activeRemote
        hdrs["Client-Instance"] = dacpId
        hdrs["User-Agent"] = USER_AGENT
        if (contentType != null) hdrs["Content-Type"] = contentType
        hdrs.putAll(headers)
        return send(method, uri, "RTSP/1.0", hdrs, body)
    }

    @Synchronized
    private fun send(method: String, uri: String, protocol: String, headers: MutableMap<String, String>, body: ByteArray?): AirPlayResponse {
        val sb = StringBuilder()
        sb.append("$method $uri $protocol\r\n")
        if (body != null) headers["Content-Length"] = body.size.toString()
        for ((key, value) in headers) sb.append("$key: $value\r\n")
        sb.append("\r\n")
        val headerBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        val requestBytes = if (body != null) headerBytes + body else headerBytes
        Log.add("airplay", ">> $method $uri ($protocol, ${requestBytes.size} B${if (session != null) ", шифр" else ""})")
        writeRaw(requestBytes)
        val resp = readResponse()
        Log.add("airplay", "<< $method $uri -> HTTP ${resp.statusCode} (${resp.body.size} B)")
        return resp
    }

    private fun writeRaw(data: ByteArray) {
        val out = output ?: throw IllegalStateException("not connected")
        out.write(session?.encrypt(data) ?: data)
        out.flush()
    }

    private fun readResponse(): AirPlayResponse {
        val statusLine = readLine()
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IllegalStateException("malformed status line: $statusLine")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) readExact(contentLength) else ByteArray(0)
        return AirPlayResponse(statusCode, headers, body)
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = readByte()
            if (prev == '\r'.toInt() && b == '\n'.toInt()) {
                val bytes = out.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
            }
            out.write(b)
            prev = b
        }
    }

    private fun readByte(): Int {
        ensurePlaintext(1)
        return plaintext[readPos++].toInt() and 0xFF
    }

    private fun readExact(n: Int): ByteArray {
        ensurePlaintext(n)
        val result = plaintext.copyOfRange(readPos, readPos + n)
        readPos += n
        return result
    }

    private fun ensurePlaintext(n: Int) {
        val inp = input ?: throw IllegalStateException("not connected")
        if (plaintext.size - readPos >= n) return
        if (readPos > 0) {
            plaintext = plaintext.copyOfRange(readPos, plaintext.size)
            readPos = 0
        }
        val buf = ByteArray(8192)
        while (plaintext.size < n) {
            val read = inp.read(buf)
            if (read < 0) throw java.io.EOFException("connection closed by receiver")
            if (read == 0) continue
            val chunk = buf.copyOfRange(0, read)
            val decoded = session?.decrypt(chunk) ?: chunk
            if (decoded.isNotEmpty()) plaintext += decoded
        }
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        const val USER_AGENT = "AirPlay/550.10"
    }
}
