package com.alexey.aircast

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * OPACK serialization (pyatv/support/opack.py, 0.18.0) — the binary format
 * of the Companion protocol (port 49153). Pure Kotlin, no Android imports,
 * so it is unit-testable on the JVM (tests/OpackTest.kt).
 *
 * Value model (mirrors the Python types pyatv packs):
 *   null -> None, Boolean, java.util.UUID, Long/Int (non-negative int),
 *   Double/Float (float64 only), String (UTF-8), ByteArray (raw bytes),
 *   List<Any?>, Map<Any?, Any?> (insertion order — LinkedHashMap).
 *   [SizedInt] forces an int of a fixed byte size (pyatv _sized_int).
 *
 * Rules copied from opack.py, including its quirks:
 * - All integers/lengths little-endian; int 0..0x27 is a single byte
 *   (value+8, 0x08..0x2F); larger ints are 0x30+1B / 0x31+2B / 0x32+4B /
 *   0x33+8B.
 * - Floats are packed ONLY as float64 (0x36 + <d); unpack also accepts
 *   float32 (0x35).
 * - Deduplication is by the PACKED BYTES of a value: if those bytes are
 *   already in the object list, a reference is emitted instead
 *   (index <0x21 -> 0xA0+index, else 0xC1/0xC2/0xC3/0xC4 + index LE).
 *   One-byte encodings (bool/None/small int) are never added to the list.
 * - Lists/dicts with >=15 entries use the endless form (0xDF/0xEF)
 *   terminated by 0x03.
 * - NEGATIVE ints are a pyatv bug (pack(-1) -> 0x07, unpack crashes):
 *   this implementation encodes them as float64 instead of repeating it.
 * - Absolute time (0x06) is not packed; unpack reads it as a Long,
 *   like pyatv's dummy implementation.
 *
 * KNOWN pyatv INCONSISTENCY, mirrored deliberately, not fixed: pack
 * appends a list/dict's own packed bytes to the object table, unpack
 * does NOT append the decoded list/dict — so the tables skew after the
 * first container, and a dedup reference to an entry created after a
 * container decodes to the wrong value (or crashes). Proven against
 * pyatv 0.18.0 itself: pack([["a"], "b", "b"]) = d3 d1 41 61 41 62 a2,
 * and pyatv unpack() of those very bytes raises IndexError. This codec
 * mirrors pyatv on BOTH sides (pack with containers, unpack without),
 * so its wire bytes are byte-identical to pyatv in both directions
 * (see opack-golden-bytes-pyatv) — "fixing" one side would break
 * interop with pyatv-packed bytes (e.g. the mock, and pyatv's own
 * test_unpack_endless_array vector, which requires the no-container
 * unpack table). No real Companion payload hits the pattern: in every
 * message this client sends or expects (_systemInfo, _touchStart,
 * _sessionStart, _launchApp, and their responses) all repeated values
 * are either single-byte encodings (never tabled) or references to
 * pre-container entries. A reference that lands outside the table
 * throws OpackException (clean failure), never IndexOutOfBounds.
 */
object Opack {

    class OpackException(message: String) : Exception(message)

    /** An int with a forced encoded size (1, 2, 4 or 8 bytes) — pyatv _sized_int. */
    class SizedInt(val value: Long, val size: Int) {
        init {
            require(size == 1 || size == 2 || size == 4 || size == 8) { "SizedInt size must be 1, 2, 4 or 8, got $size" }
            require(value >= 0) { "SizedInt value must be non-negative, got $value" }
        }

        override fun equals(other: Any?): Boolean =
            other is SizedInt && value == other.value && size == other.size

        override fun hashCode(): Int = 31 * value.hashCode() + size
        override fun toString(): String = "SizedInt($value, $size)"
    }

    // ---- pack ----

    fun pack(value: Any?): ByteArray = packInto(value, ArrayList())

