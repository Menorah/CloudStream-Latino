package com.menorah.pelicinehd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A small, off-screen WebView. It returns page data, never cookies, and handles GET only. */
internal class BrowserSiteTransport(
    private val context: Context,
    // Optional response source for deterministic on-device tests; production leaves it null.
    private val testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : SiteTransport, SitePoster, CookieSource {
    private val slots = Semaphore(4)

    override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
        require(PeliCineHDMarkup.siteUrl(url)) { "URL ajena al sitio" }
        return slots.withPermit { load(url, headers) }
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        data: Map<String, String>
    ): SiteResponse {
        require(PeliCineHDMarkup.siteUrl(url)) { "URL ajena al sitio" }
        val body = JSONObject(data as Map<*, *>).toString()
        return slots.withPermit { load(url, headers, postBody = body) }
    }

    override suspend fun cookiesFor(url: String): Map<String, String> {
        if (!PeliCineHDMarkup.siteUrl(url)) return emptyMap()
        val jar = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        return jar.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(
        target: String,
        headers: Map<String, String>,
        postBody: String? = null
    ): SiteResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var status = 200
        var fetchStarted = false
        var generation = 0
        val apiRequest = URI(target).path.orEmpty().startsWith("/wp-admin/admin-ajax.php") &&
            postBody == null
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val base = referer?.takeIf { PeliCineHDMarkup.siteUrl(it) && URI(it).host.equals(URI(target).host, true) }
            ?: URI(target).let { "${it.scheme}://${it.host}/" }

        fun finish(result: Result<SiteResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            handler.post {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
            if (result != null && continuation.isActive) result.fold(
                onSuccess = { continuation.resume(it) }, onFailure = { continuation.resumeWithException(it) }
            )
        }

        fun parseValue(raw: String?): JSONObject? {
            return try {
                val string = JSONTokener(raw ?: "null").nextValue() as? String ?: return null
                JSONObject(string)
            } catch (_: Exception) { null }
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
                finish(Result.failure(IllegalStateException("La respuesta del sitio es demasiado grande.")))
                return
            }
            // Wait for a browser challenge to finish rather than treating its HTML as a catalogue.
            if (looksLikeChallenge(text)) return
            finish(Result.success(SiteResponse(code, resultUrl, text)))
        }

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webView ?: return@Runnable
            view.evaluateJavascript("window.__peliCineResult || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val result = parseValue(raw)
                if (result != null) complete(result, 0) else handler.postDelayed(poll, 250L)
            }
        }
        val timeout = Runnable { finish(Result.failure(IllegalStateException("El navegador integrado no pudo abrir PeliCineHD a tiempo."))) }
        continuation.invokeOnCancellation { finish(null) }
        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context)
                webView = view
                // Android's WebView advertises itself with "; wv", which Cloudflare's bot rules
                // reject even after the challenge. A plain Chrome User-Agent is required here.
                view.settings.userAgentString = BROWSER_AGENT
                CookieManager.getInstance().setAcceptCookie(true)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.blockNetworkImage = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !PeliCineHDMarkup.siteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val host = uri.host.orEmpty().lowercase()
                        val allowed = PeliCineHDMarkup.siteUrl(uri.toString()) ||
                            (uri.scheme == "https" && host == "challenges.cloudflare.com")
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
                        if (finished.get() || !PeliCineHDMarkup.siteUrl(url)) return
                        val currentGeneration = generation
                        val inspect = "JSON.stringify({url:location.href,text:document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1})})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != currentGeneration) return@evaluateJavascript
                            val page = parseValue(raw) ?: return@evaluateJavascript
                            if (looksLikeChallenge(page.optString("text"))) return@evaluateJavascript
                            if (status == 0) {
                                finish(Result.failure(IllegalStateException("El navegador no pudo cargar la página del sitio.")))
                            } else if (!fetchStarted) {
                                val sameOrigin = URI(url).host.equals(URI(target).host, true)
                                if (apiRequest && !sameOrigin) {
                                    finish(Result.failure(IllegalStateException("El navegador fue redirigido fuera del catálogo.")))
                                    return@evaluateJavascript
                                }
                                // A WebView's page callbacks do not reliably expose HTTP status.
                                // Fetch even HTML from the same origin so 403 is never mistaken for 200.
                                fetchStarted = true
                                // Report an allowed landing redirect instead of issuing a cross-origin fetch.
                                val quoted = JSONObject.quote(if (sameOrigin) target else url)
                                val options = if (postBody == null) {
                                    "{credentials:'same-origin'}"
                                } else {
                                    val encoded = postBody
                                    "{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','X-Requested-With':'XMLHttpRequest'},body:${JSONObject.quote(encoded)}}"
                                }
                                val script = """window.__peliCineResult=null; fetch($quoted,$options).then(async r=>{let t=await r.text();window.__peliCineResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__peliCineResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                view.evaluateJavascript(script, null)
                                handler.postDelayed(poll, 250L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeout, WEBVIEW_MILLIS)
                if (postBody != null) {
                    // Land on the site first so the POST is same-origin and carries the cookies.
                    view.loadUrl(base)
                } else {
                    view.loadUrl(if (apiRequest) base else target)
                }
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    companion object {
        /**
         * The UA a stock Chrome on Android sends. It must not contain `; wv`: Cloudflare treats
         * an embedded WebView as a bot and answers 403 before the challenge even starts.
         */
        const val BROWSER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val WEBVIEW_MILLIS = 25_000L
        private const val MAX_BODY_CHARS = 2_000_000
        internal fun looksLikeChallenge(text: String): Boolean = PeliCineHDMarkup.isChallenge(text)
    }
}
