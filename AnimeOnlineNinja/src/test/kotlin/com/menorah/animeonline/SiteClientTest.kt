package com.menorah.animeonline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SiteClientTest {
    private val url = "https://ww4.animeonline.ninja/inicio/"
    private fun response(code: Int, body: String = "") = SiteResponse(code, url, body)

    @Test fun `403 retries in browser and remembers working transport`() = runBlocking {
        var http = 0; var browser = 0
        val client = SiteClient(
            SiteTransport { _, _ -> http++; response(403) },
            SiteTransport { _, _ -> browser++; response(200, "catalogue") }
        )
        assertEquals("catalogue", client.get(url).text)
        assertEquals("catalogue", client.get(url).text)
        assertEquals(1, http)
        assertEquals(2, browser)
    }

    @Test fun `normal response and ordinary 404 never open a browser`() = runBlocking {
        var calls = 0
        val browser = SiteTransport { _, _ -> calls++; response(200) }
        assertEquals(200, SiteClient(SiteTransport { _, _ -> response(200) }, browser).get(url).code)
        assertEquals(404, SiteClient(SiteTransport { _, _ -> response(404) }, browser).get(url).code)
        assertEquals(0, calls)
    }

    @Test fun `refusal in both transports remains an error`() = runBlocking {
        val client = SiteClient(SiteTransport { _, _ -> response(403) }, SiteTransport { _, _ -> response(403) })
        assertEquals(403, client.get(url).code)
    }

    @Test fun `failed preferred browser is not retried twice`() = runBlocking {
        var browserCalls = 0
        val client = SiteClient(
            SiteTransport { _, _ -> response(403) },
            SiteTransport { _, _ -> browserCalls++; response(if (browserCalls == 1) 200 else 403) }
        )
        client.get(url)
        assertEquals(403, client.get(url).code)
        assertEquals(2, browserCalls)
    }

    @Test fun `cancellation propagates instead of starting browser`() = runBlocking {
        var browserCalls = 0
        val client = SiteClient(SiteTransport { _, _ -> throw CancellationException() }, SiteTransport { _, _ -> browserCalls++; response(200) })
        try { client.get(url); fail("expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, browserCalls)
    }

    @Test fun `browser fallback only accepts intended https sites`() {
        assertTrue(isSiteUrl(url))
        assertTrue(isSiteUrl("https://ver2.animeonline.ninja/"))
        assertFalse(isSiteUrl("http://ww4.animeonline.ninja/"))
        assertFalse(isSiteUrl("https://ww4.animeonline.ninja.evil.example/"))
        assertFalse(isSiteUrl("https://evil.example@ww4.animeonline.ninja/"))
        assertFalse(isSiteUrl("https://ww4.animeonline.ninja:444/inicio/"))
    }
}
