package com.menorah.pelicinehd

import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PeliCineHDWebViewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = "https://pelicinehd.com"

    private fun fake(code: Int, type: String, body: String) = WebResourceResponse(
        type, "UTF-8", code, if (code == 200) "OK" else "Forbidden",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray())
    )

    @Test
    fun cleanUserAgentStripsAndroidWebViewMarker() {
        val raw = WebSettings.getDefaultUserAgent(context)
        val clean = SiteClient.cleanBrowserUserAgent(raw)
        assertFalse("Clean UA must not contain '; wv': $clean", clean.contains("; wv"))
        assertTrue("Clean UA should keep Mobile Chrome identity: $clean", clean.contains("Chrome/"))
    }

    @Test
    fun browserTransportLoadsCatalogAndWaitsPastSpanishChallenge() = runBlocking {
        val hits = AtomicInteger(0)
        val browser = BrowserSiteTransport(context) { request ->
            if (request.url.path == "/movies/") {
                if (hits.incrementAndGet() == 1) {
                    fake(
                        200,
                        "text/html",
                        """<html><head><title>Un momento…</title></head><body><script>setTimeout(function(){document.title='Movies';document.body.innerHTML='<main><ul class="post-lst"><li><article class="post movies"><h2 class="entry-title">Pacífico</h2><a href="/movies/pacifico/" class="lnk-blk"></a></article></li></ul></main>';},200);</script></body></html>"""
                    )
                } else {
                    fake(
                        200,
                        "text/html",
                        """<html><head><title>Movies</title></head><body><main><ul class="post-lst"><li><article class="post movies"><h2 class="entry-title">Pacífico</h2><a href="/movies/pacifico/" class="lnk-blk"></a></article></li></ul></main></body></html>"""
                    )
                }
            } else {
                null
            }
        }

        val response = browser.get("$root/movies/", emptyMap())
        assertEquals(200, response.code)
        val cards = PeliCineHDMarkup.cards(Jsoup.parse(response.text, response.url))
        assertEquals(listOf("Pacífico"), cards.map { it.title })
    }

    @Test
    fun browserTransportKeepsNavigatedCatalogWhenCorsFetchIsRefused() = runBlocking {
        val hits = AtomicInteger(0)
        val browser = BrowserSiteTransport(
            context,
            SiteClient.cleanBrowserUserAgent(WebSettings.getDefaultUserAgent(context))
        ) { request ->
            if (request.url.path == "/movies/") {
                if (hits.incrementAndGet() == 1) {
                    fake(
                        200,
                        "text/html",
                        """<html><head><title>Movies</title></head><body><main><ul class="post-lst"><li><article class="post movies"><h2 class="entry-title">Pacífico</h2><a href="/movies/pacifico/" class="lnk-blk"></a></article></li></ul></main></body></html>"""
                    )
                } else {
                    fake(403, "text/html", "<html><body>Forbidden</body></html>")
                }
            } else {
                null
            }
        }

        val response = browser.get("$root/movies/", emptyMap())
        assertEquals(200, response.code)
        val cards = PeliCineHDMarkup.cards(Jsoup.parse(response.text, response.url))
        assertEquals(listOf("Pacífico"), cards.map { it.title })
    }

    @Test
    fun browserTransportExecutesSameOriginSeasonAjaxPost() = runBlocking {
        val browser = BrowserSiteTransport(
            context,
            SiteClient.cleanBrowserUserAgent(WebSettings.getDefaultUserAgent(context))
        ) { request ->
            when (request.url.path) {
                "/series/one-piece/" -> fake(200, "text/html", "<html><head><title>ONE PIECE</title></head><body>Series</body></html>")
                "/wp-admin/admin-ajax.php" -> fake(
                    200,
                    "text/html",
                    """<li><article class="post dfx fcl episodes"><span class="num-epi">2x1</span><h2 class="entry-title">Loguetown</h2><a href="/episode/one-piece-2x1/" class="lnk-blk"></a></article></li>"""
                )
                else -> null
            }
        }

        val response = browser.post(
            "$root/wp-admin/admin-ajax.php",
            mapOf("action" to "action_select_season", "season" to "2", "post" to "412"),
            mapOf("Referer" to "$root/series/one-piece/")
        )
        assertEquals(200, response.code)
        val episodes = PeliCineHDMarkup.episodes(Jsoup.parse(response.text, "$root/series/one-piece/"), 2)
        assertEquals(1, episodes.size)
        assertEquals(2, episodes[0].season)
        assertEquals(1, episodes[0].number)
        assertEquals("Loguetown", episodes[0].title)
    }

    @Test
    fun browserTransportPreservesHttp403Status() = runBlocking {
        val browser = BrowserSiteTransport(context) { request ->
            if (request.url.path == "/movies/") fake(403, "text/html", "<html><body>Forbidden</body></html>") else null
        }
        val response = browser.get("$root/movies/", emptyMap())
        assertEquals(403, response.code)
    }
}
