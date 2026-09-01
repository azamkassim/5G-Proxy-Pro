package com.tokyoxpa3.androidproxy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.tokyoxpa3.androidproxy.integration.PocketBridgeRuntimeCoordinator

/**
 * Internal process initializer only. It exposes no provider data and is never
 * exported; its sole job is to attach PocketBridge sidecars to the existing
 * foreground proxy service lifecycle without creating another service.
 */
class PocketBridgeRuntimeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(PocketBridgeRuntimeCoordinator::initialize)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
