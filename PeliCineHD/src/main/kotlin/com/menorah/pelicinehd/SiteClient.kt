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
    private val alternateBrowser: SiteTransport? = null,
    private val alternateUserAgent: String = userAgent,
) {
    private val cfMutex = Mutex()
    private val cfGeneration = AtomicInteger(0)
    private val browserPreferred = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var activeBrowser: SiteTransport = browser

    @Volatile
    private var activeUserAgent: String = userAgent

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SiteResponse =
        execute(
            url,
            headers,
            { h -> http.get(url, h) },
            { t, h -> t.get(url, h) },
        )

    suspend fun post(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): SiteResponse = execute(
        url,
        headers,
        { h -> http.post(url, form, h) },
        { t, h -> t.post(url, form, h) },
    )

    private suspend fun execute(
        url: String,
        headers: Map<String, String>,
        httpCall: suspend (Map<String, String>) -> SiteResponse,
        browserCall: suspend (SiteTransport, Map<String, String>) -> SiteResponse,
    ): SiteResponse {
        if (!PeliCineHDMarkup.siteUrl(url)) return httpCall(headers)
        val host = URI(url).host.lowercase()

        if (host in browserPreferred) {
            val attempt = tryBrowserModes(headers, browserCall)
            val response = attempt.response
            if (response != null && response.isSuccessful && !isChallengeResponse(response)) {
                runCatching { cookies.flush() }
                return response
            }
            browserPreferred.remove(host)
        }

        val genBefore = cfGeneration.get()
        val cookieHeaderBefore = runCatching { cookies.getCookie(url) }.getOrNull().orEmpty()
        val hadClearanceBefore = cookieHeaderBefore.contains("cf_clearance")
        val initialHttp = attemptResult { httpCall(withHttpHeaders(headers, cookieHeaderBefore, activeUserAgent)) }
        val initialResponse = initialHttp.getOrNull()
        if (initialResponse != null && !needsBrowserFallback(initialResponse)) {
            return initialResponse
        }

        return cfMutex.withLock {
            if (host in browserPreferred) {
                val attempt = tryBrowserModes(headers, browserCall)
                val response = attempt.response
                if (response != null && response.isSuccessful && !isChallengeResponse(response)) {
                    runCatching { cookies.flush() }
                    return@withLock response
                }
                browserPreferred.remove(host)
            }

            if (cfGeneration.get() != genBefore && host !in browserPreferred) {
                val freshCookie = runCatching { cookies.getCookie(url) }.getOrNull().orEmpty()
                val retriedHttp = attemptResult { httpCall(withHttpHeaders(headers, freshCookie, activeUserAgent)) }.getOrNull()
                if (retriedHttp != null && !needsBrowserFallback(retriedHttp)) {
                    return@withLock retriedHttp
                }
                if (freshCookie.contains("cf_clearance")) {
                    browserPreferred.add(host)
                }
            }

            val browserOutcome = tryBrowserModes(headers, browserCall)
            val fromBrowser = browserOutcome.response
            if (fromBrowser != null && !needsBrowserFallback(fromBrowser)) {
                runCatching { cookies.flush() }
                cfGeneration.incrementAndGet()
                val freshCookie = runCatching { cookies.getCookie(url) }.getOrNull().orEmpty()
                if (hadClearanceBefore || freshCookie.isBlank()) {
                    // HTTP already failed even with clearance (or WebView didn't export cookies): stay on WebView.
                    browserPreferred.add(host)
                }
                return@withLock fromBrowser
            }

            if (fromBrowser != null) {
                return@withLock fromBrowser
            }
            val browserError = browserOutcome.error
            if (browserError != null && (initialResponse == null || needsBrowserFallback(initialResponse))) {
                throw browserError
            }
            initialResponse
                ?: initialHttp.exceptionOrNull()?.let { throw it }
                ?: throw IllegalStateException("No se pudo conectar con PeliCineHD desde la aplicación.")
        }
    }

    private data class BrowserAttempt(val response: SiteResponse?, val error: Throwable?)

    private suspend fun tryBrowserModes(
        headers: Map<String, String>,
        browserCall: suspend (SiteTransport, Map<String, String>) -> SiteResponse,
    ): BrowserAttempt {
        val primary = activeBrowser
        val primaryUa = activeUserAgent
        val primaryResult = attemptResult { browserCall(primary, withBrowserHeaders(headers, primaryUa)) }
        val primaryRes = primaryResult.getOrNull()
        if (primaryRes != null && !needsBrowserFallback(primaryRes)) {
            return BrowserAttempt(primaryRes, null)
        }

        val secondary = alternateBrowser?.takeIf { it !== primary }
        if (secondary != null) {
            val secondaryUa = if (secondary === browser) userAgent else alternateUserAgent
            val secondaryResult = attemptResult { browserCall(secondary, withBrowserHeaders(headers, secondaryUa)) }
            val secondaryRes = secondaryResult.getOrNull()
            if (secondaryRes != null && !needsBrowserFallback(secondaryRes)) {
                activeBrowser = secondary
                activeUserAgent = secondaryUa
                return BrowserAttempt(secondaryRes, null)
            }
            return BrowserAttempt(
                secondaryRes ?: primaryRes,
                secondaryResult.exceptionOrNull() ?: primaryResult.exceptionOrNull(),
            )
        }

        return BrowserAttempt(primaryRes, primaryResult.exceptionOrNull())
    }

    private fun withBrowserHeaders(headers: Map<String, String>, ua: String): Map<String, String> =
        mapOf("User-Agent" to ua) + headers

    private fun withHttpHeaders(
        headers: Map<String, String>,
        cachedCookie: String,
        ua: String,
    ): Map<String, String> {
        val base = linkedMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en;q=0.8",
        )
        if (cachedCookie.isNotBlank() && headers.keys.none { it.equals("Cookie", true) }) {
            base["Cookie"] = cachedCookie
        }
        base.putAll(headers)
        return base
    }

    private suspend fun <T> attemptResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
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
                text.contains("challenges.cloudflare.com/turnstile", true) ||
                text.contains("/cdn-cgi/challenge-platform/", true) ||
                text.contains("cf-turnstile", true) ||
                text.contains("<title>Just a moment", true) ||
                text.contains("<title>Un momento", true) ||
                text.contains("<title>Attention Required", true) ||
                text.contains("id=\"cf-error-details\"", true) ||
                text.contains("Enable JavaScript and cookies to continue", true) ||
                text.contains("Checking your browser", true) ||
                text.contains("Comprobando tu navegador", true)

        private fun isChallengeResponse(response: SiteResponse): Boolean =
            (response.code in listOf(403, 429, 503) || response.isSuccessful) &&
                looksLikeChallenge(response.text)

        private fun needsBrowserFallback(response: SiteResponse): Boolean =
            response.code in listOf(403, 429, 503) || looksLikeChallenge(response.text)

        fun create(context: Context): SiteClient {
            val appCtx = context.applicationContext
            val rawUa = runCatching { WebSettings.getDefaultUserAgent(appCtx) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val defaultUa = rawUa ?: FALLBACK_CHROME_UA
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
            // Start with native WebView UA (consistent with Sec-CH-UA / navigator.userAgentData);
            // keep stripped `; wv` mode as fallback if a WAF rule rejects `; wv` explicitly.
            val primaryBrowser = BrowserSiteTransport(appCtx, userAgent = null)
            val backupBrowser = if (cleanUa != defaultUa) BrowserSiteTransport(appCtx, userAgent = cleanUa) else null
            return SiteClient(http, primaryBrowser, cookieStore, defaultUa, backupBrowser, cleanUa)
        }
    }
}
