package com.swiftload.app

import android.net.Uri
import android.text.Html
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Works out WHAT a link is, so the app can choose how to download it:
 *  - a direct file (mp4, mp3, pdf, zip, apk, jpg …)  -> fast multi-connection downloader
 *  - a web page with media (YouTube, TikTok, Instagram, Facebook, X, Vimeo … 1000+ sites) -> yt-dlp
 */
object Detector {

    data class Result(
        val url: String,
        val isMediaPage: Boolean,
        val fileName: String,
        val mime: String?,
        val size: Long,
        val category: Category,
    )

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private val MEDIA_HOSTS = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "fb.watch",
        "twitter.com", "x.com", "vimeo.com", "dailymotion.com", "soundcloud.com", "reddit.com",
        "twitch.tv", "pinterest.com", "pin.it", "threads.net", "bilibili.com", "snapchat.com",
        "likee.video", "audiomack.com", "mixcloud.com", "bandcamp.com", "boomplay.com",
    )

    private val URL_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** Pulls the first link out of shared text like "Watch this! https://youtu.be/abc via @app". */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL_REGEX.find(text) ?: run {
            // "www.site.com/file.zip" without scheme
            val t = text.trim()
            return if (!t.contains(' ') && t.contains('.')) "https://$t" else null
        }
        return m.value.trimEnd('.', ',', ')', ']', '!', '?', ';')
    }

    fun isYoutube(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        return host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")
    }

    fun isKnownMediaSite(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return false
        return MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    suspend fun detect(url: String): Result = withContext(Dispatchers.IO) {
        if (isKnownMediaSite(url)) {
            return@withContext Result(url, true, "", null, -1, Category.VIDEO)
        }

        val extFromUrl = MimeTypeMap.getFileExtensionFromUrl(url).lowercase(Locale.ROOT)
        val mimeFromUrl = mimeFromExt(extFromUrl)

        // Ask the server what it is (HEAD, then a 1-byte GET if HEAD is refused).
        var mime: String? = null
        var size = -1L
        var disposition: String? = null
        var finalUrl = url
        runCatching {
            val c = open(url, "HEAD")
            var code = c.responseCode
            var conn = c
            if (code == 405 || code == 403 || code >= 500) {
                c.disconnect()
                conn = open(url, "GET").apply { setRequestProperty("Range", "bytes=0-0") }
                code = conn.responseCode
            }
            if (code in 200..299) {
                mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                disposition = conn.getHeaderField("Content-Disposition")
                size = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: conn.contentLengthLong
                finalUrl = conn.url.toString()
            }
            conn.disconnect()
        }

        val isHtml = mime == null && mimeFromUrl == null ||
            mime?.startsWith("text/html") == true ||
            mime == "application/xhtml+xml"

        if (isHtml && disposition == null) {
            // A normal web page: let yt-dlp look for video/audio inside it.
            return@withContext Result(url, true, "", null, -1, Category.VIDEO)
        }

        val realMime = when {
            mime == null || mime == "application/octet-stream" || mime == "binary/octet-stream" -> mimeFromUrl ?: mime
            else -> mime
        }
        val name = URLUtil.guessFileName(finalUrl, disposition, realMime)
        val finalMime = realMime ?: mimeFromExt(name.substringAfterLast('.', "")) ?: "application/octet-stream"
        Result(finalUrl, false, name, finalMime, size, categoryOf(finalMime, name))
    }

    // ---------------- real video title ----------------

    /** True for the temporary names used until the real title is known ("YouTube video", "TikTok MP3"). */
    fun isPlaceholderTitle(title: String) = title.endsWith(" video") || title.endsWith(" MP3")

    /** Real title + thumbnail picture of a video page; either can be null. */
    data class PageInfo(val title: String?, val thumbnailUrl: String?)

    /**
     * Finds the real title and thumbnail of a video page (e.g. the YouTube video) quickly, without yt-dlp.
     * Never throws; fields are null if nothing is found within [timeoutMs].
     */
    suspend fun fetchInfo(url: String, timeoutMs: Long = 8_000): PageInfo {
        val fallback = PageInfo(null, youtubeThumb(url))
        return try {
            // Runs on a detached IO job so a stuck network read can't hold the caller past the timeout.
            val job = CoroutineScope(Dispatchers.IO).async { runCatching { fetchInfoBlocking(url) }.getOrNull() }
            withTimeoutOrNull(timeoutMs) { job.await() } ?: run { job.cancel(); fallback }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fallback
        }
    }

    private fun fetchInfoBlocking(url: String): PageInfo {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return PageInfo(null, null)
        val enc = URLEncoder.encode(url, "UTF-8")
        val oembed = when {
            isYoutube(url) -> "https://www.youtube.com/oembed?url=$enc&format=json"
            host.endsWith("tiktok.com") -> "https://www.tiktok.com/oembed?url=$enc"
            host.endsWith("vimeo.com") -> "https://vimeo.com/api/oembed.json?url=$enc"
            else -> null
        }
        var title: String? = null
        var thumb: String? = null
        if (oembed != null) {
            runCatching {
                val o = JSONObject(readText(oembed, 64 * 1024))
                title = clean(o.optString("title"))
                thumb = o.optString("thumbnail_url").trim().ifEmpty { null }
            }
        }
        if (thumb == null) thumb = youtubeThumb(url)
        if (title == null || thumb == null) {
            // Any other site (or oEmbed failed): read the start of the HTML page.
            runCatching {
                val html = readText(url, 200 * 1024)
                if (title == null) title = clean(metaContent(html, OG_TITLE_ATTR)) ?: clean(htmlTitle(html))
                if (thumb == null) thumb = metaContent(html, OG_IMAGE_ATTR)?.let { absoluteUrl(url, it) }
            }
        }
        return PageInfo(title, thumb)
    }

    private val YT_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    /** Video id from youtube.com/watch?v=…, youtu.be/…, /shorts/…, /embed/…, /live/… */
    fun youtubeId(url: String): String? {
        if (!isYoutube(url)) return null
        val u = Uri.parse(url)
        val segs = u.pathSegments
        val id = when {
            u.host.equals("youtu.be", true) -> segs.firstOrNull()
            segs.firstOrNull() in setOf("shorts", "embed", "live", "v") -> segs.getOrNull(1)
            else -> runCatching { u.getQueryParameter("v") }.getOrNull()
        }
        return id?.takeIf { YT_ID.matches(it) }
    }

    /** Always-available YouTube picture, used when oEmbed gives none. */
    fun youtubeThumb(url: String): String? = youtubeId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val OG_TITLE_ATTR = Regex("""(?:property|name)\s*=\s*["']og:title["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_ATTR =
        Regex("""(?:property|name)\s*=\s*["'](?:og:image(?::url|:secure_url)?|twitter:image)["']""", RegexOption.IGNORE_CASE)
    private val CONTENT_ATTR = Regex("""content\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CHARSET = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    /** content="…" of the first <meta> tag whose property/name matches [attr]. */
    private fun metaContent(html: String, attr: Regex): String? = META_TAG.findAll(html)
        .map { it.value }
        .firstOrNull { attr.containsMatchIn(it) }
        ?.let { tag -> CONTENT_ATTR.find(tag)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } } }
        ?.trim()?.ifEmpty { null }

    /** "/img/a.jpg" or "//cdn/a.jpg" -> full https URL; also turns &amp; into &. */
    private fun absoluteUrl(base: String, raw: String): String? = runCatching {
        val decoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        URL(URL(base), decoded).toString().takeIf { it.startsWith("http") }
    }.getOrNull()

    private fun htmlTitle(html: String): String? = TITLE_TAG.find(html)?.groupValues?.get(1)

    /** Decodes HTML entities (&amp; &#39; …) and collapses spaces; null if empty. */
    private fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().replace(SPACES, " ").trim().ifEmpty { null }
    }

    /** Downloads at most [limit] bytes of a page as text. */
    private fun readText(url: String, limit: Int): String {
        val c = open(url, "GET").apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val charset = c.contentType?.let { CHARSET.find(it)?.groupValues?.get(1) }
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (out.size() < limit) {
                    val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return String(out.toByteArray(), charset)
        } finally {
            c.disconnect()
        }
    }

    fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }

    fun mimeFromExt(ext: String): String? {
        if (ext.isBlank()) return null
        return when (ext.lowercase(Locale.ROOT)) {
            "apk" -> "application/vnd.android.package-archive"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))
        }
    }

    fun categoryOf(mime: String?, name: String): Category {
        val m = mime ?: ""
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m.startsWith("video/") -> Category.VIDEO
            m.startsWith("audio/") -> Category.AUDIO
            m.startsWith("image/") -> Category.IMAGE
            m == "application/vnd.android.package-archive" || ext == "apk" || ext == "xapk" -> Category.APP
            ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") ||
                m.contains("zip") || m.contains("compressed") || m.contains("x-tar") -> Category.ARCHIVE
            m.startsWith("text/") || m == "application/pdf" || m.contains("document") ||
                m.contains("msword") || m.contains("sheet") || m.contains("excel") ||
                m.contains("presentation") || m.contains("powerpoint") || m.contains("epub") -> Category.DOCUMENT
            else -> Category.OTHER
        }
    }
}
