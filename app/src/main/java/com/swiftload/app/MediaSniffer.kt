package com.swiftload.app

import android.net.Uri
import android.webkit.CookieManager
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * IDM-style "Download this video": watches the requests a web page makes (it never blocks or changes them)
 * and collects the real media files — MP4/WebM/MP3… files and HLS (.m3u8) / DASH (.mpd) streams.
 * Segments (.ts, .m4s …), ads/trackers, tiny files and blocked hosts are ignored.
 */
class MediaSniffer(private val onChanged: () -> Unit) {

    enum class Kind(val label: String) {
        VIDEO("Video file"), AUDIO("Audio file"), HLS("HLS stream"), DASH("DASH stream")
    }

    /** One quality of a stream (from the HLS master playlist or the DASH manifest). */
    data class Variant(val height: Int, val label: String, val bandwidth: Long)

    class Candidate(val url: String, val kind: Kind, val ext: String, val order: Int) {
        @Volatile var size: Long = -1
        @Volatile var mime: String? = null
        @Volatile var quality: String? = qualityFromUrl(url)
        @Volatile var variants: List<Variant> = emptyList()
        @Volatile var ready = false   // probed and worth showing
    }

    private val candidates = ConcurrentHashMap<String, Candidate>()
    /** Variant playlists of a master .m3u8: only the master is listed. */
    private val childPlaylists = ConcurrentHashMap.newKeySet<String>()
    private val counter = AtomicInteger()
    private val generation = AtomicInteger()
    private val probes = Executors.newFixedThreadPool(3)

    @Volatile var pageUrl: String = ""
        private set

    /** New page: forget what was found on the previous one. */
    fun reset(newPageUrl: String) {
        pageUrl = newPageUrl
        generation.incrementAndGet()
        candidates.clear()
        childPlaylists.clear()
        onChanged()
    }

    /** Found media, in the order they appeared. */
    fun list(): List<Candidate> = candidates.values.filter { it.ready }.sortedBy { it.order }

    fun shutdown() { probes.shutdownNow() }

    /** Called from WebViewClient.shouldInterceptRequest (background thread). Only looks, never blocks. */
    fun observe(url: String, requestHeaders: Map<String, String>?) {
        if (!url.startsWith("http", ignoreCase = true)) return
        if (Blocklist.isBlocked(url) || Blocklist.isBlocked(pageUrl)) return
        if (isAdOrTracker(url)) return
        val clean = url.substringBefore('#')
        val path = runCatching { Uri.parse(clean).path }.getOrNull()?.lowercase(Locale.ROOT) ?: return
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        val accept = requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value?.lowercase(Locale.ROOT) ?: ""

        if (ext in SEGMENT_EXT || isSegment(clean.lowercase(Locale.ROOT), path)) return
        val kind = when (ext) {
            in VIDEO_EXT -> Kind.VIDEO
            in AUDIO_EXT -> Kind.AUDIO
            "m3u8" -> Kind.HLS
            "mpd" -> Kind.DASH
            else -> when {
                accept.startsWith("video/") -> Kind.VIDEO
                accept.startsWith("audio/") -> Kind.AUDIO
                clean.contains("mime=video", true) -> Kind.VIDEO
                clean.contains("mime=audio", true) -> Kind.AUDIO
                else -> return
            }
        }
        if (kind == Kind.HLS && clean in childPlaylists) return
        if (candidates.containsKey(clean)) return
        val c = Candidate(clean, kind, ext, counter.incrementAndGet())
        candidates[clean] = c
        val gen = generation.get()
        runCatching { probes.execute { probe(c, gen) } }
    }

    // ---------------------------------------------------------------- probing (background)

