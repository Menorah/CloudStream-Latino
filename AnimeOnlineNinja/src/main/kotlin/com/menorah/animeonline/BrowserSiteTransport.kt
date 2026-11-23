package com.menorah.animeonline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A small, off-screen WebView. It returns page data, never cookies, and handles GET only. */
internal class BrowserSiteTransport(
    private val context: Context,
    // Optional response source for deterministic on-device tests; production leaves it null.
    private val testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : SiteTransport {
    private val slots = Semaphore(2)

    override suspend fun get(url: String, headers: Map<String, String>): SiteResponse {
        require(isSiteUrl(url)) { "URL ajena al sitio" }
        return slots.withPermit { load(url, headers) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(target: String, headers: Map<String, String>): SiteResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var status = 200
        var fetchStarted = false
        var generation = 0
        val apiRequest = URI(target).path.orEmpty().startsWith("/wp-json/")
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val base = referer?.takeIf { isSiteUrl(it) && URI(it).host.equals(URI(target).host, true) }
            ?: URI(target).let { "${it.scheme}://${it.host}/inicio/" }

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
            if (!isSiteUrl(resultUrl)) {
                finish(Result.failure(IllegalStateException("AnimeOnline redirigió fuera del sitio.")))
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
            view.evaluateJavascript("window.__animeOnlineResult || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val result = parseValue(raw)
                if (result != null) complete(result, 0) else handler.postDelayed(poll, 250L)
            }
        }
        val timeout = Runnable { finish(Result.failure(IllegalStateException("El navegador integrado no pudo abrir AnimeOnline a tiempo."))) }
        continuation.invokeOnCancellation { finish(null) }
        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.blockNetworkImage = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !isSiteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val host = uri.host.orEmpty().lowercase()
                        val allowed = isSiteUrl(uri.toString()) ||
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
                        if (finished.get() || !isSiteUrl(url)) return
                        val currentGeneration = generation
                        val inspect = "JSON.stringify({url:location.href,text:document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1})})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != currentGeneration) return@evaluateJavascript
                            val page = parseValue(raw) ?: return@evaluateJavascript
                            if (looksLikeChallenge(page.optString("text"))) return@evaluateJavascript
                            if (status == 0) {
                                finish(Result.failure(IllegalStateException("El navegador no pudo cargar la página del sitio.")))
                            } else if (!fetchStarted && URI(url).host.equals(URI(target).host, true)) {
                                // A WebView's page callbacks do not reliably expose HTTP status.
                                // Fetch even HTML from the same origin so 403 is never mistaken for 200.
                                fetchStarted = true
                                // Same-origin browser fetch retains WebView's own TLS and cookie context.
                                val quoted = JSONObject.quote(target)
                                val script = """window.__animeOnlineResult=null; fetch($quoted,{credentials:'same-origin'}).then(async r=>{let t=await r.text();window.__animeOnlineResult=JSON.stringify({status:r.status,url:r.url,text:t.slice(0,${MAX_BODY_CHARS + 1})});}).catch(()=>{window.__animeOnlineResult=JSON.stringify({status:0,url:location.href,text:''});});"""
                                view.evaluateJavascript(script, null)
                                handler.postDelayed(poll, 250L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeout, WEBVIEW_MILLIS)
                view.loadUrl(if (apiRequest) base else target)
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    companion object {
        private const val WEBVIEW_MILLIS = 20_000L
        private const val MAX_BODY_CHARS = 2_000_000
        internal fun looksLikeChallenge(text: String): Boolean =
            text.contains("id=\"challenge-form\"", true) ||
            text.contains("id=\"cf-challenge-running\"", true) ||
            text.contains("<title>Just a moment", true)
    }
}
