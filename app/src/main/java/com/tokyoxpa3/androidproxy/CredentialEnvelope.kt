package com.tokyoxpa3.androidproxy

import java.util.Base64

/** Pure, JVM-testable encoding for Android Keystore ciphertext. */
object CredentialEnvelope {
    const val PREFIX = "keystore:v1:"

    data class Parts(val iv: ByteArray, val ciphertext: ByteArray)

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext)
    }

    fun decode(value: String): Parts? {
        if (!isEncrypted(value)) return null
        val payload = value.removePrefix(PREFIX)
        val splitAt = payload.indexOf(':')
        if (splitAt <= 0 || splitAt == payload.lastIndex) return null
        return try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(payload.substring(0, splitAt))
            val ciphertext = decoder.decode(payload.substring(splitAt + 1))
            if (iv.isEmpty() || ciphertext.isEmpty()) null else Parts(iv, ciphertext)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
