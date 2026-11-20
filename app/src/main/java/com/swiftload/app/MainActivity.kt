package com.swiftload.app

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private var toolbar: MaterialToolbar? = null

    override fun onResume() {
        super.onResume()
        toolbar?.menu?.findItem(R.id.menu_login)?.title =
            if (YoutubeCookies.isSignedIn(this)) "YouTube account ✓ (signed in)" else "Sign in to YouTube"
    }
    private val adapter = DownloadAdapter(::onItemAction, ::onItemLongPress)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.overflowIcon?.setTint(Color.WHITE)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }
        this.toolbar = toolbar

        urlInput = findViewById(R.id.urlInput)
        findViewById<View>(R.id.btnPaste).setOnClickListener {
            clipboardText()?.let { urlInput.setText(it); urlInput.setSelection(it.length) }
                ?: toast("Clipboard is empty")
        }
        findViewById<View>(R.id.btnDownload).setOnClickListener { startFromInput() }
        urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { startFromInput(); true } else false
        }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        val empty = findViewById<TextView>(R.id.empty)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.items.collect {
                    adapter.submitList(it)
                    empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        askPermissions()
    }

    private fun askPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    /** IDM-like: if you copied a link, the app offers to download it as soon as you open it. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = clipboardText() ?: return
        val url = Detector.extractUrl(clip) ?: return
        if (url == Settings.lastClipboard(this)) return
        Settings.setLastClipboard(this, url)
        Snackbar.make(findViewById(R.id.list), "Copied link found:\n$url", Snackbar.LENGTH_LONG)
            .setAction("Download") { openShareFlow(url) }
            .show()
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.coerceToText(this)?.toString()?.trim()?.ifEmpty { null }
    }

    private fun startFromInput() {
        val url = Detector.extractUrl(urlInput.text.toString())
        if (url == null) { toast("Please enter a valid link"); return }
        urlInput.setText("")
        openShareFlow(url)
    }

    /** Re-uses the same detect → download pop-up as the Share menu. */
    private fun openShareFlow(url: String) {
        startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
    }

    private fun onItemAction(item: DownloadItem) {
        when (item.status) {
            Status.RUNNING, Status.QUEUED -> DownloadService.cancel(this, item.id)
            Status.DONE -> openFile(item)
            Status.FAILED, Status.CANCELLED -> DownloadService.start(this, item.id)
        }
    }

    private fun onItemLongPress(item: DownloadItem) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (item.status == Status.DONE) {
            options += "Open" to { openFile(item) }
            options += "Share file" to { shareFile(item) }
        }
        if (item.status == Status.FAILED || item.status == Status.CANCELLED || item.status == Status.DONE) {
            options += "Download again" to { DownloadService.start(this, item.id) }
        }
        options += "Copy link" to {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("link", item.url))
            Settings.setLastClipboard(this, item.url)
            toast("Link copied")
        }
        options += "Remove from list" to {
            if (item.status == Status.RUNNING || item.status == Status.QUEUED) DownloadService.cancel(this, item.id)
            DownloadStore.remove(item.id)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun openFile(item: DownloadItem) {
        val uri = item.fileUri?.let(Uri::parse) ?: return
        try {
            startActivity(Storage.openIntent(uri, item.mime))
        } catch (e: Exception) {
            toast("No app found to open this file")
        }
    }

    private fun shareFile(item: DownloadItem) {
        val uri = item.fileUri?.let(Uri::parse) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share file"))
    }

    private fun onMenu(id: Int) {
        when (id) {
            R.id.menu_login -> {
                if (YoutubeCookies.isSignedIn(this)) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("YouTube account")
                        .setMessage("You are signed in. YouTube downloads use your account.")
                        .setPositiveButton("Sign out") { _, _ ->
                            YoutubeCookies.signOut(this)
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            toast("Signed out")
                        }
                        .setNegativeButton("Close", null)
                        .show()
                } else {
                    startActivity(Intent(this, LoginActivity::class.java))
                }
            }
            R.id.menu_media_action -> {
                val labels = arrayOf("Ask me every time", "Always download video (MP4)", "Always download audio (MP3)")
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.media_action)
                    .setSingleChoiceItems(labels, Settings.mediaAction(this)) { d, which ->
                        Settings.setMediaAction(this, which); d.dismiss()
                    }.show()
            }
            R.id.menu_quality -> {
                val values = intArrayOf(0, 1080, 720, 480, 360)
                val labels = arrayOf("Best available", "1080p (Full HD)", "720p (HD)", "480p (saves data)", "360p (smallest)")
                val current = values.indexOf(Settings.videoQuality(this)).coerceAtLeast(0)
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.video_quality)
                    .setSingleChoiceItems(labels, current) { d, which ->
                        Settings.setVideoQuality(this, values[which]); d.dismiss()
                    }.show()
            }
            R.id.menu_update -> {
                toast("Updating engine… please wait")
                lifecycleScope.launch {
                    val msg = runCatching { Engine.update(this@MainActivity) }
                        .getOrElse { "Update failed: ${it.message?.take(120)}" }
                    toast(msg)
                }
            }
            R.id.menu_open_folder -> runCatching {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            }.onFailure { toast("Open your Files app → Download → SwiftLoad") }
            R.id.menu_clear -> DownloadStore.clearFinished()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------- list adapter

class DownloadAdapter(
    private val onAction: (DownloadItem) -> Unit,
    private val onLongPress: (DownloadItem) -> Unit,
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
            override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val progress: LinearProgressIndicator = v.findViewById(R.id.progress)
        val action: ImageButton = v.findViewById(R.id.action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = getItem(position)
        val ctx = h.itemView.context
        h.icon.text = when (it.category) {
            Category.VIDEO -> "🎬"; Category.AUDIO -> "🎵"; Category.IMAGE -> "🖼️"
            Category.DOCUMENT -> "📄"; Category.ARCHIVE -> "🗜️"; Category.APP -> "📱"; Category.OTHER -> "📦"
        }
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
