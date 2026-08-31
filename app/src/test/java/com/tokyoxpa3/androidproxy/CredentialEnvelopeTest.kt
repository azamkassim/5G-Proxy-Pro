package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialEnvelopeTest {
    @Test
    fun roundTripPreservesIvAndCiphertext() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val ciphertext = byteArrayOf(21, 22, 23, 24, 25, 26)

        val encoded = CredentialEnvelope.encode(iv, ciphertext)
        val decoded = CredentialEnvelope.decode(encoded)

        assertTrue(CredentialEnvelope.isEncrypted(encoded))
        assertNotNull(decoded)
        assertArrayEquals(iv, decoded!!.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun malformedEnvelopeFailsClosed() {
        assertTrue(CredentialEnvelope.isEncrypted("${CredentialEnvelope.PREFIX}bad"))
        assertTrue(CredentialEnvelope.decode("${CredentialEnvelope.PREFIX}bad") == null)
        assertFalse(CredentialEnvelope.isEncrypted("plain-password"))
    }
}
