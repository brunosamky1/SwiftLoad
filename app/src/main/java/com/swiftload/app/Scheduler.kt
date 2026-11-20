package com.swiftload.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Download later": tonight (night data bundle), at a picked time, or when on Wi-Fi.
 * WorkManager keeps the plan across app restarts and reboots and waits for the network constraint;
 * when the time comes [ScheduledDownloadWorker] hands the item to DownloadService.
 */
object Scheduler {

    private fun workName(id: String) = "scheduled-$id"

    // ---------------------------------------------------------------- night hours

    private fun minuteOfDay(t: Long) = Calendar.getInstance().run { timeInMillis = t; get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE) }

    fun isNight(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val m = minuteOfDay(now)
        val s = Settings.nightStart(context)
        val e = Settings.nightEnd(context)
        return if (s <= e) m in s..e else m >= s || m <= e
    }

    /** Next moment the night hours start (now if it is night already). */
    fun nextNightStart(context: Context, now: Long = System.currentTimeMillis()): Long =
        if (isNight(context, now)) now else nextTime(Settings.nightStart(context), now)

    /** Next time the clock shows [minuteOfDay] (today if still ahead, else tomorrow). */
    fun nextTime(minuteOfDay: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun formatMinutes(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    fun nightLabel(context: Context) =
        "${formatMinutes(Settings.nightStart(context))}–${formatMinutes(Settings.nightEnd(context))}"

    // ---------------------------------------------------------------- scheduling

    /** Plans an existing item. [at] is used for TIME (ms). */
    fun schedule(context: Context, id: String, type: ScheduleType, at: Long = 0) {
        val item = DownloadStore.get(id) ?: return
        if (Blocklist.isBlocked(item.url) || Blocklist.isBlocked(item.referer)) {
            DownloadStore.update(id, persist = true) { it.copy(status = Status.FAILED, error = Blocklist.MESSAGE) }
            return
        }
        val start = when (type) {
            ScheduleType.NIGHT -> nextNightStart(context)
            ScheduleType.TIME -> at
            else -> 0L
        }
        DownloadStore.update(id, persist = true) {
            it.copy(status = Status.SCHEDULED, scheduleType = type, scheduledAt = start, progress = -1, speed = "", error = null)
        }
        enqueueWork(context, id, type, start)
    }

    private fun enqueueWork(context: Context, id: String, type: ScheduleType, start: Long) {
        val wifi = type == ScheduleType.WIFI || Settings.wifiOnly(context)
        val request = OneTimeWorkRequestBuilder<ScheduledDownloadWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay((start - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag("scheduled")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Starts a scheduled item right away. */
    fun startNow(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        DownloadStore.update(id, persist = true) { it.copy(scheduleType = ScheduleType.NONE, scheduledAt = 0) }
        DownloadService.start(context, id)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        DownloadStore.update(id, persist = true) {
            it.copy(status = Status.CANCELLED, scheduleType = ScheduleType.NONE, scheduledAt = 0)
        }
    }

    /** Night download paused at the end of the night hours: continue the next night. */
    fun resumeNextNight(context: Context, id: String) {
        val start = nextTime(Settings.nightStart(context))
        DownloadStore.update(id, persist = true) {
            it.copy(status = Status.SCHEDULED, scheduleType = ScheduleType.NIGHT, scheduledAt = start, speed = "", error = null)
        }
        enqueueWork(context, id, ScheduleType.NIGHT, start)
    }

    /** "Tonight 00:00", "Tomorrow 14:30", "Fri 09:00", "When on Wi-Fi". */
    fun label(item: DownloadItem): String {
        val wifiNote = ""
        return when (item.scheduleType) {
            ScheduleType.WIFI -> "When on Wi-Fi"
            ScheduleType.NIGHT, ScheduleType.TIME -> {
                val t = item.scheduledAt
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
                val days = dayDiff(t)
                val day = when {
                    item.scheduleType == ScheduleType.NIGHT && days <= 1 -> "Tonight"
                    days == 0 -> "Today"
                    days == 1 -> "Tomorrow"
                    else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
                }
                "$day $time$wifiNote"
            }
            ScheduleType.NONE -> "Waiting"
        }
    }

    private fun dayDiff(t: Long): Int {
        fun dayStart(x: Long) = Calendar.getInstance().apply {
            timeInMillis = x; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((dayStart(t) - dayStart(System.currentTimeMillis())) / (24 * 3600_000L)).toInt()
    }
}

/** Runs when a scheduled download's time / network has come: hands it to DownloadService. */
class ScheduledDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val item = DownloadStore.get(id) ?: return Result.success()
        if (item.status != Status.SCHEDULED) return Result.success()
        if (Blocklist.isBlocked(item.url) || Blocklist.isBlocked(item.referer)) {
            DownloadStore.update(id, persist = true) { it.copy(status = Status.FAILED, error = Blocklist.MESSAGE) }
            return Result.success()
        }
        try {
            DownloadService.start(applicationContext, id)
        } catch (e: Exception) {
            // Android 12+ may not allow starting a download from the background (battery optimisation on).
            // Ask the user with a notification; tapping it starts the download.
            notifyReady(item)
        }
        return Result.success()
    }

    private fun notifyReady(item: DownloadItem) {
        val ctx = applicationContext
        val start = PendingIntent.getForegroundService(
            ctx, item.id.hashCode(), DownloadService.startIntent(ctx, item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, DownloadService.CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Scheduled download is ready")
            .setContentText("Tap to start: ${item.title}")
            .setContentIntent(start)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            ctx.getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), n)
        }
    }
}
