package com.menorah.pelicinehd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            FakeBrowser({ response(200, page) }, mapOf("cf_clearance" to "abc123")),
            cookieSource = FakeBrowser({ response(200, page) }, mapOf("cf_clearance" to "abc123"))
        )

        assertEquals(page, client.get(url).text)
        // First call is refused, so it is retried once with the cookie: two HTTP calls, no more.
        assertEquals(2, httpCalls)
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
            FakeBrowser({ response(200, page) }, mapOf("cf_clearance" to "xyz")),
            cookieSource = FakeBrowser({ response(200, page) }, mapOf("cf_clearance" to "xyz"))
        )
        val result = client.get(url)
        assertEquals(page, result.text)
        assertFalse(PeliCineHDMarkup.isChallenge(result.text))
        assertEquals(1, PeliCineHDMarkup.cards(org.jsoup.Jsoup.parse(result.text, url)).size)
    }

    @Test
    fun `once solved, every page uses plain http and never the browser again`() = runBlocking {
        // This is the bug behind the 180 s timeout: a working browser used to become the
        // transport for every later page, and sixteen home sections loaded in a WebView.
        var cookiesLearned = false
        var httpCalls = 0
        val browser = FakeBrowser({ _ ->
            cookiesLearned = true
            response(200, page, home)
        }, mapOf("cf_clearance" to "abc"))
        val client = SiteClient(
            SiteTransport { requested, headers ->
                httpCalls++
                if (headers["Cookie"] != null || cookiesLearned) response(200, page, requested)
                else response(403, challenge)
            },
            browser,
            cookieSource = browser
        )

        // First page: refused, so the browser opens the site once and hands over its cookies.
        assertEquals(page, client.get(url).text)
        val browserCalls = browser.calls.size
        val httpCallsAfterFirst = httpCalls

        repeat(14) { index ->
            assertEquals(page, client.get("https://pelicinehd.com/peliculas/page/${index + 2}/").text)
        }
        assertEquals("browser opened only once", browserCalls, browser.calls.size)
        assertEquals(httpCallsAfterFirst + 14, httpCalls)
        assertTrue(browser.calls.all { it == home })
    }

    @Test
    fun `a normal page never opens a browser`() = runBlocking {
        val browser = FakeBrowser({ response(200, page) })
        val client = SiteClient(SiteTransport { _, _ -> response(200, page) }, browser, cookieSource = browser)
        assertEquals(200, client.get(url).code)
        assertTrue(browser.calls.isEmpty())
    }

    @Test
    fun `a failing 404 never opens a browser`() = runBlocking {
        val browser = FakeBrowser({ response(200, page) })
        val client = SiteClient(SiteTransport { _, _ -> response(404) }, browser, cookieSource = browser)
        assertEquals(404, client.get(url).code)
        assertTrue(browser.calls.isEmpty())
    }

    @Test
    fun `the page is fetched in the browser when cookies do not help`() = runBlocking {
        val browser = FakeBrowser({ call -> response(200, page, call) })
        val client = SiteClient(
            SiteTransport { _, _ -> response(403, challenge) },
            browser,
            cookieSource = browser
        )
        assertEquals(page, client.get(url).text)
        assertTrue(browser.calls.contains(home))
        assertTrue(browser.calls.contains(url))
    }

    @Test
    fun `both transports refusing is reported, not swallowed`() = runBlocking {
        val client = SiteClient(
            SiteTransport { _, _ -> response(403, challenge) },
            FakeBrowser({ response(403, challenge) }),
            cookieSource = FakeBrowser({ response(403, challenge) })
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
    fun `a cancelled request does not cancel the shared solve`() = runBlocking {
        // This is the v7 regression: the sixteen home sections have a per-section timeout, and a
        // solve owned by the section died with it, so no section ever got a session.
        val gate = CompletableDeferred<Unit>()
        var browserCalls = 0
        val browser = object : SiteTransport, CookieSource {
            override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                browserCalls++
                gate.await()
                return response(200, page, home)
            }

            override suspend fun cookiesFor(url: String): Map<String, String> =
                mapOf("cf_clearance" to "abc")
        }
        val client = SiteClient(
            SiteTransport { requested, headers ->
                if (headers["Cookie"] != null) response(200, page, requested) else response(403, challenge)
            },
            browser,
            cookieSource = browser
        )

        // Section 1 asks, hits the challenge, starts the solve and gives up (its own timeout).
        assertNull(withTimeoutOrNull(50L) { client.get(url) })
        assertEquals(1, browserCalls)

        // Section 2 arrives later: it must not start a second browser visit, and once the shared
        // solve finishes it must get the page over plain HTTP.
        gate.complete(Unit)
        val result = withTimeoutOrNull(5_000L) { client.get(url) }
        assertNotNull(result)
        assertEquals(page, result!!.text)
        assertEquals("browser opened once", 1, browserCalls)
    }

    @Test
    fun `a warm up resolves the session once for every later page`() = runBlocking {
        var browserCalls = 0
        val browser = FakeBrowser({ call ->
            browserCalls++
            response(200, page, call)
        }, mapOf("cf_clearance" to "abc"))
        val client = SiteClient(
            SiteTransport { requested, headers ->
                if (headers["Cookie"] != null) response(200, page, requested) else response(403, challenge)
            },
            browser,
            cookieSource = browser
        )
        assertTrue(client.warmUp(5_000L))
        repeat(3) { client.get(url) }
        assertEquals(1, browserCalls)
    }

    @Test
    fun `the browser user agent is not a webview`() {
        // Cloudflare answers 403 to UAs containing "; wv", so the transport must not send one.
        assertFalse(BrowserSiteTransport.BROWSER_AGENT.contains("wv"))
        assertTrue(BrowserSiteTransport.BROWSER_AGENT.contains("Chrome/"))
        assertTrue(BrowserSiteTransport.BROWSER_AGENT.contains("Android"))
    }
}
