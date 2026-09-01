package com.tokyoxpa3.androidproxy.integration

import android.content.Context
import com.tokyoxpa3.androidproxy.Socks5ProxyService
import com.tokyoxpa3.androidproxy.network.HotspotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local sidecar coordinator whose parent lifecycle is the existing
 * Socks5ProxyService foreground service.
 *
 * It intentionally does not create another Android service or notification.
 * When the proxy service stops, QuickDrop is closed automatically. When the
 * cellular path is temporarily paused/rebuilt but the foreground service is
 * still alive, local file sharing remains available over LAN.
 */
object PocketBridgeRuntimeCoordinator {
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var server: PocketBridgeQuickDropServer? = null
    private var activeAddresses: Set<String> = emptySet()
    private var activeTree: String? = null

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            try {
                reconcile()
            } catch (error: Exception) {
                stopServer()
                PocketBridgeRuntimeState.set(
                    PocketBridgeRuntimeSnapshot(
                        status = PocketBridgeRuntimeStatus.FAILED,
                        lastError = error.message ?: error.javaClass.simpleName
                    )
                )
            }
            delay(RECONCILE_INTERVAL_MS)
        }
    }

    private fun reconcile() {
        val context = appContext ?: return

        if (!Socks5ProxyService.isServiceRunning) {
            stopServer()
            PocketBridgeRuntimeState.reset(PocketBridgeRuntimeStatus.WAITING_FOR_PROXY)
            return
        }

        val folderStore = PocketBridgeFolderStore(context)
        if (!folderStore.hasPersistedAccess()) {
            stopServer()
            PocketBridgeRuntimeState.reset(PocketBridgeRuntimeStatus.WAITING_FOR_FOLDER)
            return
        }

        val tree = folderStore.getTreeUri()?.toString()
        if (tree.isNullOrBlank()) {
            stopServer()
            PocketBridgeRuntimeState.reset(PocketBridgeRuntimeStatus.WAITING_FOR_FOLDER)
            return
        }

        val addresses = HotspotManager.getLanIPv4Addresses(context).toSet()
        if (addresses.isEmpty()) {
            stopServer()
            PocketBridgeRuntimeState.reset(PocketBridgeRuntimeStatus.WAITING_FOR_LAN)
            return
        }

        if (server != null && activeAddresses == addresses && activeTree == tree) {
            return
        }

        stopServer()
        PocketBridgeRuntimeState.reset(PocketBridgeRuntimeStatus.STARTING)

        var lastError: Exception? = null
        for (port in PORT_CANDIDATES) {
            val candidate = PocketBridgeQuickDropServer(context, addresses.toList(), port)
            try {
                val started = candidate.start()
                server = candidate
                activeAddresses = started.boundAddresses.toSet()
                activeTree = tree
                PocketBridgeRuntimeState.set(
                    PocketBridgeRuntimeSnapshot(
                        status = PocketBridgeRuntimeStatus.RUNNING,
                        portalPort = port,
                        portalAddresses = started.boundAddresses,
                        connectUrls = started.connectUrls
                    )
                )
                return
            } catch (error: Exception) {
                lastError = error
                candidate.close()
            }
        }

        PocketBridgeRuntimeState.set(
            PocketBridgeRuntimeSnapshot(
                status = PocketBridgeRuntimeStatus.FAILED,
                lastError = lastError?.message ?: "No QuickDrop port could be opened"
            )
        )
    }

    private fun stopServer() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        activeAddresses = emptySet()
        activeTree = null
    }

    private val PORT_CANDIDATES = intArrayOf(8080, 8081, 8082, 8787)
    private const val RECONCILE_INTERVAL_MS = 1500L
}
