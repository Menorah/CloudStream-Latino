package com.menorah.pelicinehd

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Extractor para `morencius.com`, el host que usa PeliCineHD en la mayoría de sus opciones.
 *
 * Como explica la guía de scraping, cada host de vídeo es único y conviene registrar el suyo:
 * la página `/v/<id>` trae un JW Player configurado desde un `eval(...)` empaquetado, del que se
 * extraen las URLs HLS. Al registrarlo, `loadExtractor` lo encuentra por el dominio y el
 * reproductor de CloudStream muestra el nombre y el origen correctos.
 */
class MorenciusExtractor : ExtractorApi() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Las páginas de descarga (`/d/`, `/f/`, `/file/`) sirven el mismo reproductor que `/v/`.
        val embed = when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            url.contains("/f/") -> url.replace("/f/", "/v/")
            else -> url
        }
        val response = app.get(
            embed,
            referer = referer,
            headers = mapOf(
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to mainUrl,
            ),
        )
        if (!response.isSuccessful) return

        for (stream in PeliCineHDMarkup.streamsFromHost(response.text)) {
            callback(
                newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: embed
                    this.headers = mapOf("Origin" to mainUrl)
                }
            )
        }
    }
}
