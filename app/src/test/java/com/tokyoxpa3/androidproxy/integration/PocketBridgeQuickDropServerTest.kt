package com.tokyoxpa3.androidproxy.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketBridgeQuickDropServerTest {

    @Test
    fun `query parser decodes encoded file names and token`() {
        val query = PocketBridgeQuickDropServer.parseQuery("name=Quarter%201%20%26%20Notes.pdf&t=abc%2B123")

        assertEquals("Quarter 1 & Notes.pdf", query["name"])
        assertEquals("abc+123", query["t"])
    }

    @Test
    fun `json escaping protects browser payload`() {
        val escaped = PocketBridgeQuickDropServer.jsonEscape("quote\" slash\\ line\n")

        assertEquals("quote\\\" slash\\\\ line\\n", escaped)
    }

    @Test
    fun `capability registry exposes portal and local discovery`() {
        val registry = PocketBridgeAdapterRegistry()

        assertTrue(registry.withCapability(PocketBridgeCapability.BROWSER_PORTAL).any { it.id == "portal.quickdrop" })
        assertTrue(registry.withCapability(PocketBridgeCapability.DEVICE_DISCOVERY).any { it.id == "discovery.local" })
    }
}
