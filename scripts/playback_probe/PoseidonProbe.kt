package com.menorah.poseidon

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.*

// Added to a temporary build only; never part of the published plugin.
object PlaybackProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(240_000) {
                    val provider = PoseidonProvider()
                    check(provider.search("mentes").isNotEmpty()) { "empty search" }
                    val movie = "https://www.poseidonhd2.co/pelicula/9909/mentes-peligrosas"
                    check(provider.load(movie) != null) { "movie load failed" }
                    val series = provider.load("https://www.poseidonhd2.co/serie/223397/ciudad-de-dios-la-lucha-no-para") as? TvSeriesLoadResponse
                        ?: error("series load failed")
                    check(series.episodes.any { it.season == 2 && it.episode == 3 }) { "missing episodes: ${series.episodes.size}" }
                    testPlayback(context, provider, "movie", movie)
                    testPlayback(context, provider, "episode", "https://www.poseidonhd2.co/serie/223397/ciudad-de-dios-la-lucha-no-para/temporada/2/episodio/3")
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun testPlayback(context: Context, provider: PoseidonProvider, label: String, page: String) {
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
