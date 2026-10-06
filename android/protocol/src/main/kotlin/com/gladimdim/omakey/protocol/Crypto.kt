package com.gladimdim.omakey.protocol

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // RFC 5869: an absent salt is HashLen zero bytes.
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "bad HKDF length $length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}

/**
 * AES-256-GCM with a 16-byte tag. One instance per key; methods are
 * synchronized because the sender and receiver threads may share one.
 */
class Aead(key: ByteArray) {
    init {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
    }

    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    @Synchronized
    fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(Wire.TAG_LEN * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Returns the plaintext, or null when the packet doesn't authenticate. */
    @Synchronized
    fun open(nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < Wire.TAG_LEN) return null
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(Wire.TAG_LEN * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext, offset, length)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }
}

/** The two per-session keys from PROTOCOL.md. */
class SessionKeys(val clientToServer: ByteArray, val serverToClient: ByteArray) {
    companion object {
        private val INFO_C2S = "omakey v1 c2s".toByteArray(Charsets.US_ASCII)
        private val INFO_S2C = "omakey v1 s2c".toByteArray(Charsets.US_ASCII)

        fun derive(deviceKey: ByteArray, clientRandom: ByteArray, serverRandom: ByteArray): SessionKeys {
            val salt = clientRandom + serverRandom
            val prk = Hkdf.extract(salt, deviceKey)
            return SessionKeys(Hkdf.expand(prk, INFO_C2S, 32), Hkdf.expand(prk, INFO_S2C, 32))
        }
    }
}
