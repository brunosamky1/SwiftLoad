package com.swiftload.app

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Honest junk cleaner: real sizes, the user sees and chooses what is deleted,
 * and nothing is deleted without confirmation.
 */
class CleanerActivity : AppCompatActivity() {

    companion object {
        fun hasFullAccess(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

        /** Opens Android's "All files access" screen for VidSaver (Android 11+). */
        fun openAllFilesAccess(context: Context) {
            if (Build.VERSION.SDK_INT < 30) return
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            }.onFailure {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private enum class Phase { WAITING, SCANNING, RESULTS, CLEANING, DONE }

    private var phase = Phase.WAITING
    private var scanner: JunkScanner? = null
    private var scanJob: Job? = null
    private var waitingForSettings = false
    private var shownTotal = 0L
    private var animator: ValueAnimator? = null
    private var freedSoFar = 0L
    private var pendingTrash: List<JunkItem> = emptyList()

    private lateinit var totalValue: TextView
    private lateinit var totalUnit: TextView
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var mainButton: MaterialButton
    private val adapter = CategoryAdapter(
        onToggle = { c -> toggleCategory(c) },
        onOpen = { c -> showDetails(c) },
    )

    private val legacyPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startScan() }

    private val trashRequest =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == RESULT_OK) freedSoFar += pendingTrash.sumOf { it.size }
            pendingTrash = emptyList()
            showDone()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cleaner)
        totalValue = findViewById(R.id.totalValue)
        totalUnit = findViewById(R.id.totalUnit)
        status = findViewById(R.id.status)
        banner = findViewById(R.id.limitedBanner)
        mainButton = findViewById(R.id.mainButton)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(getColor(R.color.text_primary))
            setNavigationOnClickListener { finish() }
        }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@CleanerActivity)
            adapter = this@CleanerActivity.adapter
            itemAnimator = null
        }
        mainButton.setOnClickListener { onMainButton() }
        banner.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) { waitingForSettings = true; openAllFilesAccess(this) }
            else legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
        findViewById<View>(R.id.resultDone).setOnClickListener { finish() }

        askAccessThenScan()
    }

    // ---------------------------------------------------------------- permission

    private fun askAccessThenScan() {
        if (hasFullAccess(this)) { startScan(); return }
        if (Build.VERSION.SDK_INT < 30) {
            legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        if (Settings.cleanerAsked(this)) { startScan(); return }
        Settings.setCleanerAsked(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow access to all files?")
            .setMessage(
                "To find junk on your phone (thumbnails, old APK files, temporary files, empty folders, " +
                    "large and duplicate files) VidSaver needs “All files access”.\n\n" +
                    "• Nothing is deleted without your confirmation.\n" +
                    "• Nothing is uploaded — the scan runs only on your phone.\n\n" +
                    "Without it, VidSaver can only check its own cache and the trash (limited mode)."
            )
            .setPositiveButton("Allow") { _, _ -> waitingForSettings = true; openAllFilesAccess(this) }
            .setNegativeButton("Limited mode") { _, _ -> startScan() }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForSettings) {
            waitingForSettings = false
            if (phase == Phase.WAITING || hasFullAccess(this)) startScan()
        }
    }

    // ---------------------------------------------------------------- scan

    private fun startScan() {
        if (phase == Phase.CLEANING || phase == Phase.DONE) return
        scanner?.stopRequested = true
        scanJob?.cancel()
        val full = hasFullAccess(this)
        banner.visibility = if (full) View.GONE else View.VISIBLE
        val s = JunkScanner(applicationContext, full)
        scanner = s
        adapter.submit(s.categories)
        phase = Phase.SCANNING
        shownTotal = 0
        setTotal(0)
        mainButton.text = "Stop"
        mainButton.isEnabled = true
        scanJob = lifecycleScope.launch {
            val ticker = launch {
                while (isActive) {
                    val p = s.currentPath
                    status.text = if (p.isEmpty()) "Scanning…"
                    else "Scanning: " + p.removePrefix(Environment.getExternalStorageDirectory().path).ifEmpty { "/" }
                    animateTotal(s.totalFound)
                    adapter.refresh()
                    delay(150)
                }
            }
            s.run()
            ticker.cancel()
            onScanFinished(s)
        }
    }

    private fun onScanFinished(s: JunkScanner) {
        phase = Phase.RESULTS
        adapter.refresh()
        animateTotal(s.categories.sumOf { if (it.state == JunkCategory.State.DONE) it.totalSize else 0L })
        status.text = when {
            s.stopRequested -> "Scan stopped — showing what was found so far"
            s.totalFound == 0L && s.categories.all { it.items.isEmpty() } -> "No junk found 🎉"
            else -> "Junk found — tap a category to see the files"
        }
        updateCleanButton()
    }

    private fun onMainButton() {
        when (phase) {
            Phase.SCANNING -> {
                scanner?.stopRequested = true
                mainButton.text = "Stopping…"
                mainButton.isEnabled = false
            }
            Phase.RESULTS -> confirmClean()
            else -> Unit
        }
    }

    private fun selected(): List<JunkItem> =
        scanner?.categories.orEmpty().filter { it.state == JunkCategory.State.DONE }.flatMap { it.selectedItems }
            .distinctBy { it.path ?: it.uri.toString() }

    private fun updateCleanButton() {
        if (phase != Phase.RESULTS) return
        val sel = selected()
        val size = sel.sumOf { it.size }
        mainButton.isEnabled = sel.isNotEmpty()
        mainButton.text = if (sel.isEmpty()) "Nothing selected" else "Clean ${Storage.formatSize(size).ifEmpty { "0 B" }}"
    }

    // ---------------------------------------------------------------- selection

    private fun toggleCategory(c: JunkCategory) {
        if (c.state != JunkCategory.State.DONE || c.items.isEmpty()) return
        val selectAll = c.items.none { it.selected }
        c.items.forEach { it.selected = selectAll }
        adapter.refresh()
        updateCleanButton()
    }

    private fun showDetails(c: JunkCategory) {
        if (c.state != JunkCategory.State.DONE || c.items.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        val title = TextView(this).apply {
            textSize = 17f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val selectAll = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(selectAll)
        val info = TextView(this).apply {
            text = c.type.info
            textSize = 12.5f
            setTextColor(getColor(R.color.text_secondary))
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (8 * dp).toInt())
        }
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@CleanerActivity) }
        fun refreshHeader() {
            val sel = c.selectedItems
            title.text = "${c.type.title} — ${sel.size}/${c.items.size} • ${Storage.formatSize(sel.sumOf { it.size }).ifEmpty { "0 B" }}"
            selectAll.text = if (c.items.all { it.selected }) "Unselect all" else "Select all"
        }
        val fileAdapter = FileAdapter(c.items) { refreshHeader() }
        list.adapter = fileAdapter
        selectAll.setOnClickListener {
            val all = !c.items.all { it.selected }
            c.items.forEach { it.selected = all }
            fileAdapter.notifyItemRangeChanged(0, c.items.size)
            refreshHeader()
        }
        refreshHeader()
        root.addView(header)
        root.addView(info)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.62).toInt()))
        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { adapter.refresh(); updateCleanButton() }
        dialog.show()
    }

    // ---------------------------------------------------------------- cleaning

    private fun confirmClean() {
        val sel = selected()
        if (sel.isEmpty()) return
        val size = Storage.formatSize(sel.sumOf { it.size }).ifEmpty { "0 B" }
        val trashCount = sel.count { it.kind == JunkKind.MEDIASTORE }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${sel.size} files ($size)?")
            .setMessage(
                "This cannot be undone." +
                    if (trashCount > 0) "\n\nFor the $trashCount trash item(s) Android will ask you to confirm once more." else ""
            )
            .setPositiveButton("Delete") { _, _ -> clean(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clean(sel: List<JunkItem>) {
        phase = Phase.CLEANING
        mainButton.isEnabled = false
        mainButton.text = "Cleaning…"
        status.text = "Deleting selected files…"
        lifecycleScope.launch {
            val (_, freed) = JunkCleaner.deleteFiles(applicationContext, sel.filter { it.kind != JunkKind.MEDIASTORE })
            freedSoFar = freed
            val trash = sel.filter { it.kind == JunkKind.MEDIASTORE && it.uri != null }
            if (trash.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                pendingTrash = trash
                runCatching {
                    val pi = MediaStore.createDeleteRequest(contentResolver, trash.mapNotNull { it.uri })
                    trashRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }.onFailure { pendingTrash = emptyList(); showDone() }
            } else {
                showDone()
            }
        }
    }

    private fun showDone() {
        phase = Phase.DONE
        Settings.addCleaned(this, freedSoFar)
        findViewById<View>(R.id.scanView).visibility = View.GONE
        findViewById<View>(R.id.resultView).visibility = View.VISIBLE
        val check = findViewById<View>(R.id.resultCheck)
        check.scaleX = 0f; check.scaleY = 0f
        check.animate().scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(OvershootInterpolator()).start()
        val value = findViewById<TextView>(R.id.resultValue)
        ValueAnimator.ofFloat(0f, freedSoFar.toFloat()).apply {
            duration = 1200
            addUpdateListener { value.text = Storage.formatSize((it.animatedValue as Float).toLong()).ifEmpty { "0 B" } }
            start()
        }
        findViewById<TextView>(R.id.resultText).text =
            "freed\n\nCleaned so far: ${Storage.formatSize(Settings.cleanedTotal(this)).ifEmpty { "0 B" }}"
    }

    // ---------------------------------------------------------------- big number

    private fun animateTotal(target: Long) {
        if (target == shownTotal) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shownTotal.toFloat(), target.toFloat()).apply {
            duration = 400
            addUpdateListener { setTotal((it.animatedValue as Float).toLong()) }
            start()
        }
        shownTotal = target
    }

    private fun setTotal(bytes: Long) {
        val s = Storage.formatSize(bytes).ifEmpty { "0 B" }
        totalValue.text = s.substringBefore(' ')
        totalUnit.text = s.substringAfter(' ', "B")
    }

    override fun onDestroy() {
        scanner?.stopRequested = true
        animator?.cancel()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- adapters

private class CategoryAdapter(
    private val onToggle: (JunkCategory) -> Unit,
    private val onOpen: (JunkCategory) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items: List<JunkCategory> = emptyList()

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<JunkCategory>) { items = list; notifyDataSetChanged() }

    fun refresh() = notifyItemRangeChanged(0, items.size)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val spinner: View = v.findViewById(R.id.spinner)
        val size: TextView = v.findViewById(R.id.size)
        val check: MaterialCheckBox = v.findViewById(R.id.check)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_junk_category, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.icon.text = c.type.emoji
        h.title.text = c.type.title
        val done = c.state == JunkCategory.State.DONE
        when (c.state) {
            JunkCategory.State.UNAVAILABLE -> {
                h.spinner.visibility = View.GONE
                h.size.text = "—"
                h.check.visibility = View.INVISIBLE
                h.subtitle.text = c.reason ?: "Not available"
            }
            JunkCategory.State.DONE -> {
                h.spinner.visibility = View.GONE
                h.size.text = if (c.items.isEmpty()) "0 B" else Storage.formatSize(c.totalSize).ifEmpty { "0 B" }
                h.check.visibility = if (c.items.isEmpty()) View.INVISIBLE else View.VISIBLE
                val sel = c.selectedItems.size
                h.subtitle.text = when {
                    c.items.isEmpty() -> if (c.stopped) "Scan stopped" else "Nothing found"
                    else -> "${c.items.size} item${if (c.items.size == 1) "" else "s"} • $sel selected" +
                        (if (c.stopped) " • scan stopped" else "") + " — tap to review"
                }
                h.check.checkedState = when {
                    c.items.all { it.selected } -> MaterialCheckBox.STATE_CHECKED
                    c.items.none { it.selected } -> MaterialCheckBox.STATE_UNCHECKED
                    else -> MaterialCheckBox.STATE_INDETERMINATE
                }
            }
            else -> {
                h.spinner.visibility = View.VISIBLE
                val found = c.found.get()
                h.size.text = if (found > 0) Storage.formatSize(found) else ""
                h.check.visibility = View.INVISIBLE
                h.subtitle.text = c.type.info
            }
        }
        h.check.setOnClickListener { onToggle(c) }
        h.itemView.setOnClickListener { if (done) onOpen(c) }
    }
}

private class FileAdapter(
    private val items: List<JunkItem>,
    private val onChanged: () -> Unit,
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.icon)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val path: TextView = v.findViewById(R.id.path)
        val meta: TextView = v.findViewById(R.id.meta)
        val check: MaterialCheckBox = v.findViewById(R.id.check)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_junk_file, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.icon.text = when {
            it.kind == JunkKind.EMPTY_DIR || it.kind == JunkKind.DIR_TREE -> "📁"
            it.isVideo -> "🎬"
            it.isImage -> "🖼️"
            it.name.endsWith(".apk", true) || it.name.endsWith(".xapk", true) || it.name.endsWith(".apks", true) -> "📦"
            else -> "📄"
        }
        if (it.isImage || it.isVideo) {
            val src: Any? = it.uri ?: it.path?.let(::File)
            h.thumb.load(src) { crossfade(true); listener(onError = { _, _ -> h.thumb.setImageDrawable(null) }) }
        } else {
            h.thumb.dispose(); h.thumb.setImageDrawable(null)
        }
        h.name.text = it.name
        h.path.text = it.path?.removePrefix(Environment.getExternalStorageDirectory().path) ?: ""
        h.meta.text = listOfNotNull(
            if (it.kind == JunkKind.EMPTY_DIR) "Empty folder" else Storage.formatSize(it.size).ifEmpty { "0 B" },
            if (it.modified > 0) dateFormat.format(Date(it.modified)) else null,
            it.note,
        ).joinToString("  •  ")
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = it.selected
        h.check.setOnCheckedChangeListener { _, checked -> it.selected = checked; onChanged() }
        h.itemView.setOnClickListener { _ -> h.check.isChecked = !h.check.isChecked }
    }
}
