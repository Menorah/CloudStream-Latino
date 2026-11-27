package com.menorah.cinecalidadmybis

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.extractors.ByseSX
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException

/**
 * cinecalidad.my serves OnlineFilemoon IDs through https://bysedikamoum.com/e/<id>,
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
        try {
            super.getUrl(url, referer, subtitleCallback, callback)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Let provider fallback try alternate mirrors if playback fails on this host.
        }
    }
}
