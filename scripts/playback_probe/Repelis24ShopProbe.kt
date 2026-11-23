package com.menorah.repelis24shop

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
                    val provider = Repelis24ShopProvider()
                    check(provider.search("iron man").isNotEmpty()) { "empty search" }
                    val movie = "https://repelis24.shop/pelicula/la-odisea/"
                    check(provider.load(movie) != null) { "movie load failed" }
                    val series = provider.load("https://repelis24.shop/serie/the-terror/") as? TvSeriesLoadResponse
                        ?: error("series load failed")
                    check(series.episodes.any { it.season == 2 && it.episode == 3 }) { "missing episodes: ${series.episodes.size}" }
                    val samples = listOf(
                        Triple("movie La Odisea", movie, null),
                        Triple("VOE La Odisea", movie, "Voe"),
                        Triple("episode Mobland", "https://repelis24.shop/episodio/mobland-1x1/", null),
                        Triple("episode The Terror", "https://repelis24.shop/episodio/the-terror-1x1/", null),
                        Triple("movie Silent Night", "https://repelis24.shop/pelicula/silent-night-venganza-silenciosa-zsbl/", null),
                        Triple("movie Iron Man", "https://repelis24.shop/pelicula/iron-man-zepn/", null)
                    )
                    val errors = mutableListOf<String>()
                    for ((label, url, host) in samples) {
                        try { testPlayback(context, provider, label, url, host) }
                        catch (e: TimeoutCancellationException) { errors.add("$label: timeout") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { errors.add("$label: ${e.message}") }
                    }
                    check(errors.isEmpty()) { "unavailable samples: $errors" }
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun testPlayback(context: Context, provider: Repelis24ShopProvider, label: String, page: String, host: String? = null) {
        val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) { provider.loadLinks(page, false, {}, { links.add(it) }) }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label extracted=${found.size} labels=${found.map { it.name }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        var errors = mutableListOf<String>()
        val candidates = if (host == null) found else found.filter { it.name.contains(host, ignoreCase = true) }
        check(candidates.isNotEmpty()) { "$label produced no $host links" }
        for (link in candidates.take(6)) {
            try {
                play(context, link)
                Log.i("CSPlaybackProbe", "PASS $label frame and playback")
                return
            } catch (e: TimeoutCancellationException) { errors.add("playback timeout") }
            catch (e: CancellationException) { throw e }
            catch (t: Throwable) { errors.add("${link.name}: " + generateSequence(t) { it.cause }.take(5).joinToString(" -> ") { "${it.javaClass.simpleName}: ${it.message?.take(150)}" }) }
        }
        error("$label no playable link: $errors")
    }

    private suspend fun play(context: Context, link: ExtractorLink) = withTimeout(20_000) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val headers = link.headers.toMutableMap()
        if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", true) }) headers["Referer"] = link.referer
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(link.url).apply {
            if (link.type == ExtractorLinkType.M3U8) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val media = DefaultMediaSourceFactory(factory).createMediaSource(item)
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
