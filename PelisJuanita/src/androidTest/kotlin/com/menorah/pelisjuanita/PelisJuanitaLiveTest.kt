package com.menorah.pelisjuanita

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PelisJuanitaLiveTest {

    private fun getText(url: String, referer: String = "${PelisJuanitaMarkup.MAIN_URL}/"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
        c.setRequestProperty("Accept", "application/json,text/html,*/*;q=0.8")
        c.setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
        c.setRequestProperty("Referer", referer)
        try {
            assertEquals("HTTP $url", 200, c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun checkImageReachable(url: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
        try {
            assertEquals("Image HTTP $url", 200, c.responseCode)
            val ct = c.contentType.orEmpty().lowercase()
            assertTrue("Unexpected content-type $ct for $url", ct.startsWith("image/"))
            val bytes = c.inputStream.use { it.readBytes() }
            assertTrue("Empty image $url", bytes.size > 500)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Failed to decode bitmap for $url", bmp)
            assertTrue("Decoded image too small for $url: ${bmp!!.width}x${bmp.height}", bmp.width >= 50 && bmp.height >= 50)
            bmp.recycle()
        } finally {
            c.disconnect()
        }
    }

    @Test
    fun offlineSessionMarkupAndCryptoSmoke() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidPelisJuanitaSessionStore(context)
        store.clear()
        assertFalse(store.read().hasPhpSession())

        store.save(
            PelisJuanitaSessionData(
                cookieHeader = "PHPSESSID=testsession123",
                userAgent = "PelisJuanitaTestUA/1.0",
                verifiedAtMs = 1_700_000_000_000L,
            ),
        )
        val loaded = store.read()
        assertTrue(loaded.hasPhpSession())
        assertEquals("PelisJuanitaTestUA/1.0", loaded.userAgent)
        store.clear()
        assertFalse(store.read().hasPhpSession())

        assertEquals(32, PelisJuanitaVsembedCrypto.encryptHex("6168w", "vsembed.com").length)
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(302, "https://pelisjuanita.com/inicio.php", ""))
    }

    @Test
    fun livePublicApiWebViewGateImagesAndVideoHosts() = runBlocking {
        val live = InstrumentationRegistry.getArguments().getString("live") == "true"
        assumeTrue("Pass -e live true to run live network tests", live)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = InMemoryPelisJuanitaSessionStore()
        val provider = PelisJuanitaProvider(context, store)

        // 1. Live /tv/app/buscar.php catalog + pagination
        val rawPage1 = getText(PelisJuanitaMarkup.buildSearchApiUrl("%", 1))
        val parsedPage1 = PelisJuanitaMarkup.parseSearchJson(rawPage1)
        assertNotNull("buscar.php page 1 parsed", parsedPage1)
        assertTrue("Expected >= 20 items on buscar.php page 1", parsedPage1!!.items.size >= 20)
        assertTrue("Expected > 100 total pages on buscar.php", parsedPage1.totalPages > 100)

        val rawPage2 = getText(PelisJuanitaMarkup.buildSearchApiUrl("%", 2))
        val parsedPage2 = PelisJuanitaMarkup.parseSearchJson(rawPage2)
        assertNotNull("buscar.php page 2 parsed", parsedPage2)
        assertTrue("Expected page 2 items", parsedPage2!!.items.isNotEmpty())
        assertTrue(
            "Expected page 2 first url to differ from page 1",
            parsedPage2.items.first().url != parsedPage1.items.first().url,
        )

        // 2. Live unverified provider catalog, search, movie fallback & clear verification gate prompt
        val movieSec = provider.mainPage.first()
        val home1 = provider.getMainPage(1, MainPageRequest(movieSec.name, movieSec.data, movieSec.horizontalImages))
        assertNotNull("Home page 1 non-null", home1)
        val homeItems1 = home1!!.items.flatMap { it.list }
        assertTrue("Home page 1 non-empty", homeItems1.isNotEmpty())

        val home2 = provider.getMainPage(2, MainPageRequest(movieSec.name, movieSec.data, movieSec.horizontalImages))
        val homeItems2 = home2!!.items.flatMap { it.list }
        assertTrue("Home page 2 non-empty", homeItems2.isNotEmpty())
        assertTrue("Home page 2 differs from page 1", homeItems2.first().url != homeItems1.first().url)

        val matrixSearch = provider.search("matrix", 1)
        assertNotNull("Matrix search non-null", matrixSearch)
        assertTrue("Expected Matrix search results", matrixSearch!!.items.isNotEmpty())

        val firstMovieRec = parsedPage1.items.firstOrNull { it.kind == PelisJuanitaItemKind.Movie && !it.posterUrl.isNullOrBlank() }
            ?: parsedPage1.items.first()
        val loadedMovie = provider.load(firstMovieRec.url) as? MovieLoadResponse
        assertNotNull("Unverified movie load fallback succeeded", loadedMovie)
        checkImageReachable(loadedMovie!!.posterUrl!!)
        firstMovieRec.backdropUrl?.let { checkImageReachable(it) }

        var gateThrown = false
        try {
            provider.loadLinks(firstMovieRec.url, false, {}, {})
        } catch (e: ErrorLoadingException) {
            val msg = e.message.orEmpty()
            gateThrown = msg.contains("Ajustes", ignoreCase = true) && msg.contains("PelisJuanita", ignoreCase = true)
        }
        assertTrue("Expected actionable verification ErrorLoadingException on unverified loadLinks", gateThrown)

        // 3. Real Android WebView loading /inicio.php verification gate (never auto-solving CAPTCHA)
        val latch = CountDownLatch(1)
        val webViewCheck = AtomicReference<String>("")
        Handler(Looper.getMainLooper()).post {
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null && url.contains("inicio.php", ignoreCase = true)) {
                        view.evaluateJavascript(
                            "JSON.stringify({url:location.href,hasCaptcha:!!document.querySelector('input[name=\"empresa_hp\"]'),hasTerms:!!document.querySelector('input[name=\"terminos\"]'),title:document.title})",
                        ) { res ->
                            webViewCheck.set(res ?: "")
                            wv.destroy()
                            latch.countDown()
                        }
                    }
                }
            }
            wv.loadUrl("${PelisJuanitaMarkup.MAIN_URL}/inicio.php?redirect=%2Fmovies%2F")
        }
        assertTrue("WebView loaded inicio.php in time", latch.await(25, TimeUnit.SECONDS))
        val wvResult = webViewCheck.get()
        assertTrue("Expected WebView gate fields on inicio.php: $wvResult", wvResult.contains("hasCaptcha") && wvResult.contains("true"))

        // 4. Live multi-host video extraction checks (Vimeus -> Vimeos, Fastserver, Earnvids, StreamWish, Voe)
        val hostSamples = listOf(
            "Vimeus" to "https://vimeus.net/e/movie?tmdb=1131943&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1",
            "Fastserver" to "https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden",
            "FastserverSerie" to "https://seriesplayer.fortamomar.workers.dev/?id=TnGPAAqrTEHHepUKWTq8B-MobLand/1",
            "Earnvids" to "https://morencius.com/embed/036yf8jywyhr",
            "StreamWish" to "https://hglink.to/e/u8jqwoe4cnpt",
            "Voe" to "https://voe.sx/e/ltvamjdzsvxv",
        )
        var workingHosts = 0
        for ((label, embedUrl) in hostSamples) {
            val links = mutableListOf<ExtractorLink>()
            val subs = mutableListOf<SubtitleFile>()
            try {
                provider.loadLinks(embedUrl, false, { subs.add(it) }, { links.add(it) })
            } catch (_: Exception) {
            }
            if (links.isNotEmpty()) {
                workingHosts++
                assertTrue("$label emitted valid https url", links.first().url.startsWith("http"))
            }
        }
        assertTrue("Expected >= 2 live hosts extracting playable streams, got $workingHosts", workingHosts >= 2)
    }
}
