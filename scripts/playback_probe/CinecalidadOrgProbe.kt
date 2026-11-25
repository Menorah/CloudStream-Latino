package com.menorah.cinecalidadorg

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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
                    val provider = CinecalidadOrgProvider()
                    val started = android.os.SystemClock.elapsedRealtime()
                    val sections = withContext(Dispatchers.IO) {
                        coroutineScope {
                            provider.mainPage.map { section ->
                                async {
                                    val page = provider.getMainPage(
                                        1,
                                        MainPageRequest(section.name, section.data, section.horizontalImages),
                                    )
                                    section.name to page.items.flatMap { it.list }.size
                                }
                            }.awaitAll()
                        }
                    }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    val emptySections = sections.filter { it.second == 0 }.map { it.first }
                    Log.i("CSPlaybackProbe", "home sections=${sections.size} empty=$emptySections elapsedMs=$elapsed")
                    check(emptySections.size <= 2) { "too many empty home sections: $emptySections" }
                    check(elapsed < 45_000) { "home too slow: $elapsed ms" }

                    val searchText = provider.search("madden")
                    check(searchText.isNotEmpty()) { "empty text search" }
                    val searchYear = provider.search("1995")
                    check(searchYear.isNotEmpty()) { "empty year 1995 search" }

                    val movieUrl = "https://cinecalidad.org/peliculas/perfectos-mentirosos-2026/"
                    val movieLoad = provider.load(movieUrl) ?: error("movie load failed")
                    check(!movieLoad.posterUrl.isNullOrBlank()) { "missing movie poster" }

                    val seriesListing = provider.getMainPage(1, MainPageRequest("Series", "listing:tvshows", false))
                    val firstShowUrl = seriesListing.items.first().list.first().url
                    val seriesLoad = provider.load(firstShowUrl) as? TvSeriesLoadResponse
                        ?: error("series load failed for $firstShowUrl")
                    check(seriesLoad.episodes.isNotEmpty()) { "series episodes empty for $firstShowUrl" }

                    val multiSeasonUrl = "https://cinecalidad.org/series/csi-miami-2002/"
                    val csiLoad = provider.load(multiSeasonUrl) as? TvSeriesLoadResponse
                    if (csiLoad != null) {
                        Log.i("CSPlaybackProbe", "csi episodes=${csiLoad.episodes.size} seasons=${csiLoad.episodes.mapNotNull { it.season }.distinct().size}")
                        check(csiLoad.episodes.size > 150) { "expected multi-season csi episodes, got ${csiLoad.episodes.size}" }
                    }

                    val animeListing = provider.getMainPage(1, MainPageRequest("Animes", "listing:animes", false))
                    val firstAnimeUrl = animeListing.items.first().list.first().url
                    val animeLoad = provider.load(firstAnimeUrl) as? TvSeriesLoadResponse
                        ?: error("anime load failed for $firstAnimeUrl")
                    check(animeLoad.episodes.isNotEmpty()) { "anime episodes empty for $firstAnimeUrl" }

                    val novelListing = provider.getMainPage(1, MainPageRequest("Novelas", "listing:novels", false))
                    val firstNovelUrl = novelListing.items.first().list.first().url
                    val novelLoad = provider.load(firstNovelUrl) ?: error("novel load failed for $firstNovelUrl")
                    val novelPlayData = when (novelLoad) {
                        is TvSeriesLoadResponse -> {
                            check(novelLoad.episodes.isNotEmpty()) { "novel episodes empty for $firstNovelUrl" }
                            novelLoad.episodes.first().data
                        }
                        is MovieLoadResponse -> novelLoad.dataUrl
                        else -> error("unexpected novel LoadResponse: ${novelLoad.javaClass.simpleName}")
                    }

                    val wweListing = provider.getMainPage(1, MainPageRequest("WWE", "listing:wwe", false))
                    val firstWweUrl = wweListing.items.first().list.first().url
                    val wweLoad = provider.load(firstWweUrl) ?: error("wwe load failed for $firstWweUrl")
                    val wwePlayData = when (wweLoad) {
                        is TvSeriesLoadResponse -> {
                            check(wweLoad.episodes.isNotEmpty()) { "wwe episodes empty for $firstWweUrl" }
                            wweLoad.episodes.first().data
                        }
                        is MovieLoadResponse -> wweLoad.dataUrl
                        else -> error("unexpected wwe LoadResponse: ${wweLoad.javaClass.simpleName}")
                    }

                    val castMovieUrl = "https://cinecalidad.org/peliculas/bulldozer-2022/"
                    check(provider.load(castMovieUrl) != null) { "subtitled movie load failed" }

                    val errors = mutableListOf<String>()
                    val targets = mutableListOf(
                        "movie Perfectos mentirosos" to movieUrl,
                        "movie Subtitulado Bulldozer" to castMovieUrl,
                        "series ep1 (${firstShowUrl.substringAfter("/series/").trimEnd('/')})" to seriesLoad.episodes.first().data,
                        "anime ep1 (${firstAnimeUrl.substringAfter("/animes/").trimEnd('/')})" to animeLoad.episodes.first().data,
                        "novel (${firstNovelUrl.substringAfter("/novelas/").trimEnd('/')})" to novelPlayData,
                        "wwe (${firstWweUrl.substringAfter("/wwe/").trimEnd('/')})" to wwePlayData,
                    )
                    csiLoad?.episodes?.firstOrNull()?.data?.let { epData ->
                        targets += "csi-miami ep1" to epData
                    }

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
        provider: CinecalidadOrgProvider,
        label: String,
        data: String,
    ) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(data, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        val errors = mutableListOf<String>()
        for (link in found.take(4)) {
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
}
