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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

        fun start(context: Context, id: String) {
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context, id: String) {
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id)
            context.startService(i)
        }

        /** Creates a new download entry and starts it. */
        fun enqueue(
            context: Context, url: String, title: String, mode: Mode, category: Category,
            mime: String? = null, size: Long = -1, thumbnailUrl: String? = null,
        ): String {
            val item = DownloadItem(
                url = url, title = title, mode = mode, category = category, mime = mime, sizeBytes = size,
                thumbnailUrl = thumbnailUrl,
            )
            DownloadStore.add(item)
            start(context, item.id)
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
    // How many downloads run at once (Settings → Download settings, 1–5); read when the service starts
    private val limiter by lazy { Semaphore(Settings.maxParallel(this)) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START -> if (id != null && !jobs.containsKey(id)) launchDownload(id)
            ACTION_CANCEL -> if (id != null) cancelDownload(id)
        }
        stopIfIdle()
        return START_NOT_STICKY
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
        DownloadStore.update(id, persist = true) { it.copy(status = Status.QUEUED, progress = -1, error = null, speed = "") }
        val job = scope.launch {
            limiter.withPermit {
                runDownload(item)
            }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id)
            goForegroundOrStop()
        }
    }

    private suspend fun runDownload(item: DownloadItem) {
        val id = item.id
        val dir = Storage.tempDir(this, id)
        DownloadStore.update(id, persist = true) { it.copy(status = Status.RUNNING) }
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
                DirectDownloader.download(item.url, file) { done, total ->
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
                val file = MediaDownloader.download(this, id, item.url, item.mode, dir) { p, speed, title ->
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
                )
            }
            notifyDone(id)
        } catch (e: CancellationException) {
            DownloadStore.update(id, persist = true) { it.copy(status = Status.CANCELLED, speed = "") }
            cancelNotification(id)
        } catch (e: Throwable) {
            if (!currentCoroutineContext().isActive) {
                // user pressed Cancel while yt-dlp was running (process was killed)
                DownloadStore.update(id, persist = true) { it.copy(status = Status.CANCELLED, speed = "") }
                cancelNotification(id)
                return
            }
            val msg = friendlyError(e)
            DownloadStore.update(id, persist = true) { it.copy(status = Status.FAILED, error = msg, speed = "") }
            notifyFailed(id, msg)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** "Song Name.f137" (a yt-dlp part file) -> "Song Name" */
    private fun cleanName(name: String) = name.replace(Regex("""\.f\d+$"""), "").trim()

    private fun friendlyError(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).lines().lastOrNull { it.contains("ERROR") } ?: e.message ?: "Unknown error"
        return when {
            m.contains("Unsupported URL", true) -> "This link has no downloadable video or file."
            m.contains("sign in", true) || m.contains("not a bot", true) || m.contains("login", true) ->
                "YouTube blocked this download. Wait a few minutes and tap retry."
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
