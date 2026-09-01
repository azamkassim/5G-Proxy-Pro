package com.tokyoxpa3.androidproxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.tokyoxpa3.androidproxy.integration.PocketBridgeFolderStore
import com.tokyoxpa3.androidproxy.integration.PocketBridgeInboxWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Universal Android Share Sheet entry point for PocketBridge.
 *
 * The first share asks the user to select a folder through the Storage Access
 * Framework. Later shares are copied directly to that folder's Inbox using the
 * persisted narrow tree grant; no blanket storage permission is requested.
 */
class ShareReceiverActivity : Activity() {
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val folderStore by lazy { PocketBridgeFolderStore(this) }
    private val inboxWriter by lazy { PocketBridgeInboxWriter(this) }
    private var pendingShareIntent: Intent? = null
    private var pickerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isSupportedShare(intent)) {
            finish()
            return
        }

        pendingShareIntent = Intent(intent)

        if (folderStore.hasPersistedAccess()) {
            importPendingShare()
        } else {
            if (folderStore.getTreeUri() != null) folderStore.clear()
            openFolderPicker()
        }
    }

    private fun openFolderPicker() {
        if (pickerOpen) return
        pickerOpen = true

        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(picker, REQUEST_POCKETBRIDGE_FOLDER)
    }

    @Deprecated("Deprecated in Android API; retained for minSdk 26 compatibility without adding an activity-result dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_POCKETBRIDGE_FOLDER) return

        pickerOpen = false
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            Toast.makeText(this, getString(R.string.pocketbridge_folder_required), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val persisted = folderStore.persistTree(uri, data.flags)
        if (!persisted) {
            Toast.makeText(this, getString(R.string.pocketbridge_folder_permission_failed), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        importPendingShare()
    }

    private fun importPendingShare() {
        val shareIntent = pendingShareIntent ?: run {
            finish()
            return
        }
        pendingShareIntent = null

        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                inboxWriter.importShare(shareIntent)
            }

            val message = when {
                result.isSuccess -> getString(R.string.pocketbridge_import_success, result.imported)
                result.isPartial -> getString(
                    R.string.pocketbridge_import_partial,
                    result.imported,
                    result.failed
                )
                else -> getString(R.string.pocketbridge_import_failed)
            }
            Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun isSupportedShare(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
        else -> false
    }

    companion object {
        private const val REQUEST_POCKETBRIDGE_FOLDER = 4201
    }
}
