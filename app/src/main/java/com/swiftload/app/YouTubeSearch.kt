package com.swiftload.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class SearchResult(val id: String, val title: String, val channel: String, val durationSec: Long) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val bigThumbnail get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

/** YouTube search with yt-dlp ("ytsearch20:<words>", flat = fast, no video pages are opened). */
object YouTubeSearch {
    private val counter = AtomicInteger()
    @Volatile private var currentId: String? = null
    private val YT_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    /** Stops a search that is still running (e.g. the user typed a new one). */
    fun cancel() {
        currentId?.let { id -> runCatching { YoutubeDL.getInstance().destroyProcessById(id) } }
        currentId = null
    }

    suspend fun search(context: Context, query: String, count: Int = 20): List<SearchResult> =
        withContext(Dispatchers.IO) {
            Engine.ensureReady(context)
            cancel()
            val processId = "search-${counter.incrementAndGet()}"
            currentId = processId
            val req = YoutubeDLRequest("ytsearch$count:$query").apply {
                addOption("--flat-playlist")
                addOption("-J")
                addOption("--no-cache-dir")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(req, processId).out
            if (currentId == processId) currentId = null
            parse(out)
        }

    private fun parse(json: String): List<SearchResult> {
        val entries = JSONObject(json.substring(json.indexOf('{').coerceAtLeast(0))).optJSONArray("entries")
            ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val o = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            // Only videos (search can also return channels / playlists)
            if (!YT_ID.matches(id)) return@mapNotNull null
            SearchResult(
                id = id,
                title = o.optString("title").ifBlank { "YouTube video" },
                channel = o.optString("channel").ifBlank { o.optString("uploader") }.let { if (it == "null") "" else it },
                durationSec = o.optDouble("duration", -1.0).let { if (it.isNaN()) -1L else it.toLong() },
            )
        }
    }
}
