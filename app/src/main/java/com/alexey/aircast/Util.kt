package com.alexey.aircast

import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/**
 * Timestamped in-memory + file log. Everything the app does with AirPlay,
 * pairing, Kodik extraction, VLC and mirroring is logged here verbatim — no silent
 * failures. The Diagnostics screen shows it and can export it.
 */
object Log {
    data class Entry(val time: String, val tag: String, val msg: String)

    private val entries = ArrayList<Entry>()
    private const val MAX = 2000
    private var file: java.io.File? = null

    @Synchronized
    fun init(dir: java.io.File) {
        try {
            file = java.io.File(dir, "aircast.log").also { if (!it.exists()) it.createNewFile() }
        } catch (e: Exception) {
            // logging must never crash the app
        }
    }

    @Synchronized
    fun add(tag: String, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val e = Entry(t, tag, msg)
        entries.add(e)
        if (entries.size > MAX) entries.removeAt(0)
        try {
            file?.appendText("$t [$tag] $msg\n")
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    fun text(): String = entries.joinToString("\n") { "${it.time} [${it.tag}] ${it.msg}" }

    /** Файл лога (aircast.log в filesDir) — для «Поделиться логом» (v0.9.0). */
    @Synchronized
    fun logFile(): java.io.File? = file

    @Synchronized
    fun clear() {
        entries.clear()
        try { file?.writeText("") } catch (ignored: Exception) { }
    }
}

/** TLV8 encode/decode (HAP). Values >255 bytes are split into 255-byte chunks. */
class Tlv8 {
    private val items = LinkedHashMap<Int, ByteArray>()

    fun add(type: Int, value: ByteArray): Tlv8 {
        items[type] = value
        return this
    }

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            var off = 0
            do {
                val chunk = minOf(255, value.size - off)
                out.write(type)
                out.write(chunk)
                out.write(value, off, chunk)
                off += chunk
            } while (off < value.size)
        }
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): Map<Int, ByteArray> {
            val out = LinkedHashMap<Int, ByteArray>()
            var i = 0
            while (i + 1 < data.size) {
                val type = data[i].toInt() and 0xFF
                val len = data[i + 1].toInt() and 0xFF
                if (i + 2 + len > data.size) break
                val chunk = data.copyOfRange(i + 2, i + 2 + len)
                val prev = out[type]
                out[type] = if (prev == null) chunk else prev + chunk
                i += 2 + len
            }
            return out
        }
    }
}

/**
 * HKDF-SHA512 (RFC 5869), matching pyatv's hkdf_expand(salt, info, ikm) and
 * CenturyPlay's Hkdf.expand. Implemented on javax.crypto.Mac (no BouncyCastle).
 */
object Hkdf {
    fun expand(salt: String, info: String, ikm: ByteArray, length: Int = 32): ByteArray =
        expand(salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8), ikm, length)

    fun expand(salt: ByteArray, info: ByteArray, ikm: ByteArray, length: Int = 32): ByteArray {
        val saltBytes = salt
        val infoBytes = info
        // Extract: PRK = HMAC-SHA512(salt, IKM) (zero salt if empty)
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec(if (saltBytes.isEmpty()) ByteArray(64) else saltBytes, "HmacSHA512"))
        val prk = mac.doFinal(ikm)
        // Expand: T(1) = HMAC(PRK, info || 0x01), T(n) = HMAC(PRK, T(n-1) || info || n)
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val m2 = javax.crypto.Mac.getInstance("HmacSHA512")
            m2.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA512"))
            m2.update(prev)
            m2.update(infoBytes)
            m2.update(counter.toByte())
            prev = m2.doFinal()
            out.write(prev)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}
