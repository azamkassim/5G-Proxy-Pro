package com.tokyoxpa3.androidproxy.integration

enum class AdapterDependency {
    CORE,
    ANDROID_FRAMEWORK,
    OPTIONAL_APP,
    OPTIONAL_PRIVILEGED,
    EXTERNAL_CONSUMER
}

enum class AdapterTrustLevel {
    LOCAL_CORE,
    USER_HANDOFF,
    OPTIONAL_INTEGRATION,
    PRIVILEGED,
    EXTERNAL
}

data class PocketBridgeAdapterDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<PocketBridgeCapability>,
    val dependency: AdapterDependency,
    val trustLevel: AdapterTrustLevel,
    val packageNames: Set<String> = emptySet(),
    val enabledByDefault: Boolean = false
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid adapter id: $id" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(capabilities.isNotEmpty()) { "Adapter $id must declare at least one capability" }
    }
}

/**
 * Pure Kotlin registry so integration planning can be tested without Android.
 * Runtime Android code can later add package-availability checks and lifecycle
 * state without changing this contract.
 */
class PocketBridgeAdapterRegistry(
    descriptors: Iterable<PocketBridgeAdapterDescriptor> = BuiltInPocketBridgeAdapters.all
) {
    private val byId: LinkedHashMap<String, PocketBridgeAdapterDescriptor> = linkedMapOf()

    init {
        descriptors.forEach { descriptor ->
            require(byId.put(descriptor.id, descriptor) == null) {
                "Duplicate PocketBridge adapter id: ${descriptor.id}"
            }
        }
    }

    fun all(): List<PocketBridgeAdapterDescriptor> = byId.values.toList()

    fun find(id: String): PocketBridgeAdapterDescriptor? = byId[id]

    fun withCapability(capability: PocketBridgeCapability): List<PocketBridgeAdapterDescriptor> =
        byId.values.filter { capability in it.capabilities }

    fun matchingInstalledPackages(installedPackages: Set<String>): List<PocketBridgeAdapterDescriptor> =
        byId.values.filter { descriptor ->
            descriptor.packageNames.isNotEmpty() && descriptor.packageNames.any(installedPackages::contains)
        }
}

