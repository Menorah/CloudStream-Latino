package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.app
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal data class SiteResponse(val code: Int, val url: String, val text: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal fun interface SiteTransport {
    suspend fun get(url: String, headers: Map<String, String>): SiteResponse
}

/** On refusal try the browser as a transport, not merely its cookies in the HTTP client. */
internal class SiteClient(
    private val http: SiteTransport,
    private val browser: SiteTransport,
) {
    private val browserPreferred = ConcurrentHashMap.newKeySet<String>()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SiteResponse {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
        var browserResult: SiteResponse? = null
        var browserTried = false
        if (host in browserPreferred) {
            browserTried = true
            browserResult = attempt { browser.get(url, headers) }
            if (browserResult?.isSuccessful == true) return browserResult
            browserPreferred.remove(host)
        }
        val response = attempt { http.get(url, headers) }
        if (response != null && response.code !in REFUSALS) return response
        val fromBrowser = if (browserTried) browserResult else attempt { browser.get(url, headers) }
        if (fromBrowser?.isSuccessful == true) {
            if (host.isNotEmpty()) browserPreferred.add(host)
            return fromBrowser
        }
        return response ?: fromBrowser
            ?: throw IllegalStateException("No se pudo conectar con PeliCineHD desde la aplicación.")
    }

    /** True when the page that came back is the Cloudflare interstitial rather than site HTML. */
    suspend fun getHtml(url: String, headers: Map<String, String> = emptyMap()): SiteResponse {
        val response = get(url, headers)
        if (!response.isSuccessful || !PeliCineHDMarkup.isChallenge(response.text)) return response
        // The HTTP client got the challenge; the browser may still solve it.
        val fromBrowser = attempt { browser.get(url, headers) }
        if (fromBrowser != null && fromBrowser.isSuccessful &&
            !PeliCineHDMarkup.isChallenge(fromBrowser.text)
        ) {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            if (!host.isNullOrEmpty()) browserPreferred.add(host)
            return fromBrowser
        }
        return response
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 403/429/503 are the answers Cloudflare gives to a plain HTTP client. */
        private val REFUSALS = listOf(403, 429, 503)
        const val REQUEST_SECONDS = 25L

        fun create(context: Context): SiteClient {
            val http = SiteTransport { url, headers ->
                val r = app.get(url, headers = headers, timeout = REQUEST_SECONDS)
                SiteResponse(r.code, r.url, r.text)
            }
            val browser = BrowserSiteTransport(context.applicationContext)
            return SiteClient(http, browser)
        }
    }
}
