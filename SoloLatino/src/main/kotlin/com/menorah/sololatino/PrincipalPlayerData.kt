package com.menorah.sololatino

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder

internal data class PrincipalOption(val language: String, val label: String, val id: String)
internal data class PrincipalSource(val url: String, val type: String)

/** Experimental parsing of a player's responses, not a network client or a working provider. */
internal object PrincipalPlayerData {
    private val mapper = ObjectMapper()
    private fun json(text: String): JsonNode? = if (text.length > 1_000_000) null else runCatching { mapper.readTree(text) }.getOrNull()
    private fun clean(value: String, max: Int): String? = value.trim().takeIf { it.isNotEmpty() && it.length <= max && it.none { c -> c.isISOControl() } }
    private fun url(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.matches(Regex("[0-9.]+")) && !host.contains(':')
    }

    fun options(body: String): List<PrincipalOption> {
        val root = json(body) ?: return emptyList()
        val result = mutableListOf<PrincipalOption>()
        fun append(language: String, entries: JsonNode) {
            if (!entries.isArray || entries.size() > 100) return
            for (entry in entries) {
                if (!entry.isArray || entry.size() < 2 || !entry[0].isTextual || !entry[1].isTextual) continue
                val label = clean(entry[0].asText(), 80) ?: continue
                val id = clean(entry[1].asText(), 256) ?: continue
                result.add(PrincipalOption(language, label, id))
            }
        }
        val groups = root.path("langs_s")
        if (groups.isObject && groups.size() <= 30) {
            val fields = groups.fields()
            while (fields.hasNext()) {
                val field = fields.next()
                val language = clean(field.key, 32) ?: continue
                append(language, field.value)
            }
        }
        if (result.isEmpty()) append("Idioma no indicado", root.path("s"))
        return result.distinctBy { it.language to it.id }
    }

    /** Player HLS proxy requires the original target and signature as query parameters. */
    fun source(body: String): PrincipalSource? {
        val root = json(body) ?: return null
        if (root.path("type").asText() == "iframe") {
            val embed = root.path("url").asText("")
            return if (url(embed)) PrincipalSource(embed, "iframe") else null
        }
        val media = root.path("u").asText("")
        val signature = clean(root.path("sig").asText(""), 2048) ?: return null
        if (!url(media)) return null
        val encodedMedia = URLEncoder.encode(media, "UTF-8")
        val encodedSignature = URLEncoder.encode(signature, "UTF-8")
        return PrincipalSource("https://player.pelisserieshoy.com/p.php?url=$encodedMedia&sig=$encodedSignature", "signed-proxy")
    }
}
