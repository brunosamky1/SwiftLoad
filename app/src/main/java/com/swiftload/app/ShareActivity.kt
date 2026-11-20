package com.swiftload.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.launch

/**
 * Small pop-up that appears when someone taps Share → SwiftLoad (or pastes a link in the app).
 * It detects what the link is and:
 *   - direct file (pdf, zip, apk, mp3, mp4, image…) -> starts downloading immediately
 *   - video page (YouTube, TikTok, …)               -> Video or MP3 (or automatic, from Settings)
 */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val url = Detector.extractUrl(raw)
        if (url == null) {
            Toast.makeText(this, "No link found in what you shared", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val status = findViewById<TextView>(R.id.status)
        val detail = findViewById<TextView>(R.id.detail)
        val spinner = findViewById<ProgressBar>(R.id.spinner)
        val buttons = findViewById<View>(R.id.buttons)
        val btnVideo = findViewById<Button>(R.id.btnVideo)
        val btnMp3 = findViewById<Button>(R.id.btnMp3)
        val videoTitle = findViewById<TextView>(R.id.videoTitle)
        val videoThumb = findViewById<ImageView>(R.id.videoThumb)
        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }

        detail.text = url
        status.text = "Checking link…"

        lifecycleScope.launch {
            val r = runCatching { Detector.detect(url) }.getOrElse {
                Detector.Result(url, true, "", null, -1, Category.VIDEO)
            }

            if (!r.isMediaPage) {
                // Direct file: download straight away.
                spinner.visibility = View.GONE
                DownloadService.enqueue(this@ShareActivity, r.url, r.fileName, Mode.FILE, r.category, r.mime, r.size)
                val size = Storage.formatSize(r.size).let { if (it.isEmpty()) "" else " • $it" }
                toast("${r.category.label} detected$size — downloading ${r.fileName}")
                finish()
                return@launch
            }

            val site = siteName(r.url)
            status.text = "Getting video info…"
            val auto = Settings.mediaAction(this@ShareActivity)
            // Automatic mode waits less, so the download isn't held up by a slow site.
            val info = Detector.fetchInfo(r.url, if (auto == 0) 8_000 else 5_000)
            val title = info.title
            spinner.visibility = View.GONE

            when (auto) {
                1 -> { startMedia(r.url, site, info, Mode.VIDEO); return@launch }
                2 -> { startMedia(r.url, site, info, Mode.AUDIO_MP3); return@launch }
            }
            status.text = "$site link detected"
            detail.text = "Choose what to download:\n${r.url}"
            if (title != null || info.thumbnailUrl != null) {
                findViewById<View>(R.id.videoInfo).visibility = View.VISIBLE
                videoTitle.text = title ?: "$site video"
                info.thumbnailUrl?.let { thumb ->
                    videoThumb.visibility = View.VISIBLE
                    videoThumb.load(thumb) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(12 * resources.displayMetrics.density))
                        listener(onError = { _, _ -> videoThumb.visibility = View.GONE })
                    }
                }
            }
            buttons.visibility = View.VISIBLE
            btnVideo.setOnClickListener { startMedia(r.url, site, info, Mode.VIDEO) }
            btnMp3.setOnClickListener { startMedia(r.url, site, info, Mode.AUDIO_MP3) }
        }
    }

    private fun startMedia(url: String, site: String, info: Detector.PageInfo, mode: Mode) {
        val kind = if (mode == Mode.AUDIO_MP3) "MP3" else "video"
        val cat = if (mode == Mode.AUDIO_MP3) Category.AUDIO else Category.VIDEO
        val title = info.title
        // Without a real title we use a placeholder; DownloadService replaces it once the title is known.
        DownloadService.enqueue(this, url, title ?: "$site $kind", mode, cat, thumbnailUrl = info.thumbnailUrl)
        toast(if (title != null) "Downloading $kind: $title" else "Downloading $kind from $site…")
        finish()
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()

    private fun siteName(url: String): String {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return "Web"
        return when {
            host.contains("youtu") -> "YouTube"
            host.contains("tiktok") -> "TikTok"
            host.contains("instagram") -> "Instagram"
            host.contains("facebook") || host.contains("fb.watch") -> "Facebook"
            host == "x.com" || host.contains("twitter") -> "X (Twitter)"
            else -> host.substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}
