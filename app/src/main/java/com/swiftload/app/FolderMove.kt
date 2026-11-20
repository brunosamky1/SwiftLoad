package com.swiftload.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * After the rename to VidSaver new downloads go to Download/VidSaver/…
 * Earlier downloads stay in Download/SwiftLoad/… (and keep showing in the app) unless the user agrees to move them.
 * Moving only changes the folder of files VidSaver itself saved (Android 10+), the list entries keep working.
 */
object FolderMove {
    private val oldPrefix = "${Environment.DIRECTORY_DOWNLOADS}/${Storage.OLD_ROOT}/"
    private val newPrefix = "${Environment.DIRECTORY_DOWNLOADS}/${Storage.ROOT}/"

    /** (uri, current relative path) of finished downloads that are still in the old folder. */
    suspend fun oldFiles(context: Context): List<Pair<Uri, String>> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext emptyList()
        DownloadStore.items.value
            .filter { it.status == Status.DONE && it.fileUri != null }
            .mapNotNull { item ->
                val uri = Uri.parse(item.fileUri)
                if (uri.authority != MediaStore.AUTHORITY) return@mapNotNull null
                runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()?.takeIf { it.startsWith(oldPrefix, ignoreCase = true) }?.let { uri to it }
            }
    }

    /** Moves the files; returns how many were moved. */
    suspend fun move(context: Context, files: List<Pair<Uri, String>>): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext 0
        files.count { (uri, path) ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, newPrefix + path.substring(oldPrefix.length))
            }
            runCatching { context.contentResolver.update(uri, values, null, null) > 0 }.getOrDefault(false)
        }
    }
}
