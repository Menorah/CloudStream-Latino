package com.menorah.sololatino

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        Log.i("CSPlaybackProbe", "STAGE plugin_loaded sdk=${android.os.Build.VERSION.SDK_INT}")
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(3500)
                Log.i("CSPlaybackProbe", "STAGE starting_v4_probe")
                withTimeout(500_000) {
                    val provider = SoloLatinoProvider(context)
                    val sysUa = provider.client.currentUserAgent()
                    Log.i("CSPlaybackProbe", "STAGE ua=${sysUa.take(80)}")

                    // 1. Live direct host checks (VidHide/Morencius + VOE/caseyseemavoid Altcha v2 PBKDF2 cost=10000)
                    val hostTargets = listOf(
                        Triple("https://morencius.com/embed/gd8e0adu6pbg", "vidhide", "Latino"),
                        Triple("https://voe.sx/e/84emedzn9bzh", "voe", "Subtitulado"),
                        Triple("https://morencius.com/embed/1ywkwa2abeeu", "vidhide", "Latino"),
                        Triple("https://voe.sx/e/basmjvog9k1o", "voe", "Latino"),
                    )
                    val directHostLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            hostTargets.map { (u, hint, lang) ->
                                async {
                                    SoloLatinoExtractorDispatcher.extract(
                                        url = u,
                                        referer = "https://embed69.org/",
                                        sourceLabel = "SoloLatino · $lang",
                                        subtitleCallback = {},
                                        serverHint = hint,
                                        client = provider.client,
                                    ) { directHostLinks.add(it) }
                                }
                            }.awaitAll()
                        }
                    }
                    Log.i("CSPlaybackProbe", "HOST_DIRECT count=${directHostLinks.size} names=${directHostLinks.map { it.name }} urls=${directHostLinks.take(4).map { it.url.take(60) }}")
                    check(directHostLinks.isNotEmpty()) { "HOST_DIRECT returned no links" }
                    val voeLink = directHostLinks.firstOrNull { it.name.contains("Voe", ignoreCase = true) || it.url.contains("voe", ignoreCase = true) }
                    val vidhideLink = directHostLinks.firstOrNull { !it.name.contains("Voe", ignoreCase = true) }
                    if (vidhideLink != null) playFirstWorking(context, "host-vidhide", listOf(vidhideLink))
                    if (voeLink != null) {
                        playFirstWorking(context, "host-voe-altcha", listOf(voeLink))
                    } else {
                        withContext(Dispatchers.IO) {
                            val hdr = mapOf("User-Agent" to SoloLatinoHostHttp.MOBILE_UA)
                            val ck = linkedMapOf<String, String>()
                            val r1 = runCatching { com.lagradost.cloudstream3.app.get("https://caseyseemavoid.com/e/84emedzn9bzh", referer = "https://embed69.org/", headers = hdr, cookies = ck, timeout = 8L) }
                            val r1Res = r1.getOrNull()
                            r1Res?.let { ck.putAll(it.cookies) }
                            val doc = r1Res?.text?.let { Jsoup.parse(it, r1Res.url) }
                            val chAttr = doc?.selectFirst("altcha-widget")?.attr("challenge").orEmpty()
                            val tok = doc?.selectFirst("input[name=_token]")?.attr("value").orEmpty()
                            val act = doc?.selectFirst("form.access-form, form[action]")?.attr("abs:action").orEmpty()
                            val ch = if (chAttr.isNotBlank()) runCatching { com.lagradost.cloudstream3.app.get(chAttr, referer = r1Res!!.url, headers = hdr, cookies = ck, timeout = 8L) } else null
                            val chRes = ch?.getOrNull()
                            chRes?.let { ck.putAll(it.cookies) }
                            val t0 = System.currentTimeMillis()
                            val sol = chRes?.text?.let { SoloLatinoVoeCrypto.solveAltchaSuspend(it) }
                            val solveMs = System.currentTimeMillis() - t0
                            val p = if (sol != null) runCatching {
                                com.lagradost.cloudstream3.app.post(
                                    act,
                                    referer = r1Res!!.url,
                                    headers = hdr + mapOf("Origin" to "https://caseyseemavoid.com", "Content-Type" to "application/x-www-form-urlencoded"),
                                    cookies = ck,
                                    data = mapOf("_token" to tok, "access" to "0", "altcha" to sol),
                                    timeout = 10L,
                                )
                            } else null
                            val pRes = p?.getOrNull()
                            val pErr = p?.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"
                            val chErr = ch?.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"
                            val r1Err = r1.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"
                            Log.i(
                                "CSPlaybackProbe",
                                "VOE_DIAG r1=${r1Res?.code}($r1Err) ch=${chRes?.code}($chErr):${chRes?.text?.take(120)} sol=${sol != null}(${solveMs}ms) post=${pRes?.code}($pErr):hasJson=${pRes?.text?.contains("application/json")}",
                            )
                        }
                        error("VOE failed - inspect VOE_DIAG")
                    }

                    // 2. Verify Coil 3 image loading across TMDB w500 / w1280 / w400 URLs
                    verifyCoilImage(context, "tmdb-poster-w500", "https://image.tmdb.org/t/p/w500/zDE9hd1SG9695YncbZGjSf7Z9Jk.jpg", null)
                    verifyCoilImage(context, "tmdb-backdrop-w1280", "https://image.tmdb.org/t/p/w1280/zDE9hd1SG9695YncbZGjSf7Z9Jk.jpg", null)
                    verifyCoilImage(context, "tmdb-still-w400", "https://image.tmdb.org/t/p/w400/a7Ofz6lgowoSVajts2FhhQOpNJK.jpg", null)
                    Log.i("CSPlaybackProbe", "PASS coil posters=3")

                    // 3. Test v4 WebView pipeline on real Android 13 System WebView:
                    //    - Sanctum XSRF-TOKEN exclusive (reject 419 if X-CSRF-TOKEN also sent!)
                    //    - PSH CF challenge -> window.stop() -> window._isLocked()+window.fetch hook -> no_click -> busy secondary server
                    //    - Cancellation mid-flight leaves Semaphore(1) clean & zero renderer crash!
                    val liveSampleUrl = (vidhideLink ?: voeLink ?: directHostLinks.first()).url
                    runV4WebViewAndCancelTests(context, liveSampleUrl)
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun runV4WebViewAndCancelTests(context: Context, sampleMediaUrl: String) {
        val localClient = SoloLatinoClient(context)
        val epUrl = "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8"
        val cancelUrl = "https://sololatino.net/pelicula/cancel-test"
        val pshEmbedUrl = "https://player.pelisserieshoy.com/f/tt1190634-5x08"

        val siteEpisodeHtml = """
            <!DOCTYPE html><html><head>
            <meta name="csrf-token" content="stale_meta_csrf_must_not_be_sent">
            </head><body>
            <header id="main-header" style="height:60px">SoloLatino</header>
            <h1>The Boys 5x08</h1>
            <div data-lang-tab="lat">Latino</div>
            <div data-lang-group="lat">
              <button data-player-token="tok_psh_btn" data-lang="lat">Principal</button>
              <button data-player-token="tok_e69_btn" data-lang="lat">Embed69</button>
            </div>
            <div id="player-frame" data-lazy-embed="tok_lazy_btn"></div>
            </body></html>
        """.trimIndent()

        // Simulate a PSH embed that:
        // 1) runs window.stop() + location.replace('google.com') if top-level
        // 2) installs a hostile window._isLocked() + window.fetch wrapper that rejects fetch() and overwrites tk=''
        //    so our XHR-based v4 WebView resolver is verified directly!
        val pshToken = "tok_v4_test"
        val pshQc = "120"
        val pshQk = listOf("cc", "aa", "dd", "bb")
        val expectedTk = SoloLatinoMarkup.fnv1a32Hex("aabbccdd|clk|$pshQc")
        val pshHtml = """
            <!DOCTYPE html><html><head>
            <script>
              (function(){
                var isTop = false;
                try { isTop = (window.self === window.top); } catch(e) {}
                if (isTop) {
                  window.__embedBlocked = true;
                  try { window.stop && window.stop(); } catch(e) {}
                  window.location.replace('https://www.google.com');
                }
              })();
            </script>
            </head><body>
            <script>
              window._isLocked = function() { return true; };
              (function(){
                var _of = window.fetch;
                window.fetch = function() { return Promise.reject(new Error('Locked')); };
              })();
              const _t = '$pshToken';
              var _qc = '$pshQc';
              var _qk = ['${pshQk[0]}','${pshQk[1]}','${pshQk[2]}','${pshQk[3]}'];
              var _ao = '';
              var _qsrc = 'sl';
            </script>
            </body></html>
        """.trimIndent()

        val cfChallengeHtml = """
            <!DOCTYPE html><html><head><title>Just a moment...</title></head>
            <body><script>window._cf_chl_opt={};</script><div>Enable JavaScript and cookies to continue</div></body></html>
        """.trimIndent()

        // Inject fresh XSRF-TOKEN into CookieManager as /sanctum/csrf-cookie would
        CookieManager.getInstance().setCookie("https://sololatino.net", "XSRF-TOKEN=rotated_xsrf_v4; Path=/")
        CookieManager.getInstance().flush()

        val pshLoadCounter = AtomicInteger(0)
        val sPhpStep2Counter = AtomicInteger(0)
        var sawClick = false

        fun expectedShield(tok: String): String {
            val liveSlot = Math.floor(System.currentTimeMillis() / 60_000.0).toLong()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest("$tok:$liveSlot:${SoloLatinoMarkup.RENDER_SHIELD_SECRET}".toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun resp(mime: String, body: String, status: Int = 200, extraHeaders: Map<String, String> = emptyMap()): WebResourceResponse =
            WebResourceResponse(
                mime,
                "utf-8",
                status,
                if (status == 200) "OK" else "Error",
                mapOf("Access-Control-Allow-Origin" to "*") + extraHeaders,
                ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
            )

        localClient.testWebResponse = { req: WebResourceRequest ->
            val urlStr = req.url.toString()
            val path = req.url.path.orEmpty()
            val method = req.method.orEmpty().uppercase()
            val hdrs = req.requestHeaders.orEmpty()
            when {
                urlStr.startsWith(cancelUrl) -> resp("text/html", cfChallengeHtml)
                urlStr.startsWith(epUrl) -> resp("text/html", siteEpisodeHtml)
                path == "/sanctum/csrf-cookie" -> resp("text/plain", "", 204)
                path == "/api/player-url" || path == "/api/player-embed" -> {
                    val hasStaleCsrf = hdrs.keys.any { it.equals("X-CSRF-TOKEN", ignoreCase = true) }
                    val xsrf = hdrs.entries.firstOrNull { it.key.equals("X-XSRF-TOKEN", ignoreCase = true) }?.value.orEmpty()
                    if (hasStaleCsrf || xsrf != "rotated_xsrf_v4") {
                        resp("application/json", """{"message":"CSRF token mismatch"}""", 419)
                    } else {
                        val shield = hdrs.entries.firstOrNull { it.key.equals("X-SL-Render", true) }?.value.orEmpty()
                        val tok = when {
                            path == "/api/player-embed" -> "tok_lazy_btn"
                            shield == expectedShield("tok_psh_btn") -> "tok_psh_btn"
                            shield == expectedShield("tok_e69_btn") -> "tok_e69_btn"
                            else -> ""
                        }
                        when (tok) {
                            "tok_psh_btn", "tok_lazy_btn" -> resp("application/json", """{"url":"$pshEmbedUrl","type":"iframe"}""")
                            "tok_e69_btn" -> resp("application/json", """{"url":"https://embed69.org/f/tt1190634-5x08","type":"iframe"}""")
                            else -> resp("application/json", """{"error":"bad_shield"}""", 403)
                        }
                    }
                }
                urlStr.startsWith(pshEmbedUrl) -> {
                    // First navigation simulates Cloudflare JS challenge, second serves real PSH HTML
                    if (pshLoadCounter.incrementAndGet() == 1) {
                        CookieManager.getInstance().setCookie("https://player.pelisserieshoy.com", "cf_clearance=v4_cf_ok; Path=/")
                        resp(
                            "text/html",
                            """<html><head><title>Just a moment...</title><script>window._cf_chl_opt={};setTimeout(function(){location.reload();},350);</script></head><body>Checking browser...</body></html>""",
                        )
                    } else {
                        resp("text/html", pshHtml)
                    }
                }
                path == "/s.php" && method == "POST" -> {
                    val step = sPhpStep2Counter.incrementAndGet()
                    when {
                        !sawClick -> {
                            sawClick = true
                            resp("application/json", """{"ok":true}""")
                        }
                        step == 2 -> {
                            resp("application/json", """{"langs_s":{"LAT":[["Principal","srv_ok"],["Lento","srv_busy"]]}}""")
                        }
                        step == 3 -> {
                            // First server returns real playable HLS
                            resp("application/json", """{"u":"$sampleMediaUrl","sig":"sig_v4","ctx":"ctx_v4","type":"hls"}""")
                        }
                        else -> {
                            // Secondary server stays busy forever - must NOT abort or discard srv_ok!
                            resp("application/json", """{"error":"busy","retry":6}""")
                        }
                    }
                }
                else -> null
            }
        }

        // Test 3a: Cancellation safety mid-WebView CF challenge
        val cancelJob = CoroutineScope(Dispatchers.IO).launch {
            runCatching { localClient.fetchSite(cancelUrl, "${SoloLatinoMarkup.MAIN_URL}/") }
        }
        delay(1300)
        cancelJob.cancelAndJoin()
        Log.i("CSPlaybackProbe", "PASS cancel mid-webview released Semaphore(1) safely")

        // Test 3b: Exclusive XSRF-TOKEN + site tokens
        val siteRes = withContext(Dispatchers.IO) { localClient.fetchSite(epUrl, "${SoloLatinoMarkup.MAIN_URL}/") }
        check(siteRes.code == 200 && siteRes.text.contains("tok_psh_btn")) { "siteRes failed code=${siteRes.code}" }
        val doc = Jsoup.parse(siteRes.text, epUrl)
        val section = SoloLatinoMarkup.parsePlayerSection(doc, epUrl)
        val (btns, lazy) = withContext(Dispatchers.IO) { localClient.resolvePlayerSection(epUrl, section) }
        val resolvedUrls = (btns.mapNotNull { it.url } + listOfNotNull(lazy?.url)).distinct()
        Log.i("CSPlaybackProbe", "WV_PIPELINE_SITE btns=${btns.size} urls=$resolvedUrls")
        check(resolvedUrls.contains(pshEmbedUrl)) { "resolveSitePlayers failed (likely CSRF 419): $resolvedUrls" }

        // Test 3c: PSH CF challenge + window.stop() + hostile _isLocked/fetch + busy secondary server -> immediate link emission!
        val pshLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val pshOk = withContext(Dispatchers.IO) {
            SoloLatinoPelisSeriesHoyExtractor.extractWithClient(
                client = localClient,
                url = pshEmbedUrl,
                referer = epUrl,
                langTag = "Latino",
                subtitleCallback = {},
                callback = { pshLinks.add(it) },
            )
        }
        Log.i("CSPlaybackProbe", "WV_PIPELINE_PSH ok=$pshOk links=${pshLinks.map { "${it.name}->${it.url.take(90)}" }}")
        check(pshOk && pshLinks.any { it.url.contains("/p.php") && it.url.contains("&sig=sig_v4") && it.url.contains("&ctx=ctx_v4") }) {
            "SoloLatinoPelisSeriesHoyExtractor v4 failed against CF + _isLocked + busy: $pshLinks"
        }
        val pshLink = pshLinks.first()
        check(pshLink.headers["User-Agent"] == localClient.currentUserAgent(pshEmbedUrl)) {
            "User-Agent mismatch between WebView and PSH ExtractorLink headers: ${pshLink.headers}"
        }
        Log.i("CSPlaybackProbe", "PASS webview-pipeline (v4 exclusive-XSRF + CF + window.stop + XHR + busy-resilience + exact-UA)")
    }

    private suspend fun playFirstWorking(context: Context, label: String, candidates: List<ExtractorLink>) {
        val errors = mutableListOf<String>()
        for (link in candidates.take(6)) {
            try {
                play(context, link)
                Log.i("CSPlaybackProbe", "PASS playback $label via ${link.name}")
                return
            } catch (t: Throwable) {
                val msg = generateSequence(t) { it.cause }.take(4).joinToString(" <- ") {
                    "${it.javaClass.simpleName}:${it.message?.take(90)}"
                }
                errors.add("${link.name}: $msg")
            }
        }
        error("$label no playable link: $errors")
    }

    private suspend fun play(context: Context, link: ExtractorLink) = withTimeout(35_000) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val headers = link.headers.toMutableMap()
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", true) }) {
            headers["Referer"] = link.referer
        }
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: USER_AGENT
        val uaKeys = headers.keys.filter { it.equals("User-Agent", ignoreCase = true) }
        uaKeys.forEach { headers.remove(it) }
        val factory = OkHttpDataSource.Factory(com.lagradost.cloudstream3.app.baseClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(link.url).apply {
            if (link.type == ExtractorLinkType.M3U8) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val media = DefaultMediaSourceFactory(factory).createMediaSource(item)
        val player = ExoPlayer.Builder(context).build()
        try {
            player.setVideoSurface(surface)
            player.setMediaSource(media)
            player.prepare()
            player.playWhenReady = true
            while (true) {
                player.playerError?.let { throw it }
                if ((player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && player.currentPosition > 800) break
                delay(250)
            }
            check(player.videoSize.width > 0 && player.videoSize.height > 0) { "no decoded video" }
        } finally {
            player.release()
            surface.release()
            texture.release()
        }
    }

    private suspend fun verifyCoilImage(
        context: Context,
        label: String,
        url: String?,
        headers: Map<String, String>?,
    ) {
        check(!url.isNullOrBlank()) { "Poster URL missing for $label" }
        val builder = ImageRequest.Builder(context)
            .data(url)
            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
            .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
        if (url.startsWith("http", ignoreCase = true)) {
            builder.httpHeaders(
                NetworkHeaders.Builder().also { hb ->
                    hb["User-Agent"] = USER_AGENT
                    headers?.forEach { (k, v) -> hb[k] = v }
                }.build(),
            )
        }
        val result = SingletonImageLoader.get(context).execute(builder.build())
        when (result) {
            is SuccessResult -> {
                check(result.image.width >= 50 && result.image.height >= 50) {
                    "Coil image too small for $label ($url): ${result.image.width}x${result.image.height}"
                }
            }
            is ErrorResult -> {
                error("Coil failed for $label ($url): ${result.throwable.javaClass.simpleName}: ${result.throwable.message}")
            }
        }
    }
}
