package com.tokyoxpa3.androidproxy.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketBridgeIntegrationPlannerTest {

    @Test
    fun `safe core and Android integrations are enabled automatically`() {
        val plan = PocketBridgeIntegrationPlanner().plan(emptySet())
        val ids = plan.enabled.mapTo(hashSetOf()) { it.id }

        assertTrue("gateway.socks5" in ids)
        assertTrue("android.share-sheet" in ids)
        assertTrue("android.open-with" in ids)
        assertTrue("android.storage-access-framework" in ids)
        assertTrue("portal.quickdrop" in ids)
    }

    @Test
    fun `built in SFTP module is visible but not auto enabled`() {
        val plan = PocketBridgeIntegrationPlanner().plan(emptySet())

        assertFalse(plan.enabled.any { it.id == "drive.sftp" })
        assertTrue(plan.availableOptIn.any { it.id == "drive.sftp" })
    }

    @Test
    fun `installed privileged adapter remains opt in`() {
        val plan = PocketBridgeIntegrationPlanner().plan(
            setOf("moe.shizuku.privileged.api")
        )

        assertFalse(plan.enabled.any { it.id == "app.shizuku" })
        assertTrue(plan.availableOptIn.any { it.id == "app.shizuku" })
    }

    @Test
    fun `external consumer API never auto enables`() {
        val plan = PocketBridgeIntegrationPlanner().plan(emptySet())

        assertFalse(plan.enabled.any { it.id == "api.external-consumer" })
        assertTrue(plan.availableOptIn.any { it.id == "api.external-consumer" })
    }

    @Test
    fun `installed optional apps can be discovered without broad package ownership`() {
        val registry = PocketBridgeAdapterRegistry(
            BuiltInPocketBridgeAdapters.all.map {
                if (it.id == "app.termux") it.copy(enabledByDefault = true) else it
            }
        )
        val plan = PocketBridgeIntegrationPlanner(registry).plan(setOf("com.termux"))

        assertTrue(plan.enabled.any { it.id == "app.termux" })
        assertTrue(PocketBridgeCapability.DEVELOPER_RECIPE in plan.capabilities)
    }
}
