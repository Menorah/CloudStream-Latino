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
}
