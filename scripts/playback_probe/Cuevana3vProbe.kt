package com.menorah.cuevana3v

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.*

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(600_000) {
                    val provider = Cuevana3vProvider()
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
                    check(elapsed < 45_000) { "home too slow: $elapsed ms" }

                    // Check page 2 of main catalog
                    val moviesSection = provider.mainPage.first { it.data == "/peliculas" }
                    val moviesP2 = provider.getMainPage(2, MainPageRequest(moviesSection.name, moviesSection.data, moviesSection.horizontalImages))
                    val moviesP2Items = moviesP2?.items?.flatMap { it.list }.orEmpty()
                    if (moviesP2Items.isEmpty()) {
                        val rawP2 = withContext(Dispatchers.IO) {
                            com.lagradost.cloudstream3.app.get(
                                "https://cuevana3v.pro/peliculas?page=2",
                                referer = "https://cuevana3v.pro/",
                                headers = mapOf("User-Agent" to Cuevana3vMarkup.DEFAULT_USER_AGENT),
                                timeout = 20,
                            )
                        }
                        error("empty /peliculas page 2 (http=${rawP2.code}, len=${rawP2.text.length}, parsed=${Cuevana3vMarkup.parseCatalogItems(rawP2.text).size})")
                    }

                    val searchMatrix = provider.search("matrix", 1) ?: error("null search matrix")
                    check(searchMatrix.items.isNotEmpty()) { "empty matrix search" }
                    val searchLaP2 = provider.search("la", 2) ?: error("null search la page 2")
                    check(searchLaP2.items.isNotEmpty()) { "empty search la page 2" }

                    val recentEpCards = homePages.first { it.first == "Últimos Episodios" }.second
                    val movieCards = homePages.first { it.first == "Películas Online" }.second
                    val seriesCards = homePages.first { it.first == "Series Online" }.second

                    // Latest episode promoted to parent TvSeries
                    val firstEpCardUrl = recentEpCards.first().url
                    val epParentLoad = provider.load(firstEpCardUrl) as? TvSeriesLoadResponse
                        ?: error("latest episode load failed for $firstEpCardUrl")
                    check(epParentLoad.episodes.isNotEmpty()) { "promoted parent series episodes empty for $firstEpCardUrl" }
                    check(!epParentLoad.posterUrl.isNullOrBlank()) { "missing series poster" }

                    // Multi-season series check (Dexter: Resurrección)
                    val dexterUrl = "https://cuevana3v.pro/serie/dexter-resurreccion"
                    val dexterLoad = provider.load(dexterUrl) as? TvSeriesLoadResponse
                        ?: error("dexter series load failed")
                    check(dexterLoad.episodes.size >= 4) { "expected multi-episode series on Dexter" }

                    // Movie check (Los Juegos del Hambre: Amanecer en la cosecha)
                    val hungerUrl = "https://cuevana3v.pro/pelicula/los-juegos-del-hambre-amanecer-en-la-cosecha"
                    val hungerLoad = provider.load(hungerUrl) as? MovieLoadResponse
                        ?: error("hunger movie load failed")
                    check(!hungerLoad.posterUrl.isNullOrBlank()) { "missing movie poster" }
                    check(hungerLoad.duration != null && hungerLoad.duration!! > 60) { "missing movie duration" }

                    // Movie 2 check (Matrix Resurrections — 3 Spanish variants)
                    val matrixUrl = "https://cuevana3v.pro/pelicula/matrix-resurrections"
                    val matrixLoad = provider.load(matrixUrl) as? MovieLoadResponse
                        ?: error("matrix movie load failed")

                    // Verify Coil image loading across home, search, and load responses
                    val posterChecks = mutableListOf<Triple<String, String?, Map<String, String>?>>()
                    recentEpCards.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("home-ep-$i", c.posterUrl, c.posterHeaders)) }
                    movieCards.take(3).forEachIndexed { i, c -> posterChecks.add(Triple("home-movie-$i", c.posterUrl, c.posterHeaders)) }
                    seriesCards.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("home-series-$i", c.posterUrl, c.posterHeaders)) }
                    searchMatrix.items.take(2).forEachIndexed { i, c -> posterChecks.add(Triple("search-matrix-$i", c.posterUrl, c.posterHeaders)) }
                    posterChecks.add(Triple("load-epParent-poster", epParentLoad.posterUrl, epParentLoad.posterHeaders))
                    posterChecks.add(Triple("load-dexter-poster", dexterLoad.posterUrl, dexterLoad.posterHeaders))
                    posterChecks.add(Triple("load-dexter-bg", dexterLoad.backgroundPosterUrl, dexterLoad.posterHeaders))
                    posterChecks.add(Triple("load-hunger-poster", hungerLoad.posterUrl, hungerLoad.posterHeaders))
                    posterChecks.add(Triple("load-hunger-bg", hungerLoad.backgroundPosterUrl, hungerLoad.posterHeaders))

                    for ((label, pUrl, pHeaders) in posterChecks) {
                        verifyCoilImage(context, label, pUrl, pHeaders)
                    }
                    Log.i("CSPlaybackProbe", "PASS coil posters=${posterChecks.size}")

                    val targets = listOf(
                        Triple("latest ep (${firstEpCardUrl.substringAfter("/serie/")})", epParentLoad.episodes.last().data, null),
                        Triple("dexter s2e4", dexterLoad.episodes.last().data, null),
                        Triple("movie hunger (Hyper)", hungerLoad.dataUrl, "Hyper"),
                        Triple("movie hunger (Nebula)", hungerLoad.dataUrl, "Nebula"),
                        Triple("movie matrix", matrixLoad.dataUrl, null),
                    )

                    val errors = mutableListOf<String>()
                    for ((label, data, requiredServer) in targets) {
                        try {
                            testPlayback(context, provider, label, data, requiredServer)
                        } catch (e: TimeoutCancellationException) {
                            errors.add("$label: timeout")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            errors.add("$label: ${t.message}")
                        }
                    }
                    check(errors.isEmpty()) { "playback failures: $errors" }
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun testPlayback(
        context: Context,
        provider: Cuevana3vProvider,
        label: String,
        data: String,
        requiredServer: String? = null,
    ) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(data, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        val candidates = if (requiredServer != null) {
            found.filter { it.name.contains(requiredServer, ignoreCase = true) }.also {
                check(it.isNotEmpty()) { "$label missing required server $requiredServer in ${found.map { l -> l.name }}" }
            }
        } else {
            found
        }
        val errors = mutableListOf<String>()
        for (link in candidates.take(6)) {
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
