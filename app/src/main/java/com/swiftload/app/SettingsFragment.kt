package com.swiftload.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings tab: General / Tools / Info, as a normal settings list. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    companion object {
        const val WEBSITE = "https://brunosamky1.github.io"
        /** Address for "Feedback" e-mails. Empty = the user types it in their mail app. */
        const val FEEDBACK_EMAIL = ""
    }

    private lateinit var container: LinearLayout
    private var downloadSummary: TextView? = null
    private var themeSummary: TextView? = null
    private var cacheSummary: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        container = view.findViewById(R.id.container)

        section("General") {
            downloadSummary = row("⬇️", "Download settings", downloadText()) { downloadSettings() }
            row("🔔", "Notification settings", "Sounds, pop-ups and progress notifications") { openNotificationSettings() }
            themeSummary = row("🎨", "Theme", themeText()) { chooseTheme() }
        }
        section("Phone clean") {
            cleanerSummary = row("🧽", "Junk Cleaner", cleanerText()) {
                startActivity(Intent(requireContext(), CleanerActivity::class.java))
            }
            row("🗂️", "Clear all apps cache", "Android asks you to confirm") { clearAllAppsCache() }
            row("📊", "Storage overview", "Used / free space and what uses it") { storageOverview() }
        }
        section("Tools") {
            row("🔄", getString(R.string.update_engine), "Get the newest yt-dlp so sites keep working") { updateEngine() }
            row("🧹", getString(R.string.clear_finished), "Removes finished items from VidSaver's lists") { clearFinished() }
            cacheSummary = row("🖼️", "Clear thumbnail cache", "Calculating…") { clearThumbnails() }
        }
        section("Info") {
            row("❓", "How to use", null) { info("How to use", getString(R.string.how_to_use)) }
            row("📤", "Share VidSaver", "Tell your friends") { shareApp() }
            row("✉️", "Feedback", "Ideas, problems, questions") { feedback() }
            row("ℹ️", "About", "Version ${versionName()}") { about() }
        }
        refreshCacheSize()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) { refreshCacheSize(); cleanerSummary?.update(cleanerText()) }
    }

    override fun onResume() {
        super.onResume()
        cleanerSummary?.update(cleanerText())
    }

    // ---------------------------------------------------------------- Phone clean

    private var cleanerSummary: TextView? = null

    private fun cleanerText(): String {
        val total = Settings.cleanedTotal(requireContext())
        return if (total > 0) "Cleaned so far: ${Storage.formatSize(total)}" else "Find thumbnails, old APKs, temp, large & duplicate files"
    }

    private val clearCacheLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
            toast(if (res.resultCode == android.app.Activity.RESULT_OK) "Android cleared the apps' cache" else "Not cleared")
        }

    private fun clearAllAppsCache() {
        val c = requireContext()
        if (Build.VERSION.SDK_INT < 30) {
            MaterialAlertDialogBuilder(c)
                .setTitle("Clear all apps cache")
                .setMessage("On this Android version apps can't clear other apps' cache. Open Storage settings to clear cache yourself.")
                .setPositiveButton("Open Storage settings") { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (!CleanerActivity.hasFullAccess(c)) {
            MaterialAlertDialogBuilder(c)
                .setTitle("Permission needed")
                .setMessage("Android only lets an app clear all apps' cache when it has “All files access”. Allow it, then tap this again.")
                .setPositiveButton("Allow") { _, _ -> CleanerActivity.openAllFilesAccess(c) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(c)
            .setTitle("Clear all apps cache")
            .setMessage(
                "Android will show its own confirmation and then delete the cache of all apps.\n\n" +
                    "Cache is temporary data: apps keep your accounts and files, but may load a little slower the first time."
            )
            .setPositiveButton("Continue") { _, _ ->
                runCatching { clearCacheLauncher.launch(Intent(android.os.storage.StorageManager.ACTION_CLEAR_APP_CACHE)) }
                    .onFailure { toast("This phone doesn't support it") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun storageOverview() {
        val c = requireContext()
        val loading = MaterialAlertDialogBuilder(c).setTitle("Storage overview").setMessage("Calculating…").show()
        viewLifecycleOwner.lifecycleScope.launch {
            val r = StorageOverview.compute(c.applicationContext, CleanerActivity.hasFullAccess(c))
            loading.dismiss()
            val dp = resources.displayMetrics.density
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            }
            val used = (r.total - r.free).coerceAtLeast(0)
            fun label(text: String, size: Float, bold: Boolean = false, color: Int = R.color.text_primary) =
                TextView(c).apply {
                    this.text = text; textSize = size
                    setTextColor(ContextCompat.getColor(c, color))
                    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            fun bar(fraction: Double, color: Int) =
                com.google.android.material.progressindicator.LinearProgressIndicator(c).apply {
                    max = 1000
                    progress = (fraction * 1000).toInt().coerceIn(0, 1000)
                    setIndicatorColor(color)
                    trackCornerRadius = (4 * dp).toInt()
                    trackThickness = (8 * dp).toInt()
                }
            box.addView(label("${Storage.formatSize(used)} used of ${Storage.formatSize(r.total)}", 18f, bold = true))
            box.addView(bar(if (r.total > 0) used.toDouble() / r.total else 0.0, ContextCompat.getColor(c, R.color.brand)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
            box.addView(label("${Storage.formatSize(r.free)} free", 13f, color = R.color.text_secondary),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt(); bottomMargin = (12 * dp).toInt() })
            val colors = intArrayOf(0xFFF2A541.toInt(), 0xFF4DABF7.toInt(), 0xFF63E6BE.toInt(), 0xFFB197FC.toInt(), 0xFFFF8787.toInt(), 0xFF8D99AE.toInt())
            r.parts.forEachIndexed { i, (name, bytes) ->
                val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(label(name, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(label(Storage.formatSize(bytes).ifEmpty { "0 B" }, 14f, bold = true))
                box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })
                box.addView(bar(if (used > 0) bytes.toDouble() / used else 0.0, colors[i % colors.size]),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })
            }
            if (r.limited) box.addView(label(
                "Limited: without “All files access” only files VidSaver can see are counted; the rest is in “Apps, system & other”.",
                12f, color = R.color.text_secondary,
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })
            MaterialAlertDialogBuilder(c)
                .setTitle("Storage overview")
                .setView(androidx.core.widget.NestedScrollView(c).apply { addView(box) })
                .setPositiveButton("OK", null)
                .apply { if (r.limited && Build.VERSION.SDK_INT >= 30) setNeutralButton("Allow access") { _, _ -> CleanerActivity.openAllFilesAccess(c) } }
                .show()
        }
    }

    // ---------------------------------------------------------------- building the list

    private fun section(title: String, rows: LinearLayout.() -> Unit) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        container.addView(TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.brand))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((20 * dp).toInt(), (18 * dp).toInt(), 0, (6 * dp).toInt())
        })
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        inner.rows()
        container.addView(MaterialCardView(ctx).apply {
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card))
            radius = 18 * dp
            cardElevation = 0f
            strokeWidth = 0
            addView(inner)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (12 * dp).toInt(); marginEnd = (12 * dp).toInt()
        })
    }

    /** Adds a row and returns its subtitle view (to update it later). */
    private fun LinearLayout.row(icon: String, title: String, subtitle: String?, onClick: () -> Unit): TextView {
        val v = LayoutInflater.from(context).inflate(R.layout.item_setting, this, false)
        v.findViewById<TextView>(R.id.icon).text = icon
        v.findViewById<TextView>(R.id.title).text = title
        val sub = v.findViewById<TextView>(R.id.subtitle)
        if (subtitle != null) { sub.text = subtitle; sub.visibility = View.VISIBLE }
        v.setOnClickListener { onClick() }
        addView(v)
        return sub
    }

    private fun TextView.update(text: String) { this.text = text; visibility = View.VISIBLE }

    // ---------------------------------------------------------------- General

    private val actionLabels = arrayOf("Ask me every time", "Always download video (MP4)", "Always download audio (MP3)")
    private val qualityValues = intArrayOf(0, 1080, 720, 480, 360)
    private val qualityLabels = arrayOf("Best available", "1080p (Full HD)", "720p (HD)", "480p (saves data)", "360p (smallest)")

    private fun downloadText(): String {
        val c = requireContext()
        val action = arrayOf("Ask", "Video", "MP3")[Settings.mediaAction(c).coerceIn(0, 2)]
        val q = Settings.videoQuality(c).let { if (it <= 0) "Best" else "${it}p" }
        return "$action • $q • ${Settings.maxParallel(c)} at a time"
    }

    private fun downloadSettings() {
        val c = requireContext()
        val items = arrayOf(
            "${getString(R.string.media_action)}: ${actionLabels[Settings.mediaAction(c).coerceIn(0, 2)]}",
            "${getString(R.string.video_quality)}: ${qualityLabels[qualityValues.indexOf(Settings.videoQuality(c)).coerceAtLeast(0)]}",
            "Max simultaneous downloads: ${Settings.maxParallel(c)}",
            "Download folder",
        )
        MaterialAlertDialogBuilder(c)
            .setTitle("Download settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> choose(getString(R.string.media_action), actionLabels, Settings.mediaAction(c)) {
                        Settings.setMediaAction(c, it)
                    }
                    1 -> choose(getString(R.string.video_quality), qualityLabels,
                        qualityValues.indexOf(Settings.videoQuality(c)).coerceAtLeast(0)) {
                        Settings.setVideoQuality(c, qualityValues[it])
                    }
                    2 -> choose("Max simultaneous downloads", arrayOf("1", "2", "3", "4", "5"), Settings.maxParallel(c) - 1) {
                        Settings.setMaxParallel(c, it + 1)
                    }
                    3 -> info(
                        "Download folder",
                        "Files are saved in your phone's Download/VidSaver folder:\n\n" +
                            "• Video — videos\n• Music — MP3 and audio\n• Images\n• Documents — PDF, Word …\n" +
                            "• Archives — ZIP, RAR …\n• Apps — APK\n• Other\n\n" +
                            "Open them in the Play tab or with your Files app.",
                    )
                }
            }
            .show()
    }

    private fun choose(title: String, labels: Array<String>, current: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { d, which ->
                onPick(which)
                downloadSummary?.update(downloadText())
                d.dismiss()
            }
            .show()
    }

    private fun openNotificationSettings() {
        val c = requireContext()
        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
        runCatching { startActivity(i) }.onFailure {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
        }
    }

    private val themeLabels = arrayOf("Dark", "Light", "Same as phone (System)")
    private fun themeText() = themeLabels[Settings.themeMode(requireContext()).coerceIn(0, 2)]

    private fun chooseTheme() {
        val c = requireContext()
        MaterialAlertDialogBuilder(c)
            .setTitle("Theme")
            .setSingleChoiceItems(themeLabels, Settings.themeMode(c).coerceIn(0, 2)) { d, which ->
                d.dismiss()
                Settings.setThemeMode(c, which)
                themeSummary?.update(themeText())
                Settings.applyTheme(c) // recreates the screen in the new colors
            }
            .show()
    }

    // ---------------------------------------------------------------- Tools

    private fun updateEngine() {
        toast("Updating engine… please wait")
        val app = requireContext().applicationContext
        lifecycleScope.launch {
            val msg = runCatching { Engine.update(app) }.getOrElse { "Update failed: ${it.message?.take(120)}" }
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearFinished() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.clear_finished) + "?")
            .setMessage("Finished, failed and cancelled items are removed from VidSaver (also from the Play tab).\n\nYour files stay in Download/VidSaver.")
            .setPositiveButton("Clear") { _, _ -> DownloadStore.clearFinished(); toast("Cleared") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun thumbDirs(): List<File> {
        val cache = requireContext().cacheDir
        return listOf(File(cache, "thumbs"), File(cache, "image_cache")) // ours + Coil's
    }

    private fun refreshCacheSize() {
        val dirs = thumbDirs()
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { dirs.sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } } }
            cacheSummary?.update("%.1f MB".format(bytes / (1024.0 * 1024)))
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun clearThumbnails() {
        val app = requireContext().applicationContext
        val dirs = thumbDirs()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                app.imageLoader.memoryCache?.clear()
                runCatching { app.imageLoader.diskCache?.clear() }
                dirs.first().listFiles()?.forEach { it.delete() }
            }
            toast("Thumbnail cache cleared")
            refreshCacheSize()
            // Pictures of your downloads are made again from the files in the background
            withContext(Dispatchers.IO) { runCatching { Thumbs.backfill(app) } }
        }
    }

    // ---------------------------------------------------------------- Info

    private fun shareApp() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VidSaver")
            putExtra(Intent.EXTRA_TEXT, "Download videos, music and files fast with VidSaver: $WEBSITE")
        }
        startActivity(Intent.createChooser(send, "Share VidSaver"))
    }

    private fun feedback() {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "VidSaver feedback (v${versionName()})")
        }
        runCatching { startActivity(i) }.onFailure { toast("No e-mail app found") }
    }

    private fun about() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_full_title))
            .setMessage(
                "Fast downloader for videos, music and files.\n\n" +
                    "Download only content you own or have permission to download.\n\n$WEBSITE"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Open-source licenses") { _, _ -> info("Open-source licenses", getString(R.string.licenses)) }
            .show()
    }

    private fun versionName(): String = runCatching {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
    }.getOrNull() ?: ""

    private fun info(title: String, text: String) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
