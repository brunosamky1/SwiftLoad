package com.swiftload.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Row views of item_download.xml, shared by the Download tab and the Play tab. */
class DownloadRowVH(v: View) : RecyclerView.ViewHolder(v) {
    val icon: TextView = v.findViewById(R.id.icon)
    val thumb: ImageView = v.findViewById(R.id.thumb)
    val playIcon: ImageView = v.findViewById(R.id.playIcon)
    val duration: TextView = v.findViewById(R.id.duration)
    val title: TextView = v.findViewById(R.id.title)
    val meta: TextView = v.findViewById(R.id.meta)
    val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
    val action: ImageButton = v.findViewById(R.id.action)

    companion object {
        fun create(parent: ViewGroup) =
            DownloadRowVH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))
    }

    /** Emoji + thumbnail with rounded corners; videos also get a play icon and their length (mm:ss). */
    fun bindThumb(item: DownloadItem) {
        icon.text = emoji(item.category)
        val isVideo = item.category == Category.VIDEO
        duration.text = formatDuration(item.durationMs)
        duration.visibility = if (isVideo && item.durationMs > 0) View.VISIBLE else View.GONE

        val url = item.thumbnailUrl
        // The list refreshes several times a second while downloading: don't reload the same picture.
        if (url != null && url == thumb.tag) return
        thumb.tag = url
        playIcon.visibility = View.GONE
        if (url == null) {
            thumb.dispose()
            thumb.setImageDrawable(null) // the emoji underneath shows
            return
        }
        val radius = 12 * itemView.resources.displayMetrics.density
        thumb.load(url) {
            crossfade(true)
            transformations(RoundedCornersTransformation(radius))
            listener(
                onSuccess = { _, _ -> if (isVideo) playIcon.visibility = View.VISIBLE },
                onError = { _, _ -> thumb.setImageDrawable(null) },
            )
        }
    }
}

fun emoji(c: Category) = when (c) {
    Category.VIDEO -> "🎬"; Category.AUDIO -> "🎵"; Category.IMAGE -> "🖼️"
    Category.DOCUMENT -> "📄"; Category.ARCHIVE -> "🗜️"; Category.APP -> "📱"; Category.OTHER -> "📦"
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/** Active / failed downloads (Download tab) with progress, speed, cancel and retry. */
class DownloadAdapter(
    private val onAction: (DownloadItem) -> Unit,
    private val onLongPress: (DownloadItem) -> Unit,
) : ListAdapter<DownloadItem, DownloadRowVH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
            override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DownloadRowVH.create(parent)

    override fun onBindViewHolder(h: DownloadRowVH, position: Int) {
        val it = getItem(position)
        val ctx = h.itemView.context
        h.bindThumb(it)
        h.title.text = it.title
        val size = Storage.formatSize(it.sizeBytes)
        val kind = when (it.mode) { Mode.AUDIO_MP3 -> "MP3"; Mode.VIDEO -> "Video"; Mode.FILE -> it.category.label }

        when (it.status) {
            Status.QUEUED -> {
                h.progress.visibility = View.VISIBLE
                h.progress.isIndeterminate = true
                h.meta.text = "Waiting…"
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                h.action.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
            Status.RUNNING -> {
                h.progress.visibility = View.VISIBLE
                if (it.progress < 0) {
                    h.progress.isIndeterminate = true
                } else {
                    h.progress.isIndeterminate = false
                    h.progress.setProgressCompat(it.progress, true)
                }
                h.meta.text = listOf(
                    kind,
                    if (it.progress >= 0) "${it.progress}%" else "Starting…",
                    size, it.speed,
                ).filter { s -> s.isNotEmpty() }.joinToString("  •  ")
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                h.action.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            }
            Status.DONE -> {
                h.progress.visibility = View.GONE
                h.meta.text = listOf("✓ Done", kind, size).filter { s -> s.isNotEmpty() }.joinToString("  •  ")
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                h.action.setImageResource(android.R.drawable.ic_media_play)
            }
            Status.FAILED -> {
                h.progress.visibility = View.GONE
                h.meta.text = "✗ ${it.error ?: "Failed"} — tap ↻ to retry"
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.error))
                h.action.setImageResource(android.R.drawable.ic_menu_rotate)
            }
            Status.CANCELLED -> {
                h.progress.visibility = View.GONE
                h.meta.text = "Cancelled — tap ↻ to restart"
                h.meta.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                h.action.setImageResource(android.R.drawable.ic_menu_rotate)
            }
        }
        h.action.setOnClickListener { _ -> onAction(it) }
        h.itemView.setOnClickListener { _ -> if (it.status == Status.DONE) onAction(it) else onLongPress(it) }
        h.itemView.setOnLongClickListener { _ -> onLongPress(it); true }
    }
}

