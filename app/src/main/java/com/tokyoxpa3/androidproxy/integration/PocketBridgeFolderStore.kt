package com.tokyoxpa3.androidproxy.integration

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Owns the single user-selected Storage Access Framework tree used by
 * PocketBridge. No broad storage permission is required.
 */
class PocketBridgeFolderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTreeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun hasPersistedAccess(): Boolean {
        val tree = getTreeUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == tree && permission.isReadPermission && permission.isWritePermission
        }
    }

    fun persistTree(uri: Uri, resultFlags: Int): Boolean {
        val requested = resultFlags and PERSISTABLE_RW_FLAGS
        if (requested == 0) return false

        return try {
            context.contentResolver.takePersistableUriPermission(uri, requested)
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            if (permission?.isReadPermission == true && permission.isWritePermission) {
                prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
                true
            } else {
                false
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun clear() {
        val uri = getTreeUri()
        if (uri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, PERSISTABLE_RW_FLAGS)
            } catch (_: SecurityException) {
                // Permission may already have been revoked externally.
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    companion object {
        private const val PREFS_NAME = "pocketbridge_storage"
        private const val KEY_TREE_URI = "tree_uri"
        const val PERSISTABLE_RW_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
