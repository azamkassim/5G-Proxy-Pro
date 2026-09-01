package com.tokyoxpa3.androidproxy.integration

/**
 * Stable capability vocabulary for PocketBridge integrations.
 *
 * Keep this list domain-neutral: NEXUS or any future consumer may use these
 * capabilities through an adapter, but PocketBridge core must not depend on
 * a consumer-specific business model.
 */
enum class PocketBridgeCapability {
    INTERNET_GATEWAY,
    FILE_DROP,
    SHARED_FOLDER,
    BROWSER_PORTAL,
    CLIPBOARD_BOARD,
    LINK_HANDOFF,
    OPEN_WITH,
    DOCUMENT_PROVIDER,
    QR_PAIRING,
    TRUSTED_DEVICES,
    TRANSFER_HISTORY,
    CLOUD_MIRROR,
    DEVELOPER_RECIPE,
    PRIVILEGED_ENHANCEMENT,
    EXTERNAL_CONSUMER_API
}
