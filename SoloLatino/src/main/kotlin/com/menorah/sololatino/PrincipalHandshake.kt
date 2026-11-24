package com.menorah.sololatino

import java.security.MessageDigest

internal data class PrincipalHandshake(val token: String, val content: String, val signed: String) {
    /** Form values are raw: the HTTP client must encode them exactly once. */
    fun fields(action: String, serverId: String? = null): Map<String, String> {
        val fields = linkedMapOf("a" to action, "tok" to token, "rt" to signed,
            "tk" to fnv("$signed|clk|$content"), "ao" to SoloLatinoMarkup.BASE)
        if (action == "click") fields["ts"] = "1"
        if (serverId != null) fields["v"] = serverId
        return fields
    }
    companion object {
        private fun fnv(value: String): String {
            var hash = 2166136261L
            for (character in value) hash = ((hash xor character.code.toLong()) * 16777619L) and 0xffffffffL
            return hash.toString(16)
        }
        fun parse(html: String): PrincipalHandshake? {
            if (html.length > 2_000_000) return null
            fun quoted(name: String): String? = Regex("\\b${name}\\s*=\\s*['\"]([^'\"]{1,4096})['\"]")
                .find(html)?.groupValues?.get(1)
            val token = quoted("_t") ?: return null
            val content = quoted("_qc") ?: return null
            val raw = Regex("\\b_qk\\s*=\\s*\\[([^\\]]{1,8192})\\]").find(html)?.groupValues?.get(1) ?: return null
            val fragments = Regex("['\"]([^'\"]{1,2048})['\"]").findAll(raw).map { it.groupValues[1] }.toList()
            if (fragments.size != 4) return null
            return PrincipalHandshake(token, content, fragments[1] + fragments[3] + fragments[0] + fragments[2])
        }
    }
}

/** Optional render header described by a third-party implementation; unverified against the live site. */
internal fun renderHeader(token: String, timestampMillis: Long): String {
    val input = "$token:${timestampMillis / 60000}:sololatino_render_shield_2026"
    val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return hash.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
