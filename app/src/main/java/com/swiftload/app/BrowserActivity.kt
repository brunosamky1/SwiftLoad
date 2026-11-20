package com.swiftload.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import java.util.Locale

/**
 * In-app browser for the "More" sites and web search.
 *  - The media sniffer lists the video/audio files the page loads -> "Download video (N)".
 *  - "Detect automatically" sends the page URL to the normal detect → Video/MP3 pop-up (yt-dlp).
 *  - Files the page downloads (DownloadListener) go to VidSaver's fast downloader.
 *  - YouTube is not supported: such pages are never opened.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, url: String, title: String) {
            if (Blocklist.isBlocked(url)) {
                Toast.makeText(context, Blocklist.MESSAGE, Toast.LENGTH_LONG).show()
                return
            }
            context.startActivity(
                Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
            )
        }

        private const val BLOCKED_PAGE =
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                "<body style='margin:0;height:100vh;display:flex;align-items:center;justify-content:center;" +
                "background:#0F1115;color:#F2F3F5;font-family:sans-serif;font-size:20px'>" +
                "YouTube is not supported.</body></html>"
    }

    private lateinit var web: WebView
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var sniffer: MediaSniffer
    private var shownCount = -1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val startUrl = intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com/"
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        fab = findViewById(R.id.fabDownload)
        web = findViewById(R.id.web)
        sniffer = MediaSniffer { runOnUiThread { updateFab() } }

        toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: "Browser"
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.navigationIcon?.setTint(getColor(R.color.text_primary))
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add("Reload").setOnMenuItemClickListener { web.reload(); true }
        toolbar.menu.add("Open in browser").setOnMenuItemClickListener {
            val u = web.url ?: startUrl
            if (!Blocklist.isBlocked(u)) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
            true
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // A normal mobile Chrome user agent (without the "wv" WebView marker)
            userAgentString = Detector.USER_AGENT
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
                progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (Blocklist.isBlocked(url.toString())) {
                    if (request.isForMainFrame) blocked()
                    return true
                }
                val scheme = url.scheme?.lowercase(Locale.ROOT)
                if (scheme == "http" || scheme == "https") return false
                // intent://, market://, mailto: … -> another app, or the page's fallback URL
                runCatching {
                    if (scheme == "intent") {
                        val i = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                        val fallback = i.getStringExtra("browser_fallback_url")
                        if (fallback != null && !Blocklist.isBlocked(fallback)) view.loadUrl(fallback)
                        else if (!Blocklist.isBlocked(i.dataString)) startActivity(i)
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Only observe; the request itself is never blocked or changed
                if (!request.isForMainFrame) sniffer.observe(request.url.toString(), request.requestHeaders)
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (Blocklist.isBlocked(url)) { view.stopLoading(); blocked(); return }
                toolbar.subtitle = Uri.parse(url).host?.removePrefix("www.")
                pageChanged(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Single-page sites change the address without loading a new page
                if (!url.startsWith("data:")) pageChanged(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.title?.takeIf { it.isNotBlank() && !url.startsWith("data:") }?.let { toolbar.title = it }
            }
        }

        // Files the page wants to download (PDF, APK, ZIP, MP3 …) -> VidSaver's downloader
        web.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            if (Blocklist.isBlocked(url) || Blocklist.isBlocked(web.url)) { blocked(); return@setDownloadListener }
            if (!url.startsWith("http")) {
                toast("This download can't be saved by VidSaver")
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val mime = mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: Detector.mimeFromExt(name.substringAfterLast('.', ""))
            DownloadService.enqueue(this, url, name, Mode.FILE, Detector.categoryOf(mime, name), mime, contentLength, referer = web.url)
                ?.let { toast("Downloading $name") }
        }

        fab.setOnClickListener { showSheet() }
        updateFab()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        when {
            Blocklist.isBlocked(startUrl) -> blocked()
            savedInstanceState != null -> web.restoreState(savedInstanceState)
            else -> web.loadUrl(startUrl)
        }
    }

    private fun pageChanged(url: String) {
        val clean = url.substringBefore('#')
        if (clean != sniffer.pageUrl) sniffer.reset(clean)
    }

    private fun blocked() {
        web.stopLoading()
        web.loadDataWithBaseURL(null, BLOCKED_PAGE, "text/html", "utf-8", null)
        Snackbar.make(web, Blocklist.MESSAGE, Snackbar.LENGTH_LONG).show()
        sniffer.reset("")
    }

    // ---------------------------------------------------------------- floating button

    private fun updateFab() {
        val n = sniffer.list().sumOf { maxOf(1, it.variants.size) }
        if (n == shownCount) return
        val grew = n > 0 && (shownCount <= 0)
        shownCount = n
        fab.text = if (n > 0) "Download video ($n)" else getString(R.string.download)
        if (grew) {
            // Little "pop" so the user notices that videos were found
            fab.extend()
            fab.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
                fab.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
        }
    }

    // ---------------------------------------------------------------- "found files" sheet

    private data class Choice(val c: MediaSniffer.Candidate?, val height: Int, val quality: String, val detail: String)

    private fun showSheet() {
        val page = web.url
        if (page == null || page.startsWith("data:")) { toast("Page is still loading…"); return }
        if (Blocklist.isBlocked(page)) { blocked(); return }

        val dialog = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        }
        // Header: thumbnail + page title (filled in by JavaScript below)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val titleView = TextView(this).apply {
            text = cleanTitle(web.title) ?: "This page"
            textSize = 16f
            maxLines = 2
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(thumb, LinearLayout.LayoutParams((96 * dp).toInt(), (56 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        var pageTitle = cleanTitle(web.title) ?: "Video"
        var thumbUrl: String? = null
        web.evaluateJavascript(
            "(function(){function m(p){var e=document.querySelector('meta[property=\"'+p+'\"],meta[name=\"'+p+'\"]');return e?e.content:''}" +
                "var v=document.querySelector('video');return JSON.stringify([m('og:title')||document.title||''," +
                "m('og:image')||(v&&v.poster)||'']);})()"
        ) { result ->
            runCatching {
                // result is a JSON string literal containing our JSON array
                val arr = JSONArray(org.json.JSONTokener(result).nextValue() as String)
                cleanTitle(arr.optString(0))?.let { pageTitle = it; titleView.text = it }
                arr.optString(1).takeIf { it.startsWith("http") && !Blocklist.isBlocked(it) }?.let { img ->
                    thumbUrl = img
                    thumb.visibility = View.VISIBLE
                    thumb.load(img) { transformations(RoundedCornersTransformation(10 * dp)) }
                }
            }
        }

        fun row(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
            val v = layoutInflater.inflate(R.layout.item_setting, root, false)
            v.findViewById<TextView>(R.id.icon).text = icon
            v.findViewById<TextView>(R.id.title).text = title
            v.findViewById<TextView>(R.id.subtitle).apply { text = subtitle; visibility = View.VISIBLE }
            v.setOnClickListener { dialog.dismiss(); onClick() }
            root.addView(v)
        }

        row("🔎", "Detect automatically", "Let VidSaver find the video on this page (best quality, MP4 or MP3)") {
            MainActivity.openShareFlow(this, page)
        }

        val choices = sniffer.list().flatMap { c ->
            val type = when (c.kind) {
                MediaSniffer.Kind.VIDEO -> (c.ext.ifEmpty { "video" }).uppercase(Locale.ROOT)
                MediaSniffer.Kind.AUDIO -> (c.ext.ifEmpty { "audio" }).uppercase(Locale.ROOT)
                else -> c.kind.label
            }
            if (c.variants.isNotEmpty()) c.variants.map { v ->
                Choice(c, v.height, v.label, listOfNotNull(type, MediaSniffer.bandwidthLabel(v.bandwidth).takeIf { v.bandwidth > 0 }).joinToString(" · "))
            } else listOf(
                Choice(c, MediaSniffer.heightOf(c.quality), c.quality ?: if (c.kind == MediaSniffer.Kind.AUDIO) "Audio" else "Original",
                    listOfNotNull(type, Storage.formatSize(c.size).takeIf { it.isNotEmpty() }).joinToString(" · "))
            )
        }
        if (choices.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No video files found yet. Play the video on the page, then tap Download again."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            })
        }
        choices.forEach { ch ->
            val audio = ch.c?.kind == MediaSniffer.Kind.AUDIO
            row(if (audio) "🎵" else "🎬", ch.quality, ch.detail) {
                chooseAction(ch, page, pageTitle, thumbUrl)
            }
        }
        dialog.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(root) })
        dialog.show()
    }

    private fun chooseAction(ch: Choice, page: String, title: String, thumbUrl: String?) {
        val c = ch.c ?: return
        val audio = c.kind == MediaSniffer.Kind.AUDIO
        val options = arrayOf(
            if (audio) "Download audio" else "Download video",
            "Download MP3 (audio only)",
            "Download later…",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("${ch.quality} — $title")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startDownload(c, ch.height, page, title, thumbUrl, mp3 = false, later = null)
                    1 -> startDownload(c, ch.height, page, title, thumbUrl, mp3 = true, later = null)
                    else -> {
                        val fmt = arrayOf(if (audio) "Audio" else "Video", "MP3")
                        MaterialAlertDialogBuilder(this).setTitle("Download later as")
                            .setItems(fmt) { _, f ->
                                LaterPicker.show(this) { type, at ->
                                    startDownload(c, ch.height, page, title, thumbUrl, mp3 = f == 1, later = type to at)
                                }
                            }.show()
                    }
                }
            }
            .show()
    }

    /** Direct files -> fast multi-part downloader; HLS/DASH and MP3 -> yt-dlp/FFmpeg. Page Referer + cookies are used. */
    private fun startDownload(
        c: MediaSniffer.Candidate, height: Int, page: String, title: String, thumbUrl: String?,
        mp3: Boolean, later: Pair<ScheduleType, Long>?,
    ) {
        val stream = c.kind == MediaSniffer.Kind.HLS || c.kind == MediaSniffer.Kind.DASH
        val startNow = later == null
        val id = when {
            mp3 -> DownloadService.enqueue(this, c.url, title, Mode.AUDIO_MP3, Category.AUDIO,
                thumbnailUrl = thumbUrl, referer = page, startNow = startNow)
            stream -> DownloadService.enqueue(this, c.url, title, Mode.VIDEO, Category.VIDEO,
                thumbnailUrl = thumbUrl, referer = page, maxHeight = height, startNow = startNow)
            else -> {
                val ext = c.ext.ifEmpty { c.mime?.substringAfter('/')?.takeIf { it.length <= 4 } ?: "mp4" }
                val name = "${Storage.sanitize(title).take(120)}.$ext"
                val mime = c.mime ?: Detector.mimeFromExt(ext)
                DownloadService.enqueue(this, c.url, name, Mode.FILE, Detector.categoryOf(mime, name), mime, c.size,
                    thumbnailUrl = thumbUrl, referer = page, startNow = startNow)
            }
        } ?: return
        if (later != null) {
            Scheduler.schedule(this, id, later.first, later.second)
            DownloadStore.get(id)?.let { toast("Scheduled · ${Scheduler.label(it)}") }
        } else {
            toast("Downloading: $title")
        }
    }

    /** "Funny cat video | SiteName" -> "Funny cat video" (keeps the title if nothing sensible is left). */
    private fun cleanTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var t = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("""\s+"""), " ").trim()
        val cut = Regex("""\s+[|–—-]\s+[^|–—-]{2,40}$""").find(t)
        if (cut != null && cut.range.first >= 8) t = t.substring(0, cut.range.first).trim()
        return t.take(150).ifEmpty { null }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        sniffer.shutdown()
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