    private fun packInto(value: Any?, objectList: ArrayList<ByteArray>): ByteArray {
        val packed: ByteArray = when (value) {
            null -> byteArrayOf(0x04)
            is Boolean -> byteArrayOf(if (value) 0x01 else 0x02)
            is UUID -> byteArrayOf(0x05) + uuidBytes(value)
            is SizedInt -> sizedIntBytes(value.value, value.size)
            is Int -> packLong(value.toLong())
            is Long -> packLong(value)
            is Float -> packDouble(value.toDouble())
            is Double -> packDouble(value)
            is String -> packString(value.toByteArray(Charsets.UTF_8))
            is ByteArray -> packBytes(value)
            is List<*> -> {
                val out = ByteArrayOutputStream2()
                out.write(0xD0 + minOf(value.size, 0xF))
                for (item in value) out.write(packInto(item, objectList))
                if (value.size >= 0xF) out.write(0x03)
                out.toByteArray()
            }
            is Map<*, *> -> {
                val out = ByteArrayOutputStream2()
                out.write(0xE0 + minOf(value.size, 0xF))
                for ((k, v) in value) {
                    out.write(packInto(k, objectList))
                    out.write(packInto(v, objectList))
                }
                if (value.size >= 0xF) out.write(0x03)
                out.toByteArray()
            }
            else -> throw OpackException("cannot pack ${value.javaClass.name}")
        }
        // Dedup by packed bytes (pyatv _pack tail): a repeated value is a
        // reference into the object list; single-byte encodings are never
        // added to the list (and can never match a stored multi-byte entry).
        val idx = objectList.indexOfFirst { it.contentEquals(packed) }
        if (idx >= 0) {
            return when {
                idx < 0x21 -> byteArrayOf((0xA0 + idx).toByte())
                idx <= 0xFF -> byteArrayOf(0xC1.toByte(), idx.toByte())
                idx <= 0xFFFF -> byteArrayOf(0xC2.toByte(), (idx and 0xFF).toByte(), ((idx shr 8) and 0xFF).toByte())
                idx <= 0xFFFFFFFFL -> byteArrayOf(0xC3.toByte()) + leBytes(idx.toLong(), 4)
                else -> byteArrayOf(0xC4.toByte()) + leBytes(idx.toLong(), 8)
            }
        }
        if (packed.size > 1) objectList.add(packed)
        return packed
    }

    /** Negative ints have no OPACK encoding in pyatv (pack(-1) -> 0x07, a
     *  broken tag) — encode them as float64 instead. */
    private fun packLong(v: Long): ByteArray =
        if (v < 0) packDouble(v.toDouble())
        else when {
            v < 0x28 -> byteArrayOf((v + 8).toByte())
            v <= 0xFF -> byteArrayOf(0x30) + leBytes(v, 1)
            v <= 0xFFFF -> byteArrayOf(0x31) + leBytes(v, 2)
            v <= 0xFFFFFFFFL -> byteArrayOf(0x32) + leBytes(v, 4)
            else -> byteArrayOf(0x33) + leBytes(v, 8)
        }

    private fun sizedIntBytes(v: Long, size: Int): ByteArray =
        byteArrayOf((0x30 + when (size) { 1 -> 0; 2 -> 1; 4 -> 2; else -> 3 }).toByte()) + leBytes(v, size)

