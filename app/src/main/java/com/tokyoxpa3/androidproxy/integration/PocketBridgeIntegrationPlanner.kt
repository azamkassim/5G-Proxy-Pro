package com.tokyoxpa3.androidproxy.integration

/**
 * Result used by the future PocketBridge UI/session coordinator.
 *
 * `enabled` contains safe-by-default integrations.
 * `availableOptIn` contains installed/known integrations that require an
 * explicit user decision before they can participate in a session.
 */
data class PocketBridgeIntegrationPlan(
    val enabled: List<PocketBridgeAdapterDescriptor>,
    val availableOptIn: List<PocketBridgeAdapterDescriptor>
) {
    val capabilities: Set<PocketBridgeCapability> =
        enabled.flatMapTo(linkedSetOf()) { it.capabilities }
}

class PocketBridgeIntegrationPlanner(
    private val registry: PocketBridgeAdapterRegistry = PocketBridgeAdapterRegistry()
) {
    fun plan(installedPackages: Set<String>): PocketBridgeIntegrationPlan {
        val installedOptionalIds = registry.matchingInstalledPackages(installedPackages)
            .mapTo(hashSetOf()) { it.id }

        val enabled = registry.all().filter { descriptor ->
            descriptor.enabledByDefault && when (descriptor.dependency) {
                AdapterDependency.CORE,
                AdapterDependency.ANDROID_FRAMEWORK -> true

                AdapterDependency.OPTIONAL_APP -> descriptor.id in installedOptionalIds
                AdapterDependency.OPTIONAL_PRIVILEGED,
                AdapterDependency.EXTERNAL_CONSUMER -> false
            }
        }

        val availableOptIn = registry.all().filter { descriptor ->
            when (descriptor.dependency) {
                AdapterDependency.OPTIONAL_APP,
                AdapterDependency.OPTIONAL_PRIVILEGED -> descriptor.id in installedOptionalIds && !descriptor.enabledByDefault

                AdapterDependency.EXTERNAL_CONSUMER -> true
                AdapterDependency.CORE,
                AdapterDependency.ANDROID_FRAMEWORK -> false
            }
        }

        return PocketBridgeIntegrationPlan(
            enabled = enabled,
            availableOptIn = availableOptIn
        )
    }
}
