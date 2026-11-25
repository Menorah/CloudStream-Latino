package com.menorah.animeonline2

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Uqload
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

private fun hostBaseUrl(url: String, fallback: String): String {
    return runCatching {
        val u = URI(url)
        "${u.scheme}://${u.host}"
    }.getOrDefault(fallback)
}

private fun fileCodeFromPath(url: String): String {
    val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
    val seg = clean.substringAfterLast("/")
    return if (seg.startsWith("embed-")) {
        seg.removePrefix("embed-").substringBefore(".")
    } else if (seg.contains("-")) {
        // e.g. https://savefiles.top/e/dijqoi9tx3ml or .../embed-xyz.html
        val pathParts = URI(clean).path.trim('/').split('/')
        val idxE = pathParts.indexOf("e")
        if (idxE >= 0 && idxE + 1 < pathParts.size) pathParts[idxE + 1] else seg
    } else {
        // Check /e/<code >/title-slug
        val path = runCatching { URI(clean).path }.getOrNull().orEmpty()
        val m = Regex("""/e/([A-Za-z0-9]+)""").find(path)
        m?.groupValues?.get(1) ?: seg
    }
}

class GUploadSiteExtractor : ExtractorApi() {
    override val name = "GUpload"
    override val mainUrl = "https://gupload.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = hostBaseUrl(url, mainUrl)
        val html = app.get(url, referer = referer ?: "https://saidochesto.top/", timeout = 20).text
        val videoUrl = AnimeOnline2Parser.extractGuploadVideoUrl(html) ?: return
        val headers = mapOf("Referer" to "$base/", "Origin" to base)
        if (videoUrl.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(
                name,
                videoUrl,
                "$base/",
                headers = headers,
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                ) {
                    this.referer = "$base/"
                    this.headers = headers
                }
            )
        }
    }
}

class SavefilesExtractor : ExtractorApi() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.top"
    override val requiresReferer = true

    private val sourcesFileRegex = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+)["']""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = hostBaseUrl(url, mainUrl)
        val code = fileCodeFromPath(url)
        if (code.isBlank()) return
        val resp = app.post(
            "$base/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to code,
                "auto" to "1",
                "referer" to (referer ?: "https://saidochesto.top/"),
            ),
            headers = mapOf("Referer" to url, "Origin" to base),
            timeout = 20,
        ).text
        val unpacked = getAndUnpack(resp).ifBlank { resp }
        val streamUrl = sourcesFileRegex.find(resp)?.groupValues?.get(1)
            ?: sourcesFileRegex.find(unpacked)?.groupValues?.get(1)
            ?: return
        val headers = mapOf("Referer" to "$base/", "Origin" to base)
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(name, streamUrl, "$base/", headers = headers).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(source = name, name = name, url = streamUrl) {
                    this.referer = "$base/"
                }
            )
        }
    }
}

open class UqloadVcExtractor : Uqload() {
    override var name = "Uqload"
    override var mainUrl = "https://uqload.vc"
    override val requiresReferer = true

