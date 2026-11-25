package com.menorah.animeonline2

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ResolvedPoster(
    val url: String,
    val headers: Map<String, String>?,
)

internal class AnimeOnline2PosterHelper(
    context: Context?,
    private val cfKillerProvider: () -> CloudflareKiller,
    private val cfKillerActivated: AtomicBoolean,
) {
    private enum class DeliveryMode {
        UNCHECKED,
        DIRECT_WITH_HEADERS,
        WEBVIEW_CACHE,
    }

    private val appContext = context?.applicationContext ?: context
    private val modeMutex = Mutex()
    private val webViewMutex = Mutex()

    @Volatile
    private var deliveryMode = DeliveryMode.UNCHECKED

    @Volatile
    private var bareOkHttpWorks = false

    @Volatile
    private var capturedWebViewUa: String? = null

    private val extraCookies = ConcurrentHashMap<String, String>()

    private val cacheDir: File? by lazy {
        appContext?.let { ctx ->
            File(ctx.cacheDir, "ao2_posters").apply { mkdirs() }
        }
    }

    fun recordSetCookies(setCookieHeaders: List<String>) {
        for (raw in setCookieHeaders) {
            val pair = raw.substringBefore(";").trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    extraCookies[name] = value
                }
            }
        }
    }

    fun refreshRuntimeInfo() {
        WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let {
            capturedWebViewUa = it
        }
    }

    private fun resolveUserAgent(): String {
        refreshRuntimeInfo()
        return capturedWebViewUa
            ?: appContext?.let { ctx -> runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            ?: AnimeOnline2Parser.USER_AGENT
    }

    private fun resolveCookieString(): String? {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(extraCookies)
        if (cfKillerActivated.get()) {
            runCatching {
                cfKillerProvider().savedCookies["ww4.animeonline.ninja"]?.let { merged.putAll(it) }
            }
        }
        runCatching {
            val cmCookie = CookieManager.getInstance()?.getCookie(AnimeOnline2Parser.BASE).orEmpty()
            for (part in cmCookie.split(";")) {
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merged[k] = v
                }
            }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun buildSiteHeaders(includeCookies: Boolean = true): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to resolveUserAgent(),
            "Referer" to "${AnimeOnline2Parser.BASE}/inicio/",
            "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
        )
        if (includeCookies) {
            resolveCookieString()?.let { headers["Cookie"] = it }
        }
        return headers
    }

    fun headersForExternal(): Map<String, String> = mapOf(
        "User-Agent" to AnimeOnline2Parser.USER_AGENT,
        "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Accept-Language" to "es-MX,es;q=0.9,en;q=0.8",
    )

    suspend fun preparePosters(
        urls: List<String?>,
        forEpisodesWithoutHeaders: Boolean = false,
        maxBatchCache: Int = 36,
    ): Map<String, ResolvedPoster> {
        val distinctUrls = urls.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.distinct()
        if (distinctUrls.isEmpty()) return emptyMap()

        val siteUrls = distinctUrls.filter { it.startsWith("http") && AnimeOnline2Parser.isSiteUrl(it) }
        val result = LinkedHashMap<String, ResolvedPoster>(distinctUrls.size)

        if (siteUrls.isEmpty()) {
            val extHeaders = headersForExternal()
            for (u in distinctUrls) {
                result[u] = if (u.startsWith("file://")) ResolvedPoster(u, null) else ResolvedPoster(u, extHeaders)
            }
            return result
        }

        ensureDeliveryMode(siteUrls.first())

        val needsLocalForEpisodes = forEpisodesWithoutHeaders && !bareOkHttpWorks && appContext != null
        if (deliveryMode == DeliveryMode.WEBVIEW_CACHE || needsLocalForEpisodes) {
            val cachedFiles = HashMap<String, File>()
            val missing = ArrayList<String>()
            for (u in siteUrls) {
                val existing = getValidCachedFile(u)
                if (existing != null) {
                    cachedFiles[u] = existing
                } else if (missing.size < maxBatchCache) {
                    missing.add(u)
                }
            }
            if (missing.isNotEmpty()) {
                if (deliveryMode == DeliveryMode.DIRECT_WITH_HEADERS) {
                    downloadBatchWithOkHttp(missing, cachedFiles)
                }
                val stillMissing = missing.filter { it !in cachedFiles }
                if (stillMissing.isNotEmpty() && appContext != null) {
                    downloadBatchWithWebView(stillMissing, cachedFiles)
                }
            }
            val siteHeaders = buildSiteHeaders()
            val extHeaders = headersForExternal()
            for (u in distinctUrls) {
                val cached = cachedFiles[u]
                result[u] = when {
                    cached != null -> ResolvedPoster(cached.toURI().toString(), null)
                    u.startsWith("file://") -> ResolvedPoster(u, null)
                    AnimeOnline2Parser.isSiteUrl(u) -> ResolvedPoster(u, siteHeaders)
                    else -> ResolvedPoster(u, extHeaders)
                }
            }
            return result
        }

        val siteHeaders = buildSiteHeaders()
        val extHeaders = headersForExternal()
        for (u in distinctUrls) {
            result[u] = when {
                u.startsWith("file://") -> ResolvedPoster(u, null)
                AnimeOnline2Parser.isSiteUrl(u) -> ResolvedPoster(u, siteHeaders)
                else -> ResolvedPoster(u, extHeaders)
            }
        }
        return result
    }

    /**
     * If a detail page mixes a protected animeonline.ninja poster with an external backdrop (e.g. image.tmdb.org)
     * and site cookies exist, cache the site poster locally so Coil never leaks site cookies to third parties.
     */
    suspend fun ensureCookieIsolatedPoster(
        poster: ResolvedPoster?,
        backdropUrl: String?,
    ): ResolvedPoster? {
        if (poster == null) return null
        if (poster.url.startsWith("file://")) return poster
        val hasCookie = poster.headers?.containsKey("Cookie") == true
        val externalBackdrop = !backdropUrl.isNullOrBlank() &&
            !backdropUrl.startsWith("file://") &&
            !AnimeOnline2Parser.isSiteUrl(backdropUrl)
        if (!hasCookie || !externalBackdrop || appContext == null) return poster

        val existing = getValidCachedFile(poster.url)
        if (existing != null) return ResolvedPoster(existing.toURI().toString(), null)

        val out = HashMap<String, File>()
        if (deliveryMode != DeliveryMode.WEBVIEW_CACHE) {
            downloadBatchWithOkHttp(listOf(poster.url), out)
        }
        if (poster.url !in out) {
            downloadBatchWithWebView(listOf(poster.url), out)
        }
        val cached = out[poster.url]
        return if (cached != null) {
            ResolvedPoster(cached.toURI().toString(), null)
        } else {
            ResolvedPoster(poster.url, poster.headers?.filterKeys { !it.equals("Cookie", ignoreCase = true) })
        }
    }

    internal suspend fun testDownloadViaWebView(urls: List<String>): Map<String, String> {
        val out = HashMap<String, File>()
        downloadBatchWithWebView(urls, out)
        return out.mapValues { (_, f) -> f.toURI().toString() }
    }

    private suspend fun ensureDeliveryMode(probeUrl: String) {
        if (deliveryMode != DeliveryMode.UNCHECKED) return
        modeMutex.withLock {
            if (deliveryMode != DeliveryMode.UNCHECKED) return

            val (directOk, bareOk) = coroutineScope {
                val withHdrs = async(Dispatchers.IO) { probeAndMaybeCache(probeUrl, buildSiteHeaders()) }
                val bare = async(Dispatchers.IO) {
                    probeOnly(probeUrl, mapOf("User-Agent" to AnimeOnline2Parser.USER_AGENT))
                }
                withHdrs.await() to bare.await()
            }
            bareOkHttpWorks = bareOk
            if (directOk) {
                deliveryMode = DeliveryMode.DIRECT_WITH_HEADERS
                return
            }

            // Only invoke CloudflareKiller if it hasn't obtained cf_clearance yet in this session.
            val hasClearance = resolveCookieString()?.contains("cf_clearance", ignoreCase = true) == true
            if (!cfKillerActivated.get() || !hasClearance) {
                try {
                    cfKillerActivated.set(true)
                    val killer = cfKillerProvider()
                    val killerBytes = withContext(Dispatchers.IO) {
                        val killerClient = app.baseClient.newBuilder()
                            .addInterceptor(killer)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(25, TimeUnit.SECONDS)
                            .build()
                        val reqBuilder = Request.Builder().url(probeUrl).get()
                        buildSiteHeaders().forEach { (k, v) -> reqBuilder.header(k, v) }
                        killerClient.newCall(reqBuilder.build()).execute().use { resp ->
                            recordSetCookies(resp.headers.values("Set-Cookie"))
                            if (!resp.isSuccessful) return@use null
                            val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                            if (ct.contains("text/html")) return@use null
                            resp.body?.bytes()?.takeIf { looksLikeValidImage(it) }
                        }
                    }
                    refreshRuntimeInfo()
                    if (killerBytes != null) {
                        saveBytesToCache(probeUrl, killerBytes)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }

                // Verify whether plain OkHttp (what Coil uses via buildDefaultClient) succeeds now with the new headers/cookies.
                val afterKillerOk = withContext(Dispatchers.IO) {
                    probeAndMaybeCache(probeUrl, buildSiteHeaders())
                }
                if (afterKillerOk) {
                    bareOkHttpWorks = withContext(Dispatchers.IO) {
                        probeOnly(probeUrl, mapOf("User-Agent" to AnimeOnline2Parser.USER_AGENT))
                    }
                    deliveryMode = DeliveryMode.DIRECT_WITH_HEADERS
                    return
                }
            }

            deliveryMode = if (appContext != null) DeliveryMode.WEBVIEW_CACHE else DeliveryMode.DIRECT_WITH_HEADERS
        }
    }

    private fun probeOnly(url: String, headers: Map<String, String>): Boolean {
        return fetchImageBytesViaOkHttp(url, headers) != null
    }

    private fun probeAndMaybeCache(url: String, headers: Map<String, String>): Boolean {
        val bytes = fetchImageBytesViaOkHttp(url, headers) ?: return false
        saveBytesToCache(url, bytes)
        return true
    }

    private fun fetchImageBytesViaOkHttp(url: String, headers: Map<String, String>): ByteArray? {
        return try {
            val client = app.baseClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val reqBuilder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                recordSetCookies(resp.headers.values("Set-Cookie"))
                if (!resp.isSuccessful) return null
                val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                if (ct.contains("text/html")) return null
                val bytes = resp.body?.bytes() ?: return null
                if (!looksLikeValidImage(bytes)) return null
                bytes
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun downloadBatchWithOkHttp(
        urls: List<String>,
        out: MutableMap<String, File>,
    ) = coroutineScope {
        val headers = buildSiteHeaders()
        urls.chunked(6).forEach { chunk ->
            val pairs = chunk.map { u ->
                async(Dispatchers.IO) {
                    val bytes = fetchImageBytesViaOkHttp(u, headers) ?: return@async null
                    val file = saveBytesToCache(u, bytes) ?: return@async null
                    u to file
                }
            }.mapNotNull { it.await() }
            for ((u, f) in pairs) {
                out[u] = f
            }
        }
    }

    private suspend fun downloadBatchWithWebView(
        urls: List<String>,
        out: MutableMap<String, File>,
    ) {
        val ctx = appContext ?: return
        if (urls.isEmpty()) return
        webViewMutex.withLock {
            val missing = urls.filter { getValidCachedFile(it)?.also { f -> out[it] = f } == null }
            if (missing.isEmpty()) return
            withTimeoutOrNull(18_000L) {
                fetchViaHiddenWebView(ctx, missing, out)
            }
        }
    }

    private suspend fun fetchViaHiddenWebView(
        ctx: Context,
        urls: List<String>,
        out: MutableMap<String, File>,
    ) {
        val done = CompletableDeferred<Unit>()
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var fetchStarted = false
        var generation = 0

        fun cleanup() {
            if (!finished.compareAndSet(false, true)) return
            done.complete(Unit)
            handler.post {
                val view = webView
                webView = null
                if (view != null) {
                    runCatching { view.stopLoading() }
                    runCatching { view.removeJavascriptInterface("AO2PosterBridge") }
                    runCatching { view.loadUrl("about:blank") }
                    runCatching { view.destroy() }
                }
            }
        }

        val timeoutRunnable = Runnable { cleanup() }
        handler.postDelayed(timeoutRunnable, 16_000L)

        val bridge = object {
            @JavascriptInterface
            fun onImage(url: String?, base64: String?) {
                if (url.isNullOrBlank() || base64.isNullOrBlank()) return
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    if (looksLikeValidImage(bytes)) {
                        saveBytesToCache(url, bytes)?.let { file ->
                            synchronized(out) { out[url] = file }
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onAllDone() {
                handler.removeCallbacks(timeoutRunnable)
                cleanup()
            }
        }

        val urlsJson = JSONArray(urls).toString()

        try {
            handler.post {
                if (finished.get()) return@post
                runCatching {
                    val view = WebView(ctx)
                    webView = view
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(view, true)
                    }
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.allowFileAccess = false
                    view.settings.allowContentAccess = false
                    view.settings.javaScriptCanOpenWindowsAutomatically = false
                    view.settings.loadsImagesAutomatically = false
                    capturedWebViewUa = view.settings.userAgentString?.takeIf { it.isNotBlank() } ?: capturedWebViewUa
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, 1080, 1920)
                    view.addJavascriptInterface(bridge, "AO2PosterBridge")

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            val host = uri.host.orEmpty().lowercase()
                            if (scheme in listOf("blob", "data", "about")) return null
                            val allowed = host.endsWith("animeonline.ninja") ||
                                host.endsWith("cloudflare.com") ||
                                host.endsWith("cloudflareinsights.com") ||
                                host.endsWith("hcaptcha.com") ||
                                host.endsWith("gstatic.com")
                            return if (allowed) null
                            else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            generation++
                            fetchStarted = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                handler.removeCallbacks(timeoutRunnable)
                                cleanup()
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            checkAndFetch(view, url, generation)
                        }

                        private fun checkAndFetch(view: WebView, pageUrl: String, gen: Int) {
                            if (finished.get() || gen != generation || fetchStarted) return
                            if (!AnimeOnline2Parser.isSiteUrl(pageUrl)) return
                            val probeJs = "JSON.stringify({ready:document.readyState,title:document.title||'',html:(document.documentElement&&document.documentElement.innerHTML||'').slice(0,4000)})"
                            view.evaluateJavascript(probeJs) { raw ->
                                if (finished.get() || gen != generation || fetchStarted) return@evaluateJavascript
                                val parsed = parseJsonString(raw)
                                val ready = parsed?.optString("ready").orEmpty()
                                val title = parsed?.optString("title").orEmpty()
                                val htmlSnippet = parsed?.optString("html").orEmpty()
                                val isChallenge = title.contains("Just a moment", ignoreCase = true) ||
                                    htmlSnippet.contains("_cf_chl_opt", ignoreCase = true) ||
                                    htmlSnippet.contains("cf-challenge", ignoreCase = true)
                                if (isChallenge || ready == "loading") {
                                    handler.postDelayed({ checkAndFetch(view, pageUrl, gen) }, 350L)
                                    return@evaluateJavascript
                                }
                                fetchStarted = true
                                val script = """
                                    (async function() {
                                      try {
                                        var urls = $urlsJson;
                                        var conc = 6;
                                        var idx = 0;
                                        async function worker() {
                                          while (idx < urls.length) {
                                            var u = urls[idx++];
                                            try {
                                              var resp = await fetch(u, { credentials: 'include' });
                                              if (!resp || !resp.ok) continue;
                                              var blob = await resp.blob();
                                              if (!blob || blob.size < 128 || blob.size > 8388608) continue;
                                              var b64 = await new Promise(function(resolve, reject) {
                                                var reader = new FileReader();
                                                reader.onloadend = function() {
                                                  var s = String(reader.result || '');
                                                  var comma = s.indexOf(',');
                                                  resolve(comma >= 0 ? s.slice(comma + 1) : '');
                                                };
                                                reader.onerror = reject;
                                                reader.readAsDataURL(blob);
                                              });
                                              if (b64) window.AO2PosterBridge.onImage(u, b64);
                                            } catch (e) {}
                                          }
                                        }
                                        var workers = [];
                                        for (var i = 0; i < Math.min(conc, urls.length); i++) workers.push(worker());
                                        await Promise.all(workers);
                                      } catch (e) {}
                                      try { window.AO2PosterBridge.onAllDone(); } catch (e) {}
                                    })();
                                """.trimIndent()
                                view.evaluateJavascript(script, null)
                            }
                        }
                    }

                    view.loadUrl("${AnimeOnline2Parser.BASE}/inicio/")
                }.onFailure {
                    handler.removeCallbacks(timeoutRunnable)
                    cleanup()
                }
            }
            done.await()
        } finally {
            handler.removeCallbacks(timeoutRunnable)
            cleanup()
        }
    }

    private fun parseJsonString(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val unwrapped = JSONObject("{\"v\":$raw}").optString("v")
            if (unwrapped.isBlank()) null else JSONObject(unwrapped)
        }.getOrNull()
    }

    private fun cacheFileFor(url: String): File? {
        val dir = cacheDir ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        val ext = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") }
            ?: "img"
        return File(dir, "$hex.$ext")
    }

    private fun getValidCachedFile(url: String): File? {
        val f = cacheFileFor(url) ?: return null
        if (!f.exists() || f.length() < 128L) return null
        val head = runCatching {
            f.inputStream().use { input ->
                val buf = ByteArray(16)
                val read = input.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        }.getOrNull() ?: return null
        return if (looksLikeValidImage(head)) f else null
    }

    private fun saveBytesToCache(url: String, bytes: ByteArray): File? {
        if (!looksLikeValidImage(bytes)) return null
        val target = cacheFileFor(url) ?: return null
        return runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
            target
        }.getOrNull()
    }

    companion object {
        fun looksLikeValidImage(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
            // GIF87a / GIF89a
            if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()) return true
            // WEBP: RIFF....WEBP
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) return true
            // AVIF / HEIF: ....ftyp
            if (bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() && bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()) return true
            return false
        }
    }
}
