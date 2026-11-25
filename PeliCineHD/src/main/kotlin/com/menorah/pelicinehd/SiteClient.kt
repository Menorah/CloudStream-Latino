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
 * later HTTP request, so only that one page is loaded in the browser.
 *
 * A WebView page load takes seconds; sixteen home sections doing that in parallel is what made
 * the home screen time out. So the browser is a fallback for a page, never a preferred transport:
 * the only thing remembered between requests is "the browser works", which is used to go straight
 * to the single solve pass instead of wasting an HTTP attempt first.
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
        if (!solved.get()) {
            val direct = attempt { http.get(url, withCookies(headers)) }
            if (direct != null) {
                if (direct.isSuccessful && !PeliCineHDMarkup.isChallenge(direct.text)) return direct
                // A missing page is not a refusal: solving a challenge would not fix it.
                if (!direct.isSuccessful && direct.code !in REFUSALS) return direct
            }
            return viaBrowser(url, headers, direct)
        }

        // The challenge was already solved in this session: the cookies are in place, so the
        // fast transport is asked again before paying for another page load in the browser.
        val withJar = attempt { http.get(url, withCookies(headers)) }
        if (withJar != null) {
            if (withJar.isSuccessful && !PeliCineHDMarkup.isChallenge(withJar.text)) return withJar
            if (!withJar.isSuccessful && withJar.code !in REFUSALS) return withJar
        }
        // Cookies expired or the page is refused even with them: solve again, once.
        solved.set(false)
        return viaBrowser(url, headers, withJar)
    }

    private suspend fun viaBrowser(
        url: String,
        headers: Map<String, String>,
        previous: SiteResponse?
    ): SiteResponse {
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
        return previous ?: retried ?: fromBrowser
            ?: throw IllegalStateException("No se pudo conectar con PeliCineHD desde la aplicación.")
    }

    /** Opens the home page in the browser once, which is what makes Cloudflare issue cookies. */
    private suspend fun solve(): Unit = solveMutex.withLock {
        if (solved.get()) return@withLock
        val home = PeliCineHDMarkup.BASE + "/"
        val response = attempt { browser.get(home, emptyMap()) }
        learnCookies(home)
        val answered = response != null && response.isSuccessful &&
            !PeliCineHDMarkup.isChallenge(response.text)
        // Cookies are what later HTTP calls need; a challenge is solved by the browser visit even
        // if the page it returned was not the catalogue yet.
        if (answered || cookies.isNotEmpty()) solved.set(true)
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
