package com.menorah.sololatino

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
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
                    val preOkHttp = withContext(Dispatchers.IO) {
                        runCatching {
                            val r = com.lagradost.cloudstream3.app.get(
                                "${SoloLatinoMarkup.MAIN_URL}/",
                                headers = SoloLatinoSessionHelper.buildBrowserHeaders(sysUa, "${SoloLatinoMarkup.MAIN_URL}/"),
                                timeout = 15L,
                            )
                            "code=${r.code} len=${r.text.length} cf=${SoloLatinoSessionHelper.looksLikeCloudflareChallenge(r.code, r.text)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    val preWebView = withContext(Dispatchers.IO) {
                        runCatching {
                            val w = SoloLatinoWebTransport.getHtml(
                                context = context,
                                url = "${SoloLatinoMarkup.MAIN_URL}/",
                                referer = "${SoloLatinoMarkup.MAIN_URL}/",
                                userAgent = sysUa,
                            )
                            "code=${w.code} url=${w.url} len=${w.text.length} cf=${SoloLatinoSessionHelper.looksLikeCloudflareChallenge(w.code, w.text)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    val preCfKiller = withContext(Dispatchers.IO) {
                        runCatching {
                            val killer = com.lagradost.cloudstream3.network.CloudflareKiller()
                            val r = com.lagradost.cloudstream3.app.get(
                                "${SoloLatinoMarkup.MAIN_URL}/",
                                headers = SoloLatinoSessionHelper.buildBrowserHeaders(sysUa, "${SoloLatinoMarkup.MAIN_URL}/"),
                                interceptor = killer,
                                timeout = 25L,
                            )
                            val ckKeys = killer.savedCookies["sololatino.net"]?.keys.orEmpty()
                            "code=${r.code} len=${r.text.length} cf=${SoloLatinoSessionHelper.looksLikeCloudflareChallenge(r.code, r.text)} ck=$ckKeys"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    Log.i("CSPlaybackProbe", "PRECHECK ua=[${sysUa.take(90)}] okhttp=[$preOkHttp] webview=[$preWebView] cfk=[$preCfKiller]")

                    // Inspect raw responses from Embed69 & PelisSeriesHoy & test host extractors + ExoPlayer + Coil
                    val e69RawHttp = withContext(Dispatchers.IO) {
                        runCatching {
                            val r = com.lagradost.cloudstream3.app.get(
                                "https://embed69.org/f/tt45368240",
                                referer = "https://sololatino.net/pelicula/street-fighter",
                                headers = SoloLatinoSessionHelper.buildBrowserHeaders(SoloLatinoSessionHelper.DEFAULT_USER_AGENT, "https://sololatino.net/"),
                                timeout = 15L,
                            )
                            "code=${r.code} url=${r.url} len=${r.text.length} body=${r.text.replace(Regex("\\s+"), " ").take(450)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    val e69BoyHttp = withContext(Dispatchers.IO) {
                        runCatching {
                            val r = com.lagradost.cloudstream3.app.get(
                                "https://embed69.org/f/tt1190634-5x08",
                                referer = "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8",
                                headers = SoloLatinoSessionHelper.buildBrowserHeaders(SoloLatinoSessionHelper.DEFAULT_USER_AGENT, "https://sololatino.net/"),
                                timeout = 15L,
                            )
                            "code=${r.code} len=${r.text.length} dl=${r.text.contains("dataLink")} body=${r.text.replace(Regex("\\s+"), " ").take(300)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    val pshRawHttp = withContext(Dispatchers.IO) {
                        runCatching {
                            val r = com.lagradost.cloudstream3.app.get(
                                "https://player.pelisserieshoy.com/f/tt1190634-5x08",
                                referer = "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8",
                                headers = SoloLatinoSessionHelper.buildBrowserHeaders(SoloLatinoSessionHelper.DEFAULT_USER_AGENT, "https://sololatino.net/"),
                                timeout = 15L,
                            )
                            "code=${r.code} len=${r.text.length} body=${r.text.replace(Regex("\\s+"), " ").take(450)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    val pshWebView = withContext(Dispatchers.IO) {
                        runCatching {
                            val w = SoloLatinoWebTransport.getHtml(
                                context = context,
                                url = "https://player.pelisserieshoy.com/f/tt1190634-5x08",
                                referer = "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8",
                                allowedHost = { SoloLatinoMarkup.isPelisSeriesHoyUrl(it) },
                                userAgent = sysUa,
                            )
                            "code=${w.code} len=${w.text.length} body=${w.text.replace(Regex("\\s+"), " ").take(350)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    val e69WebView = withContext(Dispatchers.IO) {
                        runCatching {
                            val w = SoloLatinoWebTransport.getHtml(
                                context = context,
                                url = "https://embed69.org/f/tt45368240",
                                referer = "https://sololatino.net/pelicula/street-fighter",
                                allowedHost = { u -> SoloLatinoMarkup.hostOf(u).contains("embed69") },
                                userAgent = sysUa,
                            )
                            "code=${w.code} len=${w.text.length} dl=${w.text.contains("dataLink")} body=${w.text.replace(Regex("\\s+"), " ").take(350)}"
                        }.getOrElse { e -> "ERR ${e.javaClass.simpleName}:${e.message}" }
                    }
                    Log.i("CSPlaybackProbe", "RAW_E69_SF [$e69RawHttp]")
                    Log.i("CSPlaybackProbe", "RAW_E69_BOYS [$e69BoyHttp]")
                    Log.i("CSPlaybackProbe", "RAW_PSH [$pshRawHttp]")
                    Log.i("CSPlaybackProbe", "WV_PSH [$pshWebView]")
                    Log.i("CSPlaybackProbe", "WV_E69 [$e69WebView]")

                    // Also test direct host extraction + ExoPlayer + Coil directly
                    val hostLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    withContext(Dispatchers.IO) {
                        SoloLatinoExtractorDispatcher.extract("https://morencius.com/embed/1ywkwa2abeeu", "https://embed69.org/", "SoloLatino · Latino", {}, serverHint = "vidhide") { hostLinks.add(it) }
                        SoloLatinoExtractorDispatcher.extract("https://voe.sx/e/basmjvog9k1o", "https://embed69.org/", "SoloLatino · Latino", {}, serverHint = "voe") { hostLinks.add(it) }
                    }
                    Log.i("CSPlaybackProbe", "HOST_DIRECT count=${hostLinks.size} names=${hostLinks.map { it.name }}")
                    for (lnk in hostLinks.take(4)) {
                        try {
                            play(context, lnk)
                            Log.i("CSPlaybackProbe", "PASS playback host-direct via ${lnk.name}")
                            break
                        } catch (t: Throwable) {
                            Log.i("CSPlaybackProbe", "HOST_PLAY_ERR ${lnk.name}: ${t.javaClass.simpleName}:${t.message?.take(100)}")
                        }
                    }
                    runCatching {
                        verifyCoilImage(context, "tmdb-w500", "https://image.tmdb.org/t/p/w500/zDE9hd1SG9695YncbZGjSf7Z9Jk.jpg", null)
                        Log.i("CSPlaybackProbe", "PASS coil tmdb-w500")
                    }.onFailure {
                        Log.i("CSPlaybackProbe", "FAIL_COIL ${it.message}")
                    }

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

                    // Page 2 check
                    val moviesSec = provider.mainPage.first { it.data == "peliculas?sort=updated" }
                    val moviesP2 = provider.getMainPage(2, MainPageRequest(moviesSec.name, moviesSec.data, moviesSec.horizontalImages))
                    val moviesP2Items = moviesP2?.items?.flatMap { it.list }.orEmpty()
                    check(moviesP2Items.isNotEmpty()) { "empty /peliculas?sort=updated page 2" }

                    // Search & quickSearch
                    val searchSf = provider.search("street fighter", 1) ?: error("null search street fighter")
                    check(searchSf.items.isNotEmpty()) { "empty search street fighter" }
                    val quickDexter = provider.quickSearch("dexter").orEmpty()
                    check(quickDexter.isNotEmpty()) { "empty quickSearch dexter" }
                    Log.i("CSPlaybackProbe", "PASS home+pagination+search (sf=${searchSf.items.size}, quick=${quickDexter.size})")

                    // Load movie & series details
                    val sfUrl = "https://sololatino.net/pelicula/street-fighter"
                    val sfLoad = provider.load(sfUrl) as? MovieLoadResponse
                        ?: error("street-fighter load failed")
                    check(!sfLoad.posterUrl.isNullOrBlank()) { "missing street-fighter poster" }

                    val boysEpUrl = "https://sololatino.net/serie/the-boys-2019/temporada-5/episodio-8"
                    val boysLoad = provider.load(boysEpUrl) as? TvSeriesLoadResponse
                        ?: error("the-boys-2019 load failed")
                    check(boysLoad.episodes.isNotEmpty()) { "the-boys-2019 episodes empty" }

                    val recentEpCards = homePages.first { it.first == "Últimos Episodios" }.second
                    val recentMovieCards = homePages.first { it.first == "Películas Recientes" }.second
                    val latestEpUrl = recentEpCards.first().url
                    val latestMovieUrl = recentMovieCards.first().url
                    val latestMovieLoad = provider.load(latestMovieUrl) as? MovieLoadResponse

                    // Coil poster checks
                    val posterChecks = mutableListOf<Triple<String, String?, Map<String, String>?>>()
                    recentEpCards.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("home-ep-$i", c.posterUrl, c.posterHeaders)) }
                    recentMovieCards.take(3).forEachIndexed { i, c -> posterChecks.add(Triple("home-movie-$i", c.posterUrl, c.posterHeaders)) }
                    searchSf.items.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("search-$i", c.posterUrl, c.posterHeaders)) }
                    posterChecks.add(Triple("load-sf-poster", sfLoad.posterUrl, sfLoad.posterHeaders))
                    sfLoad.backgroundPosterUrl?.let { posterChecks.add(Triple("load-sf-bg", it, sfLoad.posterHeaders)) }
                    posterChecks.add(Triple("load-boys-poster", boysLoad.posterUrl, boysLoad.posterHeaders))
                    for ((label, pUrl, pHeaders) in posterChecks) {
                        verifyCoilImage(context, label, pUrl, pHeaders)
                    }
                    Log.i("CSPlaybackProbe", "PASS coil posters=${posterChecks.size}")

                    // Diagnostic split: inspect raw buttons, PSH alone, and Embed69 alone for street-fighter and the-boys-2019 s5e8
                    val diagTargets = listOf(
                        "sf" to sfLoad.dataUrl,
                        "boys_s5e8" to boysEpUrl,
                    )
                    var pshTotal = 0
                    var e69Total = 0
                    for ((tag, pageUrl) in diagTargets) {
                        val res = withContext(Dispatchers.IO) { provider.client.fetchSite(pageUrl, "${SoloLatinoMarkup.MAIN_URL}/") }
                        val doc = Jsoup.parse(res.text, res.url.ifBlank { pageUrl })
                        val sec = SoloLatinoMarkup.parsePlayerSection(doc, res.url.ifBlank { pageUrl })
                        val (btns, lazy) = withContext(Dispatchers.IO) { provider.client.resolvePlayerSection(pageUrl, sec) }
                        val urls = (btns.mapNotNull { it.url } + listOfNotNull(lazy?.url)).distinct()
                        val pshUrl = urls.firstOrNull { SoloLatinoMarkup.isPelisSeriesHoyUrl(it) }
                        val e69Url = urls.firstOrNull { it.contains("embed69.org", ignoreCase = true) }

                        val pshLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        if (pshUrl != null) {
                            withContext(Dispatchers.IO) {
                                SoloLatinoPelisSeriesHoyExtractor.extractWithClient(provider.client, pshUrl, pageUrl, "Latino", {}, { pshLinks.add(it) })
                            }
                            if (pshLinks.isEmpty()) {
                                val rawPsh = runCatching {
                                    withContext(Dispatchers.IO) { provider.client.resolvePelisSeriesHoy(pshUrl, pageUrl) }
                                }.getOrElse { emptyList() }
                                Log.i("CSPlaybackProbe", "DIAG_PSH_EMPTY $tag url=$pshUrl rawCount=${rawPsh.size} raw=${rawPsh.take(3).map { "${it.label}:${it.type}:u=${it.u?.take(30)}:url=${it.url?.take(30)}:sig=${it.sig != null}" }}")
                            }
                        }
                        val e69Links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        if (e69Url != null) {
                            withContext(Dispatchers.IO) {
                                SoloLatinoEmbed69Resolver.extract(e69Url, pageUrl, "Latino", {}, { e69Links.add(it) }, client = provider.client)
                            }
                            if (e69Links.isEmpty()) {
                                val e69Fetch = runCatching {
                                    withContext(Dispatchers.IO) {
                                        provider.client.fetchExternalHtml(e69Url, pageUrl) { t ->
                                            val h = SoloLatinoMarkup.hostOf(t)
                                            h == "embed69.org" || h.endsWith(".embed69.org")
                                        }
                                    }
                                }.getOrNull()
                                val mirrors = e69Fetch?.text?.let { SoloLatinoMarkup.decryptEmbed69Page(it) }.orEmpty()
                                Log.i("CSPlaybackProbe", "DIAG_E69_EMPTY $tag url=$e69Url code=${e69Fetch?.code} len=${e69Fetch?.text?.length} hasDataLink=${e69Fetch?.text?.contains("dataLink")} mirrors=${mirrors.take(4).map { "${it.serverName}:${it.url.take(35)}" }}")
                            }
                        }
                        pshTotal += pshLinks.size
                        e69Total += e69Links.size
                        Log.i(
                            "CSPlaybackProbe",
                            "DIAG $tag http=${res.code} btns=${btns.size} pshUrl=${pshUrl != null} pshLinks=${pshLinks.size}(${pshLinks.take(3).map { it.name }}) e69Url=${e69Url != null} e69Links=${e69Links.size}(${e69Links.take(4).map { it.name }})",
                        )
                    }

                    // Full end-to-end provider.loadLinks + Media3 playback
                    val playTargets = buildList {
                        add("sf-movie" to sfLoad.dataUrl)
                        add("boys-s5e8" to boysEpUrl)
                        add("latest-ep" to latestEpUrl)
                        if (latestMovieLoad != null && latestMovieLoad.dataUrl != sfLoad.dataUrl) {
                            add("latest-movie" to latestMovieLoad.dataUrl)
                        }
                    }

                    val playErrors = mutableListOf<String>()
                    for ((label, dataUrl) in playTargets) {
                        val batch = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        val ok = withContext(Dispatchers.IO) {
                            provider.loadLinks(dataUrl, false, {}, { batch.add(it) })
                        }
                        val dt = android.os.SystemClock.elapsedRealtime() - t0
                        val links = synchronized(batch) { batch.toList() }
                        Log.i(
                            "CSPlaybackProbe",
                            "loadLinks $label ok=$ok count=${links.size} ms=$dt names=${links.take(6).map { it.name }}",
                        )
                        if (!ok || links.isEmpty()) {
                            playErrors.add("$label: loadLinks empty (ok=$ok, ms=$dt)")
                            continue
                        }
                        var played = false
                        val linkErrs = mutableListOf<String>()
                        for (lnk in links.take(5)) {
                            try {
                                play(context, lnk)
                                played = true
                                Log.i("CSPlaybackProbe", "PASS playback $label via ${lnk.name}")
                                break
                            } catch (t: Throwable) {
                                val msg = generateSequence(t) { it.cause }.take(4).joinToString(" <- ") {
                                    "${it.javaClass.simpleName}:${it.message?.take(100)}"
                                }
                                linkErrs.add("${lnk.name}: $msg")
                            }
                        }
                        if (!played) {
                            playErrors.add("$label: all links failed playback ($linkErrs)")
                        }
                    }

                    check(playErrors.isEmpty()) { "errors=$playErrors pshTotal=$pshTotal e69Total=$e69Total" }
                    check(pshTotal > 0 || e69Total > 0) { "both PSH and Embed69 returned 0 diagnostic links" }
                    Log.i("CSPlaybackProbe", "SUMMARY pshTotal=$pshTotal e69Total=$e69Total targets=${playTargets.size}")
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
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
