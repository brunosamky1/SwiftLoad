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

    /** Downloads the newest yt-dlp so sites like YouTube keep working after they change. */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        ensureReady(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        when (status?.name) {
            "ALREADY_UP_TO_DATE" -> "Engine is already up to date"
            else -> "Engine updated to the latest version"
        }
    }
}

/**
 * IDM-style accelerated downloader for direct file links.
 * Splits the file into several parts downloaded at the same time when the server allows it,
 * and retries/resumes each part if the connection drops.
 */
object DirectDownloader {
    private const val MIN_SPLIT_SIZE = 2L * 1024 * 1024
    private const val MAX_PARTS = 6
    private const val RETRIES = 5

    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            // Does the server support byte ranges?
            var total = -1L
            var ranges = false
            runCatching {
                val c = Detector.open(url, "GET").apply { setRequestProperty("Range", "bytes=0-0") }
                if (c.responseCode == 206) {
                    total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                    ranges = total > 0
                } else if (c.responseCode in 200..299) {
                    total = c.contentLengthLong
                }
                c.disconnect()
            }

            val done = AtomicLong(0)
            val reporter = launch {
                while (isActive) { onProgress(done.get(), total); delay(500) }
            }
            try {
                if (ranges && total >= MIN_SPLIT_SIZE) {
                    RandomAccessFile(dest, "rw").use { it.setLength(total) }
                    val parts = (total / MIN_SPLIT_SIZE).coerceIn(2, MAX_PARTS.toLong()).toInt()
                    val chunk = total / parts
                    coroutineScope {
                        (0 until parts).map { i ->
                            val start = i * chunk
                            val end = if (i == parts - 1) total - 1 else start + chunk - 1
                            async { downloadRange(url, dest, start, end, done) }
                        }.awaitAll()
                    }
                } else {
                    downloadSingle(url, dest, done)
                    if (total <= 0) total = dest.length()
                }
            } finally {
                reporter.cancel()
            }
            onProgress(dest.length(), dest.length())
        }

    private suspend fun downloadRange(url: String, dest: File, start: Long, end: Long, done: AtomicLong) {
        var pos = start
        var attempt = 0
        while (pos <= end) {
            try {
                val c = Detector.open(url, "GET").apply { setRequestProperty("Range", "bytes=$pos-$end") }
                if (c.responseCode != 206) throw IOException("Server stopped accepting ranges (${c.responseCode})")
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(pos)
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (pos <= end) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            val take = minOf(n.toLong(), end - pos + 1).toInt()
                            raf.write(buf, 0, take)
                            pos += take
                            done.addAndGet(take.toLong())
                        }
                    }
                }
                c.disconnect()
                if (pos <= end) throw IOException("Connection closed early")
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
                delay(1000L * attempt) // back off, then resume from where this part stopped
            }
        }
    }

    private suspend fun downloadSingle(url: String, dest: File, done: AtomicLong) {
        var attempt = 0
        while (true) {
            try {
                val already = if (dest.exists()) dest.length() else 0L
                val c = Detector.open(url, "GET")
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

/** Video / MP3 downloads from YouTube, TikTok, Instagram, Facebook, X and 1000+ other sites via yt-dlp. */
object MediaDownloader {
    private val SPEED = Regex("""at\s+~?\s*([\d.]+\s*[KMG]?i?B/s)""")
    private val DEST = Regex("""Destination:\s+(.+)$""")

    suspend fun download(
        context: Context,
        id: String,
        url: String,
        mode: Mode,
        dir: File,
        onProgress: (percent: Float, speed: String, title: String?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        Engine.ensureReady(context)
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")          // a YouTube "Mix" link downloads just this song
            addOption("--no-mtime")
            addOption("--windows-filenames")
            addOption("-N", "4")                // 4 fragments in parallel = faster
            addOption("-o", "${dir.absolutePath}/%(title).120B.%(ext)s")
            if (mode == Mode.AUDIO_MP3) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("--add-metadata")
            } else {
                val h = Settings.videoQuality(context)
                val f = if (h <= 0) {
                    "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
                } else {
                    "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b"
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

        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".temp.") }
            ?.maxByOrNull { it.length() }
            ?: throw IOException("Nothing was downloaded")
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
    }
}
