package com.menorah.animeonline

import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** HTTP requests are bounded; an optional WebView is used only for a real CF challenge. */
internal class SiteClient {
    private val lock = Mutex()
    private val cookies = ConcurrentHashMap<String, String>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    private fun headersFor(url: String, headers: Map<String, String>): Map<String, String> {
        val host = URI(url).host.orEmpty()
        val cookie = cookies[host] ?: return headers
        val ua = WebViewResolver.webViewUserAgent
        return headers + mapOf("Cookie" to cookie) + (ua?.let { mapOf("User-Agent" to it) } ?: emptyMap())
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NiceResponse {
        val first = app.get(url, headers = headersFor(url, headers), timeout = REQUEST_SECONDS)
        if (!isChallenge(first)) return first
        // Consume the body before opening a second request.
        first.text
        val host = URI(url).host.orEmpty()
        if (host.isEmpty()) return first
        return lock.withLock {
            // Another caller may have solved the same challenge while we were waiting.
            if (cookies.containsKey(host)) {
                val retry = app.get(url, headers = headersFor(url, headers), timeout = REQUEST_SECONDS)
                if (!isChallenge(retry)) return@withLock retry
                retry.text
                cookies.remove(host)
            }
            if ((failedUntil[host] ?: 0L) > System.currentTimeMillis()) return@withLock first
            val cookieManager = CookieManager.getInstance()
            val resolver = WebViewResolver(
                interceptUrl = Regex(".^"), additionalUrls = listOf(Regex(".")),
                userAgent = null, useOkhttp = false, timeout = WEBVIEW_MILLIS
            )
            resolver.resolveUsingWebView(url, headers = headers) {
                cookieManager.getCookie(url)?.contains("cf_clearance=") == true
            }
            val value = cookieManager.getCookie(url).orEmpty()
            if (!value.contains("cf_clearance=")) {
                failedUntil[host] = System.currentTimeMillis() + FAILED_RETRY_MILLIS
                return@withLock first
            }
            cookies[host] = value
            val retry = app.get(url, headers = headersFor(url, headers), timeout = REQUEST_SECONDS)
            if (isChallenge(retry)) {
                cookies.remove(host)
                failedUntil[host] = System.currentTimeMillis() + FAILED_RETRY_MILLIS
            }
            retry
        }
    }

    private fun isChallenge(response: NiceResponse): Boolean =
        response.code in listOf(403, 503) &&
            (response.headers["cf-mitigated"]?.equals("challenge", true) == true ||
             response.headers["server"]?.contains("cloudflare", true) == true)

    companion object {
        const val REQUEST_SECONDS = 10L
        private const val WEBVIEW_MILLIS = 12_000L
        private const val FAILED_RETRY_MILLIS = 60_000L
    }
}
