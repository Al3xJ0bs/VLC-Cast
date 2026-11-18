package com.alexey.aircast

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Pure-Kotlin Curve25519 (X25519, RFC 7748) and Ed25519 (RFC 8032).
 *
 * No BouncyCastle dependency: Android's bundled BC is not part of the public
 * API surface we can rely on, and JDK 11 has no EdDSA. Field arithmetic is
 * BigInteger mod p = 2^255-19; Ed25519 uses extended twisted-Edwards
 * coordinates (no inversions in the scalar loop). Verified against RFC 7748
 * §5.2 and RFC 8032 §7.1 vectors in tests/TestMain.kt.
 */
object Curve25519 {
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val L = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val I = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    // Base point (from RFC 8032)
    private val BX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    private class Pt(val x: BigInteger, val y: BigInteger, val t: BigInteger, val z: BigInteger) {
        fun add(q: Pt): Pt {
            val a = x.multiply(q.x).mod(P)
            val b = y.multiply(q.y).mod(P)
            val c = t.multiply(D).multiply(q.t).mod(P)
            val d = z.multiply(q.z).mod(P)
            val e = x.add(y).mod(P).multiply(q.x.add(q.y).mod(P)).subtract(a).subtract(b).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), e.multiply(h).mod(P), f.multiply(g).mod(P))
        }
        fun dbl(): Pt = add(this)
        fun toAffine(): Pair<BigInteger, BigInteger> {
            val zi = z.modInverse(P)
            return Pair(x.multiply(zi).mod(P), y.multiply(zi).mod(P))
        }
        fun encode(): ByteArray {
            val (ax, ay) = toAffine()
            val out = toLe(ay)
            if (ax.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }
    }

    private val IDENTITY = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE)
    private val BASE = Pt(BX, BY, BX.multiply(BY).mod(P), BigInteger.ONE)

    private fun scalarMultBase(s: BigInteger): Pt {
        var r = IDENTITY
        var q = BASE
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) r = r.add(q)
            q = q.dbl()
            k = k.shiftRight(1)
        }
        return r
    }

    private fun scalarMultPoint(s: BigInteger, p: Pt): Pt {
        var r = IDENTITY
        var q = p
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) r = r.add(q)
            q = q.dbl()
            k = k.shiftRight(1)
        }
        return r
    }

    private fun decodePoint(enc: ByteArray): Pt? {
        if (enc.size != 32) return null
        val yBytes = enc.copyOf()
        val sign = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = fromLe(yBytes)
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val xx = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) x = x.multiply(I).mod(P)
        if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) return null
        if (x.testBit(0) != sign) x = P.subtract(x)
        return Pt(x, y, x.multiply(y).mod(P), BigInteger.ONE)
    }

    private fun toLe(v: BigInteger): ByteArray {
        val b = ByteArray(32)
        var x = v.mod(P)
        for (i in 0 until 32) {
            b[i] = x.and(BigInteger.valueOf(0xFF)).toByte()
            x = x.shiftRight(8)
        }
        return b
    }

    private fun fromLe(b: ByteArray): BigInteger {
        var v = BigInteger.ZERO
        for (i in b.size - 1 downTo 0) v = v.shiftLeft(8).add(BigInteger.valueOf((b[i].toInt() and 0xFF).toLong()))
        return v
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    // ---- X25519 (RFC 7748) ----
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val k = privateKey.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        val kInt = fromLe(k)
        val u = fromLe(publicKey.copyOf(32))
        // Montgomery ladder
        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val bit = if (kInt.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = bit
            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = x1.multiply(da.subtract(cb).mod(P).let { it.multiply(it).mod(P) }).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(BigInteger.valueOf(121665).multiply(e).mod(P))).mod(P)
        }
        if (swap == 1) {
            val tmp = x2; x2 = x3; x3 = tmp
            val tmp2 = z2; z2 = z3; z3 = tmp2
        }
        return toLe(x2.multiply(z2.modInverse(P)).mod(P))
    }

    fun x25519Public(privateKey: ByteArray): ByteArray {
        val base = ByteArray(32).also { it[0] = 9 }
        return x25519(privateKey, base)
    }

    // ---- Ed25519 (RFC 8032) ----
    fun ed25519PublicKey(seed: ByteArray): ByteArray {
        val h = sha512(seed)
        val a = fromLe(h.copyOfRange(0, 32))
        val aClamped = a.and(BigInteger("2").pow(254).subtract(BigInteger.valueOf(8)))
            .or(BigInteger("2").pow(254))
        return scalarMultBase(aClamped).encode()
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val h = sha512(seed)
        val aBytes = h.copyOfRange(0, 32)
        val a = fromLe(aBytes).and(BigInteger("2").pow(254).subtract(BigInteger.valueOf(8)))
            .or(BigInteger("2").pow(254))
        val prefix = h.copyOfRange(32, 64)
        val pub = scalarMultBase(a).encode()
        val r = fromLe(sha512(prefix, message)).mod(L)
        val rEnc = scalarMultBase(r).encode()
        val k = fromLe(sha512(rEnc, pub, message)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)
        return rEnc + toLe(s)
    }

    fun ed25519Verify(signature: ByteArray, publicKey: ByteArray, message: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val rEnc = signature.copyOfRange(0, 32)
        val s = fromLe(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val aPt = decodePoint(publicKey) ?: return false
        val rPt = decodePoint(rEnc) ?: return false
        val k = fromLe(sha512(rEnc, publicKey, message)).mod(L)
        val left = scalarMultBase(s)
        val right = rPt.add(scalarMultPoint(k, aPt))
        return left.encode().contentEquals(right.encode())
    }
}
