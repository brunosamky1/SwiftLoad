package com.swiftload.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/** Runs downloads in the background (even when the app is closed) and shows progress notifications. */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
        private const val FOREGROUND_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"

        fun startIntent(context: Context, id: String): Intent =
            Intent(context, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id)

        /** Queues the item; it starts as soon as a download slot is free (order = queueOrder). */
        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(context, startIntent(context, id))
        }

        fun cancel(context: Context, id: String) {
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id)
            context.startService(i)
        }

        /**
         * Creates a new download entry and starts it (or only saves it when [startNow] is false, e.g. to schedule it).
         * Returns null for blocked (YouTube) links.
         */
        fun enqueue(
            context: Context, url: String, title: String, mode: Mode, category: Category,
            mime: String? = null, size: Long = -1, thumbnailUrl: String? = null,
            referer: String? = null, maxHeight: Int = 0, startNow: Boolean = true,
        ): String? {
            if (Blocklist.isBlocked(url) || Blocklist.isBlocked(referer)) {
                Toast.makeText(context, Blocklist.MESSAGE, Toast.LENGTH_LONG).show()
                return null
            }
            val item = DownloadItem(
                url = url, title = title, mode = mode, category = category, mime = mime, sizeBytes = size,
                thumbnailUrl = thumbnailUrl?.takeUnless { Blocklist.isBlocked(it) },
                referer = referer, maxHeight = maxHeight,
                status = if (startNow) Status.QUEUED else Status.SCHEDULED,
            )
            DownloadStore.add(item)
            if (startNow) start(context, item.id)
            return item.id
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
                )
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Downloads being stopped because the night hours ended (they continue the next night). */
    private val pausing = ConcurrentHashMap.newKeySet<String>()
    private var nightWatcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> if (id != null && !jobs.containsKey(id)) queueItem(id)
            ACTION_CANCEL -> if (id != null) cancelDownload(id)
        }
        pump()
        watchNightEnd()
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun queueItem(id: String) {
        DownloadStore.update(id, persist = true) {
            if (it.status == Status.RUNNING) it
            else it.copy(status = Status.QUEUED, progress = -1, error = null, speed = "")
        }
    }

    /**
     * Starts queued items while there are free slots (Settings: 1–5 at a time),
     * in queue order — so dragging items in the Queue screen changes what starts next.
     */
    @Synchronized
    private fun pump() {
        val max = Settings.maxParallel(this)
        while (jobs.size < max) {
            val next = DownloadStore.items.value
                .filter { it.status == Status.QUEUED && !jobs.containsKey(it.id) }
                .minByOrNull { it.queueOrder } ?: return
            launchDownload(next.id)
        }
    }

    /** Pauses night-bundle downloads when the night hours end (if switched on in Settings). */
    private fun watchNightEnd() {
        if (nightWatcher?.isActive == true) return
        nightWatcher = scope.launch {
            while (isActive) {
                delay(30_000)
                if (!Settings.pauseAfterNight(this@DownloadService) || Scheduler.isNight(this@DownloadService)) continue
                jobs.keys.toList().forEach { id ->
                    if (DownloadStore.get(id)?.scheduleType == ScheduleType.NIGHT) pauseForNight(id)
                }
            }
        }
    }

    private fun pauseForNight(id: String) {
        pausing += id
        MediaDownloader.cancel(id)
        jobs[id]?.cancel()
    }

    private fun goForeground() {
        val n = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (jobs.isEmpty()) "Starting download…" else "Downloading ${jobs.size} file(s)")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, FOREGROUND_ID, n, type)
    }

    private fun launchDownload(id: String) {
        val item = DownloadStore.get(id) ?: return
        DownloadStore.update(id, persist = true) { it.copy(status = Status.RUNNING, progress = -1, error = null, speed = "") }
        val job = scope.launch { runDownload(item) }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id)
            pump()
            goForegroundOrStop()
        }
    }

    /** Referer + the browser's cookies, for files found by the media sniffer. */
    private fun pageHeaders(item: DownloadItem): Map<String, String> {
        val referer = item.referer ?: return emptyMap()
        val h = mutableMapOf("Referer" to referer)
        runCatching { android.webkit.CookieManager.getInstance().getCookie(item.url) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    private suspend fun runDownload(item: DownloadItem) {
        val id = item.id
        val dir = Storage.tempDir(this, id)
        var paused = false
        if (Blocklist.isBlocked(item.url) || Blocklist.isBlocked(item.referer)) {
            DownloadStore.update(id, persist = true) { it.copy(status = Status.FAILED, error = Blocklist.MESSAGE, speed = "") }
            dir.deleteRecursively()
            return
        }
        notifyProgress(id)
        if (item.mode != Mode.FILE && (Detector.isPlaceholderTitle(item.title) || item.thumbnailUrl == null)) {
            // Title is still "YouTube video" or there's no picture yet: look them up in the background.
            scope.launch {
                val info = Detector.fetchInfo(item.url)
                var changed = false
                DownloadStore.update(id, persist = true) {
                    var n = it
                    if (info.title != null && it.status == Status.RUNNING && Detector.isPlaceholderTitle(it.title)) {
                        n = n.copy(title = info.title)
                    }
                    if (info.thumbnailUrl != null && it.thumbnailUrl == null) n = n.copy(thumbnailUrl = info.thumbnailUrl)
                    changed = n != it
                    n
                }
                if (changed && DownloadStore.get(id)?.status == Status.RUNNING) notifyProgress(id)
            }
        }
        try {
            val uri: Uri
            var finalTitle: String
            var mime: String
            var local = Thumbs.Local(null, -1)
            var finalCat = item.category
            if (item.mode == Mode.FILE) {
                val file = java.io.File(dir, Storage.sanitize(item.title))
                var lastBytes = 0L
                var lastTime = System.currentTimeMillis()
                DirectDownloader.download(item.url, file, pageHeaders(item)) { done, total ->
                    val now = System.currentTimeMillis()
                    val dt = (now - lastTime).coerceAtLeast(1)
                    val speed = if (dt >= 400) Storage.formatSize((done - lastBytes) * 1000 / dt) + "/s" else null
                    if (speed != null) { lastBytes = done; lastTime = now }
                    DownloadStore.update(id) {
                        it.copy(
                            progress = if (total > 0) (done * 100 / total).toInt() else -1,
                            sizeBytes = if (total > 0) total else it.sizeBytes,
                            speed = speed ?: it.speed,
                        )
                    }
                    notifyProgress(id)
                }
                finalTitle = item.title
                mime = item.mime ?: Detector.mimeFromExt(file.extension) ?: "application/octet-stream"
                local = Thumbs.fromFile(this, id, file, item.category) // before publish() moves the file
                uri = Storage.publish(this, file, finalTitle, mime, item.category)
            } else {
                var lastNotify = 0L
                val file = MediaDownloader.download(this, id, item.url, item.mode, dir, pageHeaders(item), item.maxHeight,
                    fileTitle = if (item.referer != null) item.title else null) { p, speed, title ->
                    DownloadStore.update(id) {
                        it.copy(
                            progress = if (p >= 0) p.toInt() else it.progress,
                            speed = speed.ifEmpty { it.speed },
                            // yt-dlp's file name is only used while we still have a placeholder title
                            title = if (title != null && Detector.isPlaceholderTitle(it.title)) cleanName(title) else it.title,
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 700) { lastNotify = now; notifyProgress(id) }
                }
                mime = Detector.mimeFromExt(file.extension) ?: if (item.mode == Mode.AUDIO_MP3) "audio/mpeg" else "video/mp4"
                val cat = if (item.mode == Mode.AUDIO_MP3) Category.AUDIO else Detector.categoryOf(mime, file.name)
                DownloadStore.update(id) { it.copy(sizeBytes = file.length(), category = cat) }
                finalCat = cat
                // Video frame / MP3 cover art, made before publish() moves the file
                local = Thumbs.fromFile(this, id, file, cat)
                uri = Storage.publish(this, file, file.name, mime, cat)
                // List / notification show the clean saved name, without ".mp4" / ".mp3"
                finalTitle = file.nameWithoutExtension
            }
            // Prefer the local thumbnail; an image is its own thumbnail; else keep the online one.
            val thumb = local.thumbUri ?: if (finalCat == Category.IMAGE) uri.toString() else null
            DownloadStore.update(id, persist = true) {
                it.copy(
                    status = Status.DONE, progress = 100, title = finalTitle, fileUri = uri.toString(), mime = mime, speed = "",
                    thumbnailUrl = thumb ?: it.thumbnailUrl,
                    durationMs = if (local.durationMs > 0) local.durationMs else it.durationMs,
                    scheduleType = ScheduleType.NONE, scheduledAt = 0,
                )
            }
            notifyDone(id)
        } catch (e: CancellationException) {
            paused = stopped(id)
        } catch (e: Throwable) {
            if (!currentCoroutineContext().isActive || id in pausing) {
                // Cancel / night pause while yt-dlp was running (its process was killed)
                paused = stopped(id)
                return
            }
            val msg = friendlyError(e)
            DownloadStore.update(id, persist = true) { it.copy(status = Status.FAILED, error = msg, speed = "") }
            notifyFailed(id, msg)
        } finally {
            // A paused download keeps its partial files so it can continue next night
            if (!paused) dir.deleteRecursively()
        }
    }

    /** Cancelled by the user, or paused because the night hours ended. Returns true when paused. */
    private fun stopped(id: String): Boolean {
        cancelNotification(id)
        return if (pausing.remove(id)) {
            Scheduler.resumeNextNight(this, id)
            true
        } else {
            DownloadStore.update(id, persist = true) { it.copy(status = Status.CANCELLED, speed = "") }
            false
        }
    }

    /** "Song Name.f137" (a yt-dlp part file) -> "Song Name" */
    private fun cleanName(name: String) = name.replace(Regex("""\.f\d+$"""), "").trim()

    private fun friendlyError(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).lines().lastOrNull { it.contains("ERROR") } ?: e.message ?: "Unknown error"
        return when {
            m.contains("Unsupported URL", true) -> "This link has no downloadable video or file."
            m.contains("Private video", true) -> "This content is private."
            m.contains("Unable to resolve host", true) || m.contains("UnknownHost", true) -> "No internet connection."
            else -> m.removePrefix("ERROR:").trim().take(200)
        }
    }

    private fun cancelDownload(id: String) {
        MediaDownloader.cancel(id)
        jobs[id]?.cancel() ?: DownloadStore.update(id, persist = true) { it.copy(status = Status.CANCELLED) }
        cancelNotification(id)
    }

    private fun goForegroundOrStop() {
        if (jobs.isEmpty()) stopIfIdle()
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- notifications ----------------

    private fun nid(id: String) = 1000 + (id.hashCode() and 0x0FFFFFFF)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun canNotify() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyProgress(id: String) {
        if (!canNotify()) return
        val item = DownloadStore.get(id) ?: return
        val cancel = PendingIntent.getService(
            this, nid(id),
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = buildString {
            append(if (item.progress >= 0) "${item.progress}%" else "Connecting…")
            if (item.speed.isNotEmpty()) append(" • ${item.speed}")
        }
        val n = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.title)
            .setContentText(text)
            .setProgress(100, item.progress.coerceAtLeast(0), item.progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        NotificationManagerCompat.from(this).notify(nid(id), n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyDone(id: String) {
        if (!canNotify()) return
        val item = DownloadStore.get(id) ?: return
        val uri = item.fileUri?.let(Uri::parse) ?: return
        val open = PendingIntent.getActivity(
            this, nid(id), Storage.openIntent(uri, item.mime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(nid(id), n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyFailed(id: String, msg: String) {
        if (!canNotify()) return
        val item = DownloadStore.get(id) ?: return
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed: ${item.title}")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(nid(id), n)
    }

    private fun cancelNotification(id: String) = NotificationManagerCompat.from(this).cancel(nid(id))
}
