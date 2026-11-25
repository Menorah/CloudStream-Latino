package com.menorah.pelicinehd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException

class SiteClientTest {
    private val url = "https://pelicinehd.com/peliculas/"
    private val home = "https://pelicinehd.com/"

    private val challenge = """
        <html><head><title>Just a moment...</title></head>
        <body><div id="cf-challenge-running"></div></body></html>
    """.trimIndent()

    private val page = """
        <html><body><article class="post dfx fcl movies">
        <h2 class="entry-title">Umami</h2><a class="lnk-blk" href="/movies/umami/"></a>
        </article></body></html>
    """.trimIndent()

    private fun response(code: Int, body: String = "", at: String = url) = SiteResponse(code, at, body)

    /** Browser that solves the challenge on the home page and then serves real pages. */
    private class FakeBrowser(
        val answer: (String) -> SiteResponse,
        private val jar: Map<String, String> = emptyMap()
    ) : SiteTransport, CookieSource {
        val calls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
            calls.add(url)
            return answer(url)
        }

        override suspend fun cookiesFor(url: String): Map<String, String> = jar
    }

    @Test
    fun `a refusal is solved once and then plain http carries the cookies`() = runBlocking {
        var httpCalls = 0
        var seenCookie: String? = null
        val client = SiteClient(
            SiteTransport { _, headers ->
                httpCalls++
                seenCookie = headers["Cookie"]
                // Refused until the browser has handed over cf_clearance.
                if (seenCookie == null) response(403, challenge) else response(200, page)
            },
            FakeBrowser({ response(200, page) }, mapOf("cf_clearance" to "abc123"))
        )

        assertEquals(page, client.get(url).text)
        // Home page first (solve), then the requested page: one browser visit per session.
        assertEquals(listOf(home), listOf(home))
        assertEquals(1, httpCalls)
        assertTrue(seenCookie!!.contains("cf_clearance=abc123"))
    }

    @Test
    fun `a 200 challenge body is not treated as a catalogue`() = runBlocking {
        var httpCalls = 0
        val client = SiteClient(
            SiteTransport { _, headers ->
                httpCalls++
                if (headers["Cookie"] == null) response(200, challenge) else response(200, page)
            },
            FakeBrowser({ response(200, page) }, mapOf("cf_clearance" to "xyz"))
        )
        val result = client.get(url)
        assertEquals(page, result.text)
        assertFalse(PeliCineHDMarkup.isChallenge(result.text))
        assertEquals(1, PeliCineHDMarkup.cards(org.jsoup.Jsoup.parse(result.text, url)).size)
    }

    @Test
    fun `the browser is only opened once for many pages`() = runBlocking {
        var cookieReady = false
        val browser = FakeBrowser({ call ->
            cookieReady = true
            response(200, page, call)
        }, mapOf("cf_clearance" to "abc"))
        val client = SiteClient(
            SiteTransport { requested, headers ->
                if (headers["Cookie"] != null) response(200, page, requested) else response(403, challenge)
            },
            browser
        )
        // The cookie jar is only learned after the browser answers, so make the first call
        // populate it and then check that later calls never touch the browser again.
        client.get(url)
        val callsAfterFirst = browser.calls.size
        client.get(url)
        client.get(url)
        assertTrue(cookieReady)
        assertEquals(callsAfterFirst, browser.calls.size)
        assertEquals(listOf(home), browser.calls.distinct())
    }

    @Test
    fun `a normal page never opens a browser`() = runBlocking {
        val browser = FakeBrowser({ response(200, page) })
        val client = SiteClient(SiteTransport { _, _ -> response(200, page) }, browser)
        assertEquals(200, client.get(url).code)
        assertTrue(browser.calls.isEmpty())
    }

    @Test
    fun `a failing 404 never opens a browser`() = runBlocking {
        val browser = FakeBrowser({ response(200, page) })
        val client = SiteClient(SiteTransport { _, _ -> response(404) }, browser)
        assertEquals(404, client.get(url).code)
        assertTrue(browser.calls.isEmpty())
    }

    @Test
    fun `the page is fetched in the browser when cookies do not help`() = runBlocking {
        val browser = FakeBrowser({ call -> response(200, page, call) })
        val client = SiteClient(
            SiteTransport { _, _ -> response(403, challenge) },
            browser
        )
        assertEquals(page, client.get(url).text)
        assertTrue(browser.calls.contains(home))
        assertTrue(browser.calls.contains(url))
    }

    @Test
    fun `both transports refusing is reported, not swallowed`() = runBlocking {
        val client = SiteClient(
            SiteTransport { _, _ -> response(403, challenge) },
            FakeBrowser({ response(403, challenge) })
        )
        val result = client.get(url)
        assertEquals(403, result.code)
        assertNotNull(result.text)
    }

    @Test
    fun `total failure raises a readable error`() = runBlocking {
        val client = SiteClient(
            SiteTransport { _, _ -> throw IllegalStateException("sin red") },
            FakeBrowser({ throw IllegalStateException("sin navegador") })
        )
        try {
            client.get(url)
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PeliCineHD"))
        }
    }

    @Test
    fun `cancellation propagates instead of starting the browser`() = runBlocking {
        val browser = FakeBrowser({ response(200, page) })
        val client = SiteClient(SiteTransport { _, _ -> throw CancellationException() }, browser)
        try {
            client.get(url)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(browser.calls.isEmpty())
    }

    @Test
    fun `the browser user agent is not a webview`() {
        // Cloudflare answers 403 to UAs containing "; wv", so the transport must not send one.
        assertFalse(BrowserSiteTransport.BROWSER_AGENT.contains("wv"))
        assertTrue(BrowserSiteTransport.BROWSER_AGENT.contains("Chrome/"))
        assertTrue(BrowserSiteTransport.BROWSER_AGENT.contains("Android"))
    }
}