    private fun headers(url: String): Map<String, String> {
        val h = mutableMapOf("Referer" to pageUrl)
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    private fun probe(c: Candidate, gen: Int) {
        runCatching {
            when (c.kind) {
                Kind.HLS -> probeHls(c)
                Kind.DASH -> probeDash(c)
                else -> probeFile(c)
            }
        }.onFailure {
            // Could not check it: still list streams, drop unknown plain requests
            c.ready = c.kind == Kind.HLS || c.kind == Kind.DASH
        }
        if (generation.get() == gen) onChanged()
    }

    private fun probeFile(c: Candidate) {
        var size = -1L
        var type: String? = null
        val head = Detector.open(c.url, "HEAD", headers(c.url))
        try {
            if (head.responseCode in 200..299) { size = head.contentLengthLong; type = head.contentType }
        } finally { head.disconnect() }
        if (size <= 0) {
            val get = Detector.open(c.url, "GET", headers(c.url) + ("Range" to "bytes=0-0"))
            try {
                if (get.responseCode in 200..299) {
                    size = get.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: get.contentLengthLong
                    type = type ?: get.contentType
                }
            } finally { get.disconnect() }
        }
        val mime = type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (mime != null && (mime.startsWith("text/") || mime.startsWith("image/") || mime.contains("javascript") || mime.contains("json"))) {
            candidates.remove(c.url); return
        }
        if (size in 0 until MIN_SIZE) { candidates.remove(c.url); return }  // tiny = icon, beep, preview
        c.size = size
        c.mime = mime
        c.ready = true
    }

    private fun probeHls(c: Candidate) {
        val text = readText(c.url)
        if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            // Master playlist: list its qualities; its variant playlists are not listed separately
            val variants = mutableListOf<Variant>()
            val lines = text.lines().map { it.trim() }
            lines.forEachIndexed { i, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed
                val res = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(line)
                val bw = Regex("""(?<![-A-Z])BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0
                lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { uri ->
                    runCatching { childPlaylists += URL(URL(c.url), uri).toString().substringBefore('#') }
                }
                variants += Variant(height, if (height > 0) "${height}p" else bandwidthLabel(bw), bw)
            }
            childPlaylists.forEach { candidates.remove(it) }
            c.variants = variants.distinctBy { it.height to it.bandwidth }.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
            c.ready = true
        } else {
            // Media playlist: keep it only if it isn't part of a master we already have
            c.ready = c.url !in childPlaylists
            if (!c.ready) candidates.remove(c.url)
        }
    }

    private fun probeDash(c: Candidate) {
        val text = readText(c.url)
        val heights = Regex("""\sheight="(\d+)"""").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sortedDescending().toList()
        c.variants = heights.map { Variant(it, "${it}p", 0) }
        c.ready = true
    }

    private fun readText(url: String): String {
        val conn = Detector.open(url, "GET", headers(url))
        try {
            return conn.inputStream.use { input ->
                val bytes = input.readBytes()
                String(bytes, 0, minOf(bytes.size, 512 * 1024))
            }
        } finally { conn.disconnect() }
    }

    companion object {
        const val MIN_SIZE = 300L * 1024

        val VIDEO_EXT = setOf("mp4", "webm", "m4v", "mov")
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "wav")
        private val SEGMENT_EXT = setOf("ts", "m4s", "m4f", "cmfv", "cmfa", "key", "vtt", "srt")

        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "adservice.google.com", "imasdk.googleapis.com", "adnxs.com", "adsrvr.org",
            "advertising.com", "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
            "pubmatic.com", "rubiconproject.com", "scorecardresearch.com", "moatads.com", "adform.net",
            "smartadserver.com", "teads.tv", "spotxchange.com", "springserve.com", "openx.net", "casalemedia.com",
            "exoclick.com", "propellerads.com", "adsterra.com", "popads.net", "juicyads.com", "trafficjunky.net",
        )
        private val AD_PATH = Regex("""(/ads?/|/adserver|/vast|vpaid|preroll|/advert|[?&]ad_?type=)""", RegexOption.IGNORE_CASE)

        fun isAdOrTracker(url: String): Boolean {
            val host = Blocklist.hostOf(url) ?: return true
            return AD_HOSTS.any { host == it || host.endsWith(".$it") } || AD_PATH.containsMatchIn(url)
        }

        private val SEGMENT_PATH = Regex("""(/seg(ment)?[-_]?\d+|/frag(ment)?[-_]?\d+|/chunk[-_]?\d+|/range/|/init[-_.]|-frag\d+)""")

        private fun isSegment(lowerUrl: String, path: String): Boolean =
            SEGMENT_PATH.containsMatchIn(path) || lowerUrl.contains("&range=") || lowerUrl.contains("?range=") ||
                lowerUrl.contains("bytes=")

        private val Q_P = Regex("""(?<![0-9])(4320|2160|1440|1080|720|540|480|360|240|144)p(?![a-z])""", RegexOption.IGNORE_CASE)
        private val Q_WH = Regex("""(?<![0-9])(\d{3,4})x(\d{3,4})(?![0-9])""")

        fun qualityFromUrl(url: String): String? {
            Q_P.find(url)?.let { return "${it.groupValues[1]}p" }
            Q_WH.find(url)?.let { m -> val h = m.groupValues[2].toInt(); if (h in 120..4320) return "${h}p" }
            return null
        }

        fun heightOf(quality: String?): Int = quality?.removeSuffix("p")?.toIntOrNull() ?: 0

        fun bandwidthLabel(bw: Long): String =
            if (bw <= 0) "Auto" else if (bw >= 1_000_000) "%.1f Mbps".format(bw / 1_000_000.0) else "${bw / 1000} kbps"
    }
}
