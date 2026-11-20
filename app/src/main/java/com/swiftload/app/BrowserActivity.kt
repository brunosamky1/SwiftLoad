package com.swiftload.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Locale

/**
 * In-app browser for YouTube and the "More" sites. Open a video, tap the orange Download button
 * and it goes to the normal detect → Video/MP3 pop-up. File downloads started by a web page
 * (DownloadListener) go straight to SwiftLoad's fast downloader.
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, url: String, title: String) {
            context.startActivity(
                Intent(context, BrowserActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
            )
        }
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val startUrl = intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com/"
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fabDownload)
        web = findViewById(R.id.web)

        toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: "Browser"
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.navigationIcon?.setTint(getColor(R.color.text_primary))
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add("Reload").setOnMenuItemClickListener { web.reload(); true }
        toolbar.menu.add("Open in browser").setOnMenuItemClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: startUrl))) }
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
                val scheme = url.scheme?.lowercase(Locale.ROOT)
                if (scheme == "http" || scheme == "https") return false
                // intent://, market://, mailto: … -> another app, or the page's fallback URL
                runCatching {
                    if (scheme == "intent") {
                        val i = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                        val fallback = i.getStringExtra("browser_fallback_url")
                        if (fallback != null) view.loadUrl(fallback) else startActivity(i)
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                toolbar.subtitle = Uri.parse(url).host?.removePrefix("www.")
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.title?.takeIf { it.isNotBlank() }?.let { toolbar.title = it }
            }
        }

        // Files the page wants to download (PDF, APK, ZIP, MP3 …) -> SwiftLoad's downloader
        web.setDownloadListener { url, _, contentDisposition, mimetype, contentLength ->
            if (!url.startsWith("http")) {
                toast("This download can't be saved by VidSaver")
                return@setDownloadListener
            }
            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val mime = mimetype?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: Detector.mimeFromExt(name.substringAfterLast('.', ""))
            DownloadService.enqueue(this, url, name, Mode.FILE, Detector.categoryOf(mime, name), mime, contentLength)
            toast("Downloading $name")
        }

        fab.setOnClickListener {
            val url = web.url
            when {
                url.isNullOrBlank() -> toast("Page is still loading…")
                Detector.isYoutube(url) && Detector.youtubeId(url) == null ->
                    toast("Open a video first, then tap Download")
                else -> MainActivity.openShareFlow(this, url)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(startUrl)
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
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
