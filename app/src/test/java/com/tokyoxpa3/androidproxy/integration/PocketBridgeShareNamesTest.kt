package com.tokyoxpa3.androidproxy.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketBridgeShareNamesTest {

    @Test
    fun `path separators and control characters cannot escape Inbox`() {
        val safe = PocketBridgeShareNames.sanitizeFileName("../client\\report\u0000.pdf")

        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
        assertFalse(safe.contains('\u0000'))
        assertEquals("_client_report.pdf", safe)
    }

    @Test
    fun `blank name receives deterministic fallback`() {
        assertEquals("shared-item", PocketBridgeShareNames.sanitizeFileName("..."))
    }

    @Test
    fun `long names retain extension`() {
        val safe = PocketBridgeShareNames.sanitizeFileName("a".repeat(300) + ".pdf")

        assertTrue(safe.length <= 180)
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun `collision produces familiar numbered copy name`() {
        val existing = setOf("report.pdf", "report (2).pdf")
        val next = PocketBridgeShareNames.nextAvailableName("report.pdf", existing::contains)

        assertEquals("report (3).pdf", next)
    }
}
