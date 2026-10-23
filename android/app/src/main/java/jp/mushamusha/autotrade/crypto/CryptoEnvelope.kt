package jp.mushamusha.autotrade.crypto

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEnvelope {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    data class Sealed(val nonce: String, val ciphertext: String)

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** RFC5869 HKDF-SHA256, output length fixed to 32 bytes. */
    private fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val salt = ByteArray(32)
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(ikm)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, "HmacSHA256"))
                doFinal(info.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(1)).copyOf(32)
            }
        } finally {
            extract.fill(0)
            salt.fill(0)
        }
    }

    fun sign(
        pairingSecretHex: String,
        method: String,
        path: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
    ): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = "${method.uppercase()}.$path.$timestamp.$nonce.$bodyHash".toByteArray(StandardCharsets.UTF_8)
        val ikm = hexToBytes(pairingSecretHex)
        val key = hkdf(ikm, "autotrade-auth-v1")
        return try {
            val sig = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(canonical)
            }
            urlEncoder.encodeToString(sig)
        } finally {
            ikm.fill(0)
            key.fill(0)
            canonical.fill(0)
        }
    }

    fun verifyResponseSignature(
        pairingSecretHex: String,
        path: String,
        requestNonce: String,
        body: ByteArray,
        suppliedSignature: String,
    ): Boolean {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = "RESP.$path.$requestNonce.$bodyHash".toByteArray(StandardCharsets.UTF_8)
        val ikm = hexToBytes(pairingSecretHex)
        val key = hkdf(ikm, "autotrade-auth-v1")
        return try {
            val expected = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(canonical)
            }
            val supplied = runCatching { urlDecoder.decode(suppliedSignature) }.getOrNull() ?: return false
            MessageDigest.isEqual(expected, supplied)
        } finally {
            ikm.fill(0)
            key.fill(0)
            canonical.fill(0)
        }
    }

    fun sealDeepSeekKey(pairingSecretHex: String, apiKey: CharArray): Sealed {
        val ikm = hexToBytes(pairingSecretHex)
        val key = hkdf(ikm, "autotrade-secret-v1")
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("deepseek-api-key-v1".toByteArray(StandardCharsets.UTF_8))

        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(apiKey))
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        return try {
            val ct = cipher.doFinal(bytes)
            Sealed(urlEncoder.encodeToString(iv), urlEncoder.encodeToString(ct))
        } finally {
            bytes.fill(0)
            apiKey.fill('\u0000')
            ikm.fill(0)
            key.fill(0)
        }
    }

    fun newRequestNonce(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return urlEncoder.encodeToString(bytes)
    }
}
