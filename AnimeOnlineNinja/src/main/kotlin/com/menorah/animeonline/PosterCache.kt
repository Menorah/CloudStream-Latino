package com.menorah.animeonline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.MessageDigest

internal fun interface PosterFetcher {
    suspend fun get(urls: List<String>): Map<String, ByteArray>
}

/** CloudStream's image loader uses HTTP separately from the provider's WebView. */
internal class PosterCache(private val directory: File, private val fetcher: PosterFetcher) {
    suspend fun resolve(urls: List<String?>): Map<String, String> {
        val candidates = urls.filterNotNull().filter(::isProtectedPoster).distinct().take(MAX_IMAGES)
        if (candidates.isEmpty()) return emptyMap()
        val cached = withContext(Dispatchers.IO) {
            candidates.mapNotNull { url ->
                val file = fileFor(url)
                if (file.isFile && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS)
                    url to fileUrl(file) else null
            }.toMap().toMutableMap()
        }
        val missing = candidates.filterNot { it in cached }
        if (missing.isNotEmpty()) {
            val downloaded = try { fetcher.get(missing) } catch (e: java.util.concurrent.CancellationException) { throw e } catch (_: Exception) { emptyMap() }
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                for ((url, bytes) in downloaded) {
                    if (url !in missing || bytes.size > MAX_IMAGE_BYTES || !isImage(bytes)) continue
                    val file = fileFor(url)
                    val temp = File.createTempFile("poster-", ".tmp", directory)
                    try {
                        temp.writeBytes(bytes)
                        if (temp.renameTo(file)) cached[url] = fileUrl(file)
                    } finally { temp.delete() }
                }
                prune()
            }
        }
        return cached
    }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.img")
    }

    private fun prune() {
        val files = directory.listFiles { f -> f.name.endsWith(".img") }?.sortedByDescending { it.lastModified() }.orEmpty()
        var size = 0L
        for (file in files) {
            size += file.length()
            if (size > MAX_CACHE_BYTES) file.delete()
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 512 * 1024
        private const val MAX_IMAGES = 64
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        fun create(context: Context): PosterCache = PosterCache(File(context.cacheDir, "animeonline_posters"), BrowserPosters(context.applicationContext))
        private fun fileUrl(file: File) = "file://${file.absolutePath}"
        internal fun isProtectedPoster(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return isSiteUrl(url) && uri.host.equals("ww4.animeonline.ninja", true) && uri.path.orEmpty().startsWith("/wp-content/uploads/")
        }
        internal fun isImage(bytes: ByteArray): Boolean =
            (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
            (bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) ||
            (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") ||
            (bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF")
    }
}
