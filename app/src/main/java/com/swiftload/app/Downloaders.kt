package com.swiftload.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.coroutineContext

/** Lazily starts the bundled yt-dlp + FFmpeg engine. */
object Engine {
    private val mutex = Mutex()
    @Volatile private var ready = false

    suspend fun ensureReady(context: Context) = mutex.withLock {
        if (!ready) withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
        }
    }

    private val updateMutex = Mutex()

    /**
     * Downloads the newest yt-dlp (nightly = fastest YouTube fixes) so sites keep working after they change.
     * The yt-dlp bundled inside the APK is old, so this matters a lot for YouTube.
     */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        ensureReady(context)
        updateMutex.withLock {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
            prefs(context).edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            when (status?.name) {
                "ALREADY_UP_TO_DATE" -> "Engine is already up to date"
                else -> "Engine updated to the latest version"
            }
        }
    }

    /** Updates automatically at most once every 12 hours; errors (e.g. no internet) are ignored. */
    suspend fun autoUpdateIfNeeded(context: Context) {
        val last = prefs(context).getLong("lastUpdate", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        runCatching { update(context) }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("engine", Context.MODE_PRIVATE)
}

/**
 * IDM-style accelerated downloader for direct file links.
 * Splits the file into several parts downloaded at the same time when the server allows it,
 * retries/resumes each part if the connection drops, and remembers the parts' progress in
 * "<file>.parts" so a paused download (night hours ended) continues where it stopped.
 */
object DirectDownloader {
    private const val MIN_SPLIT_SIZE = 2L * 1024 * 1024
    private const val MAX_PARTS = 6
    private const val RETRIES = 5

    /** [headers]: extra request headers (Referer, Cookie) for files found on a web page. */
    suspend fun download(
        url: String, dest: File, headers: Map<String, String> = emptyMap(),
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // Does the server support byte ranges?
        var total = -1L
        var ranges = false
        runCatching {
            val c = Detector.open(url, "GET", headers).apply { setRequestProperty("Range", "bytes=0-0") }
            if (c.responseCode == 206) {
                total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                ranges = total > 0
            } else if (c.responseCode in 200..299) {
                total = c.contentLengthLong
            }
            c.disconnect()
        }

        val done = AtomicLong(0)
        val state = File(dest.path + ".parts")
        var positions: AtomicLongArray? = null
        val reporter = launch {
            while (isActive) {
                onProgress(done.get(), total)
                positions?.let { p ->
                    runCatching {
                        state.writeText("$total,${p.length()}\n" + (0 until p.length()).joinToString("\n") { p.get(it).toString() })
                    }
                }
                delay(500)
            }
        }
        try {
            if (ranges && total >= MIN_SPLIT_SIZE) {
                val parts = (total / MIN_SPLIT_SIZE).coerceIn(2, MAX_PARTS.toLong()).toInt()
                val chunk = total / parts
                val starts = LongArray(parts) { it * chunk }
                val ends = LongArray(parts) { if (it == parts - 1) total - 1 else starts[it] + chunk - 1 }
                val pos = AtomicLongArray(parts)
                // Continue a paused download if the saved state matches this file
                val saved = runCatching { state.readLines() }.getOrNull()
                if (saved != null && saved.firstOrNull() == "$total,$parts" && dest.length() == total) {
                    for (i in 0 until parts) {
                        pos.set(i, (saved.getOrNull(i + 1)?.toLongOrNull() ?: starts[i]).coerceIn(starts[i], ends[i] + 1))
                    }
                } else {
                    RandomAccessFile(dest, "rw").use { it.setLength(total) }
                    for (i in 0 until parts) pos.set(i, starts[i])
                }
                done.set((0 until parts).sumOf { pos.get(it) - starts[it] })
                positions = pos
                coroutineScope {
                    (0 until parts).map { i -> async { downloadRange(url, dest, headers, pos, i, ends[i], done) } }.awaitAll()
                }
                positions = null
                state.delete()
            } else {
                downloadSingle(url, dest, headers, done)
                if (total <= 0) total = dest.length()
            }
        } finally {
            reporter.cancel()
        }
        onProgress(dest.length(), dest.length())
    }

    private suspend fun downloadRange(
        url: String, dest: File, headers: Map<String, String>, pos: AtomicLongArray, i: Int, end: Long, done: AtomicLong,
    ) {
        var attempt = 0
        while (pos.get(i) <= end) {
            try {
                val c = Detector.open(url, "GET", headers).apply { setRequestProperty("Range", "bytes=${pos.get(i)}-$end") }
                if (c.responseCode != 206) throw IOException("Server stopped accepting ranges (${c.responseCode})")
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(pos.get(i))
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (pos.get(i) <= end) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            val take = minOf(n.toLong(), end - pos.get(i) + 1).toInt()
                            raf.write(buf, 0, take)
                            pos.addAndGet(i, take.toLong()) // the saved position is never ahead of the written data
                            done.addAndGet(take.toLong())
                        }
                    }
                }
                c.disconnect()
                if (pos.get(i) <= end) throw IOException("Connection closed early")
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
                delay(1000L * attempt) // back off, then resume from where this part stopped
            }
        }
    }

    private suspend fun downloadSingle(url: String, dest: File, headers: Map<String, String>, done: AtomicLong) {
        var attempt = 0
        while (true) {
            try {
                val already = if (dest.exists()) dest.length() else 0L
                val c = Detector.open(url, "GET", headers)
                if (already > 0) c.setRequestProperty("Range", "bytes=$already-")
                val code = c.responseCode
                if (code !in 200..299) throw IOException("HTTP error $code")
                val append = code == 206
                if (!append) done.set(0) else done.set(already)
                java.io.FileOutputStream(dest, append).use { out ->
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done.addAndGet(n.toLong())
                        }
                    }
                }
                c.disconnect()
                return
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
                delay(1000L * attempt)
            }
        }
    }
}

