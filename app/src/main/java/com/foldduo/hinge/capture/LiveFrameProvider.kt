package com.foldduo.hinge.capture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Exposes only the frame-sink Binder to the paired ADB shell process. */
class LiveFrameProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_CONNECT || Binder.getCallingUid() != SHELL_UID) return null
        return Bundle().apply { putBinder(KEY_SINK, LiveFrameHub.sink) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.foldduo.hinge.liveframes"
        const val METHOD_CONNECT = "connect"
        const val KEY_SINK = "sink"
        private const val SHELL_UID = 2000
    }
}
