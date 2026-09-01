package com.tokyoxpa3.androidproxy.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketBridgeIntegrationFoundationTest {

    @Test
    fun `built in adapter ids are unique`() {
        val all = BuiltInPocketBridgeAdapters.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun `secure defaults fail no invariant`() {
        assertTrue(PocketBridgeSecurityPolicy.DEFAULT.isSafe)
        assertTrue(PocketBridgeSecurityPolicy.DEFAULT.validate().isEmpty())
    }

    @Test
    fun `unsafe listener exposure is rejected`() {
        val policy = PocketBridgeSecurityPolicy(allowCellularListenerExposure = true)
        assertFalse(policy.isSafe)
        assertTrue(policy.validate().any { it.contains("Cellular") })
    }

    @Test
    fun `universal Android adapters are enabled by default`() {
        val registry = PocketBridgeAdapterRegistry()
        assertTrue(registry.find("android.share-sheet")!!.enabledByDefault)
        assertTrue(registry.find("android.open-with")!!.enabledByDefault)
        assertTrue(registry.find("android.storage-access-framework")!!.enabledByDefault)
    }

    @Test
    fun `privileged and external adapters are opt in`() {
        val registry = PocketBridgeAdapterRegistry()
        assertFalse(registry.find("app.shizuku")!!.enabledByDefault)
        assertFalse(registry.find("api.external-consumer")!!.enabledByDefault)
    }

    @Test
    fun `installed app matching does not require Android runtime`() {
        val registry = PocketBridgeAdapterRegistry()
        val matches = registry.matchingInstalledPackages(
            setOf("com.termux", "com.nextcloud.client", "com.example.unrelated")
        )
        assertTrue(matches.any { it.id == "app.termux" })
        assertTrue(matches.any { it.id == "app.nextcloud" })
        assertFalse(matches.any { it.id == "app.shizuku" })
    }

    @Test
    fun `external consumer is generic and domain independent`() {
        val registry = PocketBridgeAdapterRegistry()
        val external = registry.find("api.external-consumer")
        assertNotNull(external)
        assertEquals(AdapterDependency.EXTERNAL_CONSUMER, external!!.dependency)
        assertTrue(PocketBridgeCapability.EXTERNAL_CONSUMER_API in external.capabilities)
        assertFalse(external.id.contains("nexus", ignoreCase = true))
        assertFalse(external.displayName.contains("nexus", ignoreCase = true))
    }
}
