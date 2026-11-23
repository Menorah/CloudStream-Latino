package com.menorah.animeonline

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

/** Only the site's own HTTPS pages may be loaded in the browser fallback. */
internal fun isSiteUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", true) && uri.port in listOf(-1, 443) &&
        uri.userInfo == null && uri.host?.lowercase() in setOf("ww4.animeonline.ninja", "ver2.animeonline.ninja")
}

/** On refusal try the browser as a transport, not merely its cookies in OkHttp. */
internal class SiteClient(
    private val http: SiteTransport,
    private val browser: SiteTransport,
) {
    private val browserPreferred = ConcurrentHashMap.newKeySet<String>()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SiteResponse {
        if (!isSiteUrl(url)) return http.get(url, headers)
        val host = URI(url).host.lowercase()
        var browserResult: SiteResponse? = null
        var browserTried = false
        if (host in browserPreferred) {
            browserTried = true
            browserResult = attempt { browser.get(url, headers) }
            if (browserResult?.isSuccessful == true) return browserResult
            browserPreferred.remove(host)
        }
        val response = attempt { http.get(url, headers) }
        if (response != null && response.code !in listOf(403, 429, 503)) return response
        val fromBrowser = if (browserTried) browserResult else attempt { browser.get(url, headers) }
        if (fromBrowser?.isSuccessful == true) {
            browserPreferred.add(host)
            return fromBrowser
        }
        return response ?: fromBrowser ?: throw IllegalStateException("No se pudo conectar con AnimeOnline desde la aplicación.")
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val REQUEST_SECONDS = 10L

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
