package com.swiftload.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Moves a finished download from the app's private temp folder into the phone's public
 * Download/SwiftLoad/<Category>/ folder so it shows up in Files, Gallery and music players.
 */
object Storage {
    const val ROOT = "VidSaver"
    /** Folder used before the rename (v2.0 and older). */
    const val OLD_ROOT = "SwiftLoad"

    fun tempDir(context: Context, id: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "tmp/$id").apply { mkdirs() }

    fun publish(context: Context, src: File, displayName: String, mime: String, category: Category): Uri {
        val name = sanitize(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/${category.folder}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            src.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$ROOT/${category.folder}")
            dir.mkdirs()
            val dest = uniqueFile(dir, name)
            if (!src.renameTo(dest)) {
                src.copyTo(dest, overwrite = true); src.delete()
            }
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
        }
    }

    fun openIntent(uri: Uri, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.')
        return cleaned.ifEmpty { "download" }.take(150)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
