package com.swiftload.app

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class SearchResult(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val url: String,
    val thumbnail: String?,
)

/** Music search on SoundCloud with yt-dlp ("scsearch20:<words>", flat = fast). YouTube is not used. */
object MusicSearch {
    private val counter = AtomicInteger()
    @Volatile private var currentId: String? = null

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
            val req = YoutubeDLRequest("scsearch$count:$query").apply {
                addOption("--use-extractors", "default,-youtube.*")
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
            val url = o.optString("webpage_url").ifBlank { o.optString("url") }
            if (!url.startsWith("http") || Blocklist.isBlocked(url)) return@mapNotNull null
            // Flat results may lack some fields: fall back to the link ("artist/track-name")
            val segs = runCatching { Uri.parse(url).pathSegments }.getOrNull().orEmpty()
            val slugTitle = segs.lastOrNull()?.replace('-', ' ')?.replaceFirstChar { it.uppercase() }
            fun str(key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }
            val thumb = str("thumbnail") ?: o.optJSONArray("thumbnails")?.let { arr ->
                (arr.length() - 1 downTo 0).firstNotNullOfOrNull { j -> arr.optJSONObject(j)?.optString("url")?.takeIf { it.startsWith("http") } }
            }
            SearchResult(
                id = str("id") ?: url,
                title = str("title") ?: slugTitle ?: "Track",
                channel = str("uploader") ?: str("channel") ?: segs.firstOrNull() ?: "",
                durationSec = o.optDouble("duration", -1.0).let { if (it.isNaN()) -1L else it.toLong() },
                url = url,
                thumbnail = thumb?.takeUnless { Blocklist.isBlocked(it) },
            )
        }
    }
}