/**
 * Video / MP3 downloads from TikTok, Instagram, Facebook, X, Vimeo and 1000+ other sites via yt-dlp,
 * plus HLS (.m3u8) / DASH (.mpd) streams found by the media sniffer (merged into one .mp4 with FFmpeg).
 * YouTube is not supported: blocked links are refused and yt-dlp's YouTube extractors are switched off.
 */
object MediaDownloader {
    private val SPEED = Regex("""at\s+~?\s*([\d.]+\s*[KMG]?i?B/s)""")
    private val DEST = Regex("""Destination:\s+(.+)$""")

    /** Every extractor except YouTube's. */
    private const val EXTRACTORS = "default,-youtube.*"

    suspend fun download(
        context: Context,
        id: String,
        url: String,
        mode: Mode,
        dir: File,
        headers: Map<String, String> = emptyMap(),
        maxHeight: Int = 0,
        fileTitle: String? = null,
        onProgress: (percent: Float, speed: String, title: String?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (Blocklist.isBlocked(url)) throw IOException(Blocklist.MESSAGE)
        Engine.ensureReady(context)
        // Make sure we use a fresh yt-dlp (the one inside the APK is old)
        Engine.autoUpdateIfNeeded(context)
        coroutineContext.ensureActive()
        // Partial files of a paused night download are kept on purpose: yt-dlp continues them.
        runOnce(context, id, url, mode, dir, headers, maxHeight, fileTitle, onProgress)
        dir.listFiles()
            ?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") &&
                    !it.name.contains(".temp.") && !it.name.endsWith(".parts")
            }
            ?.maxByOrNull { it.length() }
            ?: throw IOException("Nothing was downloaded")
    }

    private fun runOnce(
        context: Context, id: String, url: String, mode: Mode, dir: File,
        headers: Map<String, String>, maxHeight: Int, fileTitle: String?,
        onProgress: (percent: Float, speed: String, title: String?) -> Unit,
    ) {
        val req = YoutubeDLRequest(url).apply {
            addOption("--use-extractors", EXTRACTORS)
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("--no-cache-dir")
            addOption("-N", "4")                // 4 fragments in parallel = faster
            // Streams found by the sniffer are named after the page title (their own name is often "master")
            val name = fileTitle?.let { Storage.sanitize(it).take(120).replace("%", "%%") }
            addOption("-o", if (name != null) "${dir.absolutePath}/$name.%(ext)s" else "${dir.absolutePath}/%(title).120B.%(ext)s")
            if (headers.isNotEmpty()) {
                // Files found on a web page: same Referer, browser and cookies as the page
                headers["Referer"]?.let { addOption("--referer", it) }
                addOption("--user-agent", Detector.USER_AGENT)
                headers["Cookie"]?.let { addOption("--add-header", "Cookie:$it") }
            }
            if (mode == Mode.AUDIO_MP3) {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                // Write the song name into the MP3 tags so music players show it
                addOption("--embed-metadata")
                addOption("--parse-metadata", "%(title)s:%(meta_title)s")
            } else {
                val h = if (maxHeight > 0) maxHeight else Settings.videoQuality(context)
                val f = if (h <= 0) {
                    "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
                } else {
                    "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b"
                }
                addOption("-f", f)
                addOption("--merge-output-format", "mp4")
            }
        }

        var title: String? = null
        YoutubeDL.getInstance().execute(req, id) { progress, _, line ->
            DEST.find(line)?.let { m ->
                title = File(m.groupValues[1].trim()).nameWithoutExtension
            }
            val speed = SPEED.find(line)?.groupValues?.get(1) ?: ""
            onProgress(progress, speed, title)
        }
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }
}
