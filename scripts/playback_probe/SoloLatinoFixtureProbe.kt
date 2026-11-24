package com.menorah.sololatino

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.*
import java.util.Collections

/** Diagnostic of an archived Dark player fixture, not a live SoloLatino provider test. */
object SoloLatinoFixtureProbe {
    fun start(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                delay(4000)
                withTimeout(180_000) {
                    val html = String(Base64.decode(archivedFixture, Base64.DEFAULT), Charsets.UTF_8)
                    val servers = withContext(Dispatchers.Default) { Embed69Servers.parse(html) }
                    check(servers.map { it.name } == listOf("vidhide", "streamwish", "voe")) { "unexpected fixture servers" }
                    var playable = 0
                    for (server in servers) {
                        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                        try {
                            withTimeout(35_000) { withContext(Dispatchers.IO) {
                                loadExtractor(server.url, "https://embed69.org/", {}) { links.add(it) }
                            } }
                        } catch (e: TimeoutCancellationException) {
                            Log.i("CSSoloFixture", "${server.name} extractor timeout")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { Log.i("CSSoloFixture", "${server.name} extractor ${e.javaClass.simpleName}") }
                        val found = synchronized(links) { links.toList() }
                        Log.i("CSSoloFixture", "${server.name} extracted=${found.size}")
                        for (link in found.take(3)) {
                            try {
                                play(context, link)
                                Log.i("CSSoloFixture", "${server.name} decoded video")
                                playable++
                                break
                            } catch (e: TimeoutCancellationException) { Log.i("CSSoloFixture", "${server.name} playback timeout") }
                            catch (e: CancellationException) { throw e }
                            catch (e: Throwable) { Log.i("CSSoloFixture", "${server.name} playback ${e.javaClass.simpleName}") }
                        }
                    }
                    check(playable > 0) { "archived fixture: no server played" }
                }
                Log.i("CSSoloFixture", "PASS archived fixture")
            } catch (e: Throwable) {
                Log.e("CSSoloFixture", "FAIL ${e.javaClass.simpleName}: ${e.message}")
            }
        }
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
            player.prepare()
            player.playWhenReady = true
            while (true) {
                player.playerError?.let { throw it }
                if ((player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0 && player.currentPosition > 1000) break
                delay(250)
            }
            check(player.videoSize.width > 0 && player.videoSize.height > 0)
        } finally { player.release(); surface.release(); texture.release() }
    }
}
