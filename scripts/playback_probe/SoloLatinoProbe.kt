package com.menorah.sololatino

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
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
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        Log.i("CSPlaybackProbe", "STAGE plugin_loaded sdk=${android.os.Build.VERSION.SDK_INT}")
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                Log.i("CSPlaybackProbe", "STAGE starting_probe")
                withTimeout(600_000) {
                    val provider = SoloLatinoProvider(context)
                    val sysUa = provider.client.currentUserAgent()

                    // 1. Live check: can this runner IP reach sololatino.net directly or via WebView/cfKiller?
                    val siteProbe = withContext(Dispatchers.IO) {
                        provider.client.fetchSite("${SoloLatinoMarkup.MAIN_URL}/", "${SoloLatinoMarkup.MAIN_URL}/")
                    }
                    val siteReachable = siteProbe.code in 200..299 &&
                        siteProbe.text.isNotBlank() &&
                        !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(siteProbe.code, siteProbe.text)
                    Log.i("CSPlaybackProbe", "LIVE_SITE reachable=$siteReachable code=${siteProbe.code} len=${siteProbe.text.length}")

                    // 2. Live Embed69 + Host extractors + Media3 ExoPlayer playback (with real Android client!)
                    val e69MovieLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    val e69MovieOk = withContext(Dispatchers.IO) {
                        SoloLatinoEmbed69Resolver.extract(
                            "https://embed69.org/f/tt45368240",
                            "https://sololatino.net/pelicula/street-fighter",
                            "Latino",
                            {},
                            { e69MovieLinks.add(it) },
                            client = provider.client,
                        )
                    }
                    Log.i("CSPlaybackProbe", "LIVE_E69_SF ok=$e69MovieOk count=${e69MovieLinks.size} names=${e69MovieLinks.map { it.name }}")
                    check(e69MovieOk && e69MovieLinks.isNotEmpty()) { "Embed69 Street Fighter returned no links" }

                    val e69BoysLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    val e69BoysOk = withContext(Dispatchers.IO) {
                        SoloLatinoEmbed69Resolver.extract(
                            "https://embed69.org/f/tt1190634-5x08",
                            "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8",
                            "Latino",
                            {},
                            { e69BoysLinks.add(it) },
                            client = provider.client,
                        )
                    }
                    Log.i("CSPlaybackProbe", "LIVE_E69_BOYS ok=$e69BoysOk count=${e69BoysLinks.size} names=${e69BoysLinks.take(6).map { it.name }}")
                    check(e69BoysOk && e69BoysLinks.isNotEmpty()) { "Embed69 The Boys S5E8 returned no links" }

                    // Verify live Media3 video frame decoding on extracted Embed69 streams
                    playFirstWorking(context, "e69-street-fighter", e69MovieLinks.toList())
                    playFirstWorking(context, "e69-the-boys-s5e8", e69BoysLinks.toList())

                    // 3. Verify Coil 3 image loading across TMDB w500 / w1280 / w400 URLs
                    verifyCoilImage(context, "tmdb-poster-w500", "https://image.tmdb.org/t/p/w500/zDE9hd1SG9695YncbZGjSf7Z9Jk.jpg", null)
                    verifyCoilImage(context, "tmdb-backdrop-w1280", "https://image.tmdb.org/t/p/w1280/zDE9hd1SG9695YncbZGjSf7Z9Jk.jpg", null)
                    verifyCoilImage(context, "tmdb-still-w400", "https://image.tmdb.org/t/p/w400/a7Ofz6lgowoSVajts2FhhQOpNJK.jpg", null)
                    Log.i("CSPlaybackProbe", "PASS coil posters=3")

                    if (siteReachable) {
                        // Full end-to-end live SoloLatinoProvider test
                        runFullLiveSuite(context, provider)
                    } else {
                        // Exercise real Android WebView pipeline (resolveSitePlayers + resolvePelisSeriesHoy with window.stop(),
                        // location.replace, unpadded fnv1a32, _ao='', _qsrc, s.php a=click/1/2, and p.php proxy -> ExoPlayer)
                        val livePlayableUrl = e69MovieLinks.firstOrNull { it.type == ExtractorLinkType.M3U8 }?.url
                            ?: e69BoysLinks.firstOrNull { it.type == ExtractorLinkType.M3U8 }?.url
                            ?: e69MovieLinks.first().url
                        runWebViewPipelineVerification(context, livePlayableUrl)
                    }
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun runWebViewPipelineVerification(context: Context, sampleMediaUrl: String) {
        check(
            SoloLatinoSessionHelper.isChromiumErrorPage(
                "<html><head><title>Webpage not available</title></head><!-- Upside down Android --></html>"
            )
        ) { "isChromiumErrorPage check failed" }
        val localClient = SoloLatinoClient(context)
        val pshToken = "tok_probe_68"
        val pshQc = "120" // fnv1a32("aabbccdd|clk|120") == 0x2529df4 (7 hex chars, unpadded!)
        val pshQk = listOf("cc", "aa", "dd", "bb")
        val pshRt = "aabbccdd"
        val expectedTk = SoloLatinoMarkup.fnv1a32Hex("$pshRt|clk|$pshQc")
        check(expectedTk == "2529df4") { "unexpected fnv1a32Hex=$expectedTk" }
        var sawClick = false

        val epUrl = "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8"
        val pshEmbedUrl = "https://player.pelisserieshoy.com/f/tt1190634-5x08"

        val siteEpisodeHtml = """
            <!DOCTYPE html><html><head>
            <meta name="csrf-token" content="probe-meta-csrf">
            </head><body>
            <h1>The Boys 5x08</h1>
            <div data-lang-tab="lat">Latino</div>
            <div data-lang-group="lat">
              <button data-player-token="tok_psh_btn" data-lang="lat">Principal</button>
              <button data-player-token="tok_e69_btn" data-lang="lat">Embed69</button>
            </div>
            <div id="player-frame" data-lazy-embed="tok_lazy_btn"></div>
            </body></html>
        """.trimIndent()

        // Mirrors player.pelisserieshoy.com live <head> anti-top-level redirect + IIFE variables
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
              (function(){
                var _t = '$pshToken';
                var _qc = '$pshQc';
                var _qk = ['${pshQk[0]}','${pshQk[1]}','${pshQk[2]}','${pshQk[3]}'];
                var _ao = '';
                var _qsrc = 'sl';
              })();
            </script>
            </body></html>
        """.trimIndent()

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
            when {
                urlStr.startsWith(epUrl) -> resp("text/html", siteEpisodeHtml)
                path == "/sanctum/csrf-cookie" -> resp("text/plain", "", 204)
                path == "/api/player-url" || path == "/api/player-embed" -> {
                    val shield = req.requestHeaders?.entries?.firstOrNull { it.key.equals("X-SL-Render", true) }?.value.orEmpty()
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
                urlStr.startsWith(pshEmbedUrl) -> resp("text/html", pshHtml)
                path == "/s.php" && method == "POST" -> {
                    if (!sawClick) {
                        sawClick = true
                        resp("application/json", """{"ok":true}""")
                    } else {
                        resp("application/json", """{"langs_s":{"LAT":[["Principal","srv_lat_1"]]},"u":"$sampleMediaUrl","sig":"sig_probe_1","ctx":"ctx_probe_1","type":"hls"}""")
                    }
                }
                else -> null
            }
        }

        val siteRes = withContext(Dispatchers.IO) { localClient.fetchSite(epUrl, "${SoloLatinoMarkup.MAIN_URL}/") }
        val doc = Jsoup.parse(siteRes.text, epUrl)
        val section = SoloLatinoMarkup.parsePlayerSection(doc, epUrl)
        val (btns, lazy) = withContext(Dispatchers.IO) { localClient.resolvePlayerSection(epUrl, section) }
        val resolvedUrls = (btns.mapNotNull { it.url } + listOfNotNull(lazy?.url)).distinct()
        Log.i("CSPlaybackProbe", "WV_PIPELINE_SITE btns=${btns.size} urls=$resolvedUrls")
        check(resolvedUrls.contains(pshEmbedUrl)) { "resolveSitePlayers failed in WebView: $resolvedUrls" }

        val pshLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val pshOk = withContext(Dispatchers.IO) {
            SoloLatinoPelisSeriesHoyExtractor.extractWithClient(
                client = localClient,
                url = pshEmbedUrl,
                referer = epUrl,
                sourceLabel = "SoloLatino · Latino",
                subtitleCallback = {},
                callback = { pshLinks.add(it) },
            )
        }
        Log.i("CSPlaybackProbe", "WV_PIPELINE_PSH ok=$pshOk links=${pshLinks.map { "${it.name}->${it.url.take(90)}" }}")
        check(pshOk && pshLinks.any { it.url.contains("/p.php") && it.url.contains("&sig=sig_probe_1") && it.url.contains("&ctx=ctx_probe_1") }) {
            "SoloLatinoPelisSeriesHoyExtractor WebView pipeline failed against window.stop() + IIFE + _qsrc: $pshLinks"
        }
        Log.i("CSPlaybackProbe", "PASS webview-pipeline (site-tokens + PSH window.stop() + unpadded FNV-1a + ctx + extractor)")
    }

    private suspend fun runFullLiveSuite(context: Context, provider: SoloLatinoProvider) {
        val started = android.os.SystemClock.elapsedRealtime()
        val coreSectionData = setOf(
            SoloLatinoMarkup.SECTION_HOME_RECENT_EPISODES,
            SoloLatinoMarkup.SECTION_HOME_RECENT_ADDED,
            "peliculas?sort=updated",
            "series?sort=updated",
            "animes?sort=updated",
            "doramas?sort=updated",
        )
        val homeSections = provider.mainPage.filter { it.data in coreSectionData }
        val homePages = withContext(Dispatchers.IO) {
            coroutineScope {
                homeSections.map { section ->
                    async {
                        val page = provider.getMainPage(
                            1,
                            MainPageRequest(section.name, section.data, section.horizontalImages),
                        ) ?: error("null mainPage for ${section.name}")
                        section.name to page.items.flatMap { it.list }
                    }
                }.awaitAll()
            }
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        val emptySections = homePages.filter { it.second.isEmpty() }.map { it.first }
        Log.i("CSPlaybackProbe", "home sections=${homePages.size} empty=$emptySections elapsedMs=$elapsed")
        check(emptySections.isEmpty()) { "empty home sections: $emptySections" }

        val searchSf = provider.search("street fighter", 1) ?: error("null search street fighter")
        check(searchSf.items.isNotEmpty()) { "empty search street fighter" }
        val sfLoad = provider.load("https://sololatino.net/pelicula/street-fighter") as? MovieLoadResponse
            ?: error("street-fighter load failed")
        val batch = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(sfLoad.dataUrl, false, {}, { batch.add(it) }) }
        val links = synchronized(batch) { batch.toList() }
        check(ok && links.isNotEmpty()) { "live street-fighter loadLinks empty" }
        playFirstWorking(context, "live-street-fighter", links)
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
