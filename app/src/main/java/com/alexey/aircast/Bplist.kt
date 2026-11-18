package com.alexey.aircast

import java.io.ByteArrayOutputStream

/**
 * Minimal binary plist (bplist00) writer + reader, no external dependency.
 * Supports dict, array, string (ASCII/UTF-16), data, int, double, bool.
 * Used for AirPlay RTSP/HTTP bodies: /server-info, SETUP, /play, /playback-info.
 * Verified against Python plistlib fixtures in tests/TestMain.kt.
 */
object Bplist {
    const val CONTENT_TYPE = "application/x-apple-binary-plist"
    private const val TRAILER_LEN = 32

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        val indexOf = HashMap<Any?, Int>()
        fun intern(v: Any?): Int {
            // Strings/numbers dedup by value via map; containers by identity is fine
            // (we only intern scalars).
            return when (v) {
                is Map<*, *>, is List<*> -> {
                    objects.add(v)
                    objects.size - 1
                }
                else -> {
                    val existing = indexOf[v]
                    if (existing != null) existing else {
                        objects.add(v)
                        indexOf[v] = objects.size - 1
                        objects.size - 1
                    }
                }
            }
        }
        // First pass: assign indices in traversal order (root first).
        fun walk(v: Any?) {
            intern(v)
            when (v) {
                is Map<*, *> -> for ((k, vv) in v) { walk(k.toString()); walk(vv) }
                is List<*> -> for (vv in v) walk(vv)
            }
        }
        walk(root)
        val refSize = if (objects.size < 256) 1 else 2
        val offsets = IntArray(objects.size)
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        for (i in objects.indices) {
            offsets[i] = out.size()
            writeObject(out, objects[i], refSize, intern = { o ->
                // child refs must already be interned by walk(); find index
                when (o) {
                    is Map<*, *>, is List<*> -> objects.indexOf(o)
                    else -> indexOf[o] ?: error("uninited object")
                }
            })
        }
        val offsetTableOffset = out.size()
        val offsetSize = bytesNeeded(offsets.maxOrNull() ?: 0)
        for (off in offsets) writeIntBE(out, off.toLong(), offsetSize)
        // Trailer
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        writeLongBE(out, objects.size.toLong())
        writeLongBE(out, 0L) // top object
        writeLongBE(out, offsetTableOffset.toLong())
        return out.toByteArray()
    }

    private fun bytesNeeded(v: Int): Int = when {
        v < 256 -> 1
        v < 65536 -> 2
        v < 16777216 -> 4
        else -> 8
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun writeLongBE(out: ByteArrayOutputStream, v: Long) = writeIntBE(out, v, 8)

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, info: Int, count: Int) {
        if (count < 15) {
            out.write((type shl 4) or count)
        } else {
            out.write((type shl 4) or 0xF)
            writeObject(out, count.toLong(), 1) { 0 }
        }
    }

    private fun writeObject(out: ByteArrayOutputStream, v: Any?, refSize: Int, intern: (Any?) -> Int = { 0 }) {
        when (v) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (v) 0x09 else 0x08)
            is Int -> writeIntValue(out, v.toLong())
            is Long -> writeIntValue(out, v)
            is Double -> {
                out.write(0x23) // real, 8 bytes
                val bits = java.lang.Double.doubleToRawLongBits(v)
                writeIntBE(out, bits, 8)
            }
            is Float -> {
                out.write(0x23)
                writeIntBE(out, java.lang.Double.doubleToRawLongBits(v.toDouble()), 8)
            }
            is ByteArray -> {
                writeMarker(out, 0x4, 0, v.size)
                out.write(v)
            }
            is String -> {
                val ascii = v.all { it.toInt() < 128 }
                if (ascii) {
                    writeMarker(out, 0x5, 0, v.length)
                    out.write(v.toByteArray(Charsets.US_ASCII))
                } else {
                    writeMarker(out, 0x6, 0, v.length)
                    out.write(v.toByteArray(Charsets.UTF_16BE))
                }
            }
            is List<*> -> {
                writeMarker(out, 0xA, 0, v.size)
                for (item in v) writeRef(out, intern(item), refSize)
            }
            is Map<*, *> -> {
                writeMarker(out, 0xD, 0, v.size)
                for ((k, _) in v) writeRef(out, intern(k.toString()), refSize)
                for ((_, vv) in v) writeRef(out, intern(vv), refSize)
            }
            else -> {
                val s = v.toString()
                writeMarker(out, 0x5, 0, s.length)
                out.write(s.toByteArray(Charsets.US_ASCII))
            }
        }
    }

    private fun writeRef(out: ByteArrayOutputStream, idx: Int, refSize: Int) = writeIntBE(out, idx.toLong(), refSize)

    private fun writeIntValue(out: ByteArrayOutputStream, v: Long) {
        when {
            v in -128..127 -> { out.write(0x10); writeIntBE(out, v, 1) }
            v in -32768..32767 -> { out.write(0x11); writeIntBE(out, v, 2) }
            v in -2147483648..2147483647 -> { out.write(0x12); writeIntBE(out, v, 4) }
            else -> { out.write(0x13); writeIntBE(out, v, 8) }
        }
    }

    // ---- Reader ----

    fun decode(data: ByteArray): Any? {
        if (data.size < TRAILER_LEN + 8 || String(data, 0, 8, Charsets.US_ASCII) != "bplist00") {
            throw IllegalArgumentException("not a bplist00 document (${data.size} bytes)")
        }
        val trailer = data.copyOfRange(data.size - TRAILER_LEN, data.size)
        val offsetSize = trailer[6].toInt() and 0xFF
        val refSize = trailer[7].toInt() and 0xFF
        val numObjects = readLongBE(trailer, 8)
        val topObject = readLongBE(trailer, 16).toInt()
        val offsetTableOffset = readLongBE(trailer, 24).toInt()
        val offsets = IntArray(numObjects.toInt()) { i ->
            readUIntBE(data, offsetTableOffset + i * offsetSize, offsetSize)
        }
        val cache = HashMap<Int, Any?>()
        fun readObj(idx: Int): Any? {
            cache[idx]?.let { return it }
            val off = offsets[idx]
            val marker = data[off].toInt() and 0xFF
            val type = marker shr 4
            var info = marker and 0x0F
            var pos = off + 1
            if (info == 0xF) {
                val lenObj = readObjAt(data, offsets, refSize, cache, pos)
                info = (lenObj as? Long)?.toInt() ?: (lenObj as? Int ?: 0)
                pos = pos + intObjSize(data, pos)
            }
            val result: Any? = when (type) {
                0x0 -> when (info) {
                    0x0 -> null
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> {
                    val nBytes = 1 shl info
                    if (nBytes == 8) readLongBE(data, pos) else readIntBE(data, pos, nBytes).toLong()
                }
                0x2 -> {
                    val bytes = if (info == 2) 4 else 8
                    val bits = if (bytes == 4) readIntBE(data, pos, 4).toLong() shl 32 else readLongBE(data, pos)
                    if (bytes == 4) java.lang.Float.intBitsToFloat(readIntBE(data, pos, 4)).toDouble()
                    else java.lang.Double.longBitsToDouble(bits)
                }
                0x3 -> {
                    val bits = readLongBE(data, pos)
                    java.lang.Double.longBitsToDouble(bits) // date: seconds since 2001-01-01
                }
                0x4 -> data.copyOfRange(pos, pos + info)
                0x5 -> String(data, pos, info, Charsets.US_ASCII)
                0x6 -> String(data, pos, info * 2, Charsets.UTF_16BE)
                0x8 -> readIntBE(data, pos, 1) // UID
                0xA -> {
                    val list = ArrayList<Any?>()
                    for (i in 0 until info) list.add(readObj(readUIntBE(data, pos + i * refSize, refSize)))
                    list
                }
                0xD -> {
                    val map = LinkedHashMap<String, Any?>()
                    val keys = (0 until info).map { readUIntBE(data, pos + it * refSize, refSize) }
                    val vals = (0 until info).map { readUIntBE(data, pos + (info + it) * refSize, refSize) }
                    for (i in 0 until info) {
                        val k = readObj(keys[i])?.toString() ?: ""
                        map[k] = readObj(vals[i])
                    }
                    map
                }
                else -> null
            }
            cache[idx] = result
            return result
        }
        return readObj(topObject)
    }

    private fun readObjAt(data: ByteArray, offsets: IntArray, refSize: Int, cache: HashMap<Int, Any?>, pos: Int): Any? {
        // Length is stored as a following int object (marker 0x1n); decode inline.
        val marker = data[pos].toInt() and 0xFF
        val info = marker and 0x0F
        return readUIntBE(data, pos + 1, 1 shl info).toLong()
    }

    private fun intObjSize(data: ByteArray, pos: Int): Int {
        val info = (data[pos].toInt() and 0xFF) and 0x0F
        return 1 + (1 shl info)
    }

    private fun readUIntBE(data: ByteArray, pos: Int, bytes: Int): Int {
        var v = 0
        for (i in 0 until bytes) v = (v shl 8) or (data[pos + i].toInt() and 0xFF)
        return v
    }

    private fun readIntBE(data: ByteArray, pos: Int, bytes: Int): Int {
        // v0.2.0: БЫЛ БАГ двойного знакового расширения для 2-байтовых int.
        // Старый код делал (data[pos].toInt() shl 8): Byte 0xC0 в Kotlin —
        // это -64, и сдвиг уже давал знаковое расширение (0xFFFFC000);
        // затем ещё раз вычиталось 0x10000. Итог: eventPort 0xC0A1 (49313)
        // из ответа SETUP читался как -81759, и event channel не открывался.
        // Теперь чтение знаково-корректно (-16223 для 0xC0A1), а порт
        // как uint16 восстанавливает portFromBplist()
        // (-16223 → 49313).
        var v = 0
        for (i in 0 until bytes) v = (v shl 8) or (data[pos + i].toInt() and 0xFF)
        return when (bytes) {
            1 -> if (v and 0x80 != 0) v - 0x100 else v
            2 -> if (v and 0x8000 != 0) v - 0x10000 else v
            else -> v
        }
    }

    private fun readLongBE(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        return v
    }

    /** Human-readable dump of a decoded plist (for Diagnostics). */
    fun describe(v: Any?, depth: Int = 0): String {
        val pad = "  ".repeat(depth)
        return when (v) {
            is Map<*, *> -> v.entries.joinToString("\n") { (k, vv) ->
                if (vv is Map<*, *> || vv is List<*>) "$pad$k:\n${describe(vv, depth + 1)}"
                else "$pad$k = ${describe(vv, depth + 1).trim()}"
            }
            is List<*> -> v.joinToString("\n") { describe(it, depth) }
            is ByteArray -> "data[${v.size}B] ${v.take(16).joinToString("") { "%02x".format(it) }}${if (v.size > 16) "…" else ""}"
            null -> "null"
            else -> v.toString()
        }
    }
}
