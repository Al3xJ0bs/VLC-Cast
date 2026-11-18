package com.alexey.aircast

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 with counter nonces (12 bytes: 4 zero bytes + 8-byte
 * little-endian counter), matching pyatv's support/chacha20.py.
 * Uses the platform cipher (Android API 28+ / JDK 11+).
 */
class ChaCha20Poly1305(outKey: ByteArray, inKey: ByteArray) {
    private val outKeySpec = SecretKeySpec(outKey, "ChaCha20")
    private val inKeySpec = SecretKeySpec(inKey, "ChaCha20")
    private var outCounter = 0L
    private var inCounter = 0L

    fun outNonce(): ByteArray = counterNonce(outCounter)
    fun inNonce(): ByteArray = counterNonce(inCounter)

    fun encrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray? = null): ByteArray {
        val n = if (nonce == null) counterNonce(outCounter).also { outCounter++ } else padNonce(nonce)
        return run(Cipher.ENCRYPT_MODE, outKeySpec, n, data, aad)
    }

    fun decrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray? = null): ByteArray {
        val n = if (nonce == null) counterNonce(inCounter).also { inCounter++ } else padNonce(nonce)
        return run(Cipher.DECRYPT_MODE, inKeySpec, n, data, aad)
    }

    private fun run(mode: Int, key: SecretKeySpec, nonce: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = try {
            Cipher.getInstance("ChaCha20-Poly1305")
        } catch (e: Exception) {
            throw IllegalStateException("ChaCha20-Poly1305 недоступен на этом Android (нужен API 28+): ${e.message}")
        }
        cipher.init(mode, key, IvParameterSpec(nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    companion object {
        fun counterNonce(counter: Long): ByteArray {
            val nonce = ByteArray(12)
            var c = counter
            for (i in 0 until 8) {
                nonce[4 + i] = (c and 0xFF).toByte()
                c = c ushr 8
            }
            return nonce
        }

        fun padNonce(nonce: ByteArray): ByteArray {
            if (nonce.size >= 12) return nonce
            val padded = ByteArray(12)
            System.arraycopy(nonce, 0, padded, 12 - nonce.size, nonce.size)
            return padded
        }
    }
}

/**
 * HAP session framing (HAP spec 5.2.2, pyatv auth/hap_session.py):
 * outgoing data split into frames of at most 1024 bytes, each prefixed with a
 * 2-byte little-endian length used as AAD; 16-byte Poly1305 tag appended.
 */
class HapSession(outKey: ByteArray, inKey: ByteArray) {
    private val cipher = ChaCha20Poly1305(outKey, inKey)
    private var incoming = ByteArray(0)

    fun encrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val frameLen = minOf(1024, data.size - offset)
            val frame = data.copyOfRange(offset, offset + frameLen)
            val lengthBytes = byteArrayOf((frameLen and 0xFF).toByte(), ((frameLen shr 8) and 0xFF).toByte())
            out.write(lengthBytes)
            out.write(cipher.encrypt(frame, aad = lengthBytes))
            offset += frameLen
        }
        return out.toByteArray()
    }

    fun decrypt(data: ByteArray): ByteArray {
        incoming += data
        val out = ByteArrayOutputStream()
        while (incoming.size >= 2) {
            val length = (incoming[0].toInt() and 0xFF) or ((incoming[1].toInt() and 0xFF) shl 8)
            val blockLength = length + 16
            if (incoming.size < blockLength + 2) break
            val lengthBytes = incoming.copyOfRange(0, 2)
            val block = incoming.copyOfRange(2, 2 + blockLength)
            out.write(cipher.decrypt(block, aad = lengthBytes))
            incoming = incoming.copyOfRange(2 + blockLength, incoming.size)
        }
        return out.toByteArray()
    }
}
