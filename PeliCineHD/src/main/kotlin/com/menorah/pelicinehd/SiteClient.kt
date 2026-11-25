package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
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
 * the home screen time out. So the browser is a fallback for a page, never a preferred transport.
 *
 * Solving runs in its own scope on purpose. The home sections are wrapped in a per-section
 * timeout, and a solve awaited inside a section would be cancelled with it: the sixteen sections
 * would cancel each other's solve over and over and every one of them would come back empty.
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

    /** Not a child of any request: a section's timeout must never cancel the shared solve. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var solveJob: Deferred<Unit>? = null

    @Volatile
    private var lastAttemptAt = 0L

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
        // Cookies expired or the page is refused even with them: try a fresh solve.
        solved.set(false)
        resetSolve()
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

    /**
     * Opens the home page in the browser once, which is what makes Cloudflare issue cookies.
     *
     * Callers wait for the shared job instead of doing the work themselves, so the sixteen home
     * sections share one browser visit and none of them can cancel it.
     */
    private suspend fun solve() {
        if (solved.get()) return
        val job = synchronized(this) {
            solveJob ?: startSolve().also { started -> solveJob = started }
        }
        try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller gave up (section timeout); the solve keeps running in the background.
            throw e
        } catch (_: Exception) {
            // Recorded by the solve itself; the caller falls back to the browser for its page.
        }
    }

    /**
     * Resolves the challenge before a screen asks for many pages at once. Bounded so a stuck
     * browser never blocks the screen: the pages are still tried afterwards, one by one.
     */
    suspend fun warmUp(millis: Long = WARM_UP_MILLIS): Boolean {
        if (solved.get()) return true
        return try {
            withTimeoutOrNull(millis) { solve(); true } ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** Cancels the shared job so the next caller starts a fresh, current attempt. */
    private fun resetSolve() = synchronized(this) {
        solveJob?.cancel()
        solveJob = null
    }

    private fun startSolve(): Deferred<Unit> = background.async(Dispatchers.IO) {
        solveMutex.withLock {
            if (solved.get()) return@withLock
            // A retry storm is worse than a slow answer: do not reopen the browser every second.
            val elapsed = System.currentTimeMillis() - lastAttemptAt
            if (lastAttemptAt > 0 && elapsed < SOLVE_COOLDOWN_MS) return@withLock
            lastAttemptAt = System.currentTimeMillis()
            val home = PeliCineHDMarkup.BASE + "/"
            val response = attempt { browser.get(home, emptyMap()) }
            learnCookies(home)
            val answered = response != null && response.isSuccessful &&
                !PeliCineHDMarkup.isChallenge(response.text)
            // Cookies are what later HTTP calls need; a challenge is solved by the browser visit
            // even if the page it returned was not the catalogue yet.
            if (answered || cookies.isNotEmpty()) solved.set(true)
        }
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

        /** Minimum gap between two browser visits of the same session. */
        private const val SOLVE_COOLDOWN_MS = 20_000L

        /** Ceiling for the browser visit that solves the challenge before a screen starts. */
        const val WARM_UP_MILLIS = 45_000L

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
