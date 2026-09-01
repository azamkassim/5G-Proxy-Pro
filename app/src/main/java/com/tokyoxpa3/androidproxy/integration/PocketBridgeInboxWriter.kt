package com.tokyoxpa3.androidproxy.integration

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of one explicit Android share handoff into PocketBridge. */
data class PocketBridgeImportResult(
    val imported: Int,
    val failed: Int,
    val importedNames: List<String> = emptyList(),
    val error: String? = null
) {
    val isSuccess: Boolean get() = imported > 0 && failed == 0
    val isPartial: Boolean get() = imported > 0 && failed > 0
}

/**
 * Copies content explicitly shared by the user into the SAF-backed Inbox.
 *
 * This class never scans another application's private storage. It only reads
 * content URIs that Android has granted to PocketBridge for the incoming share.
 */
class PocketBridgeInboxWriter(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val folderStore = PocketBridgeFolderStore(context)

    fun importShare(intent: Intent): PocketBridgeImportResult {
        val allUris = collectStreamUris(intent)
        val acceptedUris = allUris.take(MAX_ITEMS_PER_SHARE)
        var failed = (allUris.size - acceptedUris.size).coerceAtLeast(0)
        var imported = 0
        val importedNames = mutableListOf<String>()

        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        val rootUri = folderStore.getTreeUri()
            ?: return PocketBridgeImportResult(
                imported = 0,
                failed = acceptedUris.size + if (text != null) 1 else 0,
                error = "PocketBridge folder is not configured"
            )

        val root = DocumentFile.fromTreeUri(context, rootUri)
        if (root == null || !root.exists() || !root.isDirectory || !root.canWrite()) {
            folderStore.clear()
            return PocketBridgeImportResult(
                imported = 0,
                failed = acceptedUris.size + if (text != null) 1 else 0,
                error = "PocketBridge folder access is no longer available"
            )
        }

        val inbox = ensureInbox(root)
            ?: return PocketBridgeImportResult(
                imported = 0,
                failed = acceptedUris.size + if (text != null) 1 else 0,
                error = "Unable to create or open PocketBridge Inbox"
            )

        acceptedUris.forEach { sourceUri ->
            try {
                val importedName = copyUriToInbox(sourceUri, inbox)
                imported++
                importedNames += importedName
            } catch (_: Exception) {
                failed++
            }
        }

        if (text != null) {
            try {
                val importedName = writeTextToInbox(text, inbox)
                imported++
                importedNames += importedName
            } catch (_: Exception) {
                failed++
            }
        }

        if (imported == 0 && failed == 0) {
            return PocketBridgeImportResult(
                imported = 0,
                failed = 1,
                error = "The share did not contain a file, text, or link"
            )
        }

        return PocketBridgeImportResult(
            imported = imported,
            failed = failed,
            importedNames = importedNames,
            error = if (imported == 0 && failed > 0) "Nothing could be imported" else null
        )
    }

    private fun ensureInbox(root: DocumentFile): DocumentFile? {
        val existing = root.findFile(INBOX_DIRECTORY)
        if (existing != null) return existing.takeIf { it.isDirectory && it.canWrite() }
        return root.createDirectory(INBOX_DIRECTORY)
    }

    private fun copyUriToInbox(sourceUri: Uri, inbox: DocumentFile): String {
        val mimeType = resolver.getType(sourceUri) ?: "application/octet-stream"
        val desiredName = queryDisplayName(sourceUri) ?: fallbackFileName(mimeType)
        val targetName = PocketBridgeShareNames.nextAvailableName(desiredName) { candidate ->
            inbox.findFile(candidate) != null
        }
        val target = inbox.createFile(mimeType, targetName)
            ?: throw IOException("Unable to create destination")

        try {
            val input = resolver.openInputStream(sourceUri)
                ?: throw IOException("Unable to open shared content")
            input.use { source ->
                val output = resolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("Unable to open destination")
                output.use { destination ->
                    source.copyTo(destination, COPY_BUFFER_SIZE)
                    destination.flush()
                }
            }
            return target.name ?: targetName
        } catch (error: Exception) {
            try {
                target.delete()
            } catch (_: Exception) {
                // Best-effort cleanup only.
            }
            throw error
        }
    }

    private fun writeTextToInbox(text: String, inbox: DocumentFile): String {
        val timestamp = FILE_TIME_FORMAT.format(Date())
        val desiredName = "Shared Text $timestamp.txt"
        val targetName = PocketBridgeShareNames.nextAvailableName(desiredName) { candidate ->
            inbox.findFile(candidate) != null
        }
        val target = inbox.createFile("text/plain", targetName)
            ?: throw IOException("Unable to create text destination")

        try {
            val output = resolver.openOutputStream(target.uri, "w")
                ?: throw IOException("Unable to open text destination")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(text)
            }
            return target.name ?: targetName
        } catch (error: Exception) {
            try {
                target.delete()
            } catch (_: Exception) {
                // Best-effort cleanup only.
            }
            throw error
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fallbackFileName(mimeType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val timestamp = FILE_TIME_FORMAT.format(Date())
        return if (extension.isNullOrBlank()) "Shared File $timestamp" else "Shared File $timestamp.$extension"
    }

    @Suppress("DEPRECATION")
    private fun collectStreamUris(intent: Intent): List<Uri> {
        val uris = linkedSetOf<Uri>()

        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(uris::add)
            }
        }

        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?.forEach(uris::add)
        }

        return uris.toList()
    }

    companion object {
        private const val INBOX_DIRECTORY = "Inbox"
        private const val MAX_ITEMS_PER_SHARE = 100
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private val FILE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
    }
}
