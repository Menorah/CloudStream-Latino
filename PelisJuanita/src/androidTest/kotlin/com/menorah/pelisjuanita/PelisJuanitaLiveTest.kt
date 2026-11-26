package com.menorah.pelisjuanita

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PelisJuanitaLiveTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val noRedirectHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun getText(url: String, referer: String = "${PelisJuanitaMarkup.MAIN_URL}/"): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
            .header("Accept", "application/json,text/html,*/*;q=0.8")
            .header("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
            .header("Referer", referer)
            .build()
        http.newCall(req).execute().use { res ->
            assertEquals("HTTP $url", 200, res.code)
            return res.body?.string().orEmpty()
        }
    }

    private fun checkImageReachable(url: String) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
            .build()
        http.newCall(req).execute().use { res ->
            assertEquals("Image HTTP $url", 200, res.code)
            val ct = res.header("Content-Type").orEmpty().lowercase()
            assertTrue("Unexpected content-type $ct for $url", ct.startsWith("image/"))
            val bytes = res.body?.bytes() ?: ByteArray(0)
            assertTrue("Empty image $url", bytes.size > 500)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Failed to decode bitmap for $url", bmp)
            assertTrue("Decoded image too small for $url: ${bmp!!.width}x${bmp.height}", bmp.width >= 50 && bmp.height >= 50)
            bmp.recycle()
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
    fun livePublicApiWebViewGateImagesAndVideoHosts() {
        val live = InstrumentationRegistry.getArguments().getString("live") == "true"
        assumeTrue("Pass -e live true to run live network tests", live)

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Live /tv/app/buscar.php catalog + series slice + search
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

        val seriesProbePages = listOf(
            parsedPage1.totalPages,
            (parsedPage1.totalPages * 5) / 6,
            (parsedPage1.totalPages * 3) / 4,
        ).distinct()
        val seriesSliceFound = seriesProbePages.any { probePage ->
            val rawSeriesSlice = getText(PelisJuanitaMarkup.buildSearchApiUrl("%", probePage))
            val parsedSeriesSlice = PelisJuanitaMarkup.parseSearchJson(rawSeriesSlice)
            parsedSeriesSlice != null && parsedSeriesSlice.items.any { it.kind == PelisJuanitaItemKind.Series }
        }
        assertTrue("Expected series records in slice pages $seriesProbePages", seriesSliceFound)

        val rawMatrix = getText(PelisJuanitaMarkup.buildSearchApiUrl("matrix", 1))
        val parsedMatrix = PelisJuanitaMarkup.parseSearchJson(rawMatrix)
        assertNotNull("Matrix search parsed", parsedMatrix)
        assertTrue("Expected Matrix search results", parsedMatrix!!.items.isNotEmpty())

        // 2. TMDB poster + backdrop image reachability & Bitmap decoding
        val firstMovieRec = parsedPage1.items.firstOrNull { it.kind == PelisJuanitaItemKind.Movie && !it.posterUrl.isNullOrBlank() }
            ?: parsedPage1.items.first()
        checkImageReachable(firstMovieRec.posterUrl!!)
        firstMovieRec.backdropUrl?.let { checkImageReachable(it) }

        // 3. Unauthenticated /movies/ redirects cleanly to /inicio.php gate
        val gateReq = Request.Builder()
            .url("${PelisJuanitaMarkup.MAIN_URL}/movies/")
            .header("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
            .build()
        noRedirectHttp.newCall(gateReq).execute().use { res ->
            val loc = res.header("Location").orEmpty()
            assertTrue("Expected 302 redirect to inicio.php, got ${res.code} ($loc)", res.code in 300..399 && loc.contains("inicio.php"))
        }

        // 4. Real Android WebView loading /inicio.php verification gate (never auto-solving CAPTCHA)
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

        // 5. Live host parser checks on Vimeus->Vimeos, Fastserver Movie & Series, Earnvids
        var workingHosts = 0

        val vimeusUrl = "https://vimeus.net/e/movie?tmdb=1131943&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1"
        val vimeusHtml = getText(vimeusUrl)
        val vimeusEmbeds = PelisJuanitaVimeusParser.parseEmbeds(vimeusHtml)
        assertTrue("Expected embeds inside Vimeus", vimeusEmbeds.isNotEmpty())
        val vimeosUrl = vimeusEmbeds.first().url
        val vimeosHtml = getText(vimeosUrl, vimeusUrl)
        val vimeosParsed = PelisJuanitaVidHideParser.parse(vimeosHtml, vimeosUrl)
        if (vimeosParsed.streams.isNotEmpty()) {
            workingHosts++
            assertTrue("Vimeos stream https", vimeosParsed.streams.first().url.startsWith("http"))
        }

        val fastMovieHtml = getText("https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden")
        val fastMovieStream = PelisJuanitaFastserverParser.parseStreamUrl(fastMovieHtml)
        if (fastMovieStream != null && fastMovieStream.startsWith("http")) {
            workingHosts++
        }

        val fastSerieHtml = getText("https://seriesplayer.fortamomar.workers.dev/?id=TnGPAAqrTEHHepUKWTq8B-MobLand/1")
        val fastSerieStream = PelisJuanitaFastserverParser.parseStreamUrl(fastSerieHtml)
        if (fastSerieStream != null && fastSerieStream.startsWith("http")) {
            workingHosts++
        }

        val morenciusUrl = "https://morencius.com/embed/036yf8jywyhr"
        val morenciusHtml = getText(morenciusUrl)
        val morenciusParsed = PelisJuanitaVidHideParser.parse(morenciusHtml, morenciusUrl)
        if (morenciusParsed.streams.isNotEmpty()) {
            workingHosts++
        }

        assertTrue("Expected >= 3 live video host parsers succeeding, got $workingHosts", workingHosts >= 3)
    }
}
