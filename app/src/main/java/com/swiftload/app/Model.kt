package com.swiftload.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How a link is downloaded. */
enum class Mode { FILE, VIDEO, AUDIO_MP3 }

enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED, SCHEDULED }

/** When a scheduled download should start (see Scheduler). */
enum class ScheduleType { NONE, NIGHT, TIME, WIFI }

/** File category, used for the sub-folder inside Download/SwiftLoad and for the label in the list. */
enum class Category(val folder: String, val label: String) {
    VIDEO("Video", "Video"),
    AUDIO("Music", "Audio"),
    IMAGE("Images", "Image"),
    DOCUMENT("Documents", "Document"),
    ARCHIVE("Archives", "Archive"),
    APP("Apps", "App (APK)"),
    OTHER("Other", "File");
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val mode: Mode,
    val category: Category,
    val status: Status = Status.QUEUED,
    val progress: Int = -1,           // 0..100, -1 = unknown
    val speed: String = "",           // e.g. "2.4 MB/s"
    val sizeBytes: Long = -1,
    val fileUri: String? = null,
    val mime: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Online thumbnail (https://…) or a local one (file://… in cacheDir/thumbs, or the image's content:// uri). */
    val thumbnailUrl: String? = null,
    /** Video/audio length in ms, -1 = unknown. */
    val durationMs: Long = -1,
    /** Page the file was found on (media sniffer): sent as Referer, cookies are added at download time. */
    val referer: String? = null,
    /** Max video height for this item (HLS/DASH quality picked in the sniffer); 0 = Settings value. */
    val maxHeight: Int = 0,
    val scheduleType: ScheduleType = ScheduleType.NONE,
    /** Planned start (ms) for NIGHT / TIME schedules. */
    val scheduledAt: Long = 0,
    /** Position in the queue (lower starts first); drag to reorder in the Queue screen. */
    val queueOrder: Long = createdAt,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title)
        put("mode", mode.name); put("category", category.name); put("status", status.name)
        put("progress", progress); put("sizeBytes", sizeBytes)
        put("fileUri", fileUri ?: ""); put("mime", mime ?: ""); put("error", error ?: "")
        put("createdAt", createdAt)
        put("thumbnailUrl", thumbnailUrl ?: ""); put("durationMs", durationMs)
        put("referer", referer ?: ""); put("maxHeight", maxHeight)
        put("scheduleType", scheduleType.name); put("scheduledAt", scheduledAt); put("queueOrder", queueOrder)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val saved = Status.valueOf(o.getString("status"))
            // Anything that was running when the app was killed is shown as failed (can be retried)
            val interrupted = saved == Status.RUNNING || saved == Status.QUEUED
            val status = if (interrupted) Status.FAILED else saved
            return DownloadItem(
                id = o.getString("id"),
                url = o.getString("url"),
                title = o.getString("title"),
                mode = Mode.valueOf(o.getString("mode")),
                category = Category.valueOf(o.getString("category")),
                status = status,
                progress = o.optInt("progress", -1),
                sizeBytes = o.optLong("sizeBytes", -1),
                fileUri = o.optString("fileUri").ifEmpty { null },
                mime = o.optString("mime").ifEmpty { null },
                error = if (interrupted) "Interrupted — tap to retry" else o.optString("error").ifEmpty { null },
                createdAt = o.optLong("createdAt"),
                // Old items saved before thumbnails existed simply have no such keys
                thumbnailUrl = o.optString("thumbnailUrl").ifEmpty { null },
                durationMs = o.optLong("durationMs", -1),
                referer = o.optString("referer").ifEmpty { null },
                maxHeight = o.optInt("maxHeight", 0),
                scheduleType = runCatching { ScheduleType.valueOf(o.optString("scheduleType", "NONE")) }.getOrDefault(ScheduleType.NONE),
                scheduledAt = o.optLong("scheduledAt", 0),
                queueOrder = o.optLong("queueOrder", o.optLong("createdAt")),
            )
        }
    }
}

