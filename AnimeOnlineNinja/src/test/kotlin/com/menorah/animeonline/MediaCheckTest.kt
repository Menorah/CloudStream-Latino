package com.menorah.animeonline

import org.junit.Assert.*
import org.junit.Test

class MediaCheckTest {
    @Test fun streamtapeEmbedsAreNotDirectVideos() {
        assertTrue(MediaCheck.isKnownPlayerPage("https://streamtape.com/v/123/movie.mp4"))
        assertTrue(MediaCheck.isKnownPlayerPage("https://streamtape.com/e/123/movie.mp4"))
        assertFalse(MediaCheck.isKnownPlayerPage("https://streamtape.com/get_video?id=123"))
        assertFalse(MediaCheck.isKnownPlayerPage("https://cdn.example/v/123/movie.mp4"))
    }

    @Test fun rejectsHtmlAndKnownMissingFilesWithoutRejectingPlaylists() {
        fun broken(status: Int, mime: String?, data: String) = MediaCheck.looksBroken(status, mime, data.toByteArray())
        assertTrue(broken(404, "video/mp4", ""))
        assertTrue(broken(200, "text/html; charset=utf-8", "not video"))
        assertTrue(broken(200, "application/octet-stream", "<!DOCTYPE html>"))
        assertTrue(broken(200, "text/plain", "error_nofile"))
        assertFalse(broken(200, "text/plain", "#EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(broken(206, "video/mp4", "\u0000\u0000\u0000 ftypisom"))
        assertFalse(broken(200, "application/octet-stream", ""))
    }
}
