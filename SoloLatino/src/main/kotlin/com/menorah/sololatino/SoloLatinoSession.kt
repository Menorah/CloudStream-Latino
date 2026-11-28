package com.menorah.sololatino

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
            (body.isBlank() || body.contains("Just a moment", ignoreCase = true) || body.contains("cloudflare", ignoreCase = true))
    }

    fun buildBrowserHeaders(
        userAgent: String,
        referer: String = "${SoloLatinoMarkup.MAIN_URL}/",
        isAjax: Boolean = false,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "user-agent" to userAgent,
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
    private const val WEBVIEW_TIMEOUT_MS = 25_000L
    private const val MAX_BODY_CHARS = 2_500_000
    private val slots = Semaphore(2)
    private val challengeHosts = listOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "cloudflareinsights.com",
        "cdnjs.cloudflare.com",
    )
    private val IMAGE_EXT_REGEX = Regex("""\.(?:jpg|jpeg|png|webp|gif|avif|svg)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
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

    private fun isAllowedInfrastructureHost(host: String): Boolean {
        val h = host.lowercase()
        return challengeHosts.any { h == it || h.endsWith(".$it") }
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

    private fun blockedResourceResponse(request: WebResourceRequest): WebResourceResponse =
        if (isImageRequest(request)) {
            WebResourceResponse("image/png", null, ByteArrayInputStream(TRANSPARENT_1PX_PNG))
        } else {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

    private fun createSafeChromeClient(): WebChromeClient = object : WebChromeClient() {
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView, userAgent: String?) {
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
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            if (!userAgent.isNullOrBlank()) {
                userAgentString = userAgent
            }
        }
        view.webChromeClient = createSafeChromeClient()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
    }

    private fun destroyWebViewSafely(view: WebView?) {
        if (view == null) return
        runCatching { view.stopLoading() }
        runCatching { view.onPause() }
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.clearHistory() }
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
        userAgent: String? = null,
        fetchSameOriginEndpoint: Boolean = false,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebHtmlResponse = slots.withPermit {
        loadSiteOrEndpoint(
            context = context,
            targetUrl = url,
            referer = referer,
            allowedHost = allowedHost,
            userAgent = userAgent,
            fetchSameOriginEndpoint = fetchSameOriginEndpoint,
            testResponse = testResponse,
        )
    }

    suspend fun resolveSitePlayers(
        context: Context,
        pageUrl: String,
        userAgent: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebPlayersResponse = slots.withPermit {
        resolveSitePlayersInternal(context, pageUrl, userAgent, testResponse)
    }

    suspend fun resolvePelisSeriesHoy(
        context: Context,
        embedUrl: String,
        referer: String,
        userAgent: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): List<SoloLatinoWebPshServerResult> = slots.withPermit {
        resolvePelisSeriesHoyInternal(context, embedUrl, referer, userAgent, testResponse)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadSiteOrEndpoint(
        context: Context,
        targetUrl: String,
        referer: String,
        allowedHost: (String) -> Boolean,
        userAgent: String?,
        fetchSameOriginEndpoint: Boolean,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebHtmlResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        var httpStatus = 200
        var fetchStarted = false
        var generation = 0
        var resolvedUa = userAgent.orEmpty()
        var fallbackDomText = ""
        var fallbackDomUrl = targetUrl

        val initialLoadUrl = if (fetchSameOriginEndpoint && referer.startsWith("http", ignoreCase = true) && allowedHost(referer)) {
            referer
        } else {
            targetUrl
        }

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
                        if (!fetchSameOriginEndpoint &&
                            (code !in 200..299 || fetchedText.isBlank() || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, fetchedText)) &&
                            fallbackDomText.isNotBlank() &&
                            !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(200, fallbackDomText)
                        ) {
                            finish(Result.success(SoloLatinoWebHtmlResponse(200, fallbackDomUrl, fallbackDomText, "", resolvedUa, offsetVal)))
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
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo cargando SoloLatino")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context.applicationContext)
                webViewRef.set(view)
                configureWebView(view, userAgent)
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(userAgent.orEmpty())
                view.webViewClient = object : WebViewClient() {
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
                            val allowed = allowedHost(uri.toString()) ||
                                (scheme == "https" && isAllowedInfrastructureHost(host))
                            if (allowed) {
                                testResponse?.invoke(request)
                            } else {
                                blockedResourceResponse(request)
                            }
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
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) error.errorCode else -99
                        }.getOrDefault(-99)
                        val errDesc = runCatching {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) error.description?.toString().orEmpty() else ""
                        }.getOrDefault("")
                        // window.stop() / blocked location.replace() triggers ERR_ABORTED (-1 / ERROR_UNKNOWN)
                        if (errCode != ERROR_UNKNOWN && !errDesc.contains("ERR_ABORTED", ignoreCase = true)) {
                            httpStatus = 0
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        val inspect = "JSON.stringify({url:location.href,ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1}):'')})"
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
                                if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                    return@evaluateJavascript
                                }
                                val onAllowedPage = allowedHost(pageUrl)
                                if (httpStatus == 0 && !onAllowedPage) {
                                    finish(Result.failure(IllegalStateException("Error de red en WebView")))
                                    return@evaluateJavascript
                                }
                                val stoppedHeadPage = !fetchSameOriginEndpoint && onAllowedPage &&
                                    (pageText.contains("window.stop", ignoreCase = true) ||
                                        pageText.contains("dataLink", ignoreCase = false))
                                if (!fetchSameOriginEndpoint && readyState != "complete" && !stoppedHeadPage) {
                                    handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                    return@evaluateJavascript
                                }
                                if (!fetchSameOriginEndpoint && testResponse != null) {
                                    finish(Result.success(SoloLatinoWebHtmlResponse(200, pageUrl, pageText, "", resolvedUa, null)))
                                    return@evaluateJavascript
                                }
                                if (!fetchSameOriginEndpoint && pageText.contains("dataLink") && !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(200, pageText)) {
                                    finish(Result.success(SoloLatinoWebHtmlResponse(200, pageUrl, pageText, "", resolvedUa, null)))
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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveSitePlayersInternal(
        context: Context,
        pageUrl: String,
        userAgent: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebPlayersResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        var httpStatus = 200
        var resolveStarted = false
        var generation = 0
        var resolvedUa = userAgent.orEmpty()

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
                        handler.postDelayed(poll, 250L)
                    }
                }
            }.onFailure {
                if (!finished.get()) handler.postDelayed(poll, 250L)
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo resolviendo reproductores de SoloLatino")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context.applicationContext)
                webViewRef.set(view)
                configureWebView(view, userAgent)
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(userAgent.orEmpty())
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !SoloLatinoMarkup.isSiteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        runCatching {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            val host = uri.host.orEmpty()
                            if (scheme in listOf("blob", "data", "about")) return@runCatching testResponse?.invoke(request)
                            val allowed = SoloLatinoMarkup.isSiteUrl(uri.toString()) ||
                                (scheme == "https" && isAllowedInfrastructureHost(host))
                            if (allowed) {
                                testResponse?.invoke(request)
                            } else {
                                blockedResourceResponse(request)
                            }
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
                                          const xsrf = getCookie('XSRF-TOKEN');
                                          async function shield(token) {
                                            const slot = Math.floor((Date.now() + offsetMs) / 60000);
                                            const data = new TextEncoder().encode(token + ':' + slot + ':' + $secretQuoted);
                                            const digest = await crypto.subtle.digest('SHA-256', data);
                                            return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
                                          }
                                          async function postToken(endpoint, token) {
                                            try {
                                              const sh = await shield(token);
                                              const h = {
                                                'Content-Type': 'application/json',
                                                'Accept': 'application/json',
                                                'X-Requested-With': 'XMLHttpRequest',
                                                'X-SL-Render': sh
                                              };
                                              if (csrf) h['X-CSRF-TOKEN'] = csrf;
                                              if (xsrf) h['X-XSRF-TOKEN'] = xsrf;
                                              const r = await fetch(endpoint, {
                                                method: 'POST',
                                                credentials: 'same-origin',
                                                headers: h,
                                                body: JSON.stringify({ t: token })
                                              });
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
                                    handler.postDelayed(poll, 260L)
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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolvePelisSeriesHoyInternal(
        context: Context,
        embedUrl: String,
        referer: String,
        userAgent: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): List<SoloLatinoWebPshServerResult> = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        var httpStatus = 200
        var resolveStarted = false
        var generation = 0

        fun finish(result: Result<List<SoloLatinoWebPshServerResult>>?) {
            if (!finished.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            val viewToDestroy = webViewRef.getAndSet(null)
            handler.post {
                runCatching { CookieManager.getInstance().flush() }
                destroyWebViewSafely(viewToDestroy)
            }
            if (result != null && continuation.isActive) {
                runCatching {
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) },
                    )
                }
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo en player.pelisserieshoy.com")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context.applicationContext)
                webViewRef.set(view)
                configureWebView(view, userAgent)

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
                                handler.postDelayed(poll, 260L)
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
                                val out = mutableListOf<SoloLatinoWebPshServerResult>()
                                val arr = obj.optJSONArray("servers") ?: JSONArray()
                                for (i in 0 until arr.length()) {
                                    val s = arr.optJSONObject(i) ?: continue
                                    out.add(
                                        SoloLatinoWebPshServerResult(
                                            label = s.optString("label").ifBlank { "Servidor" },
                                            langLabel = SoloLatinoMarkup.normalizeLanguageLabel(s.optString("lang")),
                                            serverValue = s.optString("serverValue"),
                                            type = s.optString("type").takeIf { it.isNotBlank() },
                                            url = s.optString("url").takeIf { it.isNotBlank() },
                                            u = s.optString("u").takeIf { it.isNotBlank() },
                                            sig = s.optString("sig").takeIf { it.isNotBlank() },
                                            ctx = s.optString("ctx").takeIf { it.isNotBlank() },
                                        ),
                                    )
                                }
                                val cmCookie = runCatching { CookieManager.getInstance().getCookie(embedUrl).orEmpty() }.getOrDefault("")
                                finish(Result.success(out.map { it.copy(cookieHeader = cmCookie) }))
                            } else {
                                handler.postDelayed(poll, 260L)
                            }
                        }
                    }.onFailure {
                        if (!finished.get()) handler.postDelayed(poll, 260L)
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
                                if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                    handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 350L)
                                    return@evaluateJavascript
                                }
                                // Note: player.pelisserieshoy.com calls window.stop() + location.replace('google.com') when isTop === true.
                                // Ignore ERR_ABORTED when already on a PelisSeriesHoy URL and inspect DOM / fetch(embedUrl).
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
                                              const r = await fetch($quotedEmbed, { credentials: 'same-origin' });
                                              const html = await r.text();
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
                                            window.__slPshOut = JSON.stringify({ servers: [] });
                                            return;
                                          }
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
                                          async function postS(params) {
                                            const enriched = Object.assign({ rt, tk, ao: originAo }, params);
                                            if (qsrc) enriched.src = qsrc;
                                            const body = new URLSearchParams(enriched);
                                            const r = await fetch('/s.php', {
                                              method: 'POST',
                                              credentials: 'same-origin',
                                              headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
                                              body
                                            });
                                            return await r.json();
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
                                            const resolved = [];
                                            for (const srv of flat) {
                                              try {
                                                await postS({ a: 'click', tok, ts: '1', v: srv.serverValue }).catch(() => null);
                                                let a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                if (a2 && a2.type === 'error' && a2.msg === 'no_click') {
                                                  await postS({ a: 'click', tok, ts: '1' }).catch(() => null);
                                                  await new Promise(r => setTimeout(r, 350));
                                                  a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                } else if (a2 && a2.error === 'retry_once') {
                                                  await new Promise(r => setTimeout(r, 500));
                                                  a2 = await postS({ a: '2', v: srv.serverValue, tok });
                                                }
                                                resolved.push({
                                                  label: srv.label,
                                                  lang: srv.lang,
                                                  serverValue: srv.serverValue,
                                                  type: a2 && a2.type ? a2.type : '',
                                                  url: a2 && a2.url ? a2.url : '',
                                                  u: a2 && a2.u ? a2.u : '',
                                                  sig: a2 && a2.sig ? a2.sig : '',
                                                  ctx: a2 && a2.ctx ? a2.ctx : ''
                                                });
                                              } catch (_) {}
                                            }
                                            window.__slPshOut = JSON.stringify({ servers: resolved });
                                          } catch (_) {
                                            window.__slPshOut = JSON.stringify({ servers: [] });
                                          }
                                        })();
                                    """.trimIndent()
                                    targetView.evaluateJavascript(js, null)
                                    handler.postDelayed(poll, 260L)
                                }
                            }
                        }.onFailure {
                            if (!finished.get()) handler.postDelayed({ checkReadyFn(targetView, url, expectedGen) }, 220L)
                        }
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !SoloLatinoMarkup.isPelisSeriesHoyUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        runCatching {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase().orEmpty()
                            val host = uri.host.orEmpty()
                            if (scheme in listOf("blob", "data", "about")) return@runCatching testResponse?.invoke(request)
                            val allowed = SoloLatinoMarkup.isPelisSeriesHoyUrl(uri.toString()) ||
                                (scheme == "https" && isAllowedInfrastructureHost(host))
                            if (allowed) {
                                testResponse?.invoke(request)
                            } else {
                                blockedResourceResponse(request)
                            }
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
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) error.errorCode else -99
                        }.getOrDefault(-99)
                        val errDesc = runCatching {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) error.description?.toString().orEmpty() else ""
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
    private val serverTimeOffsetMs = AtomicLong(0L)
    private val hostMutexes = ConcurrentHashMap<String, Mutex>()
    private val pshMutex = Mutex()
    private val siteGate = Semaphore(4)
    private val cfKiller by lazy { CloudflareKiller() }

    private fun cfMutexFor(url: String): Mutex {
        val host = SoloLatinoMarkup.hostOf(url).removePrefix("www.").ifBlank { "default" }
        return hostMutexes.getOrPut(host) { Mutex() }
    }

    @Volatile
    private var preferredUserAgent: String = ""

    @Volatile
    internal var testWebResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null

    fun currentUserAgent(): String {
        WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
        if (preferredUserAgent.isNotBlank()) return preferredUserAgent
        val sysUa = appContext?.let { ctx -> runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return sysUa ?: SoloLatinoSessionHelper.DEFAULT_USER_AGENT
    }

    private fun readMergedCookies(url: String, localStore: ConcurrentHashMap<String, String>): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(localStore)
        if (appContext == null) {
            val host = SoloLatinoMarkup.hostOf(url)
            runCatching {
                cfKiller.savedCookies[host]?.let { merged.putAll(it) }
                if (host.startsWith("www.")) {
                    cfKiller.savedCookies[host.removePrefix("www.")]?.let { merged.putAll(it) }
                }
            }
        }
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
            val ua = currentUserAgent()
            val cookies = readMergedCookies(url, siteCookies)
            val headers = SoloLatinoSessionHelper.buildBrowserHeaders(ua, referer, isAjax)
            val direct = runCatching {
                app.get(url, headers = headers, cookies = cookies, timeout = 14L)
            }.getOrNull()
            if (direct != null) {
                code = direct.code
                finalUrl = direct.url
                text = direct.text
                dateHeader = direct.headers["date"] ?: direct.headers["Date"]
                updateServerOffset(dateHeader)
                saveCookiesFromResponse(siteCookies, direct.cookies)
            }

            if (ctx == null && (code == 0 || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text))) {
                cfMutexFor(url).withLock {
                    val uaAfter = currentUserAgent()
                    val cookiesAfter = readMergedCookies(url, siteCookies)
                    val cfRes = runCatching {
                        app.get(
                            url,
                            headers = SoloLatinoSessionHelper.buildBrowserHeaders(uaAfter, referer, isAjax),
                            cookies = cookiesAfter,
                            interceptor = cfKiller,
                            timeout = 20L,
                        )
                    }.getOrNull()
                    if (cfRes != null) {
                        code = cfRes.code
                        finalUrl = cfRes.url
                        text = cfRes.text
                        dateHeader = cfRes.headers["date"] ?: cfRes.headers["Date"]
                        updateServerOffset(dateHeader)
                        WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
                        saveCookiesFromResponse(siteCookies, cfRes.cookies)
                    }
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
                            headers = SoloLatinoSessionHelper.buildBrowserHeaders(currentUserAgent(), referer, isAjax),
                            cookies = refreshedCookies,
                            timeout = 14L,
                        )
                    }.getOrNull()
                    if (retryDirect != null &&
                        retryDirect.code in 200..299 &&
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
                        userAgent = currentUserAgent(),
                        fetchSameOriginEndpoint = isAjax,
                        testResponse = activeTestResponse,
                    )
                }.getOrNull()
                if (webRes != null) {
                    code = webRes.code
                    finalUrl = webRes.url
                    text = webRes.text
                    if (webRes.userAgent.isNotBlank()) preferredUserAgent = webRes.userAgent
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
                    userAgent = currentUserAgent(),
                    testResponse = activeTestResponse,
                )
            }.getOrNull()
            if (webOut != null) {
                webOut.offsetMs?.let { serverTimeOffsetMs.set(it) }
                if (webOut.userAgent.isNotBlank()) preferredUserAgent = webOut.userAgent
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
        val origin = "${SoloLatinoMarkup.MAIN_URL}"
        val ua = currentUserAgent()

        // Step 1: refresh Sanctum CSRF cookie and synchronize Date header offset
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

        val mergedCookies = readMergedCookies(pageUrl, siteCookies)
        val xsrfRaw = mergedCookies.entries.firstOrNull { it.key.equals("XSRF-TOKEN", ignoreCase = true) }?.value
        val xsrfToken = SoloLatinoMarkup.decodeCookieValue(xsrfRaw)
        val metaCsrf = section.csrfToken

        suspend fun postEndpoint(endpoint: String, token: String): SoloLatinoPlayerApiResult? {
            if (token.isBlank()) return null
            val shield = SoloLatinoMarkup.computeRenderShieldHeader(token, serverTimeOffsetMs.get())
            val reqHeaders = linkedMapOf(
                "user-agent" to ua,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
                "Content-Type" to "application/json",
                "Origin" to origin,
                "Referer" to pageUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "X-SL-Render" to shield,
            )
            if (xsrfToken.isNotBlank()) reqHeaders["X-XSRF-TOKEN"] = xsrfToken
            if (!metaCsrf.isNullOrBlank()) reqHeaders["X-CSRF-TOKEN"] = metaCsrf
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
                return null
            }
            if (!res.isSuccessful) return null
            saveCookiesFromResponse(siteCookies, res.cookies)
            return SoloLatinoMarkup.parsePlayerApiResponse(res.text)
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
    ): List<SoloLatinoWebPshServerResult> = pshMutex.withLock {
        val activeTestResponse = testWebResponse
        val effReferer = referer?.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${SoloLatinoMarkup.MAIN_URL}/"

        if (activeTestResponse == null) {
            val httpAttempt = tryResolvePelisSeriesHoyViaHttp(embedUrl, effReferer)
            if (httpAttempt.isNotEmpty()) return@withLock httpAttempt
        }

        val ctx = appContext
        if (ctx != null) {
            val webAttempt = runCatching {
                SoloLatinoWebTransport.resolvePelisSeriesHoy(
                    context = ctx,
                    embedUrl = embedUrl,
                    referer = effReferer,
                    userAgent = currentUserAgent(),
                    testResponse = activeTestResponse,
                )
            }.getOrNull()
            runCatching {
                val cmCookie = CookieManager.getInstance().getCookie(embedUrl).orEmpty()
                saveCookiesFromResponse(pshCookies, emptyMap(), cmCookie)
            }
            if (!webAttempt.isNullOrEmpty()) {
                return@withLock webAttempt
            }
            // If WebView solved Cloudflare (populating CookieManager) but DOM/fetch was blocked by
            // window.stop() or strict Referer checks, retry HTTP using the fresh WebView cookies + UA + Referer.
            if (activeTestResponse == null) {
                val postWebHttp = tryResolvePelisSeriesHoyViaHttp(embedUrl, effReferer, skipCloudflareKiller = true)
                if (postWebHttp.isNotEmpty()) return@withLock postWebHttp
            }
        }
        emptyList()
    }

    private suspend fun tryResolvePelisSeriesHoyViaHttp(
        embedUrl: String,
        effReferer: String,
        skipCloudflareKiller: Boolean = false,
    ): List<SoloLatinoWebPshServerResult> {
        val ua = currentUserAgent()
        val cookies = readMergedCookies(embedUrl, pshCookies)
        val getHeaders = SoloLatinoSessionHelper.buildBrowserHeaders(ua, effReferer, isAjax = false)
        var pageRes = runCatching {
            app.get(embedUrl, headers = getHeaders, cookies = cookies, timeout = 12L)
        }.getOrNull()
        if (pageRes != null) {
            saveCookiesFromResponse(pshCookies, pageRes.cookies)
        }
        if (!skipCloudflareKiller && appContext == null &&
            (pageRes == null || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(pageRes.code, pageRes.text))
        ) {
            val cfPage = runCatching {
                app.get(
                    embedUrl,
                    headers = getHeaders,
                    cookies = readMergedCookies(embedUrl, pshCookies),
                    interceptor = cfKiller,
                    timeout = 20L,
                )
            }.getOrNull()
            if (cfPage != null) {
                pageRes = cfPage
                saveCookiesFromResponse(pshCookies, cfPage.cookies)
                WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
            }
        }
        val finalPage = pageRes ?: return emptyList()
        if (!finalPage.isSuccessful || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(finalPage.code, finalPage.text)) {
            return emptyList()
        }
        val init = SoloLatinoMarkup.parsePelisSeriesHoyInit(finalPage.text, finalPage.url, effReferer) ?: return emptyList()

        val activeUa = currentUserAgent()
        val postCookies = readMergedCookies(finalPage.url, pshCookies)
        val postHeaders = mapOf(
            "user-agent" to activeUa,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            "Origin" to init.originUrl,
            "Referer" to finalPage.url,
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

        // Send initial click signal matching onPlayClick() before a=1 / a=2
        runCatching {
            val clickRes = app.post(
                sEndpoint,
                headers = postHeaders,
                cookies = postCookies,
                data = baseParams().apply {
                    put("a", "click")
                    put("ts", "1")
                },
                timeout = 8L,
            )
            saveCookiesFromResponse(pshCookies, clickRes.cookies)
        }

        val step1Res = runCatching {
            app.post(
                sEndpoint,
                headers = postHeaders,
                cookies = readMergedCookies(finalPage.url, pshCookies),
                data = baseParams().apply { put("a", "1") },
                timeout = 12L,
            )
        }.getOrNull() ?: return emptyList()
        if (!step1Res.isSuccessful) return emptyList()
        saveCookiesFromResponse(pshCookies, step1Res.cookies)

        val servers = SoloLatinoMarkup.parsePelisSeriesHoyStep1(step1Res.text)
        if (servers.isEmpty()) return emptyList()

        val results = mutableListOf<SoloLatinoWebPshServerResult>()
        for (srv in servers) {
            runCatching {
                app.post(
                    sEndpoint,
                    headers = postHeaders,
                    cookies = readMergedCookies(finalPage.url, pshCookies),
                    data = baseParams().apply {
                        put("a", "click")
                        put("ts", "1")
                        put("v", srv.url)
                    },
                    timeout = 8L,
                )
            }
            var step2Res = runCatching {
                app.post(
                    sEndpoint,
                    headers = postHeaders,
                    cookies = readMergedCookies(finalPage.url, pshCookies),
                    data = baseParams().apply {
                        put("a", "2")
                        put("v", srv.url)
                    },
                    timeout = 12L,
                )
            }.getOrNull() ?: continue
            if (step2Res.isSuccessful && step2Res.text.contains("no_click", ignoreCase = true)) {
                runCatching {
                    app.post(
                        sEndpoint,
                        headers = postHeaders,
                        cookies = readMergedCookies(finalPage.url, pshCookies),
                        data = baseParams().apply {
                            put("a", "click")
                            put("ts", "1")
                        },
                        timeout = 8L,
                    )
                }
                step2Res = runCatching {
                    app.post(
                        sEndpoint,
                        headers = postHeaders,
                        cookies = readMergedCookies(finalPage.url, pshCookies),
                        data = baseParams().apply {
                            put("a", "2")
                            put("v", srv.url)
                        },
                        timeout = 12L,
                    )
                }.getOrNull() ?: continue
            } else if (step2Res.isSuccessful && step2Res.text.contains("retry_once", ignoreCase = true)) {
                step2Res = runCatching {
                    app.post(
                        sEndpoint,
                        headers = postHeaders,
                        cookies = readMergedCookies(finalPage.url, pshCookies),
                        data = baseParams().apply {
                            put("a", "2")
                            put("v", srv.url)
                        },
                        timeout = 12L,
                    )
                }.getOrNull() ?: continue
            }
            if (!step2Res.isSuccessful) continue
            val parsed = SoloLatinoMarkup.parsePelisSeriesHoyStep2(step2Res.text) ?: continue
            val mergedCookieStr = readMergedCookies(finalPage.url, pshCookies).entries.joinToString("; ") { "${it.key}=${it.value}" }
            results.add(
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
                ),
            )
        }
        return results
    }

    suspend fun fetchExternalHtml(
        url: String,
        referer: String,
        allowedHost: (String) -> Boolean,
    ): SoloLatinoHttpResult = siteGate.withPermit {
        val ua = currentUserAgent()
        val activeTestResponse = testWebResponse
        var code = 0
        var finalUrl = url
        var text = ""
        if (activeTestResponse == null) {
            val cookies = readMergedCookies(url, externalCookies)
            val headers = SoloLatinoSessionHelper.buildBrowserHeaders(ua, referer, isAjax = false)
            val direct = runCatching { app.get(url, referer = referer, headers = headers, cookies = cookies, timeout = 12L) }.getOrNull()
            if (direct != null) {
                code = direct.code
                finalUrl = direct.url
                text = direct.text
                saveCookiesFromResponse(externalCookies, direct.cookies)
            }
        }
        val needsFallback = code !in 200..299 ||
            text.isBlank() ||
            SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text)
        if (!needsFallback) {
            return@withPermit SoloLatinoHttpResult(code, finalUrl, text)
        }

        val ctx = appContext
        val hostMutex = cfMutexFor(url)
        if (ctx == null) {
            hostMutex.withLock {
                val cfRes = runCatching {
                    app.get(
                        url,
                        referer = referer,
                        headers = SoloLatinoSessionHelper.buildBrowserHeaders(currentUserAgent(), referer, isAjax = false),
                        cookies = readMergedCookies(url, externalCookies),
                        interceptor = cfKiller,
                        timeout = 18L,
                    )
                }.getOrNull()
                if (cfRes != null) {
                    code = cfRes.code
                    finalUrl = cfRes.url
                    text = cfRes.text
                    saveCookiesFromResponse(externalCookies, cfRes.cookies)
                    WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
                }
            }
        } else {
            hostMutex.withLock {
                if (activeTestResponse == null) {
                    val retryCookies = readMergedCookies(url, externalCookies)
                    if (retryCookies.isNotEmpty()) {
                        val retryDirect = runCatching {
                            app.get(
                                url,
                                referer = referer,
                                headers = SoloLatinoSessionHelper.buildBrowserHeaders(currentUserAgent(), referer, isAjax = false),
                                cookies = retryCookies,
                                timeout = 12L,
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
                            saveCookiesFromResponse(externalCookies, retryDirect.cookies)
                            return@withLock
                        }
                    }
                }
                val webRes = runCatching {
                    SoloLatinoWebTransport.getHtml(
                        context = ctx,
                        url = url,
                        referer = referer,
                        allowedHost = allowedHost,
                        userAgent = currentUserAgent(),
                        fetchSameOriginEndpoint = false,
                        testResponse = activeTestResponse,
                    )
                }.getOrNull()
                if (webRes != null) {
                    code = webRes.code
                    finalUrl = webRes.url
                    text = webRes.text
                    if (webRes.userAgent.isNotBlank()) preferredUserAgent = webRes.userAgent
                    saveCookiesFromResponse(externalCookies, emptyMap(), webRes.cookieHeader)
                }
                val isEmbed69Incomplete = SoloLatinoMarkup.hostOf(url).contains("embed69", ignoreCase = true) &&
                    !text.contains("dataLink")
                if (activeTestResponse == null &&
                    (code !in 200..299 || text.isBlank() ||
                        SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text) || isEmbed69Incomplete)
                ) {
                    val postWebCookies = readMergedCookies(url, externalCookies)
                    val postWeb = runCatching {
                        app.get(
                            url,
                            referer = referer,
                            headers = SoloLatinoSessionHelper.buildBrowserHeaders(currentUserAgent(), referer, isAjax = false),
                            cookies = postWebCookies,
                            timeout = 12L,
                        )
                    }.getOrNull()
                    if (postWeb != null &&
                        postWeb.code in 200..299 &&
                        postWeb.text.isNotBlank() &&
                        !SoloLatinoSessionHelper.looksLikeCloudflareChallenge(postWeb.code, postWeb.text)
                    ) {
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
