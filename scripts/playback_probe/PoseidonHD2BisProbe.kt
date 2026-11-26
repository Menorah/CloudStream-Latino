package com.menorah.poseidonhd2bis

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
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
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(660_000) {
                    val provider = PoseidonHD2BisProvider()
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

                    // Check page 2 of main catalogs
                    for (route in listOf("/peliculas", "/series", "/episodios", "/genero/suspenso")) {
                        val section = provider.mainPage.first { it.data == route }
                        val p1 = homePages.first { it.first == section.name }.second
                        val p2 = provider.getMainPage(2, MainPageRequest(section.name, section.data, section.horizontalImages))
                        val p2Items = p2?.items?.flatMap { it.list }.orEmpty()
                        check(p2Items.isNotEmpty()) { "empty page 2 on $route" }
                        check(p2Items.first().url != p1.first().url) { "page 2 duplicated page 1 on $route" }
                    }
                    Log.i("CSPlaybackProbe", "PASS pagination on /peliculas, /series, /episodios, /genero/suspenso")

                    // Check search (single page)
                    val searchMatrix = provider.search("matrix", 1) ?: error("null search matrix")
                    check(searchMatrix.items.isNotEmpty()) { "empty matrix search" }
                    check(!searchMatrix.hasNext) { "search unexpectedly claimed hasNext=true" }
                    val searchPage2 = provider.search("matrix", 2)
                    check(searchPage2 == null || searchPage2.items.isEmpty()) { "expected empty page 2 for single-page search" }

                    val recentEpCards = homePages.first { it.first == "Últimos Episodios" }.second
                    val movieCards = homePages.first { it.first == "Películas" }.second
                    val seriesCards = homePages.first { it.first == "Series" }.second

                    // Latest episode promoted to parent TvSeries
                    val firstEpCardUrl = recentEpCards.first().url
                    val epParentLoad = provider.load(firstEpCardUrl) as? TvSeriesLoadResponse
                        ?: error("latest episode load failed for $firstEpCardUrl")
                    check(epParentLoad.episodes.isNotEmpty()) { "promoted parent series episodes empty for $firstEpCardUrl" }
                    check(!epParentLoad.posterUrl.isNullOrBlank()) { "missing series poster" }

                    // Multi-season series check (La Casa del Dragón)
                    val hotdUrl = "${PoseidonHD2BisData.MAIN_URL}/serie/84773/la-casa-del-dragon"
                    val hotdLoad = provider.load(hotdUrl) as? TvSeriesLoadResponse
                        ?: error("La Casa del Dragón load failed")
                    check(hotdLoad.episodes.size >= 10) { "expected >= 10 episodes on La Casa del Dragón" }
                    check(hotdLoad.episodes.all { (it.season ?: 0) > 0 }) { "empty season 0 leaked into episodes" }

                    // Classic Movie check (Matrix) & Hunger Games & Warrior
                    val matrixUrl = "${PoseidonHD2BisData.MAIN_URL}/pelicula/603/the-matrix"
                    val matrixLoad = provider.load(matrixUrl) as? MovieLoadResponse
                        ?: error("Matrix load failed")
                    check(!matrixLoad.posterUrl.isNullOrBlank()) { "missing Matrix poster" }
                    check(matrixLoad.duration != null && matrixLoad.duration!! > 100) { "missing Matrix duration" }
                    check(!matrixLoad.recommendations.isNullOrEmpty()) { "missing Matrix recommendations" }

                    val warriorUrl = "${PoseidonHD2BisData.MAIN_URL}/pelicula/1280115/el-camino-del-pequeno-guerrero"
                    val warriorLoad = provider.load(warriorUrl) as? MovieLoadResponse

                    // Recent Movie from Home
                    val latestMovieCard = movieCards.first()
                    val latestMovieLoad = provider.load(latestMovieCard.url) as? MovieLoadResponse
                        ?: error("latest movie load failed for ${latestMovieCard.url}")
                    check(!latestMovieLoad.posterUrl.isNullOrBlank()) { "missing latest movie poster" }

                    // Diagnostic trace for embed resolution (single movie to avoid rate limits)
                    diagnoseWatchPage("diag-warrior", warriorUrl)

                    // Verify Coil image loading across home, search, and load responses
                    val posterChecks = mutableListOf<Triple<String, String?, Map<String, String>?>>()
                    recentEpCards.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("home-ep-$i", c.posterUrl, c.posterHeaders)) }
                    movieCards.take(3).forEachIndexed { i, c -> posterChecks.add(Triple("home-movie-$i", c.posterUrl, c.posterHeaders)) }
                    seriesCards.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("home-series-$i", c.posterUrl, c.posterHeaders)) }
                    searchMatrix.items.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("search-matrix-$i", c.posterUrl, c.posterHeaders)) }
                    posterChecks.add(Triple("load-epParent-poster", epParentLoad.posterUrl, epParentLoad.posterHeaders))
                    posterChecks.add(Triple("load-hotd-poster", hotdLoad.posterUrl, hotdLoad.posterHeaders))
                    posterChecks.add(Triple("load-hotd-bg", hotdLoad.backgroundPosterUrl, hotdLoad.posterHeaders))
                    posterChecks.add(Triple("load-matrix-poster", matrixLoad.posterUrl, matrixLoad.posterHeaders))
                    posterChecks.add(Triple("load-matrix-bg", matrixLoad.backgroundPosterUrl, matrixLoad.posterHeaders))
                    posterChecks.add(Triple("load-latest-movie-poster", latestMovieLoad.posterUrl, latestMovieLoad.posterHeaders))

                    for ((label, pUrl, pHeaders) in posterChecks) {
                        verifyCoilImage(context, label, pUrl, pHeaders)
                    }
                    Log.i("CSPlaybackProbe", "PASS coil posters=${posterChecks.size}")

                    val targets = mutableListOf(
                        "latest ep (${firstEpCardUrl.substringAfter("/serie/")})" to epParentLoad.episodes.last().data,
                        "hotd s1e1" to hotdLoad.episodes.first().data,
                        "latest movie (${latestMovieCard.name})" to latestMovieLoad.dataUrl,
                    )
                    if (warriorLoad != null) {
                        targets += "warrior" to warriorLoad.dataUrl
                    }

                    val errors = mutableListOf<String>()
                    for ((label, data) in targets) {
                        try {
                            testPlayback(context, provider, label, data)
                        } catch (e: TimeoutCancellationException) {
                            errors.add("$label: timeout")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            errors.add("$label: ${t.message}")
                        }
                    }

                    val coverageData = listOfNotNull(
                        latestMovieLoad.dataUrl,
                        warriorLoad?.dataUrl,
                        epParentLoad.episodes.last().data,
                    )
                    verifyServerCoverage(context, provider, coverageData)

                    check(errors.isEmpty()) { "playback failures: $errors" }
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun diagnoseWatchPage(tag: String, url: String) = withContext(Dispatchers.IO) {
        withTimeoutOrNull(45_000L) {
            runCatching {
                val html = app.get(url, headers = mapOf("User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT), timeout = 12L).text
                val vids = PoseidonHD2BisData.parseWatchPageVideos(html)
                val summary = vids.filterNot { it.cyberlocker == "netu" }.take(5).map { v ->
                    withTimeoutOrNull(14_000L) {
                        val pRes = runCatching {
                            app.get(v.playerUrl, referer = url, headers = mapOf("User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT), timeout = 8L)
                        }.getOrNull()
                        val emb = pRes?.let { PoseidonHD2BisData.extractPlayerEmbedUrl(it.text) }
                        var detail = "no-emb"
                        val c = mutableListOf<String>()
                        if (emb != null) {
                            val lower = emb.lowercase()
                            val baseHeaders = mapOf("User-Agent" to PoseidonHD2BisData.DEFAULT_USER_AGENT)
                            val t0 = android.os.SystemClock.elapsedRealtime()
                            runCatching {
                                when {
                                    v.cyberlocker == "voesx" || lower.contains("voe") -> {
                                        PoseidonVoeExtractor.extract(emb, "https://player.poseidonhd2.co/", "Voe", {}) { c += it.url }
                                        val dt = android.os.SystemClock.elapsedRealtime() - t0
                                        if (c.isEmpty()) {
                                            val r1 = app.get(emb, referer = "https://player.poseidonhd2.co/", headers = baseHeaders, timeout = 6L)
                                            detail = "ms=$dt:r1=${r1.code}:${r1.url.take(35)}:alt=${r1.text.contains("altcha-widget")}:head=${r1.text.replace(Regex("\\s+"), " ").take(120)}"
                                        } else {
                                            detail = "ms=$dt:ok"
                                        }
                                    }
                                    v.cyberlocker == "doodstream" || lower.contains("dood") -> {
                                        val r1 = app.get(emb.replace("/d/", "/e/"), headers = baseHeaders, timeout = 8L)
                                        val hasMd5 = r1.text.contains("/pass_md5/")
                                        val bodyPart = r1.text.substringAfter("</head>", r1.text).replace(Regex("\\s+"), " ").take(1200)
                                        loadExtractor(emb, "https://player.poseidonhd2.co/", {}) { c += it.url }
                                        detail = "r1=${r1.code}:${r1.url.take(35)}:len=${r1.text.length}:md5=$hasMd5:body=$bodyPart"
                                    }
                                    v.cyberlocker == "streamwish" || lower.contains("streamwish") -> {
                                        PoseidonStreamWishExtractor.extract(emb, "https://player.poseidonhd2.co/", "StreamWish", {}) { c += it.url }
                                        val dt = android.os.SystemClock.elapsedRealtime() - t0
                                        detail = "ms=$dt:first=${c.firstOrNull()?.take(90)}"
                                    }
                                    v.cyberlocker == "vidhide" || lower.contains("vidhide") -> {
                                        PoseidonVidHideExtractor.extract(emb, "https://player.poseidonhd2.co/", "VidHide", {}) { c += it.url }
                                        val dt = android.os.SystemClock.elapsedRealtime() - t0
                                        detail = "ms=$dt"
                                    }
                                    else -> {
                                        loadExtractor(emb, "https://player.poseidonhd2.co/", {}) { c += it.url }
                                        detail = "other"
                                    }
                                }
                            }.onFailure { detail = "EXC:${it.javaClass.simpleName}:${it.message?.take(60)}" }
                        }
                        "${v.languageLabel}/${v.cyberlocker}:n=${c.size}:[$detail]"
                    } ?: "${v.languageLabel}/${v.cyberlocker}:TO"
                }
                Log.i("CSPlaybackProbe", "$tag -> $summary")
            }.onFailure {
                Log.i("CSPlaybackProbe", "$tag err=${it.message}")
            }
        }
    }

    private suspend fun verifyServerCoverage(
        context: Context,
        provider: PoseidonHD2BisProvider,
        dataUrls: List<String>,
    ) {
        val allLinks = mutableListOf<ExtractorLink>()
        for (url in dataUrls) {
            val batch = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
            withContext(Dispatchers.IO) { provider.loadLinks(url, false, {}, { batch.add(it) }) }
            synchronized(batch) { allLinks.addAll(batch) }
        }
        val servers = listOf("VidHide", "Voe", "Vidara", "DoodStream", "StreamWish")
        val passed = mutableListOf<String>()
        val serverFailures = mutableListOf<String>()
        for (srv in servers) {
            val candidates = allLinks.filter { it.name.contains(srv, ignoreCase = true) || it.source.contains(srv, ignoreCase = true) }
            if (candidates.isEmpty()) {
                Log.i("CSPlaybackProbe", "server $srv: not present in sample items")
                continue
            }
            var ok = false
            val errs = mutableListOf<String>()
            for (link in candidates.take(2)) {
                try {
                    play(context, link)
                    ok = true
                    passed.add("$srv(${link.name})")
                    Log.i("CSPlaybackProbe", "PASS server $srv via ${link.name}")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    errs.add("${link.name}: ${t.javaClass.simpleName}:${t.message?.take(100)}")
                }
            }
            if (!ok) {
                serverFailures.add("$srv -> $errs")
            }
        }
        Log.i("CSPlaybackProbe", "server coverage passed=$passed failed=$serverFailures")
        check(passed.size >= 2) { "expected >= 2 distinct servers playing in Media3, got passed=$passed failed=$serverFailures" }
    }

    private suspend fun testPlayback(
        context: Context,
        provider: PoseidonHD2BisProvider,
        label: String,
        data: String,
    ) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(data, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        val errors = mutableListOf<String>()
        for (link in found.take(8)) {
            try {
                play(context, link)
                Log.i("CSPlaybackProbe", "PASS $label (${link.name}) frame and playback")
                return
            } catch (e: TimeoutCancellationException) {
                errors.add("${link.name}: playback timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                errors.add(
                    "${link.name}: " + generateSequence(t) { it.cause }.take(5).joinToString(" -> ") {
                        "${it.javaClass.simpleName}: ${it.message?.take(140)}"
                    },
                )
            }
        }
        error("$label no playable link: $errors")
    }

    private suspend fun play(context: Context, link: ExtractorLink) = withTimeout(30_000) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val headers = link.headers.toMutableMap()
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", true) }) {
            headers["Referer"] = link.referer
        }
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
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
                }.build()
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
