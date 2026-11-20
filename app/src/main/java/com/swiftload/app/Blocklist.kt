package com.swiftload.app

import java.util.Locale

/**
 * YouTube is not supported by VidSaver. One place decides what is blocked; every entry point
 * (browser, Share, paste, clipboard, page downloads, media sniffer, scheduler, yt-dlp) asks here.
 *
 * Pure Kotlin (no Android classes) so it is covered by JVM unit tests.
 */
object Blocklist {
    const val MESSAGE = "YouTube is not supported."

    /** A host is blocked if it IS one of these or is a sub-domain of one (e.g. m.youtube.com, rr3---sn-x.googlevideo.com). */
    private val DOMAINS = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "googlevideo.com",
        "ytimg.com",
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
    )

    /** Works with a full URL ("https://m.youtube.com/watch?v=…"), a URL without scheme ("youtu.be/xyz") or a bare host. */
    fun isBlocked(urlOrHost: String?): Boolean {
        val host = hostOf(urlOrHost) ?: return false
        return DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /** Lower-case host of a URL or bare host; null if there is none. */
    fun hostOf(urlOrHost: String?): String? {
        var s = urlOrHost?.trim() ?: return null
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme in 1..20) s = s.substring(scheme + 3)
        else if (s.startsWith("//")) s = s.substring(2)
        // host ends at the first / ? # or backslash
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        if (end >= 0) s = s.substring(0, end)
        s = s.substringAfterLast('@')          // user:pass@host
        s = if (s.startsWith("[")) s.substringBefore(']') + "]" else s.substringBefore(':') // port
        s = s.trimEnd('.').lowercase(Locale.ROOT)
        return s.ifEmpty { null }
    }
}