/** In-memory list of downloads, saved to SharedPreferences so the history survives restarts. */
object DownloadStore {
    private const val PREFS = "downloads"
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null)
        if (raw != null) {
            runCatching {
                val arr = JSONArray(raw)
                _items.value = (0 until arr.length()).map { DownloadItem.fromJson(arr.getJSONObject(it)) }
            }
        }
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        _items.value = listOf(item) + _items.value
        save()
    }

    @Synchronized
    fun update(id: String, persist: Boolean = false, block: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) block(it) else it }
        if (persist) save()
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        Thumbs.delete(appContext, id)
        save()
    }

    fun clearFinished() {
        _items.value.filterNot { it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.SCHEDULED }
            .forEach { Thumbs.delete(appContext, it.id) }
        _items.value = _items.value.filter { it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.SCHEDULED }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _items.value.take(300).forEach { arr.put(it.toJson()) }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }
}

/** User settings. */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = ask every time, 1 = always video, 2 = always MP3 */
    fun mediaAction(c: Context) = prefs(c).getInt("mediaAction", 0)
    fun setMediaAction(c: Context, v: Int) = prefs(c).edit().putInt("mediaAction", v).apply()

    /** max video height; 0 = best available */
    fun videoQuality(c: Context) = prefs(c).getInt("videoQuality", 720)
    fun setVideoQuality(c: Context, v: Int) = prefs(c).edit().putInt("videoQuality", v).apply()

    /** How many downloads may run at the same time (1–5). */
    fun maxParallel(c: Context) = prefs(c).getInt("maxParallel", 3).coerceIn(1, 5)
    fun setMaxParallel(c: Context, v: Int) = prefs(c).edit().putInt("maxParallel", v.coerceIn(1, 5)).apply()

    /** 0 = dark (default), 1 = light, 2 = follow the phone */
    fun themeMode(c: Context) = prefs(c).getInt("theme", 0)
    fun setThemeMode(c: Context, v: Int) = prefs(c).edit().putInt("theme", v).apply()

    fun applyTheme(c: Context) = androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        when (themeMode(c)) {
            1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
    )

    /** Junk Cleaner: was the "All files access" explanation shown already? */
    fun cleanerAsked(c: Context) = prefs(c).getBoolean("cleanerAsked", false)
    fun setCleanerAsked(c: Context) = prefs(c).edit().putBoolean("cleanerAsked", true).apply()

    /** Junk Cleaner: total bytes really freed so far ("Cleaned so far"). */
    fun cleanedTotal(c: Context) = prefs(c).getLong("cleanedTotal", 0)
    fun addCleaned(c: Context, bytes: Long) = prefs(c).edit().putLong("cleanedTotal", cleanedTotal(c) + bytes.coerceAtLeast(0)).apply()

    /** Rename to VidSaver: was the user asked about moving Download/SwiftLoad -> Download/VidSaver? */
    fun folderMoveAsked(c: Context) = prefs(c).getBoolean("folderMoveAsked", false)
    fun setFolderMoveAsked(c: Context) = prefs(c).edit().putBoolean("folderMoveAsked", true).apply()

    /** Night bundle hours as minutes of the day; default 00:00 – 05:59 (end minute included). */
    fun nightStart(c: Context) = prefs(c).getInt("nightStart", 0)
    fun nightEnd(c: Context) = prefs(c).getInt("nightEnd", 5 * 60 + 59)
    fun setNightHours(c: Context, start: Int, end: Int) =
        prefs(c).edit().putInt("nightStart", start).putInt("nightEnd", end).apply()

    /** Scheduled downloads wait for Wi-Fi (unmetered network). */
    fun wifiOnly(c: Context) = prefs(c).getBoolean("wifiOnly", false)
    fun setWifiOnly(c: Context, v: Boolean) = prefs(c).edit().putBoolean("wifiOnly", v).apply()

    /** Night downloads still running when the night hours end are paused and continue the next night. */
    fun pauseAfterNight(c: Context) = prefs(c).getBoolean("pauseAfterNight", true)
    fun setPauseAfterNight(c: Context, v: Boolean) = prefs(c).edit().putBoolean("pauseAfterNight", v).apply()

    fun lastClipboard(c: Context) = prefs(c).getString("lastClip", "") ?: ""
    fun setLastClipboard(c: Context, v: String) = prefs(c).edit().putString("lastClip", v).apply()
}