/** Long-press menu and actions for a download: open, share, rename, delete, retry … */
object ItemActions {

    fun primary(activity: Activity, item: DownloadItem) {
        when (item.status) {
            Status.RUNNING, Status.QUEUED -> DownloadService.cancel(activity, item.id)
            Status.DONE -> open(activity, item)
            Status.FAILED, Status.CANCELLED -> DownloadService.start(activity, item.id)
        }
    }

    fun showMenu(activity: Activity, item: DownloadItem) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (item.status == Status.DONE) {
            options += "Open" to { open(activity, item) }
            options += "Open with other app" to { openWith(activity, item) }
            options += "Share file" to { share(activity, item) }
            options += "Rename" to { askRename(activity, item) }
            options += "Delete file" to { askDelete(activity, item) }
        }
        if (item.status == Status.RUNNING || item.status == Status.QUEUED) {
            options += "Cancel download" to { DownloadService.cancel(activity, item.id) }
        }
        if (item.status == Status.FAILED || item.status == Status.CANCELLED || item.status == Status.DONE) {
            options += "Download again" to { DownloadService.start(activity, item.id) }
        }
        options += "Copy link" to {
            activity.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("link", item.url))
            Settings.setLastClipboard(activity, item.url)
            toast(activity, "Link copied")
        }
        options += "Remove from list" to {
            if (item.status == Status.RUNNING || item.status == Status.QUEUED) DownloadService.cancel(activity, item.id)
            DownloadStore.remove(item.id)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(item.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    fun open(activity: Activity, item: DownloadItem) {
        val uri = item.fileUri?.let(Uri::parse) ?: return
        try {
            activity.startActivity(Storage.openIntent(uri, item.mime))
        } catch (e: Exception) {
            toast(activity, "No app found to open this file")
        }
    }

    fun openWith(activity: Activity, item: DownloadItem) {
        val uri = item.fileUri?.let(Uri::parse) ?: return
        runCatching {
            activity.startActivity(Intent.createChooser(Storage.openIntent(uri, item.mime), "Open with"))
        }.onFailure { toast(activity, "No app found to open this file") }
    }

    fun share(activity: Activity, item: DownloadItem) {
        val uri = item.fileUri?.let(Uri::parse) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share file"))
    }

    private fun askRename(activity: Activity, item: DownloadItem) {
        val input = EditText(activity).apply {
            setText(item.title.let { if (item.mode == Mode.FILE) it.substringBeforeLast('.') else it })
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val box = FrameLayout(activity).apply {
            val p = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) rename(activity, item, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Renames the real file in Download/SwiftLoad (Android 10+) and the list entry. */
    private fun rename(activity: Activity, item: DownloadItem, newName: String) {
        val clean = Storage.sanitize(newName)
        val uri = item.fileUri?.let(Uri::parse)
        var ext = ""
        var renamedFile = false
        if (uri != null && Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            runCatching {
                activity.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) ext = c.getString(0).substringAfterLast('.', "") }
                val display = if (ext.isEmpty()) clean else "$clean.$ext"
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, display) }
                renamedFile = activity.contentResolver.update(uri, values, null, null) > 0
            }
        }
        if (ext.isEmpty() && item.mode == Mode.FILE) ext = item.title.substringAfterLast('.', "")
        val title = if (item.mode == Mode.FILE && ext.isNotEmpty()) "$clean.$ext" else clean
        DownloadStore.update(item.id, persist = true) { it.copy(title = title) }
        toast(activity, if (renamedFile) "Renamed" else "Renamed in SwiftLoad (the file name could not be changed)")
    }

    private fun askDelete(activity: Activity, item: DownloadItem) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete file?")
            .setMessage("\"${item.title}\" will be deleted from your phone. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val uri = item.fileUri?.let(Uri::parse)
                val ok = uri != null && runCatching { activity.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                DownloadStore.remove(item.id)
                toast(activity, if (ok) "Deleted" else "Removed from the list (the file could not be deleted — use your Files app)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(activity: Activity, s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()
}
