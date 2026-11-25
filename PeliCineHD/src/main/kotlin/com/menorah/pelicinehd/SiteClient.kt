package com.menorah.pelicinehd

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class SiteResponse(val code: Int, val url: String, val text: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal interface SiteTransport {
    suspend fun get(url: String, headers: Map<String, String>): SiteResponse
    suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse
}

internal interface CookieStore {
    fun getCookie(url: String): String?
    fun flush()
}

internal class SiteClient(
    private val http: SiteTransport,
    private val browser: SiteTransport,
    private val cookies: CookieStore = EmptyCookieStore,
    val userAgent: String = FALLBACK_CHROME_UA,
) {
    private val cfMutex = Mutex()
    private val cfGeneration = AtomicInteger(0)
    private val browserPreferred = ConcurrentHashMap.newKeySet<String>()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SiteResponse =
        execute(url, headers, { h -> http.get(url, h) }, { h -> browser.get(url, h) })

    suspend fun post(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): SiteResponse = execute(url, headers, { h -> http.post(url, form, h) }, { h -> browser.post(url, form, h) })

    private suspend fun execute(
        url: String,
        headers: Map<String, String>,
        httpCall: suspend (Map<String, String>) -> SiteResponse,
        browserCall: suspend (Map<String, String>) -> SiteResponse,
    ): SiteResponse {
        if (!PeliCineHDMarkup.siteUrl(url)) return httpCall(headers)
        val host = URI(url).host.lowercase()

        if (host in browserPreferred) {
            val fromBrowser = attempt { browserCall(withBrowserHeaders(headers)) }
            if (fromBrowser != null && !isChallengeResponse(fromBrowser)) {
                if (fromBrowser.isSuccessful) {
                    runCatching { cookies.flush() }
                } else {
                    browserPreferred.remove(host)
                }
                return fromBrowser
            }
            browserPreferred.remove(host)
        }

        val genBefore = cfGeneration.get()
        val cookieHeaderBefore = runCatching { cookies.getCookie(url) }.getOrNull().orEmpty()
        val hadClearanceBefore = cookieHeaderBefore.contains("cf_clearance")
        val initialHttp = attempt { httpCall(withHttpHeaders(headers, cookieHeaderBefore)) }
        if (initialHttp != null && !needsBrowserFallback(initialHttp)) {
            return initialHttp
        }

        return cfMutex.withLock {
            if (host in browserPreferred) {
                val fromBrowser = attempt { browserCall(withBrowserHeaders(headers)) }
                if (fromBrowser?.isSuccessful == true && !isChallengeResponse(fromBrowser)) {
                    runCatching { cookies.flush() }
                    return@withLock fromBrowser
                }
            }

            if (cfGeneration.get() != genBefore && host !in browserPreferred) {
                val freshCookie = runCatching { cookies.getCookie(url) }.getOrNull().orEmpty()
                val retriedHttp = attempt { httpCall(withHttpHeaders(headers, freshCookie)) }
                if (retriedHttp != null && !needsBrowserFallback(retriedHttp)) {
                    return@withLock retriedHttp
                }
                if (freshCookie.contains("cf_clearance")) {
                    browserPreferred.add(host)
                }
            }

            val fromBrowser = attempt { browserCall(withBrowserHeaders(headers)) }
            if (fromBrowser != null && !isChallengeResponse(fromBrowser)) {
                runCatching { cookies.flush() }
                cfGeneration.incrementAndGet()
                val freshCookie = runCatching { cookies.getCookie(url) }.getOrNull().orEmpty()
                if (hadClearanceBefore || freshCookie.isBlank()) {
                    // HTTP already failed even with clearance (or WebView didn't export cookies): stay on WebView.
                    browserPreferred.add(host)
                }
                return@withLock fromBrowser
            }

            initialHttp ?: fromBrowser ?: throw IllegalStateException("No se pudo conectar con PeliCineHD desde la aplicación.")
        }
    }

    private fun withBrowserHeaders(headers: Map<String, String>): Map<String, String> =
        mapOf("User-Agent" to userAgent) + headers

    private fun withHttpHeaders(headers: Map<String, String>, cachedCookie: String): Map<String, String> {
        val base = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        )
        if (cachedCookie.isNotBlank() && headers.keys.none { it.equals("Cookie", true) }) {
            base["Cookie"] = cachedCookie
        }
        base.putAll(headers)
        return base
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private object EmptyCookieStore : CookieStore {
        override fun getCookie(url: String): String? = null
        override fun flush() = Unit
    }

    companion object {
        const val REQUEST_SECONDS = 15L
        const val FALLBACK_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun cleanBrowserUserAgent(raw: String?): String {
            val base = raw?.takeIf { it.isNotBlank() } ?: return FALLBACK_CHROME_UA
            return base
                .replace(Regex(";\\s*wv(?=\\s*[;)])", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Version/\\d+\\.\\d+(?=\\s+Chrome/)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        }

        fun looksLikeChallenge(text: String): Boolean =
            text.contains("id=\"challenge-form\"", true) ||
                text.contains("id=\"cf-challenge-running\"", true) ||
                text.contains("window._cf_chl_opt", true) ||
                text.contains("<title>Just a moment", true) ||
                text.contains("<title>Un momento", true) ||
                text.contains("challenges.cloudflare.com/turnstile", true)

        private fun isChallengeResponse(response: SiteResponse): Boolean =
            response.code in listOf(403, 429, 503) && looksLikeChallenge(response.text) ||
                (response.isSuccessful && looksLikeChallenge(response.text))

        private fun needsBrowserFallback(response: SiteResponse): Boolean =
            response.code in listOf(403, 429, 503) || looksLikeChallenge(response.text)

        fun create(context: Context): SiteClient {
            val appCtx = context.applicationContext
            val rawUa = runCatching { WebSettings.getDefaultUserAgent(appCtx) }.getOrNull()
            val cleanUa = cleanBrowserUserAgent(rawUa)
            val cookieStore = object : CookieStore {
                override fun getCookie(url: String): String? =
                    runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

                override fun flush() {
                    runCatching { CookieManager.getInstance().flush() }
                }
            }
            val http = object : SiteTransport {
                override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
                    val r = app.get(url, headers = headers, timeout = REQUEST_SECONDS)
                    return SiteResponse(r.code, r.url, r.text)
                }

                override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse {
                    val r = app.post(url, data = form, headers = headers, timeout = REQUEST_SECONDS)
                    return SiteResponse(r.code, r.url, r.text)
                }
            }
            val browser = BrowserSiteTransport(appCtx, cleanUa)
            return SiteClient(http, browser, cookieStore, cleanUa)
        }
    }
}
