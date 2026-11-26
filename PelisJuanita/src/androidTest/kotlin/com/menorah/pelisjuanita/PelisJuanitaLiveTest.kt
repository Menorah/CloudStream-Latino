package com.menorah.pelisjuanita

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
import java.util.concurrent.atomic.AtomicBoolean
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

    private fun tryGetText(url: String, referer: String = "${PelisJuanitaMarkup.MAIN_URL}/"): String? =
        runCatching { getText(url, referer) }.getOrNull()

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

        // Simulate global CookieManager wipe (e.g., another plugin's CloudflareKiller) and verify restore.
        val restoreLatch = CountDownLatch(1)
        val restoredCookie = AtomicReference("")
        Handler(Looper.getMainLooper()).post {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies {
                PelisJuanitaSessionHelper.syncMissingCookiesToWebView(loaded.cookieHeader)
                restoredCookie.set(cm.getCookie(PelisJuanitaMarkup.MAIN_URL).orEmpty())
                restoreLatch.countDown()
            }
        }
        assertTrue("CookieManager restore timed out", restoreLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Expected PHPSESSID restored into CookieManager, got '${restoredCookie.get()}'", restoredCookie.get().contains("PHPSESSID=testsession123"))

        store.clear()
        assertFalse(store.read().hasPhpSession())

        val bareSeasonEps = PelisJuanitaMarkup.parseSeasonEpisodesHtml(
            """<a class="episodio-item" href="/series/ver-serie/mobland/01x01"><div class="episodio-texto"><h2 class="list-title">[01x01] Ep 1</h2></div></a>""",
            "mobland",
            defaultSeason = 1,
        )
        assertEquals(1, bareSeasonEps.size)
        assertEquals(32, PelisJuanitaVsembedCrypto.encryptHex("6168w", "vsembed.com").length)
        assertEquals("1f5101f5dd1f5111f6aa1f6e126a1767", PelisJuanitaVsembedCrypto.generateIvHex("vsembed.com"))
        val byseKp = PelisJuanitaByseCrypto.ecKeyPairP256()
        val byseSig = PelisJuanitaCrypto.decodeBase64Flexible(PelisJuanitaByseCrypto.signChallengeNonce("android-nonce", byseKp))
        assertEquals(64, byseSig.size)
        assertNotNull(PelisJuanitaBysePow.solveBlocking("5dd6af337978c416cc2cf9a6af73daad", 12))
        assertTrue(PelisJuanitaSessionHelper.looksLikeVerificationGate(302, "https://pelisjuanita.com/inicio.php", ""))
        assertTrue(PelisJuanitaSessionHelper.shouldTryWebTransport(403, "${PelisJuanitaMarkup.MAIN_URL}/movies/", "", null))
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

        val rawSkinCrawler = getText(PelisJuanitaMarkup.buildSearchApiUrl("skin crawler", 1))
        val parsedSkinCrawler = PelisJuanitaMarkup.parseSearchJson(rawSkinCrawler)
        val skinRec = parsedSkinCrawler?.items?.firstOrNull { it.slug == "the-skin-crawler-murders" }
        if (skinRec != null) {
            assertNotNull("Expected poster for the-skin-crawler-murders", skinRec.posterUrl)
            checkImageReachable(skinRec.backdropUrl ?: skinRec.posterUrl!!)
        }

        // 2. TMDB poster + backdrop image reachability & Bitmap decoding
        val firstMovieRec = parsedPage1.items.firstOrNull { it.kind == PelisJuanitaItemKind.Movie && !it.posterUrl.isNullOrBlank() }
            ?: parsedPage1.items.first()
        checkImageReachable(firstMovieRec.posterUrl!!)
        firstMovieRec.backdropUrl?.let { checkImageReachable(it) }

        // 3. Unauthenticated /movies/ must be blocked by the verification or Cloudflare gate (never raw catalog)
        val gateReq = Request.Builder()
            .url("${PelisJuanitaMarkup.MAIN_URL}/movies/")
            .header("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
            .build()
        noRedirectHttp.newCall(gateReq).execute().use { res ->
            val loc = res.header("Location").orEmpty()
            val body = res.body?.string().orEmpty()
            val blocked = res.code == 403 ||
                PelisJuanitaSessionHelper.looksLikeVerificationGate(
                    res.code,
                    loc.ifBlank { res.request.url.toString() },
                    body,
                ) ||
                PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(res.code, body)
            assertTrue("Expected /movies/ gate (302->inicio.php or 403 challenge), got ${res.code} ($loc)", blocked)
            assertFalse("Unauthenticated /movies/ unexpectedly exposed catalog cards", PelisJuanitaSessionHelper.looksLikeVerifiedCatalogOrDetail(res.request.url.toString(), body))
        }

        // 4. Real Android WebView loading /inicio.php verification gate (never auto-solving CAPTCHA)
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val webViewCheck = AtomicReference<String>("")
        Handler(Looper.getMainLooper()).post {
            val wv = WebView(context)
            wv.settings.javaScriptEnabled = true
            wv.settings.userAgentString = PelisJuanitaSessionHelper.DEFAULT_USER_AGENT
            fun inspectGate(view: WebView, attempt: Int) {
                if (done.get()) return
                view.evaluateJavascript(
                    "JSON.stringify({url:location.href,hasCaptcha:!!document.querySelector('input[name=\"empresa_hp\"]'),hasTerms:!!document.querySelector('input[name=\"terminos\"]'),hasCf:document.title.toLowerCase().indexOf('moment')>=0||!!document.querySelector('#challenge-form')||!!window._cf_chl_opt||document.body.innerText.indexOf('Cloudflare')>=0,title:document.title})",
                ) { res ->
                    val raw = res ?: ""
                    webViewCheck.set(raw)
                    val gotCaptcha = raw.contains("hasCaptcha\\\":true") || raw.contains("hasCaptcha\":true")
                    if (gotCaptcha || attempt >= 6) {
                        if (done.compareAndSet(false, true)) {
                            wv.destroy()
                            latch.countDown()
                        }
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed({ inspectGate(view, attempt + 1) }, 1500L)
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (url != null && url.contains("pelisjuanita.com", ignoreCase = true) && !done.get()) {
                        inspectGate(view, 0)
                    }
                }
            }
            wv.loadUrl("${PelisJuanitaMarkup.MAIN_URL}/inicio.php?redirect=%2Fmovies%2F")
        }
        assertTrue("WebView loaded inicio.php in time", latch.await(35, TimeUnit.SECONDS))
        val wvResult = webViewCheck.get()
        val hasGate = wvResult.contains("hasCaptcha\\\":true") ||
            wvResult.contains("hasCaptcha\":true") ||
            wvResult.contains("hasCf\\\":true") ||
            wvResult.contains("hasCf\":true")
        assertTrue("Expected WebView gate fields on inicio.php: $wvResult", hasGate)

        // 5. Live host parser checks on Vimeus->Vimeos, Fastserver Movie & Series, Earnvids
        var workingHosts = 0

        val vimeusUrl = "https://vimeus.net/e/movie?tmdb=1131943&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1"
        val vimeusHtml = tryGetText(vimeusUrl)
        if (!vimeusHtml.isNullOrBlank()) {
            val vimeusEmbeds = PelisJuanitaVimeusParser.parseEmbeds(vimeusHtml)
            assertTrue("Expected embeds inside Vimeus", vimeusEmbeds.isNotEmpty())
            val vimeosUrl = vimeusEmbeds.first().url
            val vimeosHtml = tryGetText(vimeosUrl, vimeusUrl)
            if (!vimeosHtml.isNullOrBlank()) {
                val vimeosParsed = PelisJuanitaVidHideParser.parse(vimeosHtml, vimeosUrl)
                if (vimeosParsed.streams.isNotEmpty()) {
                    workingHosts++
                    assertTrue("Vimeos stream https", vimeosParsed.streams.first().url.startsWith("http"))
                }
            }
        }

        val fastMovieHtml = tryGetText("https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden")
        val fastMovieStream = fastMovieHtml?.let(PelisJuanitaFastserverParser::parseStreamUrl)
        if (fastMovieStream != null && fastMovieStream.startsWith("http")) {
            workingHosts++
        }

        val fastSerieHtml = tryGetText("https://seriesplayer.fortamomar.workers.dev/?id=TnGPAAqrTEHHepUKWTq8B-MobLand/1")
        val fastSerieStream = fastSerieHtml?.let(PelisJuanitaFastserverParser::parseStreamUrl)
        if (fastSerieStream != null && fastSerieStream.startsWith("http")) {
            workingHosts++
        }

        val earnvidsUrl = "https://callistanise.com/v/lg0w6ezhvyd8"
        val earnvidsHtml = tryGetText(earnvidsUrl)
        val earnvidsParsed = earnvidsHtml?.let { PelisJuanitaVidHideParser.parse(it, earnvidsUrl) }
        if (earnvidsParsed != null && earnvidsParsed.streams.isNotEmpty()) {
            workingHosts++
        }

        // Verify vsembed AES-CBC decryption with live endpoint
        val vsembedEnc = PelisJuanitaVsembedCrypto.encryptHex("cjvk", "vsembed.com")
        val vsembedRes = runCatching {
            val req = Request.Builder()
                .url("https://vsembed.com/api/stream/tracking?c=$vsembedEnc&ld=1")
                .header("User-Agent", PelisJuanitaSessionHelper.DEFAULT_USER_AGENT)
                .header("Referer", "https://vsembed.com/cjvk")
                .build()
            http.newCall(req).execute().use { r -> r.header("x-tracking") }
        }.getOrNull()
        val vsembedDec = vsembedRes?.let { PelisJuanitaVsembedCrypto.decryptHex(it, "vsembed.com") }
        if (vsembedDec != null && vsembedDec.contains("master", ignoreCase = true)) {
            workingHosts++
        }

        // Verify MixDrop wurl unpacking across mirrors
        val mixHtml = tryGetText("https://mxdrop.click/e/q11gg30obxxogvn") ?: tryGetText("https://mixdrop.top/e/q11gg30obxxogvn")
        if (!mixHtml.isNullOrBlank()) {
            val mixSources = PelisJuanitaVidHideParser.unpackAll(mixHtml) + mixHtml
            if (mixSources.any { it.contains("wurl", ignoreCase = true) }) {
                workingHosts++
            }
        }

        assertTrue("Expected >= 4 live video host parsers succeeding, got $workingHosts", workingHosts >= 4)
    }
}
