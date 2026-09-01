package com.tokyoxpa3.androidproxy.integration

enum class SharedAccessMode {
    READ_ONLY,
    READ_WRITE
}

enum class InboxAccessMode {
    WRITE_ONLY,
    READ_WRITE
}

/**
 * Security invariants for an integrated PocketBridge session.
 *
 * These defaults are intentionally stricter than a generic file server. A
 * runtime coordinator should reject startup rather than weaken these values
 * silently.
 */
data class PocketBridgeSecurityPolicy(
    val bindListenersToLanOnly: Boolean = true,
    val allowCellularListenerExposure: Boolean = false,
    val requireRemoteAuthentication: Boolean = true,
    val allowAutomaticThirdPartyPrivateDataRead: Boolean = false,
    val allowGenericRemoteShell: Boolean = false,
    val sharedAccessMode: SharedAccessMode = SharedAccessMode.READ_ONLY,
    val inboxAccessMode: InboxAccessMode = InboxAccessMode.WRITE_ONLY,
    val invalidateEphemeralTokensOnStop: Boolean = true,
    val requireExplicitPrivilegedAdapterEnablement: Boolean = true,
    val externalConsumerApiEnabledByDefault: Boolean = false
) {
    fun validate(): List<String> = buildList {
        if (!bindListenersToLanOnly) add("PocketBridge listeners must be LAN-only")
        if (allowCellularListenerExposure) add("Cellular/public listener exposure is forbidden")
        if (!requireRemoteAuthentication) add("Remote access must require authentication")
        if (allowAutomaticThirdPartyPrivateDataRead) add("Automatic third-party private-data reads are forbidden")
        if (allowGenericRemoteShell) add("Generic remote shells are forbidden; use allow-listed recipes")
        if (!invalidateEphemeralTokensOnStop) add("Ephemeral tokens must be invalidated when the session stops")
        if (!requireExplicitPrivilegedAdapterEnablement) add("Privileged adapters must be explicit opt-in")
        if (externalConsumerApiEnabledByDefault) add("External-consumer API must be explicit opt-in")
    }

    val isSafe: Boolean
        get() = validate().isEmpty()

    companion object {
        val DEFAULT = PocketBridgeSecurityPolicy()
    }
}