object BuiltInPocketBridgeAdapters {
    val all: List<PocketBridgeAdapterDescriptor> = listOf(
        PocketBridgeAdapterDescriptor(
            id = "gateway.socks5",
            displayName = "5G SOCKS5 Gateway",
            capabilities = setOf(PocketBridgeCapability.INTERNET_GATEWAY),
            dependency = AdapterDependency.CORE,
            trustLevel = AdapterTrustLevel.LOCAL_CORE,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "android.share-sheet",
            displayName = "Android Share Sheet",
            capabilities = setOf(
                PocketBridgeCapability.FILE_DROP,
                PocketBridgeCapability.LINK_HANDOFF,
                PocketBridgeCapability.CLIPBOARD_BOARD
            ),
            dependency = AdapterDependency.ANDROID_FRAMEWORK,
            trustLevel = AdapterTrustLevel.USER_HANDOFF,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "android.open-with",
            displayName = "Android Open With",
            capabilities = setOf(PocketBridgeCapability.OPEN_WITH),
            dependency = AdapterDependency.ANDROID_FRAMEWORK,
            trustLevel = AdapterTrustLevel.USER_HANDOFF,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "android.storage-access-framework",
            displayName = "Storage Access Framework",
            capabilities = setOf(
                PocketBridgeCapability.SHARED_FOLDER,
                PocketBridgeCapability.DOCUMENT_PROVIDER
            ),
            dependency = AdapterDependency.ANDROID_FRAMEWORK,
            trustLevel = AdapterTrustLevel.USER_HANDOFF,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "discovery.local",
            displayName = "Local Device Discovery",
            capabilities = setOf(PocketBridgeCapability.DEVICE_DISCOVERY),
            dependency = AdapterDependency.CORE,
            trustLevel = AdapterTrustLevel.LOCAL_CORE,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "portal.quickdrop",
            displayName = "QuickDrop Browser Portal",
            capabilities = setOf(
                PocketBridgeCapability.BROWSER_PORTAL,
                PocketBridgeCapability.FILE_DROP,
                PocketBridgeCapability.CLIPBOARD_BOARD,
                PocketBridgeCapability.LINK_HANDOFF,
                PocketBridgeCapability.QR_PAIRING,
                PocketBridgeCapability.TRUSTED_DEVICES,
                PocketBridgeCapability.TRANSFER_HISTORY
            ),
            dependency = AdapterDependency.CORE,
            trustLevel = AdapterTrustLevel.LOCAL_CORE,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "automation.recipe-engine",
            displayName = "Automation Recipe Engine",
            capabilities = setOf(PocketBridgeCapability.AUTOMATION_RECIPE),
            dependency = AdapterDependency.CORE,
            trustLevel = AdapterTrustLevel.LOCAL_CORE,
            enabledByDefault = true
        ),
        PocketBridgeAdapterDescriptor(
            id = "drive.sftp",
            displayName = "Pocket Drive SFTP",
            capabilities = setOf(
                PocketBridgeCapability.SHARED_FOLDER,
                PocketBridgeCapability.TRANSFER_HISTORY
            ),
            dependency = AdapterDependency.CORE,
            trustLevel = AdapterTrustLevel.LOCAL_CORE,
            enabledByDefault = false
        ),
        PocketBridgeAdapterDescriptor(
            id = "drive.webdav",
            displayName = "Pocket Drive WebDAV",
            capabilities = setOf(
                PocketBridgeCapability.SHARED_FOLDER,
                PocketBridgeCapability.TRANSFER_HISTORY
            ),
            dependency = AdapterDependency.CORE,
            trustLevel = AdapterTrustLevel.LOCAL_CORE,
            enabledByDefault = false
        ),
        PocketBridgeAdapterDescriptor(
            id = "app.nextcloud",
            displayName = "Nextcloud",
            capabilities = setOf(
                PocketBridgeCapability.CLOUD_MIRROR,
                PocketBridgeCapability.DOCUMENT_PROVIDER,
                PocketBridgeCapability.AUTOMATION_RECIPE
            ),
            dependency = AdapterDependency.OPTIONAL_APP,
            trustLevel = AdapterTrustLevel.OPTIONAL_INTEGRATION,
            packageNames = setOf("com.nextcloud.client", "com.nextcloud.android.beta")
        ),
        PocketBridgeAdapterDescriptor(
            id = "app.termux",
            displayName = "Termux Recipes",
            capabilities = setOf(
                PocketBridgeCapability.AUTOMATION_RECIPE,
                PocketBridgeCapability.DEVELOPER_RECIPE
            ),
            dependency = AdapterDependency.OPTIONAL_APP,
            trustLevel = AdapterTrustLevel.OPTIONAL_INTEGRATION,
            packageNames = setOf("com.termux")
        ),
        PocketBridgeAdapterDescriptor(
            id = "app.acode",
            displayName = "Acode Project Handoff",
            capabilities = setOf(
                PocketBridgeCapability.FILE_DROP,
                PocketBridgeCapability.OPEN_WITH,
                PocketBridgeCapability.DEVELOPER_RECIPE
            ),
            dependency = AdapterDependency.OPTIONAL_APP,
            trustLevel = AdapterTrustLevel.OPTIONAL_INTEGRATION,
            packageNames = setOf("com.foxdebug.acode")
        ),
        PocketBridgeAdapterDescriptor(
            id = "app.consoleflow",
            displayName = "ConsoleFlow Recipes",
            capabilities = setOf(
                PocketBridgeCapability.AUTOMATION_RECIPE,
                PocketBridgeCapability.DEVELOPER_RECIPE
            ),
            dependency = AdapterDependency.OPTIONAL_APP,
            trustLevel = AdapterTrustLevel.OPTIONAL_INTEGRATION,
            packageNames = setOf("space.karrarnazim.ConsoleFlow")
        ),
        PocketBridgeAdapterDescriptor(
            id = "app.shizuku",
            displayName = "Shizuku Enhancement",
            capabilities = setOf(PocketBridgeCapability.PRIVILEGED_ENHANCEMENT),
            dependency = AdapterDependency.OPTIONAL_PRIVILEGED,
            trustLevel = AdapterTrustLevel.PRIVILEGED,
            packageNames = setOf("moe.shizuku.privileged.api")
        ),
        PocketBridgeAdapterDescriptor(
            id = "api.external-consumer",
            displayName = "External Consumer API",
            capabilities = setOf(PocketBridgeCapability.EXTERNAL_CONSUMER_API),
            dependency = AdapterDependency.EXTERNAL_CONSUMER,
            trustLevel = AdapterTrustLevel.EXTERNAL,
            enabledByDefault = false
        )
    )
}
