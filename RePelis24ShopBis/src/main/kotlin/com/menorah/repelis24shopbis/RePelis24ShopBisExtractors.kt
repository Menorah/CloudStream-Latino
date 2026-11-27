package com.menorah.repelis24shopbis

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

internal object NuploadMyDecoder {
    private val ARRAY_VAR_REGEX = Regex("""var\s+([A-Za-z_]\w*)\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL)
    private val QUOTED_STR_REGEX = Regex("""["']([^"']+)["']""")
    private val FOREACH_OFFSET_REGEX = Regex(
        """([A-Za-z_]\w*)\.forEach\([^)]*\)\s*\{[^}]*parseInt\(atob\([^)]+\)\.replace\([^)]+\)\)\s*-\s*(\d+)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val GENERIC_OFFSET_REGEX = Regex("""parseInt\(atob\([^)]+\)[^;)]*\)\s*-\s*(\d+)""")
    private val SESZ_REGEX = Regex("""var\s+sesz\s*=\s*["']([^"']+)["']""")
    private val NON_DIGIT_REGEX = Regex("""\D+""")

    fun parseStreamUrl(html: String): String? {
        val foreachMatch = FOREACH_OFFSET_REGEX.find(html)
        val arrayName = foreachMatch?.groupValues?.getOrNull(1)
        val offset = foreachMatch?.groupValues?.getOrNull(2)?.toLongOrNull()
            ?: GENERIC_OFFSET_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null

        val arrayContents = ARRAY_VAR_REGEX.findAll(html)
            .firstOrNull { arrayName == null || it.groupValues[1] == arrayName }
            ?.groupValues?.getOrNull(2)
            ?: return null

        val items = QUOTED_STR_REGEX.findAll(arrayContents).map { it.groupValues[1] }.toList()
        if (items.isEmpty()) return null

        val sb = StringBuilder(items.size)
        for (item in items) {
            val decoded = RePelis24ShopBisMarkup.decodeBase64Utf8(item)
            val digits = decoded.replace(NON_DIGIT_REGEX, "")
            val codePoint = digits.toLongOrNull()?.minus(offset) ?: return null
            if (codePoint !in 0..0xFFFF) return null
            sb.append(codePoint.toInt().toChar())
        }
        val baseUrl = sb.toString().trim()
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

        val sesz = SESZ_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return if (sesz.isEmpty()) {
            baseUrl
        } else {
            val sep = if ('?' in baseUrl) '&' else '?'
            "$baseUrl${sep}s=$sesz"
        }
    }
}

class NuploadMyExtractor : ExtractorApi() {
    override val name = "Nupload"
    override val mainUrl = "https://nupload.my"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Wrapper URL: https://nupload.my/iframe/?url=https%3A%2F%2Fvoe.sx%2Fe%2F...
        val wrapped = RePelis24ShopBisMarkup.extractQueryParam(url, "url")
        if (wrapped != null && (wrapped.startsWith("http://") || wrapped.startsWith("https://"))) {
            loadExtractor(wrapped, url, subtitleCallback, callback)
            return
        }

        val headers = mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://repelis24.shop/"),
        )
        val res = app.get(url, headers = headers, timeout = 20L)
        if (!res.isSuccessful) return
        val m3u8Url = NuploadMyDecoder.parseStreamUrl(res.text) ?: return
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            },
        )
    }
}
