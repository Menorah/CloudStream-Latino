package com.menorah.animeonline

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class BrowserPostersTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "https://ww4.animeonline.ninja/wp-content/uploads/test.png"
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=", Base64.DEFAULT)
    private fun response(type: String, data: ByteArray) = WebResourceResponse(type, null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))

    @Test fun imageIsFetchedInWebViewAndCanBeDecodedFromLocalCache() = runBlocking {
        val browser = BrowserPosters(context) { req ->
            when (req.url.path) {
                "/inicio/" -> response("text/html", "<html><body>Home</body></html>".toByteArray())
                "/wp-content/uploads/test.png" -> response("image/png", png)
                else -> null
            }
        }
        val cacheDir = java.io.File(context.cacheDir, "poster-instrumentation-${System.nanoTime()}")
        val cache = PosterCache(cacheDir, browser)
        try {
            val local = cache.resolve(listOf(url))[url]
            assertNotNull("WebView debe devolver la imagen", local)
            val file = java.io.File(java.net.URI(local))
            assertTrue(file.readBytes().contentEquals(png))
            assertNotNull("Android debe poder decodificar el archivo", BitmapFactory.decodeFile(file.absolutePath))
        } finally { cacheDir.deleteRecursively() }
    }

    @Test fun livePosterWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val page = BrowserSiteTransport(context).get("https://ww4.animeonline.ninja/online/", emptyMap())
        val poster = SiteMarkup.cards(org.jsoup.Jsoup.parse(page.text, page.url)).mapNotNull { it.poster }.first { PosterCache.isProtectedPoster(it) }
        val bytes = BrowserPosters(context).get(listOf(poster))[poster]
        assertNotNull("WebView debe poder descargar una portada real", bytes)
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size))
    }
}
