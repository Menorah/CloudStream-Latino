package com.menorah.pelisjuanita

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object PelisJuanitaVerificationDialog {
    private const val VERIFY_URL = "${PelisJuanitaMarkup.MAIN_URL}/inicio.php?redirect=%2Fmovies%2F"

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    internal fun collectSiteCookies(currentUrl: String? = null): String = runCatching {
        val cm = CookieManager.getInstance()
        val apex = cm.getCookie(PelisJuanitaMarkup.MAIN_URL).orEmpty()
        val www = cm.getCookie("https://www.pelisjuanita.com").orEmpty()
        val cur = currentUrl?.takeIf { PelisJuanitaMarkup.isSiteUrl(it) }?.let { cm.getCookie(it).orEmpty() }.orEmpty()
        var merged = PelisJuanitaSessionHelper.mergeCookieHeaders(www, apex)
        merged = PelisJuanitaSessionHelper.mergeCookieHeaders(merged, cur)
        merged
    }.getOrNull().orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        sessionStore: PelisJuanitaSessionStore,
        onVerified: (() -> Unit)? = null,
    ) {
        val activity = context.findActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    PelisJuanitaSessionHelper.VERIFICATION_REQUIRED_MESSAGE,
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#111318"))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            val topBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#1B1F2A"))
                val pad = dp(activity, 10)
                setPadding(pad, pad, pad, pad)
            }

            val existing = sessionStore.read()
            val statusText = TextView(activity).apply {
                text = if (existing.hasPhpSession()) {
                    "Sesión guardada. Recarga o verifica de nuevo si caducó."
                } else {
                    "Completa el código y acepta términos para activar PelisJuanita"
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(activity, 8)
                }
            }

            val webViewHolder = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            }

            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isFocusable = true
                isFocusableInTouchMode = true
            }

            fun makeButton(label: String, bgHex: String): Button = Button(activity).apply {
                text = label
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundColor(Color.parseColor(bgHex))
                val hp = dp(activity, 10)
                val vp = dp(activity, 6)
                setPadding(hp, vp, hp, vp)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(activity, 6)
                }
            }

            val reloadBtn = makeButton("Recargar", "#2D3748")
            val clearBtn = makeButton("Limpiar", "#7C2D12")
            val closeBtn = makeButton("Cerrar", "#374151")

            topBar.addView(statusText)
            topBar.addView(reloadBtn)
            topBar.addView(clearBtn)
            topBar.addView(closeBtn)

            webViewHolder.addView(webView)
            root.addView(topBar)
            root.addView(webViewHolder)

            dialog.setContentView(root)
            dialog.window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                )
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }

            val handler = Handler(Looper.getMainLooper())
            val disposed = AtomicBoolean(false)
            val savedSuccess = AtomicBoolean(false)

            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(webView, true)
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
            }
            webView.webChromeClient = WebChromeClient()

            fun persistVerifiedSession(currentUrl: String, fallbackCookie: String, autoDismiss: Boolean) {
                if (!savedSuccess.compareAndSet(false, true)) return
                runCatching { CookieManager.getInstance().flush() }
                val finalCookie = collectSiteCookies(currentUrl).ifBlank { fallbackCookie }
                val ua = runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
                sessionStore.save(
                    PelisJuanitaSessionData(
                        cookieHeader = finalCookie,
                        userAgent = ua,
                        verifiedAtMs = System.currentTimeMillis(),
                    ),
                )
                statusText.text = "✅ Sesión verificada — ya puedes cerrar y reproducir"
                statusText.setTextColor(Color.parseColor("#86EFAC"))
                onVerified?.invoke()
                if (autoDismiss) {
                    handler.postDelayed({
                        if (!disposed.get() && dialog.isShowing) {
                            runCatching { dialog.dismiss() }
                        }
                    }, 900L)
                }
            }

            fun inspectCompletion(url: String?, allowEarlyDismissSave: Boolean = false) {
                if (disposed.get() || savedSuccess.get()) return
                val currentUrl = url ?: runCatching { webView.url }.getOrNull() ?: return
                if (!PelisJuanitaMarkup.isSiteUrl(currentUrl)) return
                if (currentUrl.contains("/inicio.php", ignoreCase = true)) return

                val cookieHeader = collectSiteCookies(currentUrl)
                val hasPhpSession = PelisJuanitaSessionHelper.parseCookieHeader(cookieHeader)
                    .keys.any { it.equals("PHPSESSID", ignoreCase = true) }
                if (!hasPhpSession) return

                if (allowEarlyDismissSave) {
                    persistVerifiedSession(currentUrl, cookieHeader, autoDismiss = false)
                    return
                }

                val js = "(document.documentElement ? document.documentElement.outerHTML.slice(0, 65000) : '')"
                webView.evaluateJavascript(js) { rawHtml ->
                    if (disposed.get() || savedSuccess.get()) return@evaluateJavascript
                    val html = (runCatching { JSONTokener(rawHtml ?: "null").nextValue() as? String }.getOrNull())
                        .orEmpty()
                    val verifiedBody = PelisJuanitaSessionHelper.looksLikeVerifiedCatalogOrDetail(currentUrl, html) ||
                        (html.isNotBlank() &&
                            !PelisJuanitaSessionHelper.looksLikeVerificationGate(200, currentUrl, html) &&
                            !PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(200, html))
                    if (verifiedBody) {
                        persistVerifiedSession(currentUrl, cookieHeader, autoDismiss = true)
                    }
                }
            }

            lateinit var pollVerification: Runnable
            pollVerification = Runnable {
                if (disposed.get() || savedSuccess.get()) return@Runnable
                inspectCompletion(null, allowEarlyDismissSave = false)
                handler.postDelayed(pollVerification, 650L)
            }
            handler.postDelayed(pollVerification, 900L)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (PelisJuanitaMarkup.isSiteUrl(target)) return false
                    return true
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    inspectCompletion(url, allowEarlyDismissSave = false)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    inspectCompletion(url, allowEarlyDismissSave = false)
                }
            }

            reloadBtn.setOnClickListener {
                savedSuccess.set(false)
                webView.reload()
            }

            clearBtn.setOnClickListener {
                savedSuccess.set(false)
                sessionStore.clear()
                statusText.text = "Sesión limpiada. Completa la verificación."
                statusText.setTextColor(Color.WHITE)
                webView.loadUrl(VERIFY_URL)
            }

            closeBtn.setOnClickListener {
                inspectCompletion(null, allowEarlyDismissSave = true)
                runCatching { dialog.dismiss() }
            }

            dialog.setOnDismissListener {
                inspectCompletion(null, allowEarlyDismissSave = true)
                if (disposed.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { CookieManager.getInstance().flush() }
                    runCatching {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    }
                }
            }

            dialog.show()
            webView.requestFocus()
            webView.loadUrl(VERIFY_URL)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
}

