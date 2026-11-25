package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class SiteResponse(val code: Int, val url: String, val text: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

internal fun interface SiteTransport {
    suspend fun get(url: String, headers: Map<String, String>): SiteResponse
}

internal fun interface SitePoster {
    suspend fun post(url: String, headers: Map<String, String>, data: Map<String, String>): SiteResponse
}

/** The browser is the only transport that can solve a Cloudflare challenge and read its cookies. */
internal interface CookieSource {
    suspend fun cookiesFor(url: String): Map<String, String>
}

/**
 * Two transports behind one door.
 *
 * Plain HTTP is used first because it is fast and cheap. When Cloudflare refuses it (`403`, or a
 * `200` whose body is the challenge page), the site is opened once in the device browser: that
 * solves the challenge and hands out `cf_clearance`, whose cookies are then attached to every
 * later HTTP request. A page is only fetched inside the browser when HTTP still fails with the
 * cookies in place, so a normal session opens the browser once instead of once per page.
 */
internal class SiteClient(
    private val http: SiteTransport,
    private val browser: SiteTransport,
    private val httpPost: SitePoster? = null,
    private val cookieSource: CookieSource? = null,
) {
    private val cookies = ConcurrentHashMap<String, String>()
    private val solved = AtomicBoolean(false)
    private val solveMutex = Mutex()

    private fun withCookies(headers: Map<String, String>): Map<String, String> {
        if (cookies.isEmpty()) return headers
        val jar = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        return headers + ("Cookie" to jar)
    }

    /** Copies the browser's cookies for the site so plain HTTP can reuse them. */
    private suspend fun learnCookies(url: String) {
        val source = cookieSource ?: return
        val found = attempt { source.cookiesFor(url) } ?: return
        if (found.isNotEmpty()) cookies.putAll(found)
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): SiteResponse {
        val direct = attempt { http.get(url, withCookies(headers)) }
        if (direct != null) {
            if (direct.isSuccessful && !PeliCineHDMarkup.isChallenge(direct.text)) return direct
            // A missing page is not a refusal: solving a challenge would not fix it.
            if (!direct.isSuccessful && direct.code !in REFUSALS) return direct
        }

        // Cloudflare is refusing this client: solve the challenge once, then retry over HTTP.
        solve()
        val retried = attempt { http.get(url, withCookies(headers)) }
        if (retried != null) {
            if (retried.isSuccessful && !PeliCineHDMarkup.isChallenge(retried.text)) return retried
            if (!retried.isSuccessful && retried.code !in REFUSALS) return retried
        }

        val fromBrowser = attempt { browser.get(url, headers) }
        if (fromBrowser != null && fromBrowser.isSuccessful) {
            learnCookies(url)
            return fromBrowser
        }
        return direct ?: retried ?: fromBrowser
            ?: throw IllegalStateException("No se pudo conectar con PeliCineHD desde la aplicación.")
    }

    /**
     * Opens the home page in the browser once, which is what makes Cloudflare issue cookies.
     *
     * The cookies are what the following HTTP requests need, so they are taken from the browser
     * even when the page it returned still looks like a challenge.
     */
    private suspend fun solve(): Unit = solveMutex.withLock {
        if (solved.get()) return@withLock
        val home = PeliCineHDMarkup.BASE + "/"
        attempt { browser.get(home, emptyMap()) }
        learnCookies(home)
        if (cookies.isNotEmpty()) solved.set(true)
    }

    /** POST for the theme's `admin-ajax.php` actions, with the same fallback order. */
    suspend fun post(url: String, headers: Map<String, String>, data: Map<String, String>): SiteResponse {
        val direct = attempt { httpPost?.post(url, withCookies(headers), data) }
        if (direct != null && direct.isSuccessful && !PeliCineHDMarkup.isChallenge(direct.text)) {
            return direct
        }
        solve()
        val retried = attempt { httpPost?.post(url, withCookies(headers), data) }
        if (retried != null && retried.isSuccessful && !PeliCineHDMarkup.isChallenge(retried.text)) {
            return retried
        }
        val poster = browser as? SitePoster
        val fromBrowser = if (poster != null) attempt { poster.post(url, headers, data) } else null
        if (fromBrowser != null && fromBrowser.isSuccessful) return fromBrowser
        return direct ?: retried ?: fromBrowser
            ?: throw IllegalStateException("No se pudo enviar la petición a PeliCineHD.")
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 403/429/503 are the answers Cloudflare gives to a client it does not trust. */
        private val REFUSALS = listOf(403, 429, 503)
        const val REQUEST_SECONDS = 25L

        fun create(context: Context): SiteClient {
            val http = SiteTransport { url, headers ->
                val r = app.get(url, headers = headers, timeout = REQUEST_SECONDS)
                SiteResponse(r.code, r.url, r.text)
            }
            val post = SitePoster { url, headers, data ->
                val r = app.post(url, headers = headers, data = data, timeout = REQUEST_SECONDS)
                SiteResponse(r.code, r.url, r.text)
            }
            val browser = BrowserSiteTransport(context.applicationContext)
            return SiteClient(http, browser, post, browser)
        }
    }
}
