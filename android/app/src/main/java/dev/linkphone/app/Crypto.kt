package dev.linkphone.app

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Mirrors desktop/src/main/crypto.ts exactly. Do not change one side alone. */
object Crypto {
    private const val ITERATIONS = 600_000
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun normalize(code: String): String =
        code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun deriveKey(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(normalize(code).toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun hmac(key: ByteArray, label: String, data: ByteArray? = null): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.UTF_8))
        if (data != null) mac.update(data)
        return mac.doFinal()
    }

    /** frame = nonce(12) || ciphertext || tag(16) */
    fun seal(key: ByteArray, direction: String, seq: Long, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(direction, seq))
        return nonce + cipher.doFinal(plain)
    }

    fun open(key: ByteArray, direction: String, seq: Long, frame: ByteArray): ByteArray {
        require(frame.size >= 12 + 16) { "frame too short" }
        val nonce = frame.copyOfRange(0, 12)
        val body = frame.copyOfRange(12, frame.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(direction, seq))
        return cipher.doFinal(body)
    }

    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun aad(direction: String, seq: Long): ByteArray =
        "lp1:$direction:".toByteArray(Charsets.UTF_8) + ByteBuffer.allocate(8).putLong(seq).array()
}
