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
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC", Base64.DEFAULT)
    private fun response(type: String, data: ByteArray) = WebResourceResponse(type, null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))

    @Test fun imageIsFetchedInWebViewAndCanBeDecodedFromLocalCache() = runBlocking {
        var homeRequests = 0
        val browser = BrowserPosters(context) { req ->
            when (req.url.path) {
                "/inicio/" -> { homeRequests++; response("text/html", "<html><body>Home</body></html>".toByteArray()) }
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
            assertEquals("No debe descargar la portada del sitio para obtener imágenes", 0, homeRequests)
        } finally { cacheDir.deleteRecursively() }
    }

    @Test fun fallsBackToSiteNavigationWhenSyntheticPageCannotFetch() = runBlocking {
        var imageRequests = 0
        var homeRequests = 0
        val browser = BrowserPosters(context) { req ->
            when (req.url.path) {
                "/inicio/" -> { homeRequests++; response("text/html", "<html><body>Home</body></html>".toByteArray()) }
                "/wp-content/uploads/test.png" -> {
                    imageRequests++
                    if (imageRequests == 1) WebResourceResponse("text/html", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    else response("image/png", png)
                }
                else -> null
            }
        }
        assertTrue(browser.get(listOf(url))[url]!!.contentEquals(png))
        assertEquals(1, homeRequests)
    }

    @Test fun livePosterWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val page = BrowserSiteTransport(context).get("https://ww4.animeonline.ninja/online/", emptyMap())
        val poster = SiteMarkup.cards(org.jsoup.Jsoup.parse(page.text, page.url)).mapNotNull { it.poster }.first { PosterCache.isProtectedPoster(it) }
        val bytes = BrowserPosters(context).get(listOf(poster))[poster]
        assertNotNull("WebView debe poder descargar una portada real", bytes)
        assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size))
    }
    @Test fun liveDetailArtworkWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val browser = BrowserSiteTransport(context)
        for ((path, expectedTitle) in listOf(
            "/online/black-clover-091124/" to "Black Clover",
            "/pelicula/akira-110723/" to "Akira",
        )) {
            val response = browser.get("https://ww4.animeonline.ninja$path", emptyMap())
            assertEquals(200, response.code)
            val doc = org.jsoup.Jsoup.parse(response.text, response.url)
            assertEquals(expectedTitle, SiteMarkup.detailTitle(doc))
            val background = SiteMarkup.backdrop(doc)
            assertNotNull("La ficha debe tener fondo: $path", background)
            val size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val conn = java.net.URL(background!!).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                try {
                    check(conn.responseCode == 200) { "Fondo: HTTP ${conn.responseCode}" }
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                    options.outWidth to options.outHeight
                } finally { conn.disconnect() }
            }
            assertTrue("El fondo debe ser apaisado y mayor que la miniatura: $size", size.first >= 780 && size.first > size.second)
            val thumb = SiteMarkup.image(doc.selectFirst(".sheader .poster img"))
            val original = SiteMarkup.originalPoster(thumb)
            assertNotNull("La ficha usa miniatura redimensionada", original)
            val imageBytes = BrowserPosters(context).get(listOf(original!!, thumb!!))
            val originalBytes = imageBytes[original]
            assertNotNull("Debe poder descargarse la portada original", originalBytes)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes!!.size, bounds)
            assertTrue("La portada original debe ser más grande que 185 píxeles", bounds.outWidth > 185)
            android.util.Log.i("AOPaging", org.json.JSONObject(mapOf("artwork" to path, "backgroundWidth" to size.first, "backgroundHeight" to size.second, "posterWidth" to bounds.outWidth)).toString())
        }
    }
}
