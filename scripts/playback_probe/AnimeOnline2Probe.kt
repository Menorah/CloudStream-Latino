package com.menorah.animeonline2

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
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
                    val provider = AnimeOnline2Provider()
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
                    check(emptySections.isEmpty()) { "empty home sections: $emptySections" }
                    check(elapsed < 45_000) { "home too slow: $elapsed ms" }

                    val searchNaruto = provider.search("naruto", 1) ?: error("null search naruto")
                    check(searchNaruto.items.isNotEmpty()) { "empty naruto search" }
                    val searchP2 = provider.search("a", 2) ?: error("null search a page 2")
                    check(searchP2.items.isNotEmpty()) { "empty search a page 2" }

                    val latestEpListing = provider.getMainPage(1, MainPageRequest("Últimos Episodios", "archive:/episodio/", false))
                    val firstEpCardUrl = latestEpListing.items.first().list.first().url
                    val epParentLoad = provider.load(firstEpCardUrl) as? AnimeLoadResponse
                        ?: error("latest episode load failed for $firstEpCardUrl")
                    val epList1 = epParentLoad.episodes[DubStatus.Subbed].orEmpty()
                    check(epList1.isNotEmpty()) { "promoted parent series episodes empty for $firstEpCardUrl" }
                    check(!epParentLoad.posterUrl.isNullOrBlank()) { "missing series poster" }

                    val seasonListing = provider.getMainPage(1, MainPageRequest("Temporadas", "archive:/temporada/", false))
                    val firstSeasonUrl = seasonListing.items.first().list.first().url
                    val seasonLoad = provider.load(firstSeasonUrl) as? AnimeLoadResponse
                        ?: error("season load failed for $firstSeasonUrl")
                    check(seasonLoad.episodes[DubStatus.Subbed].orEmpty().isNotEmpty()) { "season episodes empty for $firstSeasonUrl" }

                    val movieUrl = "https://ww4.animeonline.ninja/pelicula/all-you-need-is-kill/"
                    val movieLoad = provider.load(movieUrl) as? MovieLoadResponse
                        ?: error("movie load failed for $movieUrl")
                    check(!movieLoad.posterUrl.isNullOrBlank()) { "missing movie poster" }

                    val dbdLoad = provider.load("https://ww4.animeonline.ninja/online/dragon-ball-daima-070825/") as? AnimeLoadResponse
                        ?: error("dragon ball daima load failed")
                    val dbdEp1 = dbdLoad.episodes[DubStatus.Subbed].orEmpty().firstOrNull()
                        ?: error("dragon ball daima episodes empty")

                    val targets = listOf(
                        "latest ep (${firstEpCardUrl.substringAfter("/episodio/").trimEnd('/')})" to epList1.last().data,
                        "dragon-ball-daima ep1" to dbdEp1.data,
                        "movie All You Need Is Kill" to movieLoad.dataUrl,
                    )

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
        provider: AnimeOnline2Provider,
        label: String,
        data: String,
    ) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(data, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        val errors = mutableListOf<String>()
        for (link in found.take(6)) {
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
