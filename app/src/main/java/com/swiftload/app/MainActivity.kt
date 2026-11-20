package com.swiftload.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar

/** One activity with three tabs (fragments): Download | Play | Settings. */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val tabs = linkedMapOf(
        R.id.nav_download to "download",
        R.id.nav_play to "play",
        R.id.nav_settings to "settings",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bottomNav = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            // Add all three once; switching tabs only shows/hides them, so each keeps its state.
            val tx = supportFragmentManager.beginTransaction()
            tabs.forEach { (id, tag) ->
                val f = newFragment(id)
                tx.add(R.id.fragmentContainer, f, tag)
                if (id != R.id.nav_download) tx.hide(f)
            }
            tx.commitNow()
        }
        bottomNav.setOnItemSelectedListener { showTab(it.itemId); true }
        bottomNav.setOnItemReselectedListener { }
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAY, false) == true) bottomNav.selectedItemId = R.id.nav_play

        askPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAY, false)) bottomNav.selectedItemId = R.id.nav_play
    }

    private fun newFragment(id: Int): Fragment = when (id) {
        R.id.nav_play -> LibraryFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> DownloadFragment()
    }

    private fun showTab(id: Int) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tabs.forEach { (tabId, tag) ->
            val f = fm.findFragmentByTag(tag) ?: newFragment(tabId).also { tx.add(R.id.fragmentContainer, it, tag) }
            if (tabId == id) tx.show(f) else tx.hide(f)
        }
        tx.commit()
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
        val url = Detector.extractUrl(clip)?.takeIf { clip.contains("://") } ?: return
        if (url == Settings.lastClipboard(this)) return
        Settings.setLastClipboard(this, url)
        Snackbar.make(findViewById(R.id.fragmentContainer), "Copied link found:\n$url", Snackbar.LENGTH_LONG)
            .setAnchorView(bottomNav)
            .setAction("Download") { openShareFlow(this, url) }
            .show()
    }

    fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.coerceToText(this)?.toString()?.trim()?.ifEmpty { null }
    }

    companion object {
        const val EXTRA_OPEN_PLAY = "open_play"

        /** Re-uses the same detect → download pop-up as the Share menu. */
        fun openShareFlow(context: android.content.Context, url: String) {
            context.startActivity(
                Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
            )
        }
    }
}