internal data class PelisJuanitaWebResponse(
    val code: Int,
    val url: String,
    val text: String,
    val cookieHeader: String = "",
)

/**
 * Off-screen WebView transport used ONLY after human verification when Cloudflare JS checks reject OkHttp.
 * Never attempts to bypass or auto-fill `/inicio.php` verification.
 */
internal object PelisJuanitaWebTransport {
    private const val WEBVIEW_TIMEOUT_MS = 25_000L
    private const val MAX_BODY_CHARS = 2_000_000
    private val slots = Semaphore(2)
    private val challengeHosts = listOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "cloudflareinsights.com",
        "cdnjs.cloudflare.com",
    )

    suspend fun get(
        context: Context,
        url: String,
        referer: String,
        userAgent: String? = null,
        seedCookieHeader: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): PelisJuanitaWebResponse {
        require(PelisJuanitaMarkup.isSiteUrl(url)) { "URL ajena a PelisJuanita" }
        return slots.withPermit { load(context, url, referer, null, userAgent, seedCookieHeader, testResponse) }
    }

    suspend fun post(
        context: Context,
        url: String,
        referer: String,
        form: Map<String, String>,
        userAgent: String? = null,
        seedCookieHeader: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): PelisJuanitaWebResponse {
        require(PelisJuanitaMarkup.isSiteUrl(url)) { "URL ajena a PelisJuanita" }
        return slots.withPermit { load(context, url, referer, form, userAgent, seedCookieHeader, testResponse) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(
        context: Context,
        target: String,
        referer: String,
        form: Map<String, String>?,
        userAgent: String?,
        seedCookieHeader: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): PelisJuanitaWebResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var httpStatus = 200
        var fetchStarted = false
        var generation = 0
        val isAjax = form != null ||
            target.contains("movies.php", ignoreCase = true) ||
            target.contains("apiSeries.php", ignoreCase = true) ||
            target.contains("movieInfo.php", ignoreCase = true) ||
            target.contains("serieInfo.php", ignoreCase = true)

        val bootstrapUrl = referer.takeIf {
            PelisJuanitaMarkup.isSiteUrl(it) && !it.contains("/inicio.php", ignoreCase = true)
        } ?: "${PelisJuanitaMarkup.MAIN_URL}/movies/"

        fun finish(result: Result<PelisJuanitaWebResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            val enriched = result?.map { res ->
                val cmCookies = PelisJuanitaVerificationDialog.collectSiteCookies(res.url)
                if (cmCookies.isNotBlank()) res.copy(cookieHeader = cmCookies) else res
            }
            handler.post {
                runCatching { CookieManager.getInstance().flush() }
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
            if (enriched != null && continuation.isActive) {
                enriched.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) },
                )
            }
        }

        fun parseJsonObject(raw: String?): JSONObject? = try {
            val s = JSONTokener(raw ?: "null").nextValue() as? String ?: return null
            JSONObject(s)
        } catch (_: Exception) {
            null
        }

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webView ?: return@Runnable
            view.evaluateJavascript("window.__pjFetchResult || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val obj = parseJsonObject(raw)
                if (obj != null) {
                    val resultUrl = obj.optString("url").ifBlank { target }
                    val text = obj.optString("text")
                    val code = obj.optInt("status", httpStatus).let { if (it > 0) it else httpStatus }
                    finish(Result.success(PelisJuanitaWebResponse(code, resultUrl, text)))
                } else {
                    handler.postDelayed(poll, 250L)
                }
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("El navegador integrado agotó el tiempo de espera con PelisJuanita.")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context.applicationContext)
                webView = view
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(view, true)
                }
                PelisJuanitaSessionHelper.syncMissingCookiesToWebView(seedCookieHeader)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                if (!userAgent.isNullOrBlank()) {
                    view.settings.userAgentString = userAgent
                }
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 1080, 1920)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !PelisJuanitaMarkup.isSiteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        val host = uri.host.orEmpty().lowercase()
                        if (scheme in listOf("blob", "data", "about")) {
                            return testResponse?.invoke(request)
                        }
                        val allowed = PelisJuanitaMarkup.isSiteUrl(uri.toString()) ||
                            (scheme == "https" && challengeHosts.any { host == it || host.endsWith(".$it") })
                        return if (allowed) {
                            testResponse?.invoke(request)
                        } else {
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        httpStatus = 200
                        fetchStarted = false
                        generation++
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) httpStatus = 0
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        if (url.contains("/inicio.php", ignoreCase = true)) {
                            finish(Result.success(PelisJuanitaWebResponse(302, url, "inicio.php")))
                            return
                        }
                        val inspect = "JSON.stringify({url:location.href,ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1}):'')})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != expectedGen) return@evaluateJavascript
                            val page = parseJsonObject(raw) ?: run {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                return@evaluateJavascript
                            }
                            val pageUrl = page.optString("url").ifBlank { url }
                            val pageText = page.optString("text")
                            val readyState = page.optString("ready")
                            if (PelisJuanitaSessionHelper.looksLikeVerificationGate(httpStatus, pageUrl, pageText)) {
                                finish(Result.success(PelisJuanitaWebResponse(302, pageUrl, pageText)))
                                return@evaluateJavascript
                            }
                            if (PelisJuanitaSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                return@evaluateJavascript
                            }
                            if (!isAjax && (httpStatus == 403 || httpStatus == 503) && pageText.isBlank()) {
                                finish(Result.success(PelisJuanitaWebResponse(httpStatus, pageUrl, pageText)))
                                return@evaluateJavascript
                            }
                            if (httpStatus == 0) {
                                finish(Result.failure(IllegalStateException("El navegador no pudo cargar PelisJuanita.")))
                                return@evaluateJavascript
                            }
                            if (!isAjax && readyState != "complete") {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                return@evaluateJavascript
                            }
                            if (!fetchStarted) {
                                fetchStarted = true
                                val fetchTarget = if (isAjax || URI(pageUrl).host.equals(URI(target).host, true)) {
                                    target
                                } else {
                                    pageUrl
                                }
                                val quotedTarget = JSONObject.quote(fetchTarget)
                                val script = if (form != null) {
                                    val encoded = form.entries.joinToString("&") { (k, v) ->
                                        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                                    }
                                    val quotedBody = JSONObject.quote(encoded)
                                    """window.__pjFetchResult=null;fetch($quotedTarget,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','X-Requested-With':'XMLHttpRequest'},body:$quotedBody}).then(async r=>{const t=await r.text();window.__pjFetchResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__pjFetchResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                } else if (isAjax) {
                                    """window.__pjFetchResult=null;fetch($quotedTarget,{credentials:'same-origin',headers:{'X-Requested-With':'XMLHttpRequest'}}).then(async r=>{const t=await r.text();window.__pjFetchResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__pjFetchResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                } else {
                                    """window.__pjFetchResult=null;fetch($quotedTarget,{credentials:'same-origin'}).then(async r=>{const t=await r.text();window.__pjFetchResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__pjFetchResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                }
                                view.evaluateJavascript(script, null)
                                handler.postDelayed(poll, 250L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                view.loadUrl(if (isAjax) bootstrapUrl else target)
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }
}
