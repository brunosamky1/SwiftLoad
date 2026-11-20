package com.swiftload.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Lets the user sign in to YouTube inside the app. The login cookies are saved privately on the phone
 * and passed to yt-dlp, which fixes "Please sign in / Sign in to confirm you're not a bot".
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = MaterialToolbar(this).apply {
            title = "Sign in to YouTube"
            setTitleTextColor(Color.WHITE)
            setBackgroundColor(getColor(R.color.brand))
            menu.add("Done").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { finishLogin(); true }
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(Color.WHITE)
            setNavigationOnClickListener { finish() }
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        web = WebView(this).apply {
            cm.setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A normal Chrome user agent (without the "wv" WebView marker) so Google allows the sign-in
            settings.userAgentString = Detector.USER_AGENT
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Back on YouTube after a successful login -> save automatically
                    if (url != null && url.contains("youtube.com") && hasLoginCookies()) finishLogin()
                }
            }
            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F")
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun youtubeCookies(): String {
        val cm = CookieManager.getInstance()
        val parts = listOfNotNull(cm.getCookie("https://www.youtube.com"), cm.getCookie("https://m.youtube.com"))
        // merge, removing duplicate names
        return parts.flatMap { it.split(';') }.map { it.trim() }.filter { it.contains('=') }
            .associateBy { it.substringBefore('=') }.values.joinToString("; ")
    }

    private fun hasLoginCookies(): Boolean {
        val c = youtubeCookies()
        return c.contains("SAPISID") || c.contains("__Secure-3PSID") || c.contains("LOGIN_INFO")
    }

    private var saved = false

    private fun finishLogin() {
        if (saved) return
        val cm = CookieManager.getInstance()
        cm.flush()
        if (!hasLoginCookies()) {
            Toast.makeText(this, "You are not signed in yet. Sign in with your Google account first.", Toast.LENGTH_LONG).show()
            return
        }
        saved = true
        YoutubeCookies.save(this, youtubeCookies(), cm.getCookie("https://accounts.google.com"))
        Toast.makeText(this, "Signed in ✓ YouTube downloads will now use your account", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
