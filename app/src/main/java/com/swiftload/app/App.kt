package com.swiftload.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class App : Application(), ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Coil can also show a frame of a video file (Junk Cleaner lists). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(this) // dark by default
        DownloadStore.init(this)
        DownloadService.createChannels(this)
        removeOldYoutubeLogin()
        // Warm up yt-dlp in the background so the first video download starts faster.
        // and fetch the newest yt-dlp (the copy inside the APK is old and YouTube changes often).
        appScope.launch {
            runCatching { Engine.ensureReady(this@App) }
            Engine.autoUpdateIfNeeded(this@App)
        }
        // Old finished downloads without a picture get a local thumbnail.
        appScope.launch(Dispatchers.IO) { runCatching { Thumbs.backfill(this@App) } }
    }

    /**
     * The in-app "Sign in to YouTube" was removed (v1.2.0). Once, for phones that had it:
     * delete the saved YouTube cookies file and clear the WebView's login cookies.
     */
    private fun removeOldYoutubeLogin() {
        val prefs = getSharedPreferences("migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("youtubeLoginRemoved", false)) return
        runCatching { File(filesDir, "youtube_cookies.txt").delete() }
        runCatching {
            android.webkit.CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        prefs.edit().putBoolean("youtubeLoginRemoved", true).apply()
    }
}
