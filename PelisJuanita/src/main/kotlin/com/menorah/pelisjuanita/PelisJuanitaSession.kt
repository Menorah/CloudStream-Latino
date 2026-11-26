package com.menorah.pelisjuanita

import android.content.Context
import android.webkit.CookieManager

data class PelisJuanitaSessionData(
    val cookieHeader: String = "",
    val userAgent: String = "",
    val verifiedAtMs: Long = 0L,
) {
    fun hasPhpSession(): Boolean =
        cookieMap().keys.any { it.equals("PHPSESSID", ignoreCase = true) }

    fun cookieMap(): Map<String, String> = PelisJuanitaSessionHelper.parseCookieHeader(cookieHeader)
}

interface PelisJuanitaSessionStore {
    fun read(): PelisJuanitaSessionData
    fun save(data: PelisJuanitaSessionData)
    fun clear()
}

class InMemoryPelisJuanitaSessionStore(
    initial: PelisJuanitaSessionData = PelisJuanitaSessionData(),
) : PelisJuanitaSessionStore {
    @Volatile
    private var current = initial

    override fun read(): PelisJuanitaSessionData = current

    override fun save(data: PelisJuanitaSessionData) {
        current = data
    }

    override fun clear() {
        current = PelisJuanitaSessionData()
    }
}

class AndroidPelisJuanitaSessionStore(
    context: Context,
) : PelisJuanitaSessionStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: PelisJuanitaSessionData = loadFromPrefs()

    private fun loadFromPrefs(): PelisJuanitaSessionData = PelisJuanitaSessionData(
        cookieHeader = prefs.getString(KEY_COOKIES, "").orEmpty(),
        userAgent = prefs.getString(KEY_USER_AGENT, "").orEmpty(),
        verifiedAtMs = prefs.getLong(KEY_VERIFIED_AT, 0L),
    )

    override fun read(): PelisJuanitaSessionData {
        val base = cached
        val webViewCookie = runCatching {
            CookieManager.getInstance().getCookie(PelisJuanitaMarkup.MAIN_URL)
        }.getOrNull().orEmpty()
        if (webViewCookie.isBlank()) return base
        val merged = PelisJuanitaSessionHelper.mergeCookieHeaders(base.cookieHeader, webViewCookie)
        return if (merged == base.cookieHeader) base else base.copy(cookieHeader = merged)
    }

    override fun save(data: PelisJuanitaSessionData) {
        val webViewCookie = runCatching {
            CookieManager.getInstance().getCookie(PelisJuanitaMarkup.MAIN_URL)
        }.getOrNull().orEmpty()
        val mergedCookies = PelisJuanitaSessionHelper.mergeCookieHeaders(data.cookieHeader, webViewCookie)
        val normalized = data.copy(cookieHeader = mergedCookies)
        cached = normalized
        prefs.edit()
            .putString(KEY_COOKIES, normalized.cookieHeader)
            .putString(KEY_USER_AGENT, normalized.userAgent)
            .putLong(KEY_VERIFIED_AT, normalized.verifiedAtMs)
            .apply()
    }

    override fun clear() {
        cached = PelisJuanitaSessionData()
        prefs.edit().clear().apply()
        runCatching {
            val cm = CookieManager.getInstance()
            val existing = cm.getCookie(PelisJuanitaMarkup.MAIN_URL).orEmpty()
            for (name in PelisJuanitaSessionHelper.parseCookieHeader(existing).keys) {
                cm.setCookie(PelisJuanitaMarkup.MAIN_URL, "$name=; Max-Age=0; Path=/")
            }
            cm.flush()
        }
    }

    companion object {
        private const val PREFS_NAME = "pelisjuanita_session_v1"
        private const val KEY_COOKIES = "cookie_header"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_VERIFIED_AT = "verified_at_ms"
    }
}

internal object PelisJuanitaSessionHelper {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    const val VERIFICATION_REQUIRED_MESSAGE =
        "PelisJuanita requiere verificación humana. Abre Ajustes (⚙) del complemento PelisJuanita y completa el código en el navegador integrado."

    fun parseCookieHeader(raw: String): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        for (part in raw.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) {
                out[name] = value
            }
        }
        return out
    }

    fun mergeCookieHeaders(primary: String, secondary: String): String {
        val merged = linkedMapOf<String, String>()
        merged.putAll(parseCookieHeader(primary))
        merged.putAll(parseCookieHeader(secondary))
        return merged.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    fun buildRequestHeaders(
        session: PelisJuanitaSessionData,
        referer: String = "${PelisJuanitaMarkup.MAIN_URL}/",
        isAjax: Boolean = false,
    ): Map<String, String> {
        val ua = session.userAgent.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        val headers = linkedMapOf(
            "User-Agent" to ua,
            "Accept" to if (isAjax) "*/*" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
            "Referer" to referer,
            "Sec-Fetch-Dest" to if (isAjax) "empty" else "document",
            "Sec-Fetch-Mode" to if (isAjax) "cors" else "navigate",
            "Sec-Fetch-Site" to "same-origin",
        )
        if (isAjax) {
            headers["X-Requested-With"] = "XMLHttpRequest"
        }
        if (session.cookieHeader.isNotBlank()) {
            headers["Cookie"] = session.cookieHeader
        }
        return headers
    }

    fun looksLikeVerificationGate(code: Int, finalUrl: String, body: String): Boolean {
        if (code in 300..399) return true
        val lowerUrl = finalUrl.lowercase()
        if (lowerUrl.contains("/inicio.php")) return true
        if (body.contains("name=\"empresa_hp\"", ignoreCase = true) ||
            body.contains("Verificación de Seguridad", ignoreCase = true) ||
            body.contains("codigo_captcha", ignoreCase = true) ||
            body.contains("inicio.php?redirect=", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    fun looksLikeCloudflareChallenge(code: Int, body: String): Boolean {
        if (code == 403 || code == 503) {
            if (body.contains("Just a moment", ignoreCase = true) ||
                body.contains("challenge-platform", ignoreCase = true) ||
                body.contains("_cf_chl", ignoreCase = true) ||
                body.contains("cf-turnstile", ignoreCase = true)
            ) {
                return true
            }
        }
        return body.contains("<title>Just a moment...</title>", ignoreCase = true) ||
            body.contains("Enable JavaScript and cookies to continue", ignoreCase = true)
    }

    fun looksLikeVerifiedCatalogOrDetail(finalUrl: String, body: String): Boolean {
        val lowerUrl = finalUrl.lowercase()
        if (!PelisJuanitaMarkup.isSiteUrl(finalUrl) || lowerUrl.contains("/inicio.php")) return false
        if (looksLikeVerificationGate(200, finalUrl, body)) return false
        if (looksLikeCloudflareChallenge(200, body)) return false
        return body.contains("dt_contenedor", ignoreCase = true) ||
            body.contains("grid-container", ignoreCase = true) ||
            body.contains("NavBar", ignoreCase = true) ||
            body.contains("container-movie-info", ignoreCase = true)
    }
}
