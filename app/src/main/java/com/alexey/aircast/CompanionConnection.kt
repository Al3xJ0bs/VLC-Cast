package com.alexey.aircast

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Companion session cipher: ChaCha20-Poly1305 with a 12-byte counter nonce
 * where the counter sits in the FIRST 8 bytes (little-endian) and the last
 * 4 bytes are zero — pyatv/support/chacha20.py Chacha20Cipher with
 * nonce_length=12 (counter.to_bytes(12, "little")).
 *
 * This DIFFERS from the HAP convention used elsewhere in the app
 * (Crypto.kt ChaCha20Poly1305: 4 zero bytes + counter in the LAST 8 bytes,
 * i.e. Chacha20Cipher8byteNonce). Do not reuse that class here: a session
 * encrypted with the wrong nonce layout fails the Poly1305 tag on the
 * first frame. Separate in/out counters, both starting at 0; the nonce is
 * never sent on the wire; the 4-byte frame header is the AAD; the 16-byte
 * tag is appended to the ciphertext and counted in the header length.
 */
class CompanionSessionCipher(outKey: ByteArray, inKey: ByteArray) {
    private val outKeySpec = SecretKeySpec(outKey, "ChaCha20")
    private val inKeySpec = SecretKeySpec(inKey, "ChaCha20")
    private var outCounter = 0L
    private var inCounter = 0L

    fun encrypt(data: ByteArray, aad: ByteArray): ByteArray {
        val nonce = counterNonce(outCounter++)
        return run(Cipher.ENCRYPT_MODE, outKeySpec, nonce, data, aad)
    }

    fun decrypt(data: ByteArray, aad: ByteArray): ByteArray {
        val nonce = counterNonce(inCounter++)
        return run(Cipher.DECRYPT_MODE, inKeySpec, nonce, data, aad)
    }

    private fun run(mode: Int, key: SecretKeySpec, nonce: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(mode, key, IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    companion object {
        /** Counter in the FIRST 8 bytes LE, zeros in the last 4. */
        fun counterNonce(counter: Long): ByteArray {
            val nonce = ByteArray(12)
            var c = counter
            for (i in 0 until 8) {
                nonce[i] = (c and 0xFF).toByte()
                c = c ushr 8
            }
            return nonce
        }
    }
}

/** Frame types — pyatv/protocols/companion/connection.py FrameType. */
object CompanionFrameType {
    const val NO_OP = 1
    const val PS_START = 3
    const val PS_NEXT = 4
    const val PV_START = 5
    const val PV_NEXT = 6
    const val U_OPACK = 7
    const val E_OPACK = 8
    const val P_OPACK = 9
    const val SESSION_START_REQUEST = 16
    const val SESSION_START_RESPONSE = 17
    const val SESSION_DATA = 18
}

/**
 * Raw Companion TCP connection (pyatv/protocols/companion/connection.py
 * CompanionConnection). Framing: 4-byte header [type:1B][payload length:3B
 * big-endian]; the length INCLUDES the 16-byte auth tag when encrypted.
 * Reception waits for >=4 bytes, total = 4 + int(header[1:4], BE).
 * Empty payloads are never encrypted and get no tag.
 * Pure Kotlin + java.net.Socket; blocking reads with soTimeout.
 */
class CompanionConnection(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var cipher: CompanionSessionCipher? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(timeoutMs: Int = 10_000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        output = s.getOutputStream()
        input = s.getInputStream()
        Log.add("companion", "TCP подключено к $host:$port")
    }

    fun setReadTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    fun enableEncryption(outKey: ByteArray, inKey: ByteArray) {
        cipher = CompanionSessionCipher(outKey, inKey)
        Log.add("companion", "шифрование сессии Companion включено")
    }

    /** Send one frame (type + OPACK payload). */
    fun sendFrame(type: Int, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("not connected")
        var data = payload
        var payloadLength = payload.size
        if (cipher != null && payload.isNotEmpty()) {
            // Header is computed BEFORE encryption (length includes the tag)
            // and is the AAD — build it first, then encrypt with it.
            val header = frameHeader(type, payloadLength + AUTH_TAG_LENGTH)
            data = cipher!!.encrypt(payload, header)
            out.write(header)
            out.write(data)
            out.flush()
            return
        }
        out.write(frameHeader(type, payloadLength))
        out.write(data)
        out.flush()
    }

    /** Read one frame; returns (type, decrypted payload). Blocking. */
    fun readFrame(): Pair<Int, ByteArray> {
        val header = readExact(HEADER_LENGTH)
        val type = header[0].toInt() and 0xFF
        val payloadLength = ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (payloadLength < 0 || payloadLength > 16 * 1024 * 1024) {
            throw IllegalStateException("bad Companion frame length $payloadLength (type $type)")
        }
        val payload = if (payloadLength > 0) readExact(payloadLength) else ByteArray(0)
        val plain = if (cipher != null && payload.isNotEmpty()) cipher!!.decrypt(payload, header) else payload
        return Pair(type, plain)
    }

    private fun readExact(n: Int): ByteArray {
        val inp = input ?: throw IllegalStateException("not connected")
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = inp.read(out, off, n - off)
            if (read < 0) throw EOFException("connection closed by receiver ($off/$n bytes)")
            off += read
        }
        return out
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        cipher = null
    }

    companion object {
        const val HEADER_LENGTH = 4
        const val AUTH_TAG_LENGTH = 16

        fun frameHeader(type: Int, payloadLength: Int): ByteArray = byteArrayOf(
            type.toByte(),
            ((payloadLength shr 16) and 0xFF).toByte(),
            ((payloadLength shr 8) and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte(),
        )
    }
}
