package com.menorah.animeonline

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        assertFalse(broken(403, "text/html", "<html>Challenge</html>"))
        assertFalse(broken(503, "text/html", "<html>Temporary error</html>"))
        assertTrue(broken(200, "text/html; charset=utf-8", "not video"))
        assertTrue(broken(200, "application/octet-stream", "<!DOCTYPE html>"))
        assertTrue(broken(200, "text/plain", "error_nofile"))
        assertFalse(broken(200, "text/plain", "#EXTM3U\n#EXT-X-VERSION:3"))
        assertFalse(broken(206, "video/mp4", "\u0000\u0000\u0000 ftypisom"))
        assertFalse(broken(200, "application/octet-stream", ""))
    }
    @Test fun boundedProbeSendsRangeAndReferer() = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val request = AtomicReference<String>()
            val worker = thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                    request.set(lines.joinToString("\n"))
                    val body = "<html>not a video</html>"
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                }
            }
            try {
                assertTrue(MediaCheck.definitelyBroken("http://127.0.0.1:${server.localPort}/file.mp4", "https://player.example/", mapOf("X-Example" to "value")))
                worker.join(5000)
                val headers = request.get().lowercase()
                assertTrue(headers.contains("range: bytes=0-1023"))
                assertTrue(headers.contains("referer: https://player.example/"))
                assertTrue(headers.contains("x-example: value"))
            } finally { worker.join(5000) }
        }
    }
}
