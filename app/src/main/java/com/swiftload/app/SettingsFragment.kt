package com.swiftload.app

import android.content.Intent
import android.net.Uri
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
        section("Tools") {
            row("🔄", getString(R.string.update_engine), "Get the newest yt-dlp so sites keep working") { updateEngine() }
            row("🧹", getString(R.string.clear_finished), "Removes finished items from SwiftLoad's lists") { clearFinished() }
            cacheSummary = row("🖼️", "Clear thumbnail cache", "Calculating…") { clearThumbnails() }
        }
        section("Info") {
            row("❓", "How to use", null) { info("How to use", getString(R.string.how_to_use)) }
            row("📤", "Share SwiftLoad", "Tell your friends") { shareApp() }
            row("✉️", "Feedback", "Ideas, problems, questions") { feedback() }
            row("ℹ️", "About", "Version ${versionName()}") { about() }
        }
        refreshCacheSize()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) refreshCacheSize()
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
                        "Files are saved in your phone's Download/SwiftLoad folder:\n\n" +
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
            .setMessage("Finished, failed and cancelled items are removed from SwiftLoad (also from the Play tab).\n\nYour files stay in Download/SwiftLoad.")
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
            putExtra(Intent.EXTRA_SUBJECT, "SwiftLoad")
            putExtra(Intent.EXTRA_TEXT, "Download videos, music and files fast with SwiftLoad: $WEBSITE")
        }
        startActivity(Intent.createChooser(send, "Share SwiftLoad"))
    }

    private fun feedback() {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "SwiftLoad feedback (v${versionName()})")
        }
        runCatching { startActivity(i) }.onFailure { toast("No e-mail app found") }
    }

    private fun about() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("SwiftLoad ${versionName()}")
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
