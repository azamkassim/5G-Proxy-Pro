package com.tokyoxpa3.androidproxy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the persisted SOCKS5 password with a non-exportable Android Keystore AES key.
 * Legacy plaintext values are accepted only for migration and are re-written encrypted by the UI.
 */
object CredentialCrypto {
    private const val TAG = "CredentialCrypto"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "5g_proxy_socks5_password_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isEncrypted(value: String): Boolean = CredentialEnvelope.isEncrypted(value)

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            CredentialEnvelope.encode(cipher.iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to encrypt proxy credential", e)
            ""
        }
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        if (!CredentialEnvelope.isEncrypted(storedValue)) return storedValue // legacy migration path
        val parts = CredentialEnvelope.decode(storedValue) ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, parts.iv))
            String(cipher.doFinal(parts.ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to decrypt proxy credential; failing closed", e)
            ""
        }
    }

    fun migrateIfNeeded(storedValue: String): String {
        if (storedValue.isEmpty() || CredentialEnvelope.isEncrypted(storedValue)) return storedValue
        return encrypt(storedValue)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