    private val m3u8SrcRegex = Regex("""sources\s*:\s*\[\s*\{[^}]*?file\s*:\s*["']([^"']+)["']""")
    private val mp4SrcRegex = Regex("""sources\s*:\s*\[\s*["']([^"']+)["']""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = hostBaseUrl(url, mainUrl)
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        if (path.startsWith("/e/")) {
            val code = fileCodeFromPath(url)
            if (code.isNotBlank()) {
                val dlText = app.post(
                    "$base/dl",
                    data = mapOf(
                        "op" to "embed",
                        "file_code" to code,
                        "auto" to "1",
                        "referer" to (referer ?: "https://saidochesto.top/"),
                    ),
                    headers = mapOf("Referer" to url.substringBefore("?"), "Origin" to base),
                    timeout = 20,
                ).text
                val unpacked = getAndUnpack(dlText).ifBlank { dlText }
                val streamUrl = m3u8SrcRegex.find(unpacked)?.groupValues?.get(1)
                    ?: mp4SrcRegex.find(unpacked)?.groupValues?.get(1)
                    ?: m3u8SrcRegex.find(dlText)?.groupValues?.get(1)
                if (!streamUrl.isNullOrBlank()) {
                    val hdrs = mapOf("Referer" to "$base/", "Origin" to base)
                    if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                        M3u8Helper.generateM3u8(name, streamUrl, "$base/", headers = hdrs).forEach(callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(source = name, name = name, url = streamUrl) {
                                this.referer = "$base/"
                            }
                        )
                    }
                    return
                }
            }
        }

        // Classic /embed-<id>.html page
        val html = app.get(url, referer = referer, timeout = 20).text
        val link = mp4SrcRegex.find(html)?.groupValues?.get(1)
            ?: m3u8SrcRegex.find(html)?.groupValues?.get(1)
            ?: return
        val hdrs = mapOf("Referer" to "$base/")
        if (link.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(name, link, "$base/", headers = hdrs).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(source = name, name = name, url = link) {
                    this.referer = "$base/"
                }
            )
        }
    }
}

class PeeplayerExtractor : ExtractorApi() {
    override val name = "Peeplayer"
    override val mainUrl = "https://peeplayer.com"
    override val requiresReferer = true

    private val msgIdRegex = Regex("""var\s+MSG_ID\s*=\s*(\d+)""")
    private val stokRegex = Regex("""(?:var\s+|,\s*)STOK\s*=\s*["']([^"']+)["']""")
    private val strimBaseRegex = Regex("""(?:var\s+|,\s*)STRIM_BASE\s*=\s*["']([^"']+)["']""")
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+/e/[^"']+)["']""", RegexOption.IGNORE_CASE)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = hostBaseUrl(url, mainUrl)
        val embedUrl = if (url.contains("/v/")) {
            val page = app.get(url, referer = referer ?: "https://saidochesto.top/", timeout = 20).text
            iframeRegex.find(page)?.groupValues?.get(1) ?: url.replaceFirst("/v/", "/e/")
        } else {
            url
        }
        val code = fileCodeFromPath(embedUrl)
        val html = app.get(embedUrl, referer = url, timeout = 20).text
        val msgId = msgIdRegex.find(html)?.groupValues?.get(1) ?: return
        val stok = stokRegex.find(html)?.groupValues?.get(1)
        val strimBase = strimBaseRegex.find(html)?.groupValues?.get(1)?.trimEnd('/')?.ifBlank { base } ?: base

        val authToken = runCatching {
            val authText = app.get(
                "$strimBase/api/cast/auth?code=$code&msg=$msgId&pre=0",
                headers = mapOf("Referer" to embedUrl),
                timeout = 15,
            ).text
            tryParseJson<Map<String, Any?>>(authText)?.get("t")?.toString()?.ifBlank { null }
        }.getOrNull() ?: stok ?: return

        val masterUrl = "$strimBase/m/$msgId/master.m3u8?t=$authToken"
        M3u8Helper.generateM3u8(
            name,
            masterUrl,
            "$strimBase/",
            headers = mapOf("Referer" to "$strimBase/", "Origin" to strimBase),
        ).forEach(callback)
    }
}

