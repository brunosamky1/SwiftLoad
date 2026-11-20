package com.swiftload.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DownloadStore.init(this)
        DownloadService.createChannels(this)
        // Warm up yt-dlp in the background so the first video download starts faster.
        // and fetch the newest yt-dlp (the copy inside the APK is old and YouTube changes often).
        appScope.launch {
            runCatching { Engine.ensureReady(this@App) }
            Engine.autoUpdateIfNeeded(this@App)
        }
    }
}
