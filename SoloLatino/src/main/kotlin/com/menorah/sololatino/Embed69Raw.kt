package com.menorah.sololatino

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** The Embed69 `dataLink` list before any decryption: used for the server-side fallback. */
internal data class RawEmbed(val language: String, val name: String, val encrypted: String)

/**
 * Raw reading of the Embed69 player page plus the `POST /api/decrypt` fallback used by
 * public implementations when the page carries no local content key. Pure parsing only.
 */
internal object Embed69Raw {
    private val mapper = ObjectMapper()
    private val assignment = Regex("\\b(?:let|var|const)\\s+dataLink\\s*=\\s*")
    private const val MAX_PAGE = 2_000_000

    fun rawEmbeds(html: String): List<RawEmbed> {
        if (html.length > MAX_PAGE) return emptyList()
        val start = assignment.find(html)?.range?.last?.plus(1) ?: return emptyList()
        val sections = runCatching {
            mapper.factory.createParser(html.substring(start)).use { mapper.readTree<JsonNode>(it) }
        }.getOrNull() ?: return emptyList()
        if (!sections.isArray || sections.size() > 50) return emptyList()
        val found = mutableListOf<RawEmbed>()
        for (section in sections) {
            val language = section.path("video_language").asText("").trim().take(32).ifBlank { "Idioma no indicado" }
            val servers = section.path("sortedEmbeds")
            if (!servers.isArray || servers.size() > 100) continue
            for (item in servers) {
                val name = item.path("servername").asText("").trim().take(80)
                val type = item.path("type").asText("")
                val link = item.path("link").asText("").trim()
                if (name.isBlank() || name.equals("download", true) || type.equals("download", true)) continue
                if (link.isEmpty() || link.length > 8192 || link.any { it.isISOControl() }) continue
                found.add(RawEmbed(language, name, link))
            }
        }
        return found
    }

    /** Decrypted links from `POST /api/decrypt`, in the order the API returns them. */
    fun decryptedLinks(body: String): List<String>? {
        if (body.length > 1_000_000) return null
        val root = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        if (root.path("success").asBoolean(false) != true) return null
        val links = root.path("links")
        if (!links.isArray || links.size() > 100) return null
        return links.map { it.path("link").asText("").trim() }
            .takeIf { it.all { link -> link.startsWith("https://") && link.length <= 2048 } }
    }
}
