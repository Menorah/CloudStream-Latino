package com.menorah.pelis182

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull

open class PaulinitoExtractor : ExtractorApi() {
    override var name = "Paulinito"
    override var mainUrl = "https://paulinito.com"
    override val requiresReferer = true

    companion object {
        private val DEMUXED_AUDIO_REGEX = Regex("""#EXT-X-MEDIA:[^\n]*TYPE=AUDIO""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        extractWithDisplayName(
            url = url,
            referer = referer,
            displayName = name,
            subtitleCallback = subtitleCallback,
            callback = callback,
        )
    }

    internal suspend fun extractWithDisplayName(
        url: String,
        referer: String?,
        displayName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val cleanUrl = url.trim().substringBefore('#').trim()
        if (!cleanUrl.startsWith("http", ignoreCase = true)) return false

        val playerOrigin = Pelis182Markup.hostBaseUrl(cleanUrl, mainUrl).trimEnd('/')
        val pageReferer = referer?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: "${Pelis182Markup.MAIN_URL}/"

        val pageHeaders = mapOf(
            "User-Agent" to Pelis182Markup.DEFAULT_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            "Referer" to pageReferer,
        )

        val res = runCatching {
            app.get(cleanUrl, referer = pageReferer, headers = pageHeaders, timeout = 15L)
        }.getOrNull() ?: return false
        if (!res.isSuccessful) return false

        val config = Pelis182Markup.parsePaulinitoConfig(res.text)
        if (config.sources.isEmpty()) return false

        val streamReferer = "$playerOrigin/"
        val streamHeaders = mapOf(
            "User-Agent" to Pelis182Markup.DEFAULT_USER_AGENT,
            "Referer" to streamReferer,
        )

        for (track in config.tracks) {
            subtitleCallback(
                newSubtitleFile(lang = track.label, url = track.file) {
                    this.headers = streamHeaders
                },
            )
        }

        var emitted = false
        for (src in config.sources) {
            val isHls = src.file.contains(".m3u8", ignoreCase = true) ||
                src.type?.contains("mpegURL", ignoreCase = true) == true
            val labelSuffix = src.label?.takeIf {
                it.isNotBlank() && !it.equals("HD", ignoreCase = true) && !it.equals("Auto", ignoreCase = true)
            }
            val linkName = if (labelSuffix != null) "$displayName ($labelSuffix)" else displayName

            if (isHls) {
                val ok = emitHlsStream(
                    sourceName = name,
                    displayName = linkName,
                    masterUrl = src.file,
                    referer = streamReferer,
                    headers = streamHeaders,
                    fallbackQuality = Qualities.Unknown.value,
                    fallbackMaxQuality = getQualityFromName(src.label),
                    callback = callback,
                )
                if (ok) emitted = true
            } else {
                callback(
                    newExtractorLink(name, linkName, src.file, INFER_TYPE) {
                        this.referer = streamReferer
                        this.headers = streamHeaders
                        this.quality = getQualityFromName(src.label)
                    },
                )
                emitted = true
            }
        }
        return emitted
    }

    private suspend fun emitHlsStream(
        sourceName: String,
        displayName: String,
        masterUrl: String,
        referer: String,
        headers: Map<String, String>,
        fallbackQuality: Int,
        fallbackMaxQuality: Int = fallbackQuality,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playlistText = withTimeoutOrNull(6_000L) {
            runCatching {
                app.get(masterUrl, referer = referer, headers = headers, verify = false, timeout = 5L)
                    .takeIf { it.isSuccessful }
                    ?.text
            }.getOrNull()
        }

        if (!playlistText.isNullOrBlank() && playlistText.contains("#EXTM3U")) {
            val maxHeight = RESOLUTION_HEIGHT_REGEX.findAll(playlistText)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull() ?: fallbackMaxQuality.takeIf { it > 0 } ?: Qualities.Unknown.value

            if (DEMUXED_AUDIO_REGEX.containsMatchIn(playlistText)) {
                callback(
                    newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.headers = headers
                        this.quality = maxHeight
                    },
                )
                return true
            }

            val generated = withTimeoutOrNull(6_000L) {
                runCatching {
                    M3u8Helper.generateM3u8(
                        source = sourceName,
                        streamUrl = masterUrl,
                        referer = referer,
                        quality = fallbackQuality,
                        headers = headers,
                        name = displayName,
                    )
                }.getOrElse { emptyList() }
            }.orEmpty()

            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                return true
            }

            callback(
                newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = maxHeight
                },
            )
            return true
        }

        callback(
            newExtractorLink(sourceName, displayName, masterUrl, ExtractorLinkType.M3U8) {
                this.referer = referer
                this.headers = headers
                this.quality = fallbackQuality
            },
        )
        return true
    }
}
