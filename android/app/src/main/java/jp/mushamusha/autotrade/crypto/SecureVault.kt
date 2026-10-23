package jp.mushamusha.autotrade.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only the Windows pairing secret. DeepSeek API keys are NEVER persisted on Android.
 */
class SecureVault(context: Context) {
    private val prefs = context.getSharedPreferences("secure_vault", Context.MODE_PRIVATE)
    private val alias = "autotrade_pairing_v1"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing

        runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun savePairingSecret(hex: String) {
        require(hex.matches(Regex("^[0-9a-fA-F]{64}$"))) { "ペアリングコードは64桁の16進数です" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = hex.lowercase().toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted = cipher.doFinal(plain)
            prefs.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("ct", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
        } finally {
            plain.fill(0)
        }
    }

    fun loadPairingSecret(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val ct = prefs.getString("ct", null) ?: return null
        val decoded = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8)
        }.getOrNull()
        if (decoded == null) clear()
        return decoded
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }
}
