package com.swiftload.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    @Test fun blocksMainYoutubeHosts() {
        assertTrue(Blocklist.isBlocked("https://www.youtube.com/watch?v=T2Lbii-Mu-4"))
        assertTrue(Blocklist.isBlocked("https://youtube.com/"))
        assertTrue(Blocklist.isBlocked("https://m.youtube.com/"))
        assertTrue(Blocklist.isBlocked("https://music.youtube.com/watch?v=abc"))
        assertTrue(Blocklist.isBlocked("https://www.youtube.com/shorts/abcdefghijk"))
    }

    @Test fun blocksShortLinksAndUrlsWithoutScheme() {
        assertTrue(Blocklist.isBlocked("https://youtu.be/xyz"))
        assertTrue(Blocklist.isBlocked("youtu.be/xyz"))
        assertTrue(Blocklist.isBlocked("m.youtube.com/watch?v=1"))
        assertTrue(Blocklist.isBlocked("//www.youtube.com/embed/abc"))
    }

    @Test fun blocksNoCookieAndVideoCdns() {
        assertTrue(Blocklist.isBlocked("https://www.youtube-nocookie.com/embed/abc"))
        assertTrue(Blocklist.isBlocked("https://youtube-nocookie.com/embed/abc"))
        assertTrue(Blocklist.isBlocked("https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1"))
        assertTrue(Blocklist.isBlocked("https://googlevideo.com/x"))
        assertTrue(Blocklist.isBlocked("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertTrue(Blocklist.isBlocked("https://ytimg.com/"))
        assertTrue(Blocklist.isBlocked("https://youtubei.googleapis.com/youtubei/v1/player"))
        assertTrue(Blocklist.isBlocked("https://youtube.googleapis.com/v/abc"))
    }

    @Test fun uppercasePortsUserInfoAndTrailingDots() {
        assertTrue(Blocklist.isBlocked("HTTPS://WWW.YOUTUBE.COM/WATCH?V=ABC"))
        assertTrue(Blocklist.isBlocked("https://M.YouTube.Com/"))
        assertTrue(Blocklist.isBlocked("https://www.youtube.com:443/watch?v=1"))
        assertTrue(Blocklist.isBlocked("https://user:pw@youtube.com/"))
        assertTrue(Blocklist.isBlocked("https://www.youtube.com./"))
        assertTrue(Blocklist.isBlocked("YOUTU.BE"))
        assertTrue(Blocklist.isBlocked("  https://youtu.be/xyz  "))
    }

    @Test fun barehosts() {
        assertTrue(Blocklist.isBlocked("youtube.com"))
        assertTrue(Blocklist.isBlocked("rr1---sn-4g5e6nsz.googlevideo.com"))
        assertFalse(Blocklist.isBlocked("vimeo.com"))
    }

    @Test fun doesNotBlockOtherSites() {
        assertFalse(Blocklist.isBlocked("https://www.tiktok.com/@a/video/1"))
        assertFalse(Blocklist.isBlocked("https://vimeo.com/123"))
        assertFalse(Blocklist.isBlocked("https://www.google.com/search?q=youtube.com"))
        assertFalse(Blocklist.isBlocked("https://example.com/watch?u=https://youtube.com/x"))
        assertFalse(Blocklist.isBlocked("https://notyoutube.com/"))
        assertFalse(Blocklist.isBlocked("https://myyoutu.be/"))
        assertFalse(Blocklist.isBlocked("https://youtube.com.example.org/"))
        assertFalse(Blocklist.isBlocked("https://fakegooglevideo.com/"))
        assertFalse(Blocklist.isBlocked("https://googleapis.com/"))
        assertFalse(Blocklist.isBlocked("https://www.googleapis.com/drive/v3"))
    }

    @Test fun emptyAndNull() {
        assertFalse(Blocklist.isBlocked(null))
        assertFalse(Blocklist.isBlocked(""))
        assertFalse(Blocklist.isBlocked("   "))
    }

    @Test fun hostParsing() {
        assertEquals("m.youtube.com", Blocklist.hostOf("https://M.YOUTUBE.COM/watch?v=1#t=3"))
        assertEquals("youtu.be", Blocklist.hostOf("youtu.be/xyz"))
        assertEquals("example.com", Blocklist.hostOf("http://a:b@example.com:8080/p"))
    }
}
