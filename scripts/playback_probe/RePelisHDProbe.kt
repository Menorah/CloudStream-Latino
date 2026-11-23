package com.menorah.repelishd

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
                    val provider = RePelisHDProvider()
                    check(provider.search("iron").isNotEmpty()) { "empty search" }
                    val movie = "https://repelishd.surf/ver-pelicula/1726-iron-man-online-espanol"
                    check(provider.load(movie) != null) { "movie load failed" }
                    val series = provider.load("https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol") as? TvSeriesLoadResponse
                        ?: error("series load failed")
                    check(series.episodes.size >= 144 && series.episodes.any { it.season == 13 }) { "missing episodes: ${series.episodes.size}" }
                    testPlayback(context, provider, "movie", movie)
                    testPlayback(context, provider, "episode", "https://repelishd.surf/ver-serie/1413-american-horror-story-online-espanol/temporada-2/episodio-1")
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun testPlayback(context: Context, provider: RePelisHDProvider, label: String, page: String) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(page, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        if (label == "movie") check(listOf("Latino", "Castellano", "Subtitulado").all { language -> found.any { it.name.contains("[$language]") } }) { "missing movie languages: ${found.map { it.name }}" }
        val groups = if (label == "movie") listOf("Latino", "Castellano", "Subtitulado") else listOf("Latino")
        for (language in groups) {
            val errors = mutableListOf<String>()
            var played = false
            for (link in found.filter { it.name.contains("[$language]") }.take(4)) {
                try {
                    play(context, link)
                    Log.i("CSPlaybackProbe", "PASS $label $language frame and playback")
                    played = true
                    break
                } catch (e: TimeoutCancellationException) { errors.add("playback timeout") }
                catch (e: CancellationException) { throw e }
                catch (t: Throwable) { errors.add(generateSequence(t) { it.cause }.take(4).joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message?.take(100)}" }) }
            }
            check(played) { "$label $language no playable link: $errors" }
        }
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
