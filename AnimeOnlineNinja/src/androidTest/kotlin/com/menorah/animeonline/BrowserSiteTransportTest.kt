package com.menorah.animeonline

import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class BrowserSiteTransportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = "https://ww4.animeonline.ninja"
    private fun fake(code: Int, type: String, body: String) = WebResourceResponse(
        type, "UTF-8", code, if (code == 200) "OK" else "Forbidden",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray())
    )

    @Test fun pageReturnsRealWebViewDom() = runBlocking {
        val browser = BrowserSiteTransport(context) { request ->
            if (request.url.path == "/inicio/") fake(200, "text/html", "<html><head><title>Fixture</title></head><body><h1>Catalogo real</h1></body></html>") else null
        }
        val response = browser.get("$root/inicio/", emptyMap())
        assertEquals(200, response.code)
        assertEquals("$root/inicio/", response.url)
        assertTrue(response.text.contains("<h1>Catalogo real</h1>"))
    }

    @Test fun restApiIsFetchedInsideSameOriginWebView() = runBlocking {
        val browser = BrowserSiteTransport(context) { request ->
            when (request.url.path) {
                "/inicio/" -> fake(200, "text/html", "<html><head><title>Fixture</title></head><body>Home</body></html>")
                "/wp-json/dooplayer/v1/post/42" -> fake(200, "application/json", """{"embed_url":"https://player.example/id"}""")
                else -> null
            }
        }
        val response = browser.get("$root/wp-json/dooplayer/v1/post/42?type=tv&source=1", emptyMap())
        assertEquals(200, response.code)
        assertEquals("""{"embed_url":"https://player.example/id"}""", response.text)
        assertTrue(response.url.contains("post/42?type=tv"))
    }

    @Test fun browserRefusalIsNotReportedAsSuccess() = runBlocking {
        val browser = BrowserSiteTransport(context) { request ->
            if (request.url.path == "/inicio/") fake(403, "text/html", "<html><body>Forbidden</body></html>") else null
        }
        assertEquals(403, browser.get("$root/inicio/", emptyMap()).code)
    }

    @Test fun liveSiteWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val browser = BrowserSiteTransport(context)
        val page = browser.get("$root/online/", emptyMap())
        assertEquals("El catálogo real debe responder desde WebView", 200, page.code)
        assertTrue("El catálogo debe contener series", SiteMarkup.cards(org.jsoup.Jsoup.parse(page.text, page.url)).isNotEmpty())
        val api = browser.get("$root/wp-json/dooplayer/v1/post/82790?type=tv&source=1", mapOf("Referer" to "$root/episodio/dragon-ball-super-cap-1/"))
        assertEquals("La API real debe responder desde WebView", 200, api.code)
        assertTrue(org.json.JSONObject(api.text).optString("embed_url").startsWith("https://"))
    }
    @Test fun livePaginationWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val browser = BrowserSiteTransport(context)
        for (path in listOf("/online/", "/pelicula/", "/genero/audio-latino/", "/genero/anime-castellano/", "/genero/audio-latino/page/2/")) {
            val start = System.currentTimeMillis()
            val response = browser.get(root + path, emptyMap())
            assertEquals(200, response.code)
            val doc = org.jsoup.Jsoup.parse(response.text, response.url)
            val pageLinks = doc.select("a[href*='/page/']").takeLast(10).map { a ->
                mapOf("href" to a.attr("href"), "class" to a.className(), "parent" to (a.parent()?.className() ?: ""), "text" to a.text())
            }
            android.util.Log.i("AOPaging", org.json.JSONObject(mapOf("path" to path, "ms" to (System.currentTimeMillis() - start), "cards" to SiteMarkup.cards(doc).size, "links" to pageLinks)).toString())
        }
    }
}
