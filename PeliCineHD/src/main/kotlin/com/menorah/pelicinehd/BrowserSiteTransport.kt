package com.menorah.pelicinehd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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

/**
 * Off-screen WebView transport using a clean mobile Chrome User-Agent (without `; wv`) so
 * Cloudflare does not reject the embedded WebView before/during challenges. Supports both GET
 * and same-origin form POST (for WordPress `admin-ajax.php`).
 */
internal class BrowserSiteTransport(
    private val context: Context,
    private val userAgent: String = SiteClient.FALLBACK_CHROME_UA,
    private val testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : SiteTransport {
    private val slots = Semaphore(3)

    override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
        require(PeliCineHDMarkup.siteUrl(url)) { "URL ajena a PeliCineHD" }
        return slots.withPermit { load(url, null, headers) }
    }

    override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): SiteResponse {
        require(PeliCineHDMarkup.siteUrl(url)) { "URL ajena a PeliCineHD" }
        return slots.withPermit { load(url, form, headers) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(
        target: String,
        form: Map<String, String>?,
        headers: Map<String, String>,
    ): SiteResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var status = 200
        var fetchStarted = false
        var generation = 0
        val isAjaxOrPost = form != null || URI(target).path.orEmpty().contains("/wp-admin/admin-ajax.php")
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val base = referer?.takeIf { PeliCineHDMarkup.siteUrl(it) && URI(it).host.equals(URI(target).host, true) }
            ?: URI(target).let { "${it.scheme}://${it.host}/" }

        fun finish(result: Result<SiteResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            handler.post {
                runCatching { CookieManager.getInstance().flush() }
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
            if (result != null && continuation.isActive) {
                result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) },
                )
            }
        }

        fun parseValue(raw: String?): JSONObject? = try {
            val string = JSONTokener(raw ?: "null").nextValue() as? String ?: return null
            JSONObject(string)
        } catch (_: Exception) {
            null
        }

        fun complete(data: JSONObject, defaultStatus: Int) {
            val resultUrl = data.optString("url")
            if (!PeliCineHDMarkup.siteUrl(resultUrl)) {
                finish(Result.failure(IllegalStateException("PeliCineHD redirigió fuera del sitio.")))
                return
            }
            val text = data.optString("text")
            val code = data.optInt("status", defaultStatus)
            if (text.length > MAX_BODY_CHARS) {
                finish(Result.failure(IllegalStateException("La respuesta de PeliCineHD es demasiado grande.")))
                return
            }
            if (SiteClient.looksLikeChallenge(text)) return
            finish(Result.success(SiteResponse(code, resultUrl, text)))
        }

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webView ?: return@Runnable
            view.evaluateJavascript("window.__pelicineResult || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val result = parseValue(raw)
                if (result != null) complete(result, 0) else handler.postDelayed(poll, 250L)
            }
        }
        val timeout = Runnable {
            finish(Result.failure(IllegalStateException("El navegador integrado no pudo abrir PeliCineHD a tiempo.")))
        }
        continuation.invokeOnCancellation { finish(null) }

        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context)
                webView = view
                runCatching {
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(view, true)
                }
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.blockNetworkImage = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                view.settings.userAgentString = userAgent
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !PeliCineHDMarkup.siteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val host = uri.host.orEmpty().lowercase()
                        val allowed = PeliCineHDMarkup.siteUrl(uri.toString()) ||
                            (uri.scheme == "https" && (host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com")))
                        return if (allowed) testResponse?.invoke(request)
                        else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        status = 200
                        fetchStarted = false
                        generation++
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) status = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) status = 0
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, currentGeneration: Int) {
                        if (finished.get() || generation != currentGeneration || !PeliCineHDMarkup.siteUrl(url)) return
                        val inspect = "JSON.stringify({url:location.href,text:document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1})})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != currentGeneration) return@evaluateJavascript
                            val page = parseValue(raw) ?: return@evaluateJavascript
                            if (SiteClient.looksLikeChallenge(page.optString("text"))) {
                                handler.postDelayed({ checkReady(view, url, currentGeneration) }, 300L)
                                return@evaluateJavascript
                            }
                            if (status == 0) {
                                finish(Result.failure(IllegalStateException("El navegador no pudo cargar PeliCineHD.")))
                            } else if (!fetchStarted) {
                                val sameOrigin = URI(url).host.equals(URI(target).host, true)
                                if (isAjaxOrPost && !sameOrigin) {
                                    finish(Result.failure(IllegalStateException("El navegador fue redirigido fuera del catálogo.")))
                                    return@evaluateJavascript
                                }
                                fetchStarted = true
                                val fetchTarget = if (sameOrigin || isAjaxOrPost) target else url
                                val quotedTarget = JSONObject.quote(fetchTarget)
                                val fetchScript = if (form != null) {
                                    val encodedBody = form.entries.joinToString("&") { (k, v) ->
                                        "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                                    }
                                    val quotedBody = JSONObject.quote(encodedBody)
                                    """window.__pelicineResult=null; fetch($quotedTarget,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','X-Requested-With':'XMLHttpRequest'},body:$quotedBody}).then(async r=>{let t=await r.text();window.__pelicineResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__pelicineResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                } else {
                                    """window.__pelicineResult=null; fetch($quotedTarget,{credentials:'same-origin'}).then(async r=>{let t=await r.text();window.__pelicineResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__pelicineResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                }
                                view.evaluateJavascript(fetchScript, null)
                                handler.postDelayed(poll, 250L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeout, WEBVIEW_MILLIS)
                view.loadUrl(if (isAjaxOrPost) base else target)
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    companion object {
        private const val WEBVIEW_MILLIS = 25_000L
        private const val MAX_BODY_CHARS = 2_000_000
    }
}
