package com.menorah.animeonline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PosterCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00)
    private val url = "https://ww4.animeonline.ninja/wp-content/uploads/cover.jpg"

    @Test fun `browser image is cached in an app local file and reused`() = runBlocking {
        var calls = 0
        val cache = PosterCache(tmp.root, PosterFetcher { urls -> calls++; assertEquals(listOf(url), urls); mapOf(url to image) })
        val first = cache.resolve(listOf(url, url, "https://image.tmdb.org/pic.jpg"))
        assertTrue(first.getValue(url).startsWith("file:///"))
        assertTrue(java.io.File(java.net.URI(first.getValue(url))).readBytes().contentEquals(image))
        assertEquals(first, cache.resolve(listOf(url)))
        assertEquals(1, calls)
    }

    @Test fun `invalid images and unrelated origins are not cached`() = runBlocking {
        val cache = PosterCache(tmp.root, PosterFetcher { mapOf(url to "<html>403</html>".toByteArray()) })
        assertTrue(cache.resolve(listOf(url)).isEmpty())
        assertFalse(PosterCache.isProtectedPoster("https://ww4.animeonline.ninja.evil.test/wp-content/uploads/a.jpg"))
        assertFalse(PosterCache.isProtectedPoster("https://ww4.animeonline.ninja/episodio/a/"))
        assertTrue(PosterCache.isProtectedPoster(url))
    }
}
