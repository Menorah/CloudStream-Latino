package com.menorah.pelicinehd

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections

// Temporary verification harness injected during emulator playback probe only.
object PlaybackProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(240_000) {
                    val provider = PeliCineHDProvider(context)
                    check(provider.mainPage.size >= 25) { "unexpected mainPage count: ${provider.mainPage.size}" }
                    Log.i("CSPlaybackProbe", "provider=${provider.name} sections=${provider.mainPage.size}")

                    testEmbed(context, "morencius", "https://morencius.com/embed/15tgiexsgp7h")
                    testEmbed(context, "minochinos", "https://minochinos.com/embed/td9hewhsozc5")

                    // Diagnostic check on hls4 (PNG-masked segments) vs hls2 so we know Media3 behavior empirically.
                    val hls4Result = runCatching {
                        val res = withContext(Dispatchers.IO) { app.get("https://morencius.com/embed/15tgiexsgp7h", referer = "https://pelicinehd.com/", timeout = 15) }
                        val parsed = EarnVidsParser.parse(res.text, res.url)
                        val hls4 = parsed.streams.firstOrNull { it.key == "hls4" } ?: error("no hls4")
                        val headers = mapOf("Origin" to parsed.finalOrigin, "Referer" to "${parsed.finalOrigin}/", "User-Agent" to SiteClient.FALLBACK_CHROME_UA)
                        playUrl(context, hls4.url, headers)
                        "playable"
                    }.getOrElse { "${it.javaClass.simpleName}:${it.message}" }
                    Log.i("CSPlaybackProbe", "hls4_check=$hls4Result")
                }
                Log.i("CSPlaybackProbe", "PASS all")
            } catch (t: Throwable) {
                Log.e("CSPlaybackProbe", "FAIL ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    private suspend fun testEmbed(context: Context, label: String, url: String) {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val ok = withContext(Dispatchers.IO) {
            loadExtractor(url, "https://pelicinehd.com/", {}, { links.add(it) })
        }
        val found = synchronized(links) { links.toList() }
        Log.i("CSPlaybackProbe", "$label ok=$ok extracted=${found.size} sources=${found.map { it.source }} urls=${found.map { it.url.take(65) }}")
        check(ok && found.isNotEmpty()) { "$label produced no links" }
        val errors = mutableListOf<String>()
        for (link in found.take(3)) {
            try {
                val headers = link.headers.toMutableMap()
                if (link.referer.isNotBlank() && headers.keys.none { it.equals("Referer", true) }) {
                    headers["Referer"] = link.referer
                }
                playUrl(context, link.url, headers)
                Log.i("CSPlaybackProbe", "PASS $label frame and playback quality=${link.quality}")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                errors.add("${t.javaClass.simpleName}: ${t.message}")
            }
        }
        error("$label no playable link: $errors")
    }

    private suspend fun playUrl(context: Context, url: String, headers: Map<String, String>) = withTimeout(30_000) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val media = HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
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
