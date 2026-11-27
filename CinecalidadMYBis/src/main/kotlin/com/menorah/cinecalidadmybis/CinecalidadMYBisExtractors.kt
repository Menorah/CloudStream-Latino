package com.menorah.cinecalidadmybis

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * cinecalidad.my serves OnlineFilemoon IDs via https://bysedikamoum.com/d/<id> (or /e/<id>),
 * which runs the ByseSX /api/videos/<id>/embed/details -> /playback AES-GCM flow.
 */
class CinecalidadMYBysedikamoumExtractor : ByseSX() {
    override val name = "Filemoon"
    override val mainUrl = "https://bysedikamoum.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val emitted = AtomicBoolean(false)
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            emitted.set(true)
            callback(link)
        }
        try {
            super.getUrl(url, referer, subtitleCallback, trackingCallback)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Fall through to /e/ variant if /d/ failed.
        }
        if (!emitted.get() && url.contains("/d/", ignoreCase = true)) {
            val embedVariant = url.replace("/d/", "/e/", ignoreCase = true)
            try {
                super.getUrl(embedVariant, referer, subtitleCallback, trackingCallback)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Let provider fallback try alternate mirrors if playback fails on this host.
            }
        }
    }
}
