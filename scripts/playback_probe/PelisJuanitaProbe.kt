package com.menorah.pelisjuanita

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.coroutines.resume

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(660_000) {
                    val provider = PelisJuanitaProvider(context, InMemoryPelisJuanitaSessionStore())
                    val started = android.os.SystemClock.elapsedRealtime()
                    val homePages = withContext(Dispatchers.IO) {
                        coroutineScope {
                            provider.mainPage.map { section ->
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
                    check(elapsed < 60_000) { "home too slow: $elapsed ms" }

                    // Check page 2 on movie and series tabs
                    for (secName in listOf("Películas Estrenos", "Series Estrenos")) {
                        val section = provider.mainPage.first { it.name == secName }
                        val p1 = homePages.first { it.first == secName }.second
                        val p2 = provider.getMainPage(2, MainPageRequest(section.name, section.data, section.horizontalImages))
                        val p2Items = p2?.items?.flatMap { it.list }.orEmpty()
                        check(p2Items.isNotEmpty()) { "empty page 2 on $secName" }
                        check(p2Items.first().url != p1.first().url) { "page 2 duplicated page 1 on $secName" }
                    }
                    Log.i("CSPlaybackProbe", "PASS pagination on PelisJuanita public catalog")

                    // Search checks
                    val searchMatrix = provider.search("matrix", 1) ?: error("null search matrix")
                    check(searchMatrix.items.isNotEmpty()) { "empty matrix search" }
                    val searchAll1 = provider.search("%", 1) ?: error("null search % page 1")
                    val searchAll2 = provider.search("%", 2) ?: error("null search % page 2")
                    check(searchAll1.hasNext && searchAll2.items.isNotEmpty()) { "expected multi-page % search" }
                    check(searchAll1.items.first().url != searchAll2.items.first().url) { "search page 2 duplicated page 1" }

                    // Movie unverified load check & actionable gate check
                    val movieCards = homePages.first { it.first == "Películas Estrenos" }.second
                    val latestMovie = movieCards.first { !it.posterUrl.isNullOrBlank() }
                    val movieLoad = provider.load(latestMovie.url) as? MovieLoadResponse
                        ?: error("latest movie load failed for ${latestMovie.url}")
                    check(!movieLoad.posterUrl.isNullOrBlank()) { "missing movie poster" }

                    // Empty-backdrop regression check on The Skin Crawler Murders (img_fondo="", img_frente present)
                    val skinCrawlerLoad = runCatching {
                        provider.load("${PelisJuanitaMarkup.MAIN_URL}/movies/pelicula/the-skin-crawler-murders") as? MovieLoadResponse
                    }.getOrNull()
                    if (skinCrawlerLoad != null) {
                        check(!skinCrawlerLoad.posterUrl.isNullOrBlank()) { "missing skin-crawler poster" }
                        check(!skinCrawlerLoad.backgroundPosterUrl.isNullOrBlank()) {
                            "expected backdrop fallback when img_fondo is empty for the-skin-crawler-murders"
                        }
                        verifyCoilImage(context, "skin-crawler-bg", skinCrawlerLoad.backgroundPosterUrl, skinCrawlerLoad.posterHeaders)
                    }

                    var gateOk = false
                    try {
                        provider.loadLinks(movieLoad.dataUrl, false, {}, {})
                    } catch (e: ErrorLoadingException) {
                        gateOk = e.message.orEmpty().contains("Ajustes") && e.message.orEmpty().contains("PelisJuanita")
                    }
                    check(gateOk) { "expected clear Ajustes verification ErrorLoadingException" }
                    Log.i("CSPlaybackProbe", "PASS unverified movie metadata and verification prompt")

                    // Verified-session WebView regression check: CookieManager wipe recovery,
                    // empty #ulTemporada + bare temporada=N fragment series loading, and movie ?p=<id> loadLinks.
                    verifyVerifiedSessionFlows(context)
                    Log.i("CSPlaybackProbe", "PASS verified session WebView series/movie/cookie flow")

                    // Coil image checks across home, search and loaded detail
                    val posterChecks = mutableListOf<Triple<String, String?, Map<String, String>?>>()
                    movieCards.take(4).forEachIndexed { i, c -> posterChecks.add(Triple("home-movie-$i", c.posterUrl, c.posterHeaders)) }
                    homePages.first { it.first == "Series Estrenos" }.second.take(2).forEachIndexed { i, c ->
                        posterChecks.add(Triple("home-series-$i", c.posterUrl, c.posterHeaders))
                    }
                    searchMatrix.items.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("search-matrix-$i", c.posterUrl, c.posterHeaders)) }
                    posterChecks.add(Triple("load-movie-poster", movieLoad.posterUrl, movieLoad.posterHeaders))
                    movieLoad.backgroundPosterUrl?.let { posterChecks.add(Triple("load-movie-bg", it, movieLoad.posterHeaders)) }

                    for ((label, pUrl, pHeaders) in posterChecks) {
                        verifyCoilImage(context, label, pUrl, pHeaders)
                    }
                    Log.i("CSPlaybackProbe", "PASS coil posters=${posterChecks.size}")

                    // Live server extraction + Media3 frame playback across PelisJuanita hosts
                    val serverProbes = listOf(
                        "Vimeus" to "https://vimeus.net/e/movie?tmdb=1131943&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1",
                        "VimeusSerie" to "https://vimeus.net/e/serie?tmdb=247718&se=1&ep=1&view_key=D3dX7LTo6CgnhLGPyKahD9gDNZ2n82wJ2mpWu-SjS0M&title=Pelisjuanita&theme=minimal&autoplay=1",
                        "Fastserver" to "https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden",
                        "FastserverSerie" to "https://seriesplayer.fortamomar.workers.dev/?id=TnGPAAqrTEHHepUKWTq8B-MobLand/1",
                        "Earnvids" to "https://callistanise.com/v/lg0w6ezhvyd8",
                        "StreamWish" to "https://hglink.to/e/u8jqwoe4cnpt",
                        "Byse" to "https://bysesukior.com/e/92rgcn88ka85",
                        "MixDrop" to "https://mixdrop.top/e/q11gg30obxxogvn",
                        "Voe" to "https://voe.sx/e/ltvamjdzsvxv",
                        "Savefiles" to "https://savefiles.com/e/lpkobe2ki65z",
                        "Lulustream" to "https://lulustream.com/e/zxfcz3pz68ds",
                        "Vidara" to "https://vidara.to/e/HnuAfGnH5Z97l",
                        "StreamWishVsembed" to "https://streamwish.to/e/k9u77dklzfob",
                    )

                    val passedServers = mutableListOf<String>()
                    val passedKeys = mutableSetOf<String>()
                    val failedServers = mutableListOf<String>()
                    for ((srv, embedUrl) in serverProbes) {
                        val batch = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        withContext(Dispatchers.IO) {
                            provider.loadLinks(embedUrl, false, {}, { batch.add(it) })
                            if (batch.isEmpty()) {
                                loadExtractor(embedUrl, "${PelisJuanitaMarkup.MAIN_URL}/", {}) { batch.add(it) }
                            }
                        }
                        val links = synchronized(batch) { batch.toList() }
                        if (links.isEmpty()) {
                            failedServers.add("$srv: no extracted links")
                            continue
                        }
                        var played = false
                        val errs = mutableListOf<String>()
                        for (link in links.take(3)) {
                            try {
                                play(context, link)
                                played = true
                                passedKeys.add(srv)
                                passedServers.add("$srv(${link.name})")
                                Log.i("CSPlaybackProbe", "PASS server $srv via ${link.name} frame")
                                break
                            } catch (t: Throwable) {
                                if (t is CancellationException && t !is TimeoutCancellationException) throw t
                                errs.add("${link.name}[${link.url.take(60)}]: ${t.javaClass.simpleName}:${t.message?.take(100)}")
                            }
                        }
                        if (!played) failedServers.add("$srv -> $errs")
                    }
                    Log.i("CSPlaybackProbe", "server coverage passed=$passedServers failed=$failedServers")
                    check(passedServers.size >= 12) {
                        "expected >= 12 distinct PelisJuanita servers playing in Media3, got passed=$passedServers failed=$failedServers"
                    }
                    check("Lulustream" in passedKeys) {
                        "expected Lulustream to resolve and play in Media3, failed=$failedServers"
                    }
                    check("Vidara" in passedKeys) {
                        "expected Vidara to resolve and play in Media3, failed=$failedServers"
                    }
                    check("Byse" in passedKeys) {
                        "expected Byse to decrypt and play in Media3, failed=$failedServers"
                    }
                    check("StreamWish" in passedKeys || "StreamWishVsembed" in passedKeys) {
                        "expected StreamWish/vsembed to decrypt and play in Media3, failed=$failedServers"
                    }
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun play(context: Context, link: ExtractorLink) = withTimeout(30_000) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val headers = link.headers.toMutableMap()
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", true) }) {
            headers["Referer"] = link.referer
        }
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val uaKeys = headers.keys.filter { it.equals("User-Agent", ignoreCase = true) }
        uaKeys.forEach { headers.remove(it) }
        val factory = DefaultHttpDataSource.Factory().apply {
            if (!userAgent.isNullOrBlank()) setUserAgent(userAgent)
            setDefaultRequestProperties(headers)
        }
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
                if ((player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && player.currentPosition > 1000) break
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

    private suspend fun clearWebViewCookies() {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies {
                        cm.flush()
                        if (cont.isActive) cont.resume(Unit)
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private suspend fun verifyVerifiedSessionFlows(context: Context) {
        clearWebViewCookies()

        val verifiedStore = InMemoryPelisJuanitaSessionStore(
            PelisJuanitaSessionData(
                cookieHeader = "PHPSESSID=verified-probe-sess; cf_clearance=probe-clear",
                userAgent = PelisJuanitaSessionHelper.DEFAULT_USER_AGENT,
                verifiedAtMs = System.currentTimeMillis(),
            ),
        )
        val verifiedProvider = PelisJuanitaProvider(context, verifiedStore)
        val seenMovieInfoUrls = Collections.synchronizedList(mutableListOf<String>())

        verifiedProvider.testWebResponse = { req ->
            val u = req.url.toString()
            val html = when {
                u.contains("/series/ver-serie/mobland") -> """
                    <html><head><title>MobLand</title></head><body>
                    <script>window.CURRENT_SERIE_ID=7650;</script>
                    <div id="dt_contenedor"></div>
                    </body></html>
                """.trimIndent()
                u.contains("serieInfo.php") && u.contains("temporada=1") -> """
                    <a class="episodio-item" href="/series/ver-serie/mobland/01x01"><div class="episodio-texto"><h2 class="list-title">[01x01] Ep 1</h2></div></a>
                    <a class="episodio-item" href="/series/ver-serie/mobland/01x02"><div class="episodio-texto"><h2 class="list-title">[01x02] Ep 2</h2></div></a>
                """.trimIndent()
                u.contains("serieInfo.php") && u.contains("temporada=2") -> """
                    <a class="episodio-item" href="/series/ver-serie/mobland/02x01"><div class="episodio-texto"><h2 class="list-title">[02x01] Ep 1 T2</h2></div></a>
                    <a class="episodio-item" href="/series/ver-serie/mobland/02x02"><div class="episodio-texto"><h2 class="list-title">[02x02] Ep 2 T2</h2></div></a>
                """.trimIndent()
                u.contains("serieInfo.php") -> """
                    <html><head><meta name="snum" content="1"/><meta name="enum" content="1"/></head><body>
                    <div id="dt_contenedor"><div class="titleInfo"><h1>MobLand</h1><p class="sinopsis">Serie de prueba.</p></div>
                    <div id="temporadasDropdown">
                      <div class="server-item" onclick="cargarEpisodiosDeTemporada('1');"><span>Temporada 1</span></div>
                      <div class="server-item" onclick="cargarEpisodiosDeTemporada('2');"><span>Temporada 2</span></div>
                    </div>
                    <ul id="ulTemporada"></ul></div></body></html>
                """.trimIndent()
                u.contains("/movies/pelicula/madden") -> """
                    <html><body><div id="dt_contenedor"></div>
                    <script>let url = `movieInfo.php?title=` + encodeURIComponent('madden') + `&p=55775`;</script>
                    </body></html>
                """.trimIndent()
                u.contains("movieInfo.php") -> {
                    seenMovieInfoUrls.add(u)
                    if (u.contains("p=55775")) {
                        """
                        <html><body><div id="dt_contenedor"><div class="titleInfo">
                          <div class="columna imagen"><img src="https://image.tmdb.org/t/p/w300//bRa83oPFFPqaAF3wrpVWPqqfFel.jpg"/></div>
                          <h1>Madden</h1><p class="sinopsis">Película verificada.</p>
                        </div>
                        <div class="box-download">
                          <a class="row-download" data-idioma="latino" data-tipo="stream" data-url="https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden">
                            <span>Fastserver</span><span>FULLHD</span>
                          </a>
                        </div></div></body></html>
                        """.trimIndent()
                    } else if (u.contains("title=no-backdrop-sub")) {
                        """
                        <html><body><div id="dt_contenedor">
                          <div class="Image"><img id="cpt" src="https://image.tmdb.org/t/p/original"/></div>
                          <div class="titleInfo">
                            <div class="columna imagen"><img src="https://image.tmdb.org/t/p/w500/huc4u7fcxJ849qzg7Vf881FdMXJ.jpg"/></div>
                            <h1>Sin Fondo Sub</h1><p class="sinopsis">Película con img_fondo vacío.</p>
                          </div>
                          <a class="row-download" data-idioma="subtitulada" data-tipo="stream" data-url="https://pelisplayer.fortamomar.workers.dev/?id=tOXk1CLcSgETAoD5ifN4x-Madden">
                            <span>Fastserver</span><span>HD</span>
                          </a>
                        </div></body></html>
                        """.trimIndent()
                    } else {
                        """<html><body><form><input name="empresa_hp"></form></body></html>"""
                    }
                }
                u.contains("/movies/pelicula/no-backdrop-sub") -> """
                    <html><body><div id="dt_contenedor"></div></body></html>
                """.trimIndent()
                else -> "<html><body><div id=\"dt_contenedor\"></div></body></html>"
            }
            WebResourceResponse("text/html", "UTF-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8)))
        }

        val seriesResp = withContext(Dispatchers.IO) {
            verifiedProvider.load("${PelisJuanitaMarkup.MAIN_URL}/series/ver-serie/mobland")
        } as? TvSeriesLoadResponse ?: error("verified series load returned null")
        check(!seriesResp.comingSoon) { "verified series still flagged comingSoon=true" }
        check(seriesResp.episodes.size == 4) { "expected 4 episodes across 2 seasons, got ${seriesResp.episodes.size}" }
        check(seriesResp.episodes.any { it.season == 1 && it.episode == 1 } && seriesResp.episodes.any { it.season == 2 && it.episode == 2 }) {
            "missing season 1 or season 2 episodes: ${seriesResp.episodes.map { it.season to it.episode }}"
        }

        val restoredCookies = withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie(PelisJuanitaMarkup.MAIN_URL).orEmpty()
        }
        check(restoredCookies.contains("PHPSESSID=verified-probe-sess")) {
            "expected CookieManager PHPSESSID restoration after wipe, got '$restoredCookies'"
        }

        val movieResp = withContext(Dispatchers.IO) {
            verifiedProvider.load("${PelisJuanitaMarkup.MAIN_URL}/movies/pelicula/madden")
        } as? MovieLoadResponse ?: error("verified movie load returned null")
        check(movieResp.name == "Madden") { "unexpected movie title ${movieResp.name}" }
        check(movieResp.posterUrl == "https://image.tmdb.org/t/p/w300/bRa83oPFFPqaAF3wrpVWPqqfFel.jpg") {
            "expected normalized TMDB poster from double-slashed w300// path, got ${movieResp.posterUrl}"
        }
        verifyCoilImage(context, "verified-madden-poster", movieResp.posterUrl, movieResp.posterHeaders)
        check(seenMovieInfoUrls.any { it.contains("p=55775") }) {
            "expected movieInfo.php to receive &p=55775, saw $seenMovieInfoUrls"
        }

        val extractedLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val okLinks = withContext(Dispatchers.IO) {
            verifiedProvider.loadLinks(movieResp.dataUrl, false, {}, { extractedLinks.add(it) })
        }
        check(okLinks && extractedLinks.isNotEmpty()) { "expected verified movie loadLinks to extract playable links" }

        // Expired verified session must throw verification prompt on load() instead of hiding behind buscar.php
        var expiredThrew = false
        try {
            withContext(Dispatchers.IO) {
                verifiedProvider.load("${PelisJuanitaMarkup.MAIN_URL}/movies/pelicula/the-skin-crawler-murders")
            }
        } catch (e: ErrorLoadingException) {
            expiredThrew = e.message.orEmpty().contains("Ajustes") && e.message.orEmpty().contains("PelisJuanita")
        }
        check(expiredThrew) { "expected expired verified session on load() to prompt re-verification in Ajustes" }

        // Empty backdrop + subtitulada-only stream row regression inside verified session
        val noBgResp = withContext(Dispatchers.IO) {
            verifiedProvider.load("${PelisJuanitaMarkup.MAIN_URL}/movies/pelicula/no-backdrop-sub")
        } as? MovieLoadResponse ?: error("no-backdrop-sub load returned null")
        check(noBgResp.posterUrl == "https://image.tmdb.org/t/p/w500/huc4u7fcxJ849qzg7Vf881FdMXJ.jpg")
        check(noBgResp.backgroundPosterUrl == noBgResp.posterUrl) {
            "expected backdrop fallback to posterUrl when img_fondo/cpt is empty, got ${noBgResp.backgroundPosterUrl}"
        }
        val subLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val okSub = withContext(Dispatchers.IO) {
            verifiedProvider.loadLinks(noBgResp.dataUrl, false, {}, { subLinks.add(it) })
        }
        check(okSub && subLinks.any { it.name.contains("Subtitulado", ignoreCase = true) }) {
            "expected subtitulada stream link, got $subLinks"
        }

        clearWebViewCookies()
    }
}
