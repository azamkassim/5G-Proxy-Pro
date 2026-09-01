package com.tokyoxpa3.androidproxy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tokyoxpa3.androidproxy.integration.PocketBridgeFolderStore
import com.tokyoxpa3.androidproxy.integration.PocketBridgeRuntimeState
import com.tokyoxpa3.androidproxy.integration.PocketBridgeRuntimeStatus

/**
 * Small owner-only control surface for PocketBridge connectivity.
 *
 * It deliberately does not duplicate the proxy UI. Its responsibilities are
 * limited to the one-time SAF folder grant and showing/sharing the ephemeral
 * QuickDrop connection URL produced by the session coordinator.
 */
class PocketBridgeConnectActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var folderText: TextView
    private lateinit var copyButton: Button
    private lateinit var openButton: Button
    private lateinit var shareButton: Button
    private lateinit var folderButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pocketbridge_title)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        refreshRunnable.run()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.pocketbridge_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.pocketbridge_subtitle)
            textSize = 15f
            setPadding(0, dp(4), 0, dp(20))
        })

        statusText = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        }
        container.addView(statusText)

        detailText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(20))
        }
        container.addView(detailText)

        copyButton = Button(this).apply {
            text = getString(R.string.pocketbridge_copy_link)
            setOnClickListener { currentConnectUrl()?.let(::copyLink) }
        }
        container.addView(copyButton, matchWidth(dp(8)))

        openButton = Button(this).apply {
            text = getString(R.string.pocketbridge_open_portal)
            setOnClickListener { currentConnectUrl()?.let(::openLink) }
        }
        container.addView(openButton, matchWidth(dp(8)))

        shareButton = Button(this).apply {
            text = getString(R.string.pocketbridge_share_link)
            setOnClickListener { currentConnectUrl()?.let(::shareLink) }
        }
        container.addView(shareButton, matchWidth(dp(18)))

        container.addView(TextView(this).apply {
            text = getString(R.string.pocketbridge_storage_heading)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        })

        folderText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(folderText)

        folderButton = Button(this).apply {
            text = getString(R.string.pocketbridge_choose_folder)
            setOnClickListener { openFolderPicker() }
        }
        container.addView(folderButton, matchWidth(dp(16)))

        container.addView(TextView(this).apply {
            text = getString(R.string.pocketbridge_security_note)
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(12), 0, 0)
        })

        return ScrollView(this).apply { addView(container) }
    }

    private fun matchWidth(bottomMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, bottomMargin) }

    private fun renderState() {
        val snapshot = PocketBridgeRuntimeState.get()
        val folderStore = PocketBridgeFolderStore(this)
        val folderUri = folderStore.getTreeUri()
        folderText.text = if (folderStore.hasPersistedAccess() && folderUri != null) {
            getString(R.string.pocketbridge_folder_configured, folderUri.toString())
        } else {
            getString(R.string.pocketbridge_folder_not_configured)
        }
        folderButton.text = if (folderUri == null) {
            getString(R.string.pocketbridge_choose_folder)
        } else {
            getString(R.string.pocketbridge_change_folder)
        }

        val hasLink = snapshot.status == PocketBridgeRuntimeStatus.RUNNING && snapshot.connectUrls.isNotEmpty()
        copyButton.isEnabled = hasLink
        openButton.isEnabled = hasLink
        shareButton.isEnabled = hasLink

        when (snapshot.status) {
            PocketBridgeRuntimeStatus.RUNNING -> {
                statusText.text = getString(R.string.pocketbridge_status_ready)
                detailText.text = buildString {
                    append(getString(R.string.pocketbridge_quickdrop_detail, snapshot.portalPort ?: 0))
                    val publicAddress = snapshot.connectUrls.firstOrNull()?.substringBefore('#')
                    if (!publicAddress.isNullOrBlank()) {
                        append("\n")
                        append(publicAddress)
                    }
                    append("\n\n")
                    append(getString(R.string.pocketbridge_token_private_note))
                }
            }

            PocketBridgeRuntimeStatus.WAITING_FOR_FOLDER -> {
                statusText.text = getString(R.string.pocketbridge_status_folder_needed)
                detailText.text = getString(R.string.pocketbridge_status_folder_needed_detail)
            }

            PocketBridgeRuntimeStatus.WAITING_FOR_LAN -> {
                statusText.text = getString(R.string.pocketbridge_status_lan_needed)
                detailText.text = getString(R.string.pocketbridge_status_lan_needed_detail)
            }

            PocketBridgeRuntimeStatus.WAITING_FOR_PROXY,
            PocketBridgeRuntimeStatus.IDLE -> {
                statusText.text = getString(R.string.pocketbridge_status_proxy_needed)
                detailText.text = getString(R.string.pocketbridge_status_proxy_needed_detail)
            }

            PocketBridgeRuntimeStatus.STARTING -> {
                statusText.text = getString(R.string.pocketbridge_status_starting)
                detailText.text = getString(R.string.pocketbridge_status_starting_detail)
            }

            PocketBridgeRuntimeStatus.FAILED -> {
                statusText.text = getString(R.string.pocketbridge_status_failed)
                detailText.text = snapshot.lastError ?: getString(R.string.pocketbridge_status_failed_detail)
            }
        }
    }

    private fun currentConnectUrl(): String? = PocketBridgeRuntimeState.get().connectUrls.firstOrNull()

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.pocketbridge_connection_link_label), url))
        Toast.makeText(this, getString(R.string.pocketbridge_link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.pocketbridge_no_browser), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLink(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.pocketbridge_connection_link_label))
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, getString(R.string.pocketbridge_share_link)))
    }

    private fun openFolderPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(picker, REQUEST_POCKETBRIDGE_FOLDER)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk 26 compatibility without another dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_POCKETBRIDGE_FOLDER) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return

        val store = PocketBridgeFolderStore(this)
        if (!store.persistTree(uri, data.flags)) {
            Toast.makeText(this, getString(R.string.pocketbridge_folder_permission_failed), Toast.LENGTH_LONG).show()
        }
        renderState()
    }

    companion object {
        private const val REQUEST_POCKETBRIDGE_FOLDER = 4203
        private const val REFRESH_MS = 1000L
    }
}
