package com.menorah.animeonline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A negative check, not a guarantee of playback. A timeout is deliberately inconclusive. */
internal object MediaCheck {
    fun isKnownPlayerPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
        val path = uri.path.orEmpty().lowercase()
        // Streamtape player pages may end in .mp4 despite not being media files.
        return host in setOf("streamtape.com", "streamtape.net", "streamtape.xyz", "watchadsontape.com", "shavetape.cash") &&
            (path.startsWith("/v/") || path.startsWith("/e/"))
    }

    fun looksBroken(status: Int, mime: String?, bytes: ByteArray): Boolean {
        if (status == 404 || status == 410) return true
        if (status !in 200..299) return false
        val prefix = bytes.toString(Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        if (prefix.startsWith("#extm3u")) return false
        if (mime?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true) return true
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("error_nofile")
    }

    suspend fun definitelyBroken(url: String, referer: String, headers: Map<String, String> = emptyMap()): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", referer)
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            conn.setRequestProperty("Range", "bytes=0-1023")
            val status = conn.responseCode
            if (status == 404 || status == 410) return@withContext true
            if (status !in 200..299) return@withContext false
            val bytes = conn.inputStream.use { stream ->
                val buffer = ByteArray(1024)
                val count = stream.read(buffer)
                if (count > 0) buffer.copyOf(count) else ByteArray(0)
            }
            looksBroken(status, conn.contentType, bytes)
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
