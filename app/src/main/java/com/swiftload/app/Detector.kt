package com.swiftload.app

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
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
