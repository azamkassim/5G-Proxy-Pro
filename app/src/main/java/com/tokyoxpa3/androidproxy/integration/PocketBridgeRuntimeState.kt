package com.tokyoxpa3.androidproxy.integration

enum class PocketBridgeRuntimeStatus {
    IDLE,
    WAITING_FOR_PROXY,
    WAITING_FOR_FOLDER,
    WAITING_FOR_LAN,
    STARTING,
    RUNNING,
    FAILED
}

data class PocketBridgeRuntimeSnapshot(
    val status: PocketBridgeRuntimeStatus = PocketBridgeRuntimeStatus.IDLE,
    val portalPort: Int? = null,
    val portalAddresses: List<String> = emptyList(),
    val connectUrls: List<String> = emptyList(),
    val lastError: String? = null
)

/**
 * Process-local read model for the UI and diagnostics. Connect URLs contain a
 * short-lived session fragment token and therefore must never be logged.
 */
object PocketBridgeRuntimeState {
    private val lock = Any()
    private var snapshot = PocketBridgeRuntimeSnapshot()

    fun get(): PocketBridgeRuntimeSnapshot = synchronized(lock) { snapshot }

    internal fun set(value: PocketBridgeRuntimeSnapshot) {
        synchronized(lock) { snapshot = value }
    }

    internal fun reset(status: PocketBridgeRuntimeStatus = PocketBridgeRuntimeStatus.IDLE) {
        set(PocketBridgeRuntimeSnapshot(status = status))
    }
}