    private fun packDouble(v: Double): ByteArray {
        val buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x36.toByte())
        buf.putDouble(v)
        return buf.array()
    }

    private fun packString(bytes: ByteArray): ByteArray = when {
        bytes.size <= 0x20 -> byteArrayOf((0x40 + bytes.size).toByte()) + bytes
        bytes.size <= 0xFF -> byteArrayOf(0x61) + leBytes(bytes.size.toLong(), 1) + bytes
        bytes.size <= 0xFFFF -> byteArrayOf(0x62) + leBytes(bytes.size.toLong(), 2) + bytes
        bytes.size <= 0xFFFFFF -> byteArrayOf(0x63) + leBytes(bytes.size.toLong(), 3) + bytes
        else -> byteArrayOf(0x64) + leBytes(bytes.size.toLong(), 4) + bytes
    }

    private fun packBytes(bytes: ByteArray): ByteArray = when {
        bytes.size <= 0x20 -> byteArrayOf((0x70 + bytes.size).toByte()) + bytes
        bytes.size <= 0xFF -> byteArrayOf(0x91.toByte()) + leBytes(bytes.size.toLong(), 1) + bytes
        bytes.size <= 0xFFFF -> byteArrayOf(0x92.toByte()) + leBytes(bytes.size.toLong(), 2) + bytes
        bytes.size <= 0xFFFFFFFFL -> byteArrayOf(0x93.toByte()) + leBytes(bytes.size.toLong(), 4) + bytes
        else -> byteArrayOf(0x94.toByte()) + leBytes(bytes.size.toLong(), 8) + bytes
    }

    private fun leBytes(v: Long, n: Int): ByteArray =
        ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun uuidBytes(u: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(u.mostSignificantBits)
        buf.putLong(u.leastSignificantBits)
        return buf.array()
    }

    // ---- unpack ----

    /** Unpack one value; returns (value, remaining bytes) like pyatv unpack(). */
    fun unpack(data: ByteArray): Pair<Any?, ByteArray> {
        val parser = Parser(data)
        val value = parser.parseValue()
        return Pair(value, data.copyOfRange(parser.pos, data.size))
    }

    private class Parser(val data: ByteArray) {
        var pos = 0
        val objectList = ArrayList<Any?>()

        fun parseValue(): Any? {
            if (pos >= data.size) throw OpackException("truncated OPACK at $pos")
            val tag = data[pos].toInt() and 0xFF
            var addToList = true
            val value: Any?
            when {
                tag == 0x01 -> { value = true; pos++; addToList = false }
                tag == 0x02 -> { value = false; pos++; addToList = false }
                tag == 0x04 -> { value = null; pos++; addToList = false }
                tag == 0x05 -> {
                    requireBytes(17)
                    val buf = ByteBuffer.wrap(data, pos + 1, 16).order(ByteOrder.BIG_ENDIAN)
                    value = UUID(buf.long, buf.long)
                    pos += 17
                }
                tag == 0x06 -> { // absolute time: pyatv dummy — integer only
                    requireBytes(9)
                    value = leLong(pos + 1, 8)
                    pos += 9
                }
                tag in 0x08..0x2F -> { value = (tag - 8).toLong(); pos++; addToList = false }
                tag == 0x35 -> {
                    requireBytes(5)
                    value = ByteBuffer.wrap(data, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
                    pos += 5
                }
                tag == 0x36 -> {
                    requireBytes(9)
                    value = ByteBuffer.wrap(data, pos + 1, 8).order(ByteOrder.LITTLE_ENDIAN).double
                    pos += 9
                }
                tag in 0x30..0x33 -> {
                    val n = 1 shl (tag and 0xF) // 0x30->1, 0x31->2, 0x32->4, 0x33->8
                    requireBytes(1 + n)
                    value = SizedInt(leLong(pos + 1, n), n)
                    pos += 1 + n
                }
                tag in 0x40..0x60 -> {
                    val len = tag - 0x40
                    requireBytes(1 + len)
                    value = String(data, pos + 1, len, Charsets.UTF_8)
                    pos += 1 + len
                }
                tag in 0x61..0x64 -> {
                    val n = tag and 0xF
                    requireBytes(1 + n)
                    val len = leLong(pos + 1, n).toInt()
                    requireBytes(1 + n + len)
                    value = String(data, pos + 1 + n, len, Charsets.UTF_8)
                    pos += 1 + n + len
                }
                tag in 0x70..0x90 -> {
                    val len = tag - 0x70
                    requireBytes(1 + len)
                    value = data.copyOfRange(pos + 1, pos + 1 + len)
                    pos += 1 + len
                }
                tag in 0x91..0x94 -> {
                    val n = 1 shl ((tag and 0xF) - 1) // 0x91->1, 0x92->2, 0x93->4, 0x94->8
                    requireBytes(1 + n)
                    val len = leLong(pos + 1, n).toInt()
                    requireBytes(1 + n + len)
                    value = data.copyOfRange(pos + 1 + n, pos + 1 + n + len)
                    pos += 1 + n + len
                }
                (tag and 0xF0) == 0xD0 -> {
                    val count = tag and 0xF
                    pos++
                    val out = ArrayList<Any?>()
                    if (count == 0xF) {
                        while (true) {
                            if (pos >= data.size) throw OpackException("unterminated endless list")
                            if ((data[pos].toInt() and 0xFF) == 0x03) { pos++; break }
                            out.add(parseValue())
                        }
                    } else {
                        repeat(count) { out.add(parseValue()) }
                    }
                    value = out
                    addToList = false
                }
                (tag and 0xE0) == 0xE0 -> {
                    val count = tag and 0xF
                    pos++
                    val out = LinkedHashMap<Any?, Any?>()
                    if (count == 0xF) {
                        while (true) {
                            if (pos >= data.size) throw OpackException("unterminated endless dict")
                            if ((data[pos].toInt() and 0xFF) == 0x03) { pos++; break }
                            val k = parseValue()
                            val v = parseValue()
                            out[k] = v
                        }
                    } else {
                        repeat(count) {
                            val k = parseValue()
                            val v = parseValue()
                            out[k] = v
                        }
                    }
                    value = out
                    addToList = false
                }
                tag in 0xA0..0xC0 -> {
                    val idx = tag - 0xA0
                    if (idx >= objectList.size) throw OpackException("OPACK ref 0x${tag.toString(16)} past table (size ${objectList.size}) — see the pyatv pack/unpack skew note in the file header")
                    value = objectList[idx]
                    pos++
                }
                tag in 0xC1..0xC4 -> {
                    val n = tag - 0xC0
                    requireBytes(1 + n)
                    val idx = leLong(pos + 1, n).toInt()
                    if (idx < 0 || idx >= objectList.size) throw OpackException("OPACK ref 0x${tag.toString(16)} idx $idx past table (size ${objectList.size}) — see the pyatv pack/unpack skew note in the file header")
                    value = objectList[idx]
                    pos += 1 + n
                }
                else -> throw OpackException("unknown OPACK tag 0x${tag.toString(16)} at $pos")
            }
            if (addToList && !containsValue(objectList, value)) objectList.add(value)
            return value
        }

        private fun containsValue(list: ArrayList<Any?>, v: Any?): Boolean {
            for (item in list) {
                if (item is ByteArray && v is ByteArray) { if (item.contentEquals(v)) return true }
                else if (item == v) return true
            }
            return false
        }

        private fun requireBytes(n: Int) {
            if (pos + n > data.size) throw OpackException("truncated OPACK: need $n bytes at $pos, have ${data.size}")
        }

        private fun leLong(off: Int, n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((data[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
    }

    private class ByteArrayOutputStream2 {
        private val buf = java.io.ByteArrayOutputStream()
        fun write(b: Int) = buf.write(b)
        fun write(bytes: ByteArray) = buf.write(bytes)
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
