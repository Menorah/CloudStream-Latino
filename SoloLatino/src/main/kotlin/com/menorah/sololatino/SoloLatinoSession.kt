package com.menorah.sololatino

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SoloLatinoHttpResult(
    val code: Int,
    val url: String,
    val text: String,
    val dateHeader: String? = null,
)

internal data class SoloLatinoResolvedButtonOutput(
    val langLabel: String,
    val label: String,
    val token: String,
    val url: String?,
    val type: String?,
)

internal data class SoloLatinoWebPshServerResult(
    val label: String,
    val langLabel: String,
    val serverValue: String,
    val type: String?,
    val url: String?,
    val u: String?,
    val sig: String?,
    val ctx: String? = null,
    val cookieHeader: String = "",
    val userAgent: String = "",
)

internal data class SoloLatinoWebPshProbeResult(
    val init: SoloLatinoPelisSeriesHoyInit?,
    val servers: List<SoloLatinoPelisSeriesHoyServer>,
    val resolvedServers: List<SoloLatinoWebPshServerResult>,
    val cookieHeader: String,
    val userAgent: String,
)

internal object SoloLatinoSessionHelper {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun parseCookieHeader(raw: String?): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        if (raw.isNullOrBlank()) return out
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

    fun mergeCookies(base: Map<String, String>, extra: Map<String, String>): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(base)
        for ((k, v) in extra) {
            if (k.isNotBlank() && v.isNotBlank()) {
                merged[k] = v
            }
        }
        return merged
    }

    fun looksLikeCloudflareAccessDenied(code: Int, body: String): Boolean {
        if (code != 403 && code != 200 && code != 503) return false
        return body.contains("used Cloudflare to restrict access", ignoreCase = true) ||
            body.contains("Attention Required! | Cloudflare", ignoreCase = true) ||
            (body.contains("Access denied", ignoreCase = true) && body.contains("Cloudflare", ignoreCase = true)) ||
            body.contains("error code: 1020", ignoreCase = true)
    }

    fun isChromiumErrorPage(body: String): Boolean =
        body.contains("Upside down Android", ignoreCase = true) ||
            body.contains("<title>Webpage not available</title>", ignoreCase = true)

    fun looksLikeCloudflareChallenge(code: Int, body: String): Boolean {
        if (body.contains("_cf_chl_opt", ignoreCase = true) ||
            body.contains("challenge-platform", ignoreCase = true) ||
            body.contains("cf-turnstile", ignoreCase = true) ||
            body.contains("<title>Just a moment...</title>", ignoreCase = true) ||
            body.contains("Enable JavaScript and cookies to continue", ignoreCase = true)
        ) {
            return true
        }
        return (code == 403 || code == 503) &&
            (body.isBlank() ||
                body.contains("Just a moment", ignoreCase = true) ||
                looksLikeCloudflareAccessDenied(code, body) ||
                body.contains("cf-error-details", ignoreCase = true))
    }

    /**
     * Laravel VerifyCsrfToken prioritizes X-CSRF-TOKEN over X-XSRF-TOKEN.
     * Matching site app.js, emit X-XSRF-TOKEN exclusively when available and fall back
     * to meta X-CSRF-TOKEN only when XSRF-TOKEN cookie is absent.
     */
    fun buildSanctumCsrfHeaders(xsrfToken: String?, metaCsrfToken: String?): Map<String, String> {
        val cleanXsrf = xsrfToken?.trim().orEmpty()
        if (cleanXsrf.isNotEmpty()) {
            return mapOf("X-XSRF-TOKEN" to cleanXsrf)
        }
        val cleanMeta = metaCsrfToken?.trim().orEmpty()
        if (cleanMeta.isNotEmpty()) {
            return mapOf("X-CSRF-TOKEN" to cleanMeta)
        }
        return emptyMap()
    }

    fun buildBrowserHeaders(
        userAgent: String,
        referer: String = "${SoloLatinoMarkup.MAIN_URL}/",
        isAjax: Boolean = false,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "User-Agent" to userAgent,
        "Accept" to if (isAjax) "application/json, text/plain, */*" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
        "Referer" to referer,
    ).apply {
        if (isAjax) {
            put("X-Requested-With", "XMLHttpRequest")
        }
    }
}

internal data class SoloLatinoWebHtmlResponse(
    val code: Int,
    val url: String,
    val text: String,
    val cookieHeader: String,
    val userAgent: String,
    val serverOffsetMs: Long?,
)

internal data class SoloLatinoWebPlayersResponse(
    val offsetMs: Long?,
    val buttons: List<SoloLatinoResolvedButtonOutput>,
    val lazyResult: SoloLatinoPlayerApiResult?,
    val cookieHeader: String,
    val userAgent: String,
)

internal object SoloLatinoWebTransport {
    private const val WEBVIEW_TIMEOUT_MS = 26_000L
    private const val MAX_BODY_CHARS = 2_500_000
    private const val VIEWPORT_WIDTH = 412
    private const val VIEWPORT_HEIGHT = 915

    // Serialize WebView creation to 1 instance at a time so Chromium native memory stays bounded
    // and renderer kills / OOM crashes never happen during heavy link resolution.
    private val slots = Semaphore(1)

