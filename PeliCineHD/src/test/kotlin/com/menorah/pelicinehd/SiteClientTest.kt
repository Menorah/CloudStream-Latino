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

    @Test
    fun `refusal moves to the browser transport`() = runBlocking {
        var browserCalls = 0
        val client = SiteClient(
            SiteTransport { _, _ -> response(403, challenge) },
            SiteTransport { _, _ -> browserCalls++; response(200, page) }
        )
        assertEquals(page, client.getHtml(url).text)
        assertEquals(1, browserCalls)
    }

    @Test
    fun `the challenge page of the http client is replaced by the browser page`() = runBlocking {
        // A 200 that is actually the Cloudflare interstitial must not be parsed as a catalogue.
        val client = SiteClient(
            SiteTransport { _, _ -> response(200, challenge) },
            SiteTransport { _, _ -> response(200, page) }
        )
        val result = client.getHtml(url)
        assertEquals(page, result.text)
        assertFalse(PeliCineHDMarkup.isChallenge(result.text))
        assertEquals(1, PeliCineHDMarkup.cards(org.jsoup.Jsoup.parse(result.text, url)).size)
    }

    @Test
    fun `a normal page never opens a browser`() = runBlocking {
        var browserCalls = 0
        val browser = SiteTransport { _, _ -> browserCalls++; response(200, page) }
        assertEquals(200, SiteClient(SiteTransport { _, _ -> response(200, page) }, browser).getHtml(url).code)
        assertEquals(404, SiteClient(SiteTransport { _, _ -> response(404) }, browser).getHtml(url).code)
        assertEquals(0, browserCalls)
    }

    @Test
    fun `a working browser is remembered for the next page`() = runBlocking {
        var httpCalls = 0
        var browserCalls = 0
        val client = SiteClient(
            SiteTransport { _, _ -> httpCalls++; response(403, challenge) },
            SiteTransport { _, _ -> browserCalls++; response(200, page) }
        )
        client.getHtml(url)
        client.getHtml(url)
        assertEquals(1, httpCalls)
        assertEquals(2, browserCalls)
    }

    @Test
    fun `both transports refusing is reported, not swallowed`() = runBlocking {
        val client = SiteClient(
            SiteTransport { _, _ -> response(403, challenge) },
            SiteTransport { _, _ -> response(403, challenge) }
        )
        val result = client.getHtml(url)
        assertEquals(403, result.code)
        assertNotNull(result.text)
    }

    @Test
    fun `total failure raises a readable error`() = runBlocking {
        val client = SiteClient(
            SiteTransport { _, _ -> throw IllegalStateException("sin red") },
            SiteTransport { _, _ -> throw IllegalStateException("sin navegador") }
        )
        try {
            client.getHtml(url)
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PeliCineHD"))
        }
    }

    @Test
    fun `cancellation propagates instead of starting the browser`() = runBlocking {
        var browserCalls = 0
        val client = SiteClient(
            SiteTransport { _, _ -> throw CancellationException() },
            SiteTransport { _, _ -> browserCalls++; response(200, page) }
        )
        try {
            client.getHtml(url)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(0, browserCalls)
    }
}
