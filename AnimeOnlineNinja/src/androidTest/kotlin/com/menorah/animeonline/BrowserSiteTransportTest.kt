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
        val urls = mutableMapOf<String, Set<String>>()
        for (path in listOf("/online/", "/online/page/2/", "/pelicula/", "/genero/audio-latino/", "/genero/anime-castellano/", "/genero/audio-latino/page/2/")) {
            val start = System.currentTimeMillis()
            val response = browser.get(root + path, emptyMap())
            assertEquals(200, response.code)
            val doc = org.jsoup.Jsoup.parse(response.text, response.url)
            val isSecond = path.contains("/page/2/")
            val section = path.substringBefore("/page/").trimEnd('/') + "/"
            assertTrue("Debe detectar la siguiente página: $path", SiteMarkup.hasNextPage(doc, if (isSecond) 2 else 1, section))
            urls[path] = SiteMarkup.cards(doc).map { it.url }.toSet()
            val groups = doc.select("article.item").groupingBy { e -> e.parents().take(3).joinToString(" > ") { it.tagName() + "#" + it.id() + "." + it.className() } }.eachCount()
            val pageLinks = doc.select("a[href*='/page/']").takeLast(10).map { a ->
                mapOf("href" to a.attr("href"), "class" to a.className(), "parent" to (a.parent()?.className() ?: ""), "text" to a.text())
            }
            android.util.Log.i("AOPaging", org.json.JSONObject(mapOf("path" to path, "ms" to (System.currentTimeMillis() - start), "cards" to SiteMarkup.cards(doc).size, "groups" to groups, "links" to pageLinks)).toString())
        }
        assertTrue("La página 2 debe aportar títulos nuevos", (urls.getValue("/genero/audio-latino/page/2/") - urls.getValue("/genero/audio-latino/")).isNotEmpty())
        assertTrue("La página 2 de animes debe aportar títulos nuevos", (urls.getValue("/online/page/2/") - urls.getValue("/online/")).isNotEmpty())
    }
    @Test fun liveGroupedCatalogueWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val browser = BrowserSiteTransport(context)
        val pager = CatalogPager { path ->
            val response = browser.get(root + path, emptyMap())
            check(response.isSuccessful)
            org.jsoup.Jsoup.parse(response.text, response.url)
        }
        val start = System.currentTimeMillis()
        val first = pager.get("/genero/audio-latino/", 1)
        val second = pager.get("/genero/audio-latino/", 2)
        assertTrue("Debe haber títulos en dos páginas", first.cards.size > 30)
        assertTrue("La siguiente expansión debe aportar títulos nuevos", (second.cards.map { it.url }.toSet() - first.cards.map { it.url }.toSet()).isNotEmpty())
        assertTrue(second.hasNext)
        val posters = first.cards.mapNotNull { it.poster }.filter { PosterCache.isProtectedPoster(it) }
        val posterStart = System.currentTimeMillis()
        val fetched = BrowserPosters(context).get(posters)
        assertTrue("Deben poder descargarse portadas del catálogo real", fetched.isNotEmpty())
        android.util.Log.i("AOPaging", org.json.JSONObject(mapOf("group1" to first.cards.size, "group2" to second.cards.size, "fetchMs" to (posterStart - start), "posters" to fetched.size, "posterMs" to (System.currentTimeMillis() - posterStart))).toString())
    }
    @Test fun liveHomeBatchWhenRequested() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return@runBlocking
        val browser = BrowserSiteTransport(context)
        val pager = CatalogPager { path ->
            val response = browser.get(root + path, emptyMap())
            check(response.isSuccessful)
            org.jsoup.Jsoup.parse(response.text, response.url)
        }
        val home = pager.get("/inicio/", 1)
        val next = pager.get("/inicio/", 2)
        assertTrue("Inicio debe contener títulos", home.cards.isNotEmpty())
        assertTrue("La siguiente página de Inicio debe aportar títulos nuevos", next.cards.isNotEmpty())
        assertTrue(next.hasNext)
        assertTrue(home.cards.map { it.url }.toSet().intersect(next.cards.map { it.url }.toSet()).isEmpty())
    }
}