    private val challengeHosts = listOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "cloudflare.net",
        "cloudflareinsights.com",
        "cdnjs.cloudflare.com",
        "hcaptcha.com",
        "gstatic.com",
        "recaptcha.net",
    )

    private val adOrTrackerHostMarkers = listOf(
        "googletagmanager.com",
        "google-analytics.com",
        "mc.yandex.ru",
        "openfpcdn.io",
        "popads",
        "displayvertising",
        "d3mzokty951c5w.cloudfront.net",
        "iipvhcvoajpch.com",
        "sjlxpnisnow.com",
        "wangalamascled",
        "doubleclick.net",
        "googlesyndication.com",
        "adsterra",
        "latgw.fun",
        "franecki.net",
    )

    private val MEDIA_OR_FONT_EXT_REGEX = Regex(
        """\.(?:jpg|jpeg|png|webp|gif|avif|svg|ico|woff2?|ttf|eot|mp4|m4s|m3u8|ts|mp3|ogg|webm)(?:\?.*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val IMAGE_EXT_REGEX = Regex("""\.(?:jpg|jpeg|png|webp|gif|avif|svg|ico)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
    private val TRANSPARENT_1PX_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
        0x42, 0x60, 0x82.toByte(),
    )

    private val SAFE_TEARDOWN_CLIENT = object : WebViewClient() {
        @SuppressLint("NewApi")
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true
    }

    private fun isAllowedInfrastructureHost(host: String): Boolean {
        val h = host.lowercase()
        return challengeHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun isAdOrTrackerHost(host: String): Boolean {
        val h = host.lowercase()
        return adOrTrackerHostMarkers.any { h.contains(it) }
    }

    private fun isImageRequest(request: WebResourceRequest): Boolean {
        val path = request.url?.path.orEmpty()
        if (IMAGE_EXT_REGEX.containsMatchIn(path)) return true
        val accept = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            .orEmpty()
        return accept.contains("image/", ignoreCase = true)
    }

    private fun isHeavyStaticAsset(request: WebResourceRequest): Boolean {
        val path = request.url?.path.orEmpty()
        if (path.contains("/cdn-cgi/", ignoreCase = true)) return false
        if (MEDIA_OR_FONT_EXT_REGEX.containsMatchIn(path)) return true
        val accept = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            .orEmpty()
        return accept.contains("image/", ignoreCase = true)
    }

    private fun blockedResourceResponse(request: WebResourceRequest): WebResourceResponse =
        if (isImageRequest(request)) {
            WebResourceResponse("image/png", null, ByteArrayInputStream(TRANSPARENT_1PX_PNG))
        } else {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

    private fun createSafeChromeClient(onConsoleErr: ((String) -> Unit)? = null): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                onConsoleErr?.invoke("${consoleMessage.message()}@${consoleMessage.lineNumber()}")
            }
            return super.onConsoleMessage(consoleMessage)
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.cancel()
            return true
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

        override fun onPermissionRequest(request: PermissionRequest?) {
            runCatching { request?.deny() }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            runCatching { callback?.invoke(origin, false, false) }
        }
    }

    /**
     * Never override settings.userAgentString here unless explicit test override is impossible;
     * Cloudflare validates User-Agent against Chromium's Sec-CH-UA client hints and TLS fingerprint.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(
        view: WebView,
        overrideUserAgent: String? = null,
        blockImages: Boolean = true,
        onConsoleErr: ((String) -> Unit)? = null,
    ) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(view, true)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            if (blockImages) {
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
            mediaPlaybackRequiresUserGesture = true
            if (!overrideUserAgent.isNullOrBlank()) {
                userAgentString = overrideUserAgent
            }
        }
        view.webChromeClient = createSafeChromeClient(onConsoleErr)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    }

    private fun destroyWebViewSafely(view: WebView?) {
        if (view == null) return
        runCatching { view.stopLoading() }
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = SAFE_TEARDOWN_CLIENT }
        runCatching { view.onPause() }
        runCatching { view.clearHistory() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    private fun parseJsonObject(raw: String?): JSONObject? = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String ?: return null
        JSONObject(s)
    } catch (_: Exception) {
        null
    }

    suspend fun getHtml(
        context: Context,
        url: String,
        referer: String,
        allowedHost: (String) -> Boolean = { SoloLatinoMarkup.isSiteUrl(it) },
        overrideUserAgent: String? = null,
        fetchSameOriginEndpoint: Boolean = false,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebHtmlResponse = slots.withPermit {
        loadSiteOrEndpoint(
            context = context,
            targetUrl = url,
            referer = referer,
            allowedHost = allowedHost,
            overrideUserAgent = overrideUserAgent,
            fetchSameOriginEndpoint = fetchSameOriginEndpoint,
            testResponse = testResponse,
        )
    }

    suspend fun resolveSitePlayers(
        context: Context,
        pageUrl: String,
        overrideUserAgent: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebPlayersResponse = slots.withPermit {
        resolveSitePlayersInternal(context, pageUrl, overrideUserAgent, testResponse)
    }

    suspend fun resolvePelisSeriesHoy(
        context: Context,
        embedUrl: String,
        referer: String,
        overrideUserAgent: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebPshProbeResult = slots.withPermit {
        resolvePelisSeriesHoyInternal(context, embedUrl, referer, overrideUserAgent, testResponse)
    }

    @SuppressLint("SetJavaScriptEnabled", "NewApi")
    private suspend fun loadSiteOrEndpoint(
        context: Context,
        targetUrl: String,
        referer: String,
        allowedHost: (String) -> Boolean,
        overrideUserAgent: String?,
        fetchSameOriginEndpoint: Boolean,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebHtmlResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        var httpStatus = 200
        var fetchStarted = false
        var generation = 0
        var resolvedUa = overrideUserAgent.orEmpty()
        var fallbackDomText = ""
        var fallbackDomUrl = targetUrl
        var lastDiag = "init"
        val blockedHosts = LinkedHashSet<String>()
        var lastConsoleErr = ""

        val initialLoadUrl = if (fetchSameOriginEndpoint && referer.startsWith("http", ignoreCase = true) && allowedHost(referer)) {
            referer
        } else {
            targetUrl
        }
        val targetHostLower = SoloLatinoMarkup.hostOf(targetUrl)
        val isEmbed69 = targetHostLower.contains("embed69", ignoreCase = true)

        fun finish(result: Result<SoloLatinoWebHtmlResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val enriched = result?.map { res ->
                val cmCookie = runCatching { CookieManager.getInstance().getCookie(res.url).orEmpty() }.getOrDefault("")
                res.copy(cookieHeader = cmCookie.ifBlank { res.cookieHeader })
            }
            val viewToDestroy = webViewRef.getAndSet(null)
            handler.post {
                runCatching { CookieManager.getInstance().flush() }
                destroyWebViewSafely(viewToDestroy)
            }
            if (enriched != null && continuation.isActive) {
                runCatching {
                    enriched.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            }
        }

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webViewRef.get() ?: return@Runnable
            runCatching {
                view.evaluateJavascript("window.__slFetchResult || null") { raw ->
                    if (finished.get()) return@evaluateJavascript
                    val obj = parseJsonObject(raw)
                    if (obj != null) {
                        val code = obj.optInt("status", httpStatus).let { if (it > 0) it else httpStatus }
                        val resUrl = obj.optString("url").ifBlank { fallbackDomUrl.ifBlank { targetUrl } }
                        val fetchedText = obj.optString("text")
                        val offsetVal = if (obj.has("offsetMs") && !obj.isNull("offsetMs")) obj.optLong("offsetMs") else null
                        val fetchedBad = code !in 200..299 || fetchedText.isBlank() ||
                            SoloLatinoSessionHelper.isChromiumErrorPage(fetchedText) ||
                            SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, fetchedText)
                        val domUsable = fallbackDomText.isNotBlank() &&
                            !SoloLatinoSessionHelper.isChromiumErrorPage(fallbackDomText) &&
                            !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(200, fallbackDomText)
                        if (!fetchSameOriginEndpoint && fetchedBad && domUsable) {
                            finish(Result.success(SoloLatinoWebHtmlResponse(200, fallbackDomUrl, fallbackDomText, "", resolvedUa, offsetVal)))
                        } else if (SoloLatinoSessionHelper.isChromiumErrorPage(fetchedText)) {
                            finish(Result.success(SoloLatinoWebHtmlResponse(0, resUrl, "", "", resolvedUa, offsetVal)))
                        } else {
                            finish(Result.success(SoloLatinoWebHtmlResponse(code, resUrl, fetchedText, "", resolvedUa, offsetVal)))
                        }
                    } else {
                        handler.postDelayed(poll, 200L)
                    }
                }
            }.onFailure {
                if (!finished.get()) handler.postDelayed(poll, 220L)
            }
        }

        val timeoutTask = Runnable {
            val blocked = synchronized(blockedHosts) { blockedHosts.take(6).joinToString(",") }
            val jsErr = lastConsoleErr.take(120)
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo cargando SoloLatino [$lastDiag blocked=$blocked js=$jsErr]")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val appCtx = context.applicationContext ?: context
                val view = WebView(appCtx)
                webViewRef.set(view)
                configureWebView(view, overrideUserAgent, blockImages = true) { err ->
                    lastConsoleErr = err
                }
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(overrideUserAgent.orEmpty())
                view.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        finish(Result.failure(IllegalStateException("WebView renderer died cleanly")))
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !allowedHost(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        runCatching {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            val host = uri.host.orEmpty()
                            if (scheme in listOf("blob", "data", "about")) {
                                return@runCatching testResponse?.invoke(request)
                            }
                            if (isAdOrTrackerHost(host)) {
                                return@runCatching blockedResourceResponse(request)
                            }
                            val isInfra = scheme == "https" && isAllowedInfrastructureHost(host)
                            val allowed = allowedHost(uri.toString()) || isInfra
                            if (!allowed) {
                                if (host.isNotBlank()) synchronized(blockedHosts) { if (blockedHosts.size < 12) blockedHosts.add(host) }
                                return@runCatching blockedResourceResponse(request)
                            }
                            if (!isInfra && !request.isForMainFrame && isHeavyStaticAsset(request)) {
                                return@runCatching blockedResourceResponse(request)
                            }
                            testResponse?.invoke(request)
                        }.getOrElse { blockedResourceResponse(request) }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        httpStatus = 200
                        fetchStarted = false
                        val gen = ++generation
                        if (!fetchSameOriginEndpoint) {
                            handler.postDelayed({ checkReady(view, url, gen) }, 1400L)
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!request.isForMainFrame) return
                        val errCode = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.errorCode else -99
                        }.getOrDefault(-99)
                        val errDesc = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description?.toString().orEmpty() else ""
                        }.getOrDefault("")
                        if (errCode != ERROR_UNKNOWN && !errDesc.contains("ERR_ABORTED", ignoreCase = true)) {
                            httpStatus = 0
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        val inspect = "JSON.stringify({url:location.href,ready:document.readyState,title:document.title||'',body:(document.body?document.body.innerText:'').slice(0,120),offsetMs:(typeof window._slOff==='number'?window._slOff:null),text:(document.documentElement?document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1}):'')})"
                        runCatching {
                            view.evaluateJavascript(inspect) { raw ->
                                if (finished.get() || generation != expectedGen) return@evaluateJavascript
                                val page = parseJsonObject(raw) ?: run {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                                    return@evaluateJavascript
                                }
                                val pageUrl = page.optString("url").ifBlank { url }
                                val pageText = page.optString("text")
                                val readyState = page.optString("ready")
                                val title = page.optString("title")
                                val bodySample = page.optString("body").replace(Regex("\\s+"), " ").trim()
                                val offsetVal = if (page.has("offsetMs") && !page.isNull("offsetMs")) page.optLong("offsetMs") else null
                                val isCf = SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)
                                lastDiag = "status=$httpStatus ready=$readyState cf=$isCf title=${title.take(40)} body=${bodySample.take(80)} url=${pageUrl.take(80)} len=${pageText.length}"
                                if (SoloLatinoSessionHelper.looksLikeCloudflareAccessDenied(httpStatus, pageText)) {
                                    finish(Result.success(SoloLatinoWebHtmlResponse(403, pageUrl, pageText, "", resolvedUa, offsetVal)))
                                    return@evaluateJavascript
                                }
                                if (SoloLatinoSessionHelper.isChromiumErrorPage(pageText)) {
                                    finish(Result.success(SoloLatinoWebHtmlResponse(0, pageUrl, "", "", resolvedUa, offsetVal)))
                                    return@evaluateJavascript
                                }
                                if (isCf) {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                    return@evaluateJavascript
                                }
                                val onAllowedPage = allowedHost(pageUrl)
                                if (httpStatus == 0 && !onAllowedPage) {
                                    finish(Result.failure(IllegalStateException("Error de red en WebView")))
                                    return@evaluateJavascript
                                }
                                val domLooksPopulated = !fetchSameOriginEndpoint &&
                                    onAllowedPage &&
                                    pageText.length >= 400 &&
                                    (!isEmbed69 || pageText.contains("dataLink")) &&
                                    (readyState == "complete" || pageText.contains("dataLink") || pageText.contains("data-player-token") || pageText.contains("class=\"card\""))
                                if (domLooksPopulated) {
                                    finish(Result.success(SoloLatinoWebHtmlResponse(200, pageUrl, pageText, "", resolvedUa, offsetVal)))
                                    return@evaluateJavascript
                                }
                                val stoppedHeadPage = !fetchSameOriginEndpoint && onAllowedPage &&
                                    (pageText.contains("window.stop", ignoreCase = true) ||
                                        pageText.contains("dataLink") ||
                                        (isEmbed69 && pageText.length < 200 && readyState != "loading"))
                                if (!fetchSameOriginEndpoint && readyState != "complete" && !stoppedHeadPage) {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                                    return@evaluateJavascript
                                }
                                if (!fetchSameOriginEndpoint && testResponse != null && !isEmbed69) {
                                    finish(Result.success(SoloLatinoWebHtmlResponse(200, pageUrl, pageText, "", resolvedUa, offsetVal)))
                                    return@evaluateJavascript
                                }
                                if (!fetchStarted) {
                                    fetchStarted = true
                                    fallbackDomText = pageText
                                    fallbackDomUrl = pageUrl
                                    val quotedTarget = JSONObject.quote(targetUrl)
                                    val acceptHeader = if (fetchSameOriginEndpoint) {
                                        "application/json, text/html, */*"
                                    } else {
                                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                    }
                                    val xrwLine = if (fetchSameOriginEndpoint) "'X-Requested-With':'XMLHttpRequest'," else ""
                                    val js = """
                                        window.__slFetchResult=null;
                                        fetch($quotedTarget,{credentials:'same-origin',headers:{${xrwLine}'Accept':'$acceptHeader'}})
                                          .then(async r=>{
                                            const d=r.headers.get('date');
                                            const offsetMs=d&&!isNaN(Date.parse(d))?(Date.parse(d)-Date.now()):null;
                                            const t=await r.text();
                                            window.__slFetchResult=JSON.stringify({status:r.status,url:r.url||$quotedTarget,text:t.slice(0,${MAX_BODY_CHARS + 1}),offsetMs});
                                          })
                                          .catch(()=>{
                                            window.__slFetchResult=JSON.stringify({status:0,url:location.href,text:'',offsetMs:null});
                                          });
                                    """.trimIndent()
                                    view.evaluateJavascript(js, null)
                                    handler.postDelayed(poll, 160L)
                                }
                            }
                        }.onFailure {
                            if (!finished.get()) handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                        }
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                val navHeaders = linkedMapOf<String, String>()
                if (referer.startsWith("http", ignoreCase = true)) {
                    navHeaders["Referer"] = referer
                }
                if (navHeaders.isNotEmpty()) view.loadUrl(initialLoadUrl, navHeaders) else view.loadUrl(initialLoadUrl)
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "NewApi")
    private suspend fun resolveSitePlayersInternal(
        context: Context,
        pageUrl: String,
        overrideUserAgent: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebPlayersResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        var httpStatus = 200
        var resolveStarted = false
        var generation = 0
        var resolvedUa = overrideUserAgent.orEmpty()

        fun finish(result: Result<SoloLatinoWebPlayersResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val enriched = result?.map { res ->
                val cmCookie = runCatching { CookieManager.getInstance().getCookie(pageUrl).orEmpty() }.getOrDefault("")
                res.copy(cookieHeader = cmCookie.ifBlank { res.cookieHeader })
            }
            val viewToDestroy = webViewRef.getAndSet(null)
            handler.post {
                runCatching { CookieManager.getInstance().flush() }
                destroyWebViewSafely(viewToDestroy)
            }
            if (enriched != null && continuation.isActive) {
                runCatching {
                    enriched.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            }
        }

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webViewRef.get() ?: return@Runnable
            runCatching {
                view.evaluateJavascript("window.__slPlayerResolveOut || null") { raw ->
                    if (finished.get()) return@evaluateJavascript
                    val obj = parseJsonObject(raw)
                    if (obj != null) {
                        val offsetMs = if (obj.has("offsetMs") && !obj.isNull("offsetMs")) obj.optLong("offsetMs") else null
                        val buttonsArr = obj.optJSONArray("buttons") ?: JSONArray()
                        val buttons = mutableListOf<SoloLatinoResolvedButtonOutput>()
                        for (i in 0 until buttonsArr.length()) {
                            val b = buttonsArr.optJSONObject(i) ?: continue
                            val url = b.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }
                            val type = b.optString("type").takeIf { it.isNotBlank() }
                            buttons.add(
                                SoloLatinoResolvedButtonOutput(
                                    langLabel = SoloLatinoMarkup.normalizeLanguageLabel(b.optString("lang")),
                                    label = b.optString("label").ifBlank { "Servidor" },
                                    token = b.optString("token"),
                                    url = url,
                                    type = type,
                                ),
                            )
                        }
                        val lazyObj = obj.optJSONObject("lazy")
                        val lazyRes = lazyObj?.let {
                            val u = it.optString("url").takeIf { s -> s.startsWith("http", ignoreCase = true) }
                            val tp = it.optString("type").takeIf { s -> s.isNotBlank() }
                            if (u != null || tp != null) SoloLatinoPlayerApiResult(u, tp) else null
                        }
                        finish(Result.success(SoloLatinoWebPlayersResponse(offsetMs, buttons, lazyRes, "", resolvedUa)))
                    } else {
                        handler.postDelayed(poll, 240L)
                    }
                }
            }.onFailure {
                if (!finished.get()) handler.postDelayed(poll, 240L)
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo resolviendo reproductores de SoloLatino")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val appCtx = context.applicationContext ?: context
                val view = WebView(appCtx)
                webViewRef.set(view)
                configureWebView(view, overrideUserAgent, blockImages = true)
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(overrideUserAgent.orEmpty())
                view.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        finish(Result.failure(IllegalStateException("WebView renderer died cleanly")))
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !SoloLatinoMarkup.isSiteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        runCatching {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            val host = uri.host.orEmpty()
                            if (scheme in listOf("blob", "data", "about")) return@runCatching testResponse?.invoke(request)
                            if (isAdOrTrackerHost(host)) return@runCatching blockedResourceResponse(request)
                            val isInfra = scheme == "https" && isAllowedInfrastructureHost(host)
                            val allowed = SoloLatinoMarkup.isSiteUrl(uri.toString()) || isInfra
                            if (!allowed) return@runCatching blockedResourceResponse(request)
                            if (!isInfra && !request.isForMainFrame && isHeavyStaticAsset(request)) {
                                return@runCatching blockedResourceResponse(request)
                            }
                            testResponse?.invoke(request)
                        }.getOrElse { blockedResourceResponse(request) }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        httpStatus = 200
                        resolveStarted = false
                        generation++
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) httpStatus = 0
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        val inspect = "JSON.stringify({ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,20000):''),hasPlayer:Boolean(document.querySelector('button[data-player-token],#player-frame'))})"
                        runCatching {
                            view.evaluateJavascript(inspect) { raw ->
                                if (finished.get() || generation != expectedGen) return@evaluateJavascript
                                val page = parseJsonObject(raw) ?: run {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                                    return@evaluateJavascript
                                }
                                val pageText = page.optString("text")
                                val readyState = page.optString("ready")
                                if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                    return@evaluateJavascript
                                }
                                if (httpStatus == 0) {
                                    finish(Result.failure(IllegalStateException("Error de red resolviendo reproductores")))
                                    return@evaluateJavascript
                                }
                                if (readyState != "complete" && !page.optBoolean("hasPlayer")) {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                    return@evaluateJavascript
                                }
                                if (!resolveStarted) {
                                    resolveStarted = true
                                    val secretQuoted = JSONObject.quote(SoloLatinoMarkup.RENDER_SHIELD_SECRET)
                                    val js = """
                                        (async () => {
                                          window.__slPlayerResolveOut = null;
                                          const getCookie = (n) => {
                                            const m = document.cookie.match(new RegExp('(?:^|; )' + n + '=([^;]*)'));
                                            return m ? decodeURIComponent(m[1]) : '';
                                          };
                                          let offsetMs = typeof window._slOff === 'number' ? window._slOff : 0;
                                          try {
                                            const cr = await fetch('/sanctum/csrf-cookie', { credentials: 'same-origin' });
                                            const dh = cr.headers.get('date');
                                            if (dh && !isNaN(Date.parse(dh))) offsetMs = Date.parse(dh) - Date.now();
                                          } catch (_) {}
                                          const csrf = (document.querySelector('meta[name="csrf-token"]') || {}).content || '';
                                          async function shield(token) {
                                            const slot = Math.floor((Date.now() + offsetMs) / 60000);
                                            const data = new TextEncoder().encode(token + ':' + slot + ':' + $secretQuoted);
                                            const digest = await crypto.subtle.digest('SHA-256', data);
                                            return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
                                          }
                                          async function postToken(endpoint, token) {
                                            try {
                                              const sh = await shield(token);
                                              const xsrf = getCookie('XSRF-TOKEN');
                                              const h = {
                                                'Content-Type': 'application/json',
                                                'Accept': 'application/json',
                                                'X-Requested-With': 'XMLHttpRequest',
                                                'X-SL-Render': sh
                                              };
                                              if (xsrf) {
                                                h['X-XSRF-TOKEN'] = xsrf;
                                              } else if (csrf) {
                                                h['X-CSRF-TOKEN'] = csrf;
                                              }
                                              const r = await fetch(endpoint, {
                                                method: 'POST',
                                                credentials: 'same-origin',
                                                headers: h,
                                                body: JSON.stringify({ t: token })
                                              });
                                              if (r.status === 419) {
                                                await fetch('/sanctum/csrf-cookie', { credentials: 'same-origin' }).catch(() => null);
                                                const freshXsrf = getCookie('XSRF-TOKEN');
                                                if (freshXsrf) {
                                                  delete h['X-CSRF-TOKEN'];
                                                  h['X-XSRF-TOKEN'] = freshXsrf;
                                                  const r2 = await fetch(endpoint, {
                                                    method: 'POST',
                                                    credentials: 'same-origin',
                                                    headers: h,
                                                    body: JSON.stringify({ t: token })
                                                  });
                                                  if (r2.ok) return await r2.json();
                                                }
                                                return null;
                                              }
                                              if (!r.ok) return null;
                                              return await r.json();
                                            } catch (_) {
                                              return null;
                                            }
                                          }
                                          const langLabels = {};
                                          document.querySelectorAll('[data-lang-tab]').forEach(el => {
                                            const k = (el.getAttribute('data-lang-tab') || '').trim();
                                            const v = (el.textContent || '').trim();
                                            if (k && v) langLabels[k] = v;
                                          });
                                          const btns = Array.from(document.querySelectorAll('button[data-player-token], [data-player-token]'));
                                          const seen = new Set();
                                          const jobs = [];
                                          for (const btn of btns) {
                                            const tok = (btn.getAttribute('data-player-token') || '').trim();
                                            const preUrl = (btn.getAttribute('data-resolved-url') || '').trim();
                                            const preType = (btn.getAttribute('data-resolved-type') || '').trim();
                                            if (!tok && !preUrl) continue;
                                            const key = tok || ('u:' + preUrl);
                                            if (seen.has(key)) continue;
                                            seen.add(key);
                                            const grp = btn.closest('[data-lang-group]');
                                            const langKey = grp ? (grp.getAttribute('data-lang-group') || '').trim() : (btn.getAttribute('data-lang') || 'lat');
                                            const lang = langLabels[langKey] || langKey || 'Latino';
                                            const label = (btn.textContent || '').trim().replace(/\s+/g, ' ') || 'Servidor';
                                            jobs.push((async () => {
                                              if (preUrl && preUrl.startsWith('http')) {
                                                return { lang, label, token: tok, url: preUrl, type: preType || 'iframe' };
                                              }
                                              const res = tok ? await postToken('/api/player-url', tok) : null;
                                              return { lang, label, token: tok, url: res ? res.url : null, type: res ? res.type : null };
                                            })());
                                          }
                                          const pf = document.querySelector('#player-frame');
                                          const lazyTok = pf ? (pf.getAttribute('data-lazy-embed') || '').trim() : '';
                                          const [buttons, lazy] = await Promise.all([
                                            Promise.all(jobs),
                                            lazyTok ? postToken('/api/player-embed', lazyTok) : Promise.resolve(null)
                                          ]);
                                          window.__slPlayerResolveOut = JSON.stringify({ offsetMs, buttons, lazy });
                                        })();
                                    """.trimIndent()
                                    view.evaluateJavascript(js, null)
                                    handler.postDelayed(poll, 240L)
                                }
                            }
                        }.onFailure {
                            if (!finished.get()) handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                        }
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                view.loadUrl(pageUrl, mapOf("Referer" to "${SoloLatinoMarkup.MAIN_URL}/"))
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    /**
     * Loads player.pelisserieshoy.com inside WebView to defeat Cloudflare Managed Challenge,
     * extracts init (_t/_qc/_qk) and runs step 1 + rapid initial step 2 calls with a tight
     * sub-timeout so partial results are NEVER discarded if secondary servers reply "busy".
     */
    @SuppressLint("SetJavaScriptEnabled", "NewApi")
    private suspend fun resolvePelisSeriesHoyInternal(
        context: Context,
        embedUrl: String,
        referer: String,
        overrideUserAgent: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebPshProbeResult = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        var httpStatus = 200
        var resolveStarted = false
        var generation = 0
        var resolvedUa = overrideUserAgent.orEmpty()

        fun finish(result: Result<SoloLatinoWebPshProbeResult>?) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val enriched = result?.map { probe ->
                val cmCookie = runCatching { CookieManager.getInstance().getCookie(embedUrl).orEmpty() }.getOrDefault("")
                val ua = probe.userAgent.ifBlank { resolvedUa }
                probe.copy(
                    cookieHeader = cmCookie.ifBlank { probe.cookieHeader },
                    userAgent = ua,
                    resolvedServers = probe.resolvedServers.map { srv ->
                        srv.copy(
                            cookieHeader = cmCookie.ifBlank { srv.cookieHeader },
                            userAgent = ua,
                        )
                    },
                )
            }
            val viewToDestroy = webViewRef.getAndSet(null)
            handler.post {
                runCatching { CookieManager.getInstance().flush() }
                destroyWebViewSafely(viewToDestroy)
            }
            if (enriched != null && continuation.isActive) {
                runCatching {
                    enriched.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            }
        }

        val timeoutTask = Runnable {
            val cmCookie = runCatching { CookieManager.getInstance().getCookie(embedUrl).orEmpty() }.getOrDefault("")
            if (cmCookie.isNotBlank()) {
                // Return fresh cookies + UA even on WebView sub-timeout so Kotlin HTTP can retry immediately
                finish(Result.success(SoloLatinoWebPshProbeResult(null, emptyList(), emptyList(), cmCookie, resolvedUa)))
            } else {
                finish(Result.failure(IllegalStateException("WebView agotó el tiempo en player.pelisserieshoy.com")))
            }
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val appCtx = context.applicationContext ?: context
                val view = WebView(appCtx)
                webViewRef.set(view)
                configureWebView(view, overrideUserAgent, blockImages = true)
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(overrideUserAgent.orEmpty())

                lateinit var checkReadyFn: (WebView, String, Int) -> Unit

                lateinit var poll: Runnable
                poll = Runnable {
                    if (finished.get()) return@Runnable
                    val currentView = webViewRef.get() ?: return@Runnable
                    runCatching {
                        currentView.evaluateJavascript("JSON.stringify({out:window.__slPshOut||null,retry:Boolean(window.__slPshRetry)})") { raw ->
                            if (finished.get()) return@evaluateJavascript
                            val wrapper = parseJsonObject(raw)
                            if (wrapper == null) {
                                handler.postDelayed(poll, 240L)
                                return@evaluateJavascript
                            }
                            if (wrapper.optBoolean("retry")) {
                                resolveStarted = false
                                handler.postDelayed({ checkReadyFn(currentView, embedUrl, generation) }, 450L)
                                return@evaluateJavascript
                            }
                            val outStr = wrapper.optString("out", "").takeIf { it.isNotBlank() && it != "null" }
                            val obj = outStr?.let { runCatching { JSONObject(it) }.getOrNull() }
                            if (obj != null) {
                                val initObj = obj.optJSONObject("init")
                                val parsedInit = initObj?.let { i ->
                                    val t = i.optString("t")
                                    val qc = i.optString("qc")
                                    val qkArr = i.optJSONArray("qk")
                                    val qkList = if (qkArr != null) (0 until qkArr.length()).mapNotNull { idx -> qkArr.optString(idx) } else emptyList()
                                    if (t.isNotBlank() && qc.isNotBlank() && qkList.size >= 4) {
                                        val qv = qkList[1] + qkList[3] + qkList[0] + qkList[2]
                                        SoloLatinoPelisSeriesHoyInit(
                                            token = t,
                                            qc = qc,
                                            qk = qkList,
                                            qv = qv,
                                            qt = SoloLatinoMarkup.fnv1a32Hex("$qv|clk|$qc"),
                                            originUrl = SoloLatinoMarkup.originFromUrl(embedUrl, "https://player.pelisserieshoy.com"),
                                            ao = i.optString("ao").ifBlank { SoloLatinoMarkup.MAIN_URL },
                                            qsrc = i.optString("src"),
                                        )
                                    } else null
                                }
                                val listServers = mutableListOf<SoloLatinoPelisSeriesHoyServer>()
                                val listArr = obj.optJSONArray("list") ?: JSONArray()
                                for (i in 0 until listArr.length()) {
                                    val s = listArr.optJSONObject(i) ?: continue
                                    val sv = s.optString("serverValue")
                                    if (sv.isBlank()) continue
                                    listServers.add(
                                        SoloLatinoPelisSeriesHoyServer(
                                            label = s.optString("label").ifBlank { "Servidor" },
                                            url = sv,
                                            langLabel = SoloLatinoMarkup.normalizeLanguageLabel(s.optString("lang")),
                                        ),
                                    )
                                }
                                val resolved = mutableListOf<SoloLatinoWebPshServerResult>()
                                val arr = obj.optJSONArray("servers") ?: JSONArray()
                                for (i in 0 until arr.length()) {
                                    val s = arr.optJSONObject(i) ?: continue
                                    resolved.add(
                                        SoloLatinoWebPshServerResult(
                                            label = s.optString("label").ifBlank { "Servidor" },
                                            langLabel = SoloLatinoMarkup.normalizeLanguageLabel(s.optString("lang")),
                                            serverValue = s.optString("serverValue"),
                                            type = s.optString("type").takeIf { it.isNotBlank() },
                                            url = s.optString("url").takeIf { it.isNotBlank() },
                                            u = s.optString("u").takeIf { it.isNotBlank() },
                                            sig = s.optString("sig").takeIf { it.isNotBlank() },
                                            ctx = s.optString("ctx").takeIf { it.isNotBlank() },
                                            userAgent = resolvedUa,
                                        ),
                                    )
                                }
                                finish(Result.success(SoloLatinoWebPshProbeResult(parsedInit, listServers, resolved, "", resolvedUa)))
                            } else {
                                handler.postDelayed(poll, 240L)
                            }
                        }
                    }.onFailure {
                        if (!finished.get()) handler.postDelayed(poll, 240L)
                    }
                }

                val defaultAoOrigin = SoloLatinoMarkup.originFromUrl(referer, SoloLatinoMarkup.MAIN_URL)
                val quotedDefaultAo = JSONObject.quote(defaultAoOrigin)

                checkReadyFn = { targetView: WebView, url: String, expectedGen: Int ->
                    if (!finished.get() && generation == expectedGen && !resolveStarted) {
                        val inspect = "JSON.stringify({url:location.href,ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,25000):''),hasInit:typeof _t!=='undefined'&&typeof _qk!=='undefined'})"
                        runCatching {
                            targetView.evaluateJavascript(inspect) { raw ->
                                if (finished.get() || generation != expectedGen || resolveStarted) return@evaluateJavascript
                                val page = parseJsonObject(raw) ?: run {
                                    handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 220L)
                                    return@evaluateJavascript
                                }
                                val pageText = page.optString("text")
                                val pageUrl = page.optString("url").ifBlank { url }
                                if (!SoloLatinoMarkup.isPelisSeriesHoyUrl(pageUrl)) {
                                    handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 250L)
                                    return@evaluateJavascript
                                }
                                if (SoloLatinoSessionHelper.looksLikeCloudflareAccessDenied(httpStatus, pageText)) {
                                    finish(Result.success(SoloLatinoWebPshProbeResult(null, emptyList(), emptyList(), "", resolvedUa)))
                                    return@evaluateJavascript
                                }
                                if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                    handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 350L)
                                    return@evaluateJavascript
                                }
                                val readyState = page.optString("ready")
                                val canProceed = page.optBoolean("hasInit") ||
                                    readyState == "complete" ||
                                    pageText.contains("__embedBlocked") ||
                                    pageText.contains("window.location.replace") ||
                                    pageText.contains("window.stop") ||
                                    (readyState == "interactive" && pageText.contains("<script", ignoreCase = true))
                                if (!canProceed) {
                                    handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 200L)
                                    return@evaluateJavascript
                                }
                                if (!resolveStarted) {
                                    resolveStarted = true
                                    val quotedEmbed = JSONObject.quote(embedUrl)
                                    val js = """
                                        (async () => {
                                          window.__slPshOut = null;
                                          window.__slPshRetry = false;
                                          function extractInitFromHtml(html) {
                                            if (!html) return null;
                                            if (html.indexOf('_cf_chl_opt') !== -1 || html.indexOf('Just a moment') !== -1) {
                                              return { challenge: true };
                                            }
                                            const mT = html.match(/\b(?:var|let|const)?\s*_t\s*=\s*['"]([^'"]+)['"]/);
                                            const mQc = html.match(/\b(?:var|let|const)?\s*_qc\s*=\s*['"]([^'"]+)['"]/);
                                            const mQk = html.match(/\b(?:var|let|const)?\s*_qk\s*=\s*(\[[^\]]+\])/);
                                            const mAo = html.match(/\b(?:var|let|const)?\s*_?ao\s*=\s*['"]([^'"]*)['"]/);
                                            const mSrc = html.match(/\b(?:var|let|const)?\s*_qsrc\s*=\s*['"]([^'"]*)['"]/);
                                            if (!mT || !mQc || !mQk) return null;
                                            let qkArr = null;
                                            try {
                                              qkArr = JSON.parse(mQk[1].replace(/'/g, '"'));
                                            } catch (_) {
                                              return null;
                                            }
                                            if (!Array.isArray(qkArr) || qkArr.length < 4) return null;
                                            return {
                                              challenge: false,
                                              t: mT[1],
                                              qc: mQc[1],
                                              qk: qkArr,
                                              ao: (mAo && mAo[1]) ? mAo[1] : $quotedDefaultAo,
                                              src: (mSrc && mSrc[1]) ? mSrc[1] : ''
                                            };
                                          }
                                          let tok = (typeof _t !== 'undefined') ? _t : '';
                                          let qc = (typeof _qc !== 'undefined') ? _qc : '';
                                          let qk = (typeof _qk !== 'undefined' && Array.isArray(_qk)) ? _qk : null;
                                          let originAo = (typeof _ao !== 'undefined' && _ao) ? _ao : ((typeof ao !== 'undefined' && ao) ? ao : $quotedDefaultAo);
                                          let qsrc = (typeof _qsrc !== 'undefined' && _qsrc) ? _qsrc : '';
                                          if (!tok || !qc || !qk || qk.length < 4) {
                                            const domParsed = extractInitFromHtml(document.documentElement ? document.documentElement.outerHTML : '');
                                            if (domParsed && !domParsed.challenge) {
                                              tok = domParsed.t;
                                              qc = domParsed.qc;
                                              qk = domParsed.qk;
                                              originAo = domParsed.ao;
                                              qsrc = domParsed.src;
                                            }
                                          }
                                          if (!tok || !qc || !qk || qk.length < 4) {
                                            try {
                                              const html = await new Promise((resolve, reject) => {
                                                const x = new XMLHttpRequest();
                                                x.open('GET', $quotedEmbed, true);
                                                x.withCredentials = true;
                                                x.timeout = 10000;
                                                x.onload = () => resolve(x.responseText || '');
                                                x.onerror = () => reject(new Error('get_err'));
                                                x.ontimeout = () => reject(new Error('get_timeout'));
                                                x.send();
                                              });
                                              const parsed = extractInitFromHtml(html);
                                              if (parsed && parsed.challenge) {
                                                window.__slPshRetry = true;
                                                return;
                                              }
                                              if (parsed) {
                                                tok = parsed.t;
                                                qc = parsed.qc;
                                                qk = parsed.qk;
                                                originAo = parsed.ao;
                                                qsrc = parsed.src;
                                              }
                                            } catch (_) {}
                                          }
                                          if (!tok || !qc || !qk || qk.length < 4) {
                                            window.__slPshOut = JSON.stringify({ init: null, list: [], servers: [] });
                                            return;
                                          }
                                          const initOut = { t: tok, qc, qk, ao: originAo, src: qsrc };
                                          function fnv(s) {
                                            let h = 2166136261;
                                            for (let i = 0; i < s.length; i++) {
                                              h ^= s.charCodeAt(i);
                                              h = Math.imul(h, 16777619);
                                            }
                                            return (h >>> 0).toString(16);
                                          }
                                          const rt = qk[1] + qk[3] + qk[0] + qk[2];
                                          const tk = fnv(rt + '|clk|' + qc);
                                          try { window._qt = tk; } catch (_) {}
                                          async function postS(params) {
                                            const enriched = Object.assign({ rt, tk, ao: originAo }, params);
                                            if (qsrc) enriched.src = qsrc;
                                            const bodyStr = new URLSearchParams(enriched).toString();
                                            return await new Promise((resolve, reject) => {
                                              const xhr = new XMLHttpRequest();
                                              xhr.open('POST', '/s.php', true);
                                              xhr.withCredentials = true;
                                              xhr.timeout = 10000;
                                              xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
                                              xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                                              xhr.onload = () => {
                                                try { resolve(JSON.parse(xhr.responseText)); }
                                                catch (e) { reject(e); }
                                              };
                                              xhr.onerror = () => reject(new Error('xhr_err'));
                                              xhr.ontimeout = () => reject(new Error('xhr_timeout'));
                                              xhr.send(bodyStr);
                                            });
                                          }
                                          try {
                                            await postS({ a: 'click', tok, ts: '1' }).catch(() => null);
                                            const s1 = await postS({ a: '1', tok });
                                            const flat = [];
                                            const seen = new Set();
                                            if (s1 && s1.langs_s) {
                                              for (const [lang, arr] of Object.entries(s1.langs_s)) {
                                                if (!Array.isArray(arr)) continue;
                                                for (const item of arr) {
                                                  if (Array.isArray(item) && item.length >= 2 && !seen.has(item[1])) {
                                                    seen.add(item[1]);
                                                    flat.push({ label: item[0], serverValue: item[1], lang });
                                                  }
                                                }
                                              }
                                            }
                                            if (s1 && Array.isArray(s1.s)) {
                                              for (const item of s1.s) {
                                                if (Array.isArray(item) && item.length >= 2 && !seen.has(item[1])) {
                                                  seen.add(item[1]);
                                                  flat.push({ label: item[0], serverValue: item[1], lang: 'LAT' });
                                                }
                                              }
                                            }
                                            // Resolve up to top 3 servers inside a bounded 8.5s window inside WebView;
                                            // remaining servers + busy retries are handled by Kotlin HTTP without discarding anything.
                                            const deadline = Date.now() + 8500;
                                            const resolved = [];
                                            for (let idx = 0; idx < flat.length && idx < 3 && Date.now() < deadline; idx++) {
                                              const srv = flat[idx];
                                              try {
                                                let a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                if (a2 && a2.type === 'error' && a2.msg === 'no_click') {
                                                  await postS({ a: 'click', tok, ts: '1' }).catch(() => null);
                                                  await new Promise(r => setTimeout(r, 350));
                                                  a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                } else if (a2 && a2.error === 'retry_once') {
                                                  await new Promise(r => setTimeout(r, 500));
                                                  a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                } else if (a2 && a2.error === 'busy' && resolved.length === 0 && Date.now() + 5000 < deadline) {
                                                  const w = Math.min((Number(a2.retry) || 4) * 1000, 4500);
                                                  await new Promise(r => setTimeout(r, w));
                                                  a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                }
                                                if (a2 && (a2.u || a2.url)) {
                                                  resolved.push({
                                                    label: srv.label,
                                                    lang: srv.lang,
                                                    serverValue: srv.serverValue,
                                                    type: a2.type || '',
                                                    url: a2.url || '',
                                                    u: a2.u || '',
                                                    sig: a2.sig || '',
                                                    ctx: a2.ctx || ''
                                                  });
                                                }
                                              } catch (_) {}
                                            }
                                            window.__slPshOut = JSON.stringify({ init: initOut, list: flat, servers: resolved });
                                          } catch (_) {
                                            window.__slPshOut = JSON.stringify({ init: initOut, list: [], servers: [] });
                                          }
                                        })();
                                    """.trimIndent()
                                    targetView.evaluateJavascript(js, null)
                                    handler.postDelayed(poll, 240L)
                                }
                            }
                        }.onFailure {
                            if (!finished.get()) handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 220L)
                        }
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        finish(Result.failure(IllegalStateException("WebView renderer died cleanly")))
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !SoloLatinoMarkup.isPelisSeriesHoyUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        runCatching {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            val host = uri.host.orEmpty()
                            if (scheme in listOf("blob", "data", "about")) return@runCatching testResponse?.invoke(request)
                            if (isAdOrTrackerHost(host)) return@runCatching blockedResourceResponse(request)
                            val isInfra = scheme == "https" && isAllowedInfrastructureHost(host)
                            val allowed = SoloLatinoMarkup.isPelisSeriesHoyUrl(uri.toString()) || isInfra
                            if (!allowed) return@runCatching blockedResourceResponse(request)
                            if (!isInfra && !request.isForMainFrame && isHeavyStaticAsset(request)) {
                                return@runCatching blockedResourceResponse(request)
                            }
                            testResponse?.invoke(request)
                        }.getOrElse { blockedResourceResponse(request) }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        httpStatus = 200
                        resolveStarted = false
                        val gen = ++generation
                        handler.postDelayed({ checkReadyFn(view, url, gen) }, 1100L)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!request.isForMainFrame) return
                        val errCode = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.errorCode else -99
                        }.getOrDefault(-99)
                        val errDesc = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.description?.toString().orEmpty() else ""
                        }.getOrDefault("")
                        if (errCode != ERROR_UNKNOWN && !errDesc.contains("ERR_ABORTED", ignoreCase = true)) {
                            httpStatus = 0
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReadyFn(view, url, generation)
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                val effReferer = referer.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${SoloLatinoMarkup.MAIN_URL}/"
                view.loadUrl(embedUrl, mapOf("Referer" to effReferer))
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }
}

