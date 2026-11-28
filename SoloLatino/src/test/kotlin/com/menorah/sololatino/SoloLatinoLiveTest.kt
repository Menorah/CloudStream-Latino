package com.menorah.sololatino

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SoloLatinoLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Before
    fun checkEnabled() {
        val enabled = System.getProperty("sololatino.live") == "true" || System.getenv("SOLOLATINO_LIVE") == "true"
        assumeTrue("Set SOLOLATINO_LIVE=true to run live SoloLatino tests", enabled)
    }

    private fun fetchWithStatus(url: String, referer: String = "${SoloLatinoMarkup.MAIN_URL}/"): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", SoloLatinoSessionHelper.DEFAULT_USER_AGENT)
            .header("Referer", referer)
            .header("Accept-Language", "es-419,es;q=0.9,en;q=0.8")
            .build()
        client.newCall(req).execute().use { res ->
            return res.code to (res.body?.string().orEmpty())
        }
    }

    @Test
    fun `live embed69 mirrors decrypt and resolve playable host streams`() {
        val embedUrl = "https://embed69.org/f/tt45368240"
        val (code, html) = fetchWithStatus(embedUrl)
        assertTrue("Expected HTTP 200 on Embed69 ($code)", code == 200)
        val mirrors = SoloLatinoMarkup.decryptEmbed69Page(html)
        assertTrue("Expected decrypted Embed69 mirrors", mirrors.isNotEmpty())

        val morencius = mirrors.firstOrNull { it.url.contains("morencius", ignoreCase = true) || it.url.contains("vidhide", ignoreCase = true) }
        if (morencius != null) {
            val (mCode, mHtml) = fetchWithStatus(morencius.url, referer = embedUrl)
            assertTrue("Morencius returned HTTP $mCode", mCode == 200)
            val parsed = SoloLatinoVidHideParser.parse(mHtml, morencius.url)
            assertTrue("Morencius embed yields playable HLS stream", parsed.streams.isNotEmpty())
        }

        // Also check SoloLatino main site if runner IP is not Cloudflare-challenged
        val (slCode, slHtml) = fetchWithStatus("${SoloLatinoMarkup.MAIN_URL}/peliculas")
        if (slCode == 200 && !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(slCode, slHtml)) {
            val page = SoloLatinoMarkup.parseCatalogPage(Jsoup.parse(slHtml, "${SoloLatinoMarkup.MAIN_URL}/peliculas"))
            assertFalse("Catalog page should return cards when reachable", page.items.isEmpty())
        }
    }
}