open class DynamicMixDropExtractor(override var mainUrl: String) : MixDrop() {
    override var name = "MixDrop"
    override val requiresReferer = true
    private val wurlRegex = Regex("""wurl.*?=.*?"([^"]+)";""")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val norm = url.replaceFirst("/f/", "/e/")
        val resp = app.get(norm, referer = referer, timeout = 20)
        val unpacked = getAndUnpack(resp.text).ifBlank { resp.text }
        val link = wurlRegex.find(unpacked)?.groupValues?.get(1) ?: return null
        val fixed = if (link.startsWith("//")) "https:$link" else link
        return listOf(
            newExtractorLink(name, name, fixed) {
                this.referer = resp.url.ifBlank { norm }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class MxdropSxExtractor : DynamicMixDropExtractor("https://mxdrop.sx")
class MxdropClickExtractor : DynamicMixDropExtractor("https://mxdrop.click")
class MixdropJmkExtractor : DynamicMixDropExtractor("https://mixdropjmk.pw")

open class ByseFilemoonExtractor(override var mainUrl: String) : ExtractorApi() {
    override var name = "Filemoon"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val base = hostBaseUrl(url, mainUrl)
        val code = fileCodeFromPath(url)

        // 1) Try native ByseSX details -> playback flow first
        val detailsJson = runCatching {
            app.get("$base/api/videos/$code/embed/details", referer = url, timeout = 15).text
        }.getOrNull()
        val embedFrameUrl = detailsJson?.let {
            tryParseJson<Map<String, Any?>>(it)?.get("embed_frame_url")?.toString()?.trim()
        }
        if (!embedFrameUrl.isNullOrBlank()) {
            val embedBase = hostBaseUrl(embedFrameUrl, base)
            val frameCode = fileCodeFromPath(embedFrameUrl)
            val pbResp = runCatching {
                app.get(
                    "$embedBase/api/videos/$frameCode/embed/playback",
                    headers = mapOf(
                        "accept" to "*/*",
                        "referer" to embedFrameUrl,
                        "x-embed-parent" to url,
                    ),
                    timeout = 15,
                )
            }.getOrNull()
            if (pbResp != null && pbResp.isSuccessful) {
                val root = tryParseJson<Map<String, Any?>>(pbResp.text)
                @Suppress("UNCHECKED_CAST")
                val playback = root?.get("playback") as? Map<String, Any?>
                val iv = playback?.get("iv")?.toString()
                val payload = playback?.get("payload")?.toString()
                @Suppress("UNCHECKED_CAST")
                val keyParts = (playback?.get("key_parts") as? List<Any?>)?.mapNotNull { it?.toString() }
                if (!iv.isNullOrBlank() && !payload.isNullOrBlank() && !keyParts.isNullOrEmpty()) {
                    val streamUrl = runCatching {
                        AnimeOnline2Parser.decryptBysePlayback(iv, payload, keyParts)
                    }.getOrNull()
                    if (!streamUrl.isNullOrBlank()) {
                        M3u8Helper.generateM3u8(
                            name,
                            streamUrl,
                            "$embedBase/",
                            headers = mapOf("Referer" to embedBase),
                        ).forEach(callback)
                        return
                    }
                }
            }
        }

        // 2) Fallback in Android WebView to intercept live .m3u8 stream
        val resolver = WebViewResolver(
            interceptUrl = Regex("""(m3u8|master\.txt)"""),
            additionalUrls = listOf(Regex("""(m3u8|master\.txt)""")),
            useOkhttp = false,
            timeout = 15_000L,
        )
        val interceptedUrl = runCatching {
            app.get(url, referer = referer, interceptor = resolver).url
        }.getOrNull().orEmpty()
        if (interceptedUrl.isNotBlank() && interceptedUrl != url) {
            M3u8Helper.generateM3u8(
                name,
                interceptedUrl,
                "$base/",
                headers = mapOf("Referer" to url),
            ).forEach(callback)
        }
    }
}

class Filemoon0TopExtractor : ByseFilemoonExtractor("https://filemoon0.top")
class FilemooonLinkExtractor : ByseFilemoonExtractor("https://filemooon.link")

class LuluvidExtractor : LuluStream() {
    override val name = "LuluStream"
    override val mainUrl = "https://luluvid.com"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val code = fileCodeFromPath(url)
        val candidates = listOf(mainUrl, "https://luluvdo.com", "https://lulustream.com")
        for (domain in candidates) {
            val found = mutableListOf<ExtractorLink>()
            runCatching {
                super.getUrl("$domain/e/$code", referer, subtitleCallback) { found.add(it) }
            }
            if (found.isNotEmpty()) {
                found.forEach(callback)
                return
            }
        }
    }
}