internal class SoloLatinoClient(
    private val appContext: Context? = null,
) {
    private val siteCookies = ConcurrentHashMap<String, String>()
    private val pshCookies = ConcurrentHashMap<String, String>()
    private val externalCookies = ConcurrentHashMap<String, String>()
    private val hostUserAgents = ConcurrentHashMap<String, String>()
    private val serverTimeOffsetMs = AtomicLong(0L)
    private val hostMutexes = ConcurrentHashMap<String, Mutex>()
    private val pshMutex = Mutex()
    private val siteGate = Semaphore(4)

    private fun hostKey(url: String): String =
        SoloLatinoMarkup.hostOf(url).removePrefix("www.").ifBlank { "default" }

    private fun cfMutexFor(url: String): Mutex =
        hostMutexes.getOrPut(hostKey(url)) { Mutex() }

    @Volatile
    private var preferredUserAgent: String = ""

    @Volatile
    internal var testWebResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null

    fun currentUserAgent(forUrl: String? = null): String {
        if (!forUrl.isNullOrBlank()) {
            hostUserAgents[hostKey(forUrl)]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (preferredUserAgent.isNotBlank()) return preferredUserAgent
        val sysUa = appContext?.let { ctx ->
            runCatching { WebSettings.getDefaultUserAgent(ctx.applicationContext ?: ctx) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (sysUa != null) {
            preferredUserAgent = sysUa
            return sysUa
        }
        return SoloLatinoSessionHelper.DEFAULT_USER_AGENT
    }

    private fun recordWebUserAgent(url: String, ua: String) {
        if (ua.isNotBlank()) {
            preferredUserAgent = ua
            hostUserAgents[hostKey(url)] = ua
        }
    }

    private fun readMergedCookies(url: String, localStore: ConcurrentHashMap<String, String>): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(localStore)
        if (appContext != null) {
            runCatching {
                val cm = CookieManager.getInstance()
                SoloLatinoSessionHelper.parseCookieHeader(cm.getCookie(url)).forEach { (k, v) -> merged[k] = v }
            }
        }
        return merged
    }

    private fun saveCookiesFromResponse(
        localStore: ConcurrentHashMap<String, String>,
        resCookies: Map<String, String>,
        cookieHeader: String = "",
    ) {
        for ((k, v) in resCookies) {
            if (k.isNotBlank() && v.isNotBlank()) localStore[k] = v
        }
        if (cookieHeader.isNotBlank()) {
            SoloLatinoSessionHelper.parseCookieHeader(cookieHeader).forEach { (k, v) ->
                localStore[k] = v
            }
        }
    }

    private fun updateServerOffset(dateHeader: String?) {
        val serverMs = SoloLatinoMarkup.parseHttpDateMillis(dateHeader) ?: return
        serverTimeOffsetMs.set(serverMs - System.currentTimeMillis())
    }

    private fun buildCandidateUserAgents(url: String, hasClearance: Boolean): List<String> {
        val pinned = hostUserAgents[hostKey(url)]
        if (!pinned.isNullOrBlank()) return listOf(pinned)
        val primary = currentUserAgent(url)
        return if (hasClearance || appContext != null) {
            listOf(primary)
        } else {
            listOf(primary, SoloLatinoSessionHelper.DEFAULT_USER_AGENT).distinct()
        }
    }

    suspend fun fetchSite(
        url: String,
        referer: String = "${SoloLatinoMarkup.MAIN_URL}/",
        isAjax: Boolean = false,
    ): SoloLatinoHttpResult = siteGate.withPermit {
        val activeTestResponse = testWebResponse
        val ctx = appContext
        var code = 0
        var finalUrl = url
        var text = ""
        var dateHeader: String? = null

        if (activeTestResponse == null) {
            val cookies = readMergedCookies(url, siteCookies)
            val hasClearance = cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
            val uaList = buildCandidateUserAgents(url, hasClearance)
            for (ua in uaList) {
                val headers = SoloLatinoSessionHelper.buildBrowserHeaders(ua, referer, isAjax)
                val direct = runCatching {
                    app.get(url, headers = headers, cookies = cookies, timeout = 14L)
                }.getOrNull() ?: continue
                code = direct.code
                finalUrl = direct.url
                text = direct.text
                dateHeader = direct.headers["date"] ?: direct.headers["Date"]
                updateServerOffset(dateHeader)
                saveCookiesFromResponse(siteCookies, direct.cookies)
                if (code in 200..299 && text.isNotBlank() &&
                    !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text)
                ) {
                    break
                }
            }
        } else {
            code = 403
        }

        if (ctx != null && (code !in 200..299 || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text))) {
            cfMutexFor(url).withLock {
                if (activeTestResponse == null) {
                    val refreshedCookies = readMergedCookies(url, siteCookies)
                    val retryDirect = runCatching {
                        app.get(
                            url,
                            headers = SoloLatinoSessionHelper.buildBrowserHeaders(currentUserAgent(url), referer, isAjax),
                            cookies = refreshedCookies,
                            timeout = 14L,
                        )
                    }.getOrNull()
                    if (retryDirect != null &&
                        retryDirect.code in 200..299 &&
                        retryDirect.text.isNotBlank() &&
                        !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(retryDirect.code, retryDirect.text)
                    ) {
                        code = retryDirect.code
                        finalUrl = retryDirect.url
                        text = retryDirect.text
                        dateHeader = retryDirect.headers["date"] ?: retryDirect.headers["Date"]
                        updateServerOffset(dateHeader)
                        saveCookiesFromResponse(siteCookies, retryDirect.cookies)
                        return@withLock
                    }
                }

                val webRes = runCatching {
                    SoloLatinoWebTransport.getHtml(
                        context = ctx,
                        url = url,
                        referer = referer,
                        allowedHost = { SoloLatinoMarkup.isSiteUrl(it) },
                        overrideUserAgent = null,
                        fetchSameOriginEndpoint = isAjax,
                        testResponse = activeTestResponse,
                    )
                }.getOrNull()
                if (webRes != null) {
                    code = webRes.code
                    finalUrl = webRes.url
                    text = webRes.text
                    recordWebUserAgent(url, webRes.userAgent)
                    webRes.serverOffsetMs?.let { serverTimeOffsetMs.set(it) }
                    saveCookiesFromResponse(siteCookies, emptyMap(), webRes.cookieHeader)
                }
            }
        }

        SoloLatinoHttpResult(code = code, url = finalUrl, text = text, dateHeader = dateHeader)
    }

    suspend fun resolvePlayerSection(
        pageUrl: String,
        section: SoloLatinoPlayerSection,
    ): Pair<List<SoloLatinoResolvedButtonOutput>, SoloLatinoPlayerApiResult?> {
        val activeTestResponse = testWebResponse
        val allHavePreResolved = section.buttons.isNotEmpty() &&
            section.buttons.all { !it.resolvedUrl.isNullOrBlank() } &&
            section.lazyEmbedToken.isNullOrBlank()
        if (allHavePreResolved) {
            return section.buttons.map {
                SoloLatinoResolvedButtonOutput(it.langLabel, it.label, it.token, it.resolvedUrl, it.resolvedType)
            } to null
        }

        if (activeTestResponse == null) {
            val httpRes = tryResolvePlayersViaHttp(pageUrl, section)
            val resolvedCount = httpRes.first.count { !it.url.isNullOrBlank() }
            if (resolvedCount > 0 || !httpRes.second?.url.isNullOrBlank()) {
                return httpRes
            }
        }

        val ctx = appContext
        if (ctx != null) {
            val webOut = runCatching {
                SoloLatinoWebTransport.resolveSitePlayers(
                    context = ctx,
                    pageUrl = pageUrl,
                    overrideUserAgent = null,
                    testResponse = activeTestResponse,
                )
            }.getOrNull()
            if (webOut != null) {
                webOut.offsetMs?.let { serverTimeOffsetMs.set(it) }
                recordWebUserAgent(pageUrl, webOut.userAgent)
                saveCookiesFromResponse(siteCookies, emptyMap(), webOut.cookieHeader)
                return webOut.buttons to webOut.lazyResult
            }
        }

        val preOnly = section.buttons.map {
            SoloLatinoResolvedButtonOutput(it.langLabel, it.label, it.token, it.resolvedUrl, it.resolvedType)
        }
        return preOnly to null
    }

    private suspend fun tryResolvePlayersViaHttp(
        pageUrl: String,
        section: SoloLatinoPlayerSection,
    ): Pair<List<SoloLatinoResolvedButtonOutput>, SoloLatinoPlayerApiResult?> {
        val origin = SoloLatinoMarkup.MAIN_URL
        val ua = currentUserAgent(pageUrl)

        suspend fun refreshCsrfCookie() {
            val csrfUrl = "$origin/sanctum/csrf-cookie"
            val cookiesBefore = readMergedCookies(pageUrl, siteCookies)
            val csrfRes = runCatching {
                app.get(
                    csrfUrl,
                    headers = SoloLatinoSessionHelper.buildBrowserHeaders(ua, pageUrl, isAjax = true),
                    cookies = cookiesBefore,
                    timeout = 12L,
                )
            }.getOrNull()
            if (csrfRes != null) {
                val dateHeader = csrfRes.headers["date"] ?: csrfRes.headers["Date"]
                updateServerOffset(dateHeader)
                saveCookiesFromResponse(siteCookies, csrfRes.cookies)
            }
        }

        val initialCookies = readMergedCookies(pageUrl, siteCookies)
        if (initialCookies.keys.none { it.equals("XSRF-TOKEN", ignoreCase = true) }) {
            refreshCsrfCookie()
        }

        val csrfRetryMutex = Mutex()
        val refreshedOnce = AtomicBoolean(false)

        suspend fun postEndpoint(endpoint: String, token: String): SoloLatinoPlayerApiResult? {
            if (token.isBlank()) return null

            suspend fun execPost(metaFallback: String?): Pair<Int, SoloLatinoPlayerApiResult?> {
                val mergedCookies = readMergedCookies(pageUrl, siteCookies)
                val xsrfRaw = mergedCookies.entries.firstOrNull { it.key.equals("XSRF-TOKEN", ignoreCase = true) }?.value
                val xsrfToken = SoloLatinoMarkup.decodeCookieValue(xsrfRaw)
                val shield = SoloLatinoMarkup.computeRenderShieldHeader(token, serverTimeOffsetMs.get())
                val reqHeaders = linkedMapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
                    "Content-Type" to "application/json",
                    "Origin" to origin,
                    "Referer" to pageUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-SL-Render" to shield,
                )
                reqHeaders.putAll(SoloLatinoSessionHelper.buildSanctumCsrfHeaders(xsrfToken, metaFallback))
                val payload = SoloLatinoMarkup.jsonMapper.writeValueAsString(mapOf("t" to token))
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val res = try {
                    app.post(
                        "$origin$endpoint",
                        headers = reqHeaders,
                        cookies = mergedCookies,
                        requestBody = body,
                        timeout = 12L,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    return 0 to null
                }
                val dateHdr = res.headers["date"] ?: res.headers["Date"]
                updateServerOffset(dateHdr)
                saveCookiesFromResponse(siteCookies, res.cookies)
                if (!res.isSuccessful) return res.code to null
                return res.code to SoloLatinoMarkup.parsePlayerApiResponse(res.text)
            }

            val (firstCode, firstParsed) = execPost(section.csrfToken)
            if (firstParsed != null) return firstParsed
            if (firstCode == 419 || firstCode == 401) {
                csrfRetryMutex.withLock {
                    if (refreshedOnce.compareAndSet(false, true)) {
                        refreshCsrfCookie()
                    }
                }
                val (_, secondParsed) = execPost(null)
                return secondParsed
            }
            return null
        }

        return coroutineScope {
            val btnDeferred = section.buttons.map { btn ->
                async(Dispatchers.IO) {
                    if (!btn.resolvedUrl.isNullOrBlank()) {
                        SoloLatinoResolvedButtonOutput(btn.langLabel, btn.label, btn.token, btn.resolvedUrl, btn.resolvedType)
                    } else {
                        val apiRes = postEndpoint("/api/player-url", btn.token)
                        SoloLatinoResolvedButtonOutput(btn.langLabel, btn.label, btn.token, apiRes?.url, apiRes?.type)
                    }
                }
            }
            val lazyDeferred = async(Dispatchers.IO) {
                val tok = section.lazyEmbedToken
                if (!tok.isNullOrBlank()) postEndpoint("/api/player-embed", tok) else null
            }
            btnDeferred.awaitAll() to lazyDeferred.await()
        }
    }

    suspend fun resolvePelisSeriesHoy(
        embedUrl: String,
        referer: String?,
    ): List<SoloLatinoWebPshServerResult> {
        val collected = mutableListOf<SoloLatinoWebPshServerResult>()
        resolvePelisSeriesHoyStreaming(embedUrl, referer) { srv ->
            synchronized(collected) { collected.add(srv) }
        }
        return collected
    }

    suspend fun resolvePelisSeriesHoyStreaming(
        embedUrl: String,
        referer: String?,
        onResolved: suspend (SoloLatinoWebPshServerResult) -> Unit,
    ): Boolean = pshMutex.withLock {
        val activeTestResponse = testWebResponse
        val effReferer = referer?.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${SoloLatinoMarkup.MAIN_URL}/"
        val emittedValues = HashSet<String>()
        val emittedAny = AtomicBoolean(false)

        suspend fun emitUnique(srv: SoloLatinoWebPshServerResult) {
            val key = "${srv.serverValue}|${srv.u.orEmpty()}|${srv.url.orEmpty()}"
            val added = synchronized(emittedValues) { emittedValues.add(key) }
            if (added) {
                emittedAny.set(true)
                onResolved(srv)
            }
        }

        if (activeTestResponse == null) {
            val httpInit = tryFetchPelisSeriesHoyInitViaHttp(embedUrl, effReferer)
            if (httpInit != null) {
                val (init, activeUa) = httpInit
                resolvePelisSeriesHoyFromInitHttp(
                    embedUrl = embedUrl,
                    init = init,
                    activeUa = activeUa,
                    seedServers = emptyList(),
                    skipServerValues = emptySet(),
                    onResolved = ::emitUnique,
                )
                if (emittedAny.get()) return@withLock true
            }
        }

        val ctx = appContext
        if (ctx != null) {
            val probe = runCatching {
                SoloLatinoWebTransport.resolvePelisSeriesHoy(
                    context = ctx,
                    embedUrl = embedUrl,
                    referer = effReferer,
                    overrideUserAgent = null,
                    testResponse = activeTestResponse,
                )
            }.getOrNull()

            if (probe != null) {
                recordWebUserAgent(embedUrl, probe.userAgent)
                saveCookiesFromResponse(pshCookies, emptyMap(), probe.cookieHeader)

                for (srv in probe.resolvedServers) {
                    emitUnique(srv)
                }

                if (activeTestResponse == null) {
                    val doneValues = probe.resolvedServers.map { it.serverValue }.toSet()
                    val initToUse = probe.init ?: tryFetchPelisSeriesHoyInitViaHttp(
                        embedUrl = embedUrl,
                        effReferer = effReferer,
                        forceUa = probe.userAgent.takeIf { it.isNotBlank() },
                    )?.first
                    if (initToUse != null) {
                        val activeUa = probe.userAgent.ifBlank { currentUserAgent(embedUrl) }
                        resolvePelisSeriesHoyFromInitHttp(
                            embedUrl = embedUrl,
                            init = initToUse,
                            activeUa = activeUa,
                            seedServers = probe.servers,
                            skipServerValues = doneValues,
                            alreadyEmitted = emittedAny.get(),
                            onResolved = ::emitUnique,
                        )
                    }
                }
            }
        }

        emittedAny.get()
    }

    private suspend fun tryFetchPelisSeriesHoyInitViaHttp(
        embedUrl: String,
        effReferer: String,
        forceUa: String? = null,
    ): Pair<SoloLatinoPelisSeriesHoyInit, String>? {
        val cookies = readMergedCookies(embedUrl, pshCookies)
        val hasClearance = cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
        val uaCandidates = if (!forceUa.isNullOrBlank()) {
            listOf(forceUa)
        } else {
            buildCandidateUserAgents(embedUrl, hasClearance)
        }
        for (uaAttempt in uaCandidates) {
            val hdrs = SoloLatinoSessionHelper.buildBrowserHeaders(uaAttempt, effReferer, isAjax = false)
            val attempt = runCatching {
                app.get(embedUrl, headers = hdrs, cookies = cookies, timeout = 12L)
            }.getOrNull() ?: continue
            saveCookiesFromResponse(pshCookies, attempt.cookies)
            if (attempt.code !in 200..299 || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(attempt.code, attempt.text)) {
                continue
            }
            val parsedInit = SoloLatinoMarkup.parsePelisSeriesHoyInit(attempt.text, attempt.url, effReferer)
            if (parsedInit != null) {
                return parsedInit to uaAttempt
            }
        }
        return null
    }

    private suspend fun resolvePelisSeriesHoyFromInitHttp(
        embedUrl: String,
        init: SoloLatinoPelisSeriesHoyInit,
        activeUa: String,
        seedServers: List<SoloLatinoPelisSeriesHoyServer>,
        skipServerValues: Set<String>,
        alreadyEmitted: Boolean = false,
        onResolved: suspend (SoloLatinoWebPshServerResult) -> Unit,
    ) {
        val postHeaders = mapOf(
            "User-Agent" to activeUa,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            "Origin" to init.originUrl,
            "Referer" to embedUrl,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val sEndpoint = "${init.originUrl}/s.php"

        fun baseParams(): MutableMap<String, String> = linkedMapOf(
            "tok" to init.token,
            "rt" to init.qv,
            "tk" to init.qt,
            "ao" to init.ao,
        ).apply {
            if (init.qsrc.isNotBlank()) put("src", init.qsrc)
        }

        // Send initial click signal matching onPlayClick() (without v param, matching player JS)
        runCatching {
            val clickRes = app.post(
                sEndpoint,
                headers = postHeaders,
                cookies = readMergedCookies(embedUrl, pshCookies),
                data = baseParams().apply {
                    put("a", "click")
                    put("ts", "1")
                },
                timeout = 8L,
            )
            saveCookiesFromResponse(pshCookies, clickRes.cookies)
        }

        val servers = if (seedServers.isNotEmpty()) {
            seedServers
        } else {
            val step1Res = runCatching {
                app.post(
                    sEndpoint,
                    headers = postHeaders,
                    cookies = readMergedCookies(embedUrl, pshCookies),
                    data = baseParams().apply { put("a", "1") },
                    timeout = 12L,
                )
            }.getOrNull()
            if (step1Res != null && step1Res.isSuccessful) {
                saveCookiesFromResponse(pshCookies, step1Res.cookies)
                SoloLatinoMarkup.parsePelisSeriesHoyStep1(step1Res.text)
            } else {
                emptyList()
            }
        }
        if (servers.isEmpty()) return

        val remaining = servers
            .filter { it.url !in skipServerValues }
            .take(5)
        if (remaining.isEmpty()) return

        val clickMutex = Mutex()
        val hasEmittedAny = AtomicBoolean(alreadyEmitted)
        val serverGate = Semaphore(2)

        coroutineScope {
            remaining.mapIndexed { idx, srv ->
                async(Dispatchers.IO) {
                    serverGate.withPermit {
                        withTimeoutOrNull(18_000L) {
                            suspend fun postStep2(): String? {
                                val res = runCatching {
                                    app.post(
                                        sEndpoint,
                                        headers = postHeaders,
                                        cookies = readMergedCookies(embedUrl, pshCookies),
                                        data = baseParams().apply {
                                            put("a", "2")
                                            put("v", srv.url)
                                        },
                                        timeout = 12L,
                                    )
                                }.getOrNull() ?: return null
                                saveCookiesFromResponse(pshCookies, res.cookies)
                                return if (res.isSuccessful) res.text else null
                            }

                            var bodyText = postStep2() ?: return@withTimeoutOrNull
                            if (bodyText.contains("no_click", ignoreCase = true) && !bodyText.contains("\"dead\":true", ignoreCase = true)) {
                                clickMutex.withLock {
                                    runCatching {
                                        app.post(
                                            sEndpoint,
                                            headers = postHeaders,
                                            cookies = readMergedCookies(embedUrl, pshCookies),
                                            data = baseParams().apply {
                                                put("a", "click")
                                                put("ts", "1")
                                            },
                                            timeout = 8L,
                                        )
                                    }
                                    delay(350L)
                                }
                                bodyText = postStep2() ?: return@withTimeoutOrNull
                            }
                            if (bodyText.contains("retry_once", ignoreCase = true)) {
                                delay(550L)
                                bodyText = postStep2() ?: return@withTimeoutOrNull
                            }
                            if (bodyText.contains("\"busy\"", ignoreCase = true) && (!hasEmittedAny.get() || idx == 0)) {
                                val retryMatch = Regex(""""retry"\s*:\s*(\d+)""").find(bodyText)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 6L
                                delay((retryMatch * 1000L).coerceIn(2_500L, 7_000L))
                                bodyText = postStep2() ?: return@withTimeoutOrNull
                            }

                            val parsed = SoloLatinoMarkup.parsePelisSeriesHoyStep2(bodyText) ?: return@withTimeoutOrNull
                            val mergedCookieStr = readMergedCookies(embedUrl, pshCookies)
                                .entries
                                .joinToString("; ") { "${it.key}=${it.value}" }
                            hasEmittedAny.set(true)
                            onResolved(
                                SoloLatinoWebPshServerResult(
                                    label = srv.label,
                                    langLabel = srv.langLabel,
                                    serverValue = srv.url,
                                    type = parsed.type,
                                    url = parsed.url,
                                    u = parsed.u,
                                    sig = parsed.sig,
                                    ctx = parsed.ctx,
                                    cookieHeader = mergedCookieStr,
                                    userAgent = activeUa,
                                ),
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun fetchExternalHtml(
        url: String,
        referer: String,
        allowedHost: (String) -> Boolean,
    ): SoloLatinoHttpResult = siteGate.withPermit {
        val isEmbed69 = SoloLatinoMarkup.hostOf(url).contains("embed69", ignoreCase = true)
        fun isUsable(c: Int, t: String): Boolean =
            c in 200..299 && t.isNotBlank() &&
                !SoloLatinoSessionHelper.isChromiumErrorPage(t) &&
                !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(c, t) &&
                (!isEmbed69 || t.contains("dataLink"))

        val activeTestResponse = testWebResponse
        var code = 0
        var finalUrl = url
        var text = ""
        if (activeTestResponse == null) {
            val cookies = readMergedCookies(url, externalCookies)
            val hasClearance = cookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
            val uaCandidates = buildCandidateUserAgents(url, hasClearance)
            for (uaAttempt in uaCandidates) {
                val headers = SoloLatinoSessionHelper.buildBrowserHeaders(uaAttempt, referer, isAjax = false)
                val direct = runCatching {
                    app.get(url, referer = referer, headers = headers, cookies = cookies, timeout = 12L)
                }.getOrNull() ?: continue
                code = direct.code
                finalUrl = direct.url
                text = direct.text
                saveCookiesFromResponse(externalCookies, direct.cookies)
                if (isUsable(code, text)) {
                    return@withPermit SoloLatinoHttpResult(code, finalUrl, text)
                }
            }
        }

        val ctx = appContext
        if (ctx != null) {
            val hostMutex = cfMutexFor(url)
            hostMutex.withLock {
                if (activeTestResponse == null) {
                    val retryCookies = readMergedCookies(url, externalCookies)
                    if (retryCookies.isNotEmpty()) {
                        for (uaAttempt in buildCandidateUserAgents(url, hasClearance = true)) {
                            val retryDirect = runCatching {
                                app.get(
                                    url,
                                    referer = referer,
                                    headers = SoloLatinoSessionHelper.buildBrowserHeaders(uaAttempt, referer, isAjax = false),
                                    cookies = retryCookies,
                                    timeout = 12L,
                                )
                            }.getOrNull() ?: continue
                            if (isUsable(retryDirect.code, retryDirect.text)) {
                                code = retryDirect.code
                                finalUrl = retryDirect.url
                                text = retryDirect.text
                                saveCookiesFromResponse(externalCookies, retryDirect.cookies)
                                return@withLock
                            }
                        }
                    }
                }
                val webRes = runCatching {
                    SoloLatinoWebTransport.getHtml(
                        context = ctx,
                        url = url,
                        referer = referer,
                        allowedHost = allowedHost,
                        overrideUserAgent = null,
                        fetchSameOriginEndpoint = false,
                        testResponse = activeTestResponse,
                    )
                }.getOrNull()
                if (webRes != null && !SoloLatinoSessionHelper.isChromiumErrorPage(webRes.text)) {
                    code = webRes.code
                    finalUrl = webRes.url
                    text = webRes.text
                    recordWebUserAgent(url, webRes.userAgent)
                    saveCookiesFromResponse(externalCookies, emptyMap(), webRes.cookieHeader)
                }
                if (activeTestResponse == null && !isUsable(code, text)) {
                    val postWebCookies = readMergedCookies(url, externalCookies)
                    val pinnedUa = currentUserAgent(url)
                    val postWeb = runCatching {
                        app.get(
                            url,
                            referer = referer,
                            headers = SoloLatinoSessionHelper.buildBrowserHeaders(pinnedUa, referer, isAjax = false),
                            cookies = postWebCookies,
                            timeout = 12L,
                        )
                    }.getOrNull()
                    if (postWeb != null && isUsable(postWeb.code, postWeb.text)) {
                        code = postWeb.code
                        finalUrl = postWeb.url
                        text = postWeb.text
                        saveCookiesFromResponse(externalCookies, postWeb.cookies)
                    }
                }
            }
        }
        SoloLatinoHttpResult(code, finalUrl, text)
    }
}
