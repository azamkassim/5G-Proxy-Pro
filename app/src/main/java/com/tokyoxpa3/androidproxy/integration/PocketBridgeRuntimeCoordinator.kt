package com.tokyoxpa3.androidproxy.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tokyoxpa3.androidproxy.PocketBridgeConnectActivity
import com.tokyoxpa3.androidproxy.R
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
 * It intentionally does not create another Android service. QuickDrop derives
 * its lifecycle from the existing foreground proxy service; a low-priority
 * status notification is only a control surface for one-time folder setup and
 * the ephemeral connection link. When the proxy service stops, QuickDrop and
 * that status notification are closed automatically.
 */
object PocketBridgeRuntimeCoordinator {
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var server: PocketBridgeQuickDropServer? = null
    private var activeAddresses: Set<String> = emptySet()
    private var activeTree: String? = null
    private var lastPublishedStatus: PocketBridgeRuntimeStatus? = null

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        createNotificationChannel(context.applicationContext)
        scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            try {
                reconcile()
            } catch (error: Exception) {
                stopServer()
                setState(
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
            setState(PocketBridgeRuntimeSnapshot(status = PocketBridgeRuntimeStatus.WAITING_FOR_PROXY))
            cancelStatusNotification()
            return
        }

        val folderStore = PocketBridgeFolderStore(context)
        if (!folderStore.hasPersistedAccess()) {
            stopServer()
            setState(PocketBridgeRuntimeSnapshot(status = PocketBridgeRuntimeStatus.WAITING_FOR_FOLDER))
            return
        }

        val tree = folderStore.getTreeUri()?.toString()
        if (tree.isNullOrBlank()) {
            stopServer()
            setState(PocketBridgeRuntimeSnapshot(status = PocketBridgeRuntimeStatus.WAITING_FOR_FOLDER))
            return
        }

        val addresses = HotspotManager.getLanIPv4Addresses(context).toSet()
        if (addresses.isEmpty()) {
            stopServer()
            setState(PocketBridgeRuntimeSnapshot(status = PocketBridgeRuntimeStatus.WAITING_FOR_LAN))
            return
        }

        if (server != null && activeAddresses == addresses && activeTree == tree) {
            return
        }

        stopServer()
        setState(PocketBridgeRuntimeSnapshot(status = PocketBridgeRuntimeStatus.STARTING))

        var lastError: Exception? = null
        for (port in PORT_CANDIDATES) {
            val candidate = PocketBridgeQuickDropServer(context, addresses.toList(), port)
            try {
                val started = candidate.start()
                server = candidate
                activeAddresses = started.boundAddresses.toSet()
                activeTree = tree
                setState(
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

        setState(
            PocketBridgeRuntimeSnapshot(
                status = PocketBridgeRuntimeStatus.FAILED,
                lastError = lastError?.message ?: "No QuickDrop port could be opened"
            )
        )
    }

    private fun setState(snapshot: PocketBridgeRuntimeSnapshot) {
        PocketBridgeRuntimeState.set(snapshot)
        publishStatusNotification(snapshot.status)
    }

    private fun publishStatusNotification(status: PocketBridgeRuntimeStatus) {
        if (status == lastPublishedStatus) return
        lastPublishedStatus = status

        if (status == PocketBridgeRuntimeStatus.WAITING_FOR_PROXY || status == PocketBridgeRuntimeStatus.IDLE) {
            cancelStatusNotification()
            return
        }

        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (titleRes, textRes) = when (status) {
            PocketBridgeRuntimeStatus.RUNNING ->
                R.string.pocketbridge_notification_ready_title to R.string.pocketbridge_notification_ready_text

            PocketBridgeRuntimeStatus.WAITING_FOR_FOLDER ->
                R.string.pocketbridge_notification_setup_title to R.string.pocketbridge_notification_setup_text

            PocketBridgeRuntimeStatus.WAITING_FOR_LAN ->
                R.string.pocketbridge_notification_lan_title to R.string.pocketbridge_notification_lan_text

            PocketBridgeRuntimeStatus.FAILED ->
                R.string.pocketbridge_notification_failed_title to R.string.pocketbridge_notification_failed_text

            PocketBridgeRuntimeStatus.STARTING ->
                R.string.pocketbridge_status_starting to R.string.pocketbridge_status_starting_detail

            PocketBridgeRuntimeStatus.IDLE,
            PocketBridgeRuntimeStatus.WAITING_FOR_PROXY -> return
        }

        val launch = Intent(context, PocketBridgeConnectActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setOngoing(status == PocketBridgeRuntimeStatus.RUNNING)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ may have notification permission disabled. The
            // PocketBridge session remains functional; the owner can still use
            // the app's explicit control surface once integrated into main UI.
        }
    }

    private fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pocketbridge_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.pocketbridge_subtitle)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun cancelStatusNotification() {
        lastPublishedStatus = null
        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
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
    private const val CHANNEL_ID = "pocketbridge-local-share"
    private const val NOTIFICATION_ID = 4202
}
