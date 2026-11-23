package com.menorah.cinecalidadam

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.*

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(600_000) {
                    val provider = CinecalidadAMProvider()
                    val started = android.os.SystemClock.elapsedRealtime()
                    val sections = withContext(Dispatchers.IO) { coroutineScope {
                        provider.mainPage.map { section -> async {
                            val page = provider.getMainPage(1, MainPageRequest(section.name, section.data, section.horizontalImages))
                            section.name to page.items.flatMap { it.list }.size
                        } }.awaitAll()
                    } }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    Log.i("CSPlaybackProbe", "home sections=${sections.size} empty=${sections.filter { it.second == 0 }.map { it.first }} elapsedMs=$elapsed")
                    check(sections.all { it.second > 0 }) { "empty home sections: ${sections.filter { it.second == 0 }}" }
                    check(elapsed < 35_000) { "home too slow: $elapsed ms" }
                    check(provider.search("2025").isNotEmpty()) { "year search empty" }
                    check(provider.search("madden").isNotEmpty()) { "empty search" }
                    val movie = "https://www.cinecalidad.am/ver-pelicula/madden/"
                    check(provider.load(movie) != null) { "movie load failed" }
                    val series = provider.load("https://www.cinecalidad.am/ver-serie/el-espectacular-hombre-arana/") as? TvSeriesLoadResponse
                        ?: error("series load failed")
                    check(series.episodes.size >= 26) { "missing episodes: ${series.episodes.size}" }
                    val cases = listOf(
                        Triple("Jeffrey Epstein", "jeffrey-epstein-asquerosamente-rico", 1),
                        Triple("Vivir 100 años", "vivir-100-anos-los-secretos-de-las-zonas-azules", 1),
                        Triple("Pinky y Cerebro", "pinky-y-cerebro", 3),
                        Triple("La Niñera", "la-ninera", 6)
                    )
                    val episodePages = mutableListOf<Pair<String, String>>()
                    for ((label, slug, seasonCount) in cases) {
                        val result = provider.load("https://www.cinecalidad.am/ver-serie/$slug/") as? TvSeriesLoadResponse
                            ?: error("$label series failed to load")
                        check(result.episodes.size >= 4) { "$label missing episodes: ${result.episodes.size}" }
                        check(result.episodes.mapNotNull { it.season }.distinct().sorted() == (1..seasonCount).toList()) {
                            "$label seasons incomplete: ${result.episodes.mapNotNull { it.season }.distinct()}"
                        }
                        val first = result.episodes.firstOrNull { it.season == 1 && it.episode == 1 }
                            ?: error("$label missing 1x1")
                        check(first.data.endsWith("/$slug-1x1/")) { "$label incorrect episode URL: ${first.data}" }
                        Log.i("CSPlaybackProbe", "series $label episodes=${result.episodes.size} seasons=$seasonCount")
                        episodePages.add(label to first.data)
                    }
                    testPlayback(context, provider, "movie", movie)
                    testPlayback(context, provider, "episode TMDB", "https://www.cinecalidad.am/ver-el-episodio/tmdb-3854-s1-e1/")
                    val playbackErrors = mutableListOf<String>()
                    for ((label, episode) in episodePages) {
                        try { testPlayback(context, provider, label, episode) }
                        catch (e: TimeoutCancellationException) { playbackErrors.add("$label: timeout") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { playbackErrors.add("$label: ${e.message}") }
                    }
                    check(playbackErrors.isEmpty()) { "unplayable samples: $playbackErrors" }
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun testPlayback(context: Context, provider: CinecalidadAMProvider, label: String, page: String) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(page, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        var errors = mutableListOf<String>()
        for (link in found.take(4)) {
            try {
                play(context, link)
                Log.i("CSPlaybackProbe", "PASS $label frame and playback")
                return
            } catch (e: TimeoutCancellationException) { errors.add("playback timeout") }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) { errors.add("${t.javaClass.simpleName}: ${t.message}") }
        }
        error("$label no playable link: $errors")
    }

    private suspend fun play(context: Context, link: ExtractorLink) = withTimeout(35_000) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val headers = link.headers.toMutableMap()
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", true) }) headers["Referer"] = link.referer
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val media = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(link.url))
        val player = ExoPlayer.Builder(context).build()
        try {
            player.setVideoSurface(surface)
            player.setMediaSource(media)
            player.prepare(); player.playWhenReady = true
            while (true) {
                player.playerError?.let { throw it }
                if ((player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && player.currentPosition > 1000) break
                delay(250)
            }
            check(player.videoSize.width > 0 && player.videoSize.height > 0) { "no decoded video" }
        } finally { player.release(); surface.release(); texture.release() }
    }
}
