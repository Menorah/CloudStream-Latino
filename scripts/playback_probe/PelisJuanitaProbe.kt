package com.menorah.pelisjuanita

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
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import java.util.Collections

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

                    var gateOk = false
                    try {
                        provider.loadLinks(movieLoad.dataUrl, false, {}, {})
                    } catch (e: ErrorLoadingException) {
                        gateOk = e.message.orEmpty().contains("Ajustes") && e.message.orEmpty().contains("PelisJuanita")
                    }
                    check(gateOk) { "expected clear Ajustes verification ErrorLoadingException" }
                    Log.i("CSPlaybackProbe", "PASS unverified movie metadata and verification prompt")

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
                        "Voe" to "https://voe.sx/e/ltvamjdzsvxv",
                        "Savefiles" to "https://savefiles.com/e/lpkobe2ki65z",
                        "Lulustream" to "https://lulustream.com/e/zxfcz3pz68ds",
                        "Vidara" to "https://vidara.to/e/HnuAfGnH5Z97l",
                        "Byse" to "https://bysesukior.com/e/92rgcn88ka85",
                    )

                    val passedServers = mutableListOf<String>()
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
                                passedServers.add("$srv(${link.name})")
                                Log.i("CSPlaybackProbe", "PASS server $srv via ${link.name} frame")
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                errs.add("${link.name}: ${t.javaClass.simpleName}:${t.message?.take(100)}")
                            }
                        }
                        if (!played) failedServers.add("$srv -> $errs")
                    }
                    Log.i("CSPlaybackProbe", "server coverage passed=$passedServers failed=$failedServers")
                    check(passedServers.size >= 3) {
                        "expected >= 3 distinct PelisJuanita servers playing in Media3, got passed=$passedServers failed=$failedServers"
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
