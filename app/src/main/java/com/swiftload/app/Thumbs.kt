package com.swiftload.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * Local thumbnails for finished downloads, saved as cacheDir/thumbs/<id>.jpg:
 *  - video -> a frame from about 1 s in
 *  - MP3/audio -> the embedded cover art (if any)
 *  - image -> no file needed, the image itself is shown
 */
object Thumbs {
    data class Local(val thumbUri: String?, val durationMs: Long)

    private const val MAX_SIDE = 320

    fun file(context: Context, id: String) = File(File(context.cacheDir, "thumbs"), "$id.jpg")

    fun delete(context: Context, id: String) {
        runCatching { file(context, id).delete() }
    }

    /** From the downloaded temp file (call this BEFORE it is moved into Downloads). */
    fun fromFile(context: Context, id: String, f: File, category: Category): Local =
        generate(context, id, category) { it.setDataSource(f.absolutePath) }

    /** From a file already saved in Downloads (old list items). */
    fun fromUri(context: Context, id: String, uri: Uri, category: Category): Local =
        generate(context, id, category) { it.setDataSource(context, uri) }

    private fun generate(
        context: Context, id: String, category: Category, source: (MediaMetadataRetriever) -> Unit,
    ): Local {
        if (category != Category.VIDEO && category != Category.AUDIO) return Local(null, -1)
        val r = MediaMetadataRetriever()
        return try {
            source(r)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
            val bmp: Bitmap? = if (category == Category.AUDIO) {
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else {
                val t = 1_000_000L // 1 s in, skips black first frames
                val scaled = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_SIDE, MAX_SIDE)
                } else null
                scaled ?: r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime()
            }
            Local(bmp?.let { save(context, id, it) }, duration)
        } catch (e: Throwable) {
            Local(null, -1)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun save(context: Context, id: String, src: Bitmap): String? = runCatching {
        val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        } else src
        val f = file(context, id)
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        Uri.fromFile(f).toString()
    }.getOrNull()

    /** True if the item should get a (new) local thumbnail: none yet, or its cached file was cleared. */
    private fun needsLocal(context: Context, item: DownloadItem): Boolean {
        val t = item.thumbnailUrl ?: return true
        if (t.startsWith("file:")) return !File(Uri.parse(t).path ?: return true).exists()
        // Online thumbnail only: videos still get a local frame (and their duration)
        return item.category == Category.VIDEO && item.durationMs <= 0
    }

    /** Old finished downloads in the list without a thumbnail: create one from the saved file. */
    fun backfill(context: Context) {
        DownloadStore.items.value
            .filter { it.status == Status.DONE && it.fileUri != null && needsLocal(context, it) }
            .forEach { item ->
                val uri = Uri.parse(item.fileUri)
                when (item.category) {
                    Category.IMAGE -> DownloadStore.update(item.id, persist = true) { it.copy(thumbnailUrl = item.fileUri) }
                    Category.VIDEO, Category.AUDIO -> {
                        val local = fromUri(context, item.id, uri, item.category)
                        if (local.thumbUri != null || local.durationMs > 0) {
                            DownloadStore.update(item.id, persist = true) {
                                it.copy(
                                    thumbnailUrl = local.thumbUri ?: it.thumbnailUrl,
                                    durationMs = if (local.durationMs > 0) local.durationMs else it.durationMs,
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
    }
}
