package com.menorah.pelicinehd

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Off-screen WebView used when Cloudflare rejects plain HTTP (403 or the
 * «Just a moment» interstitial). Returns the final page HTML; never cookies.
 * Same pattern the repository already uses for AnimeOnline.
 */
internal class PeliCineBrowser(private val context: Context) {
    private data class Result(val url: String, val text: String)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(target: String): Result = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null

        fun finish(result: Result?) {
            if (!finished.compareAndSet(false, true)) return
            handler.post {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
            if (result != null && continuation.isActive) continuation.resume(result)
            else if (continuation.isActive) continuation.resume(Result(target, ""))
        }

        handler.post {
            val view = WebView(context.applicationContext)
            webView = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.userAgentString = UA
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = null
                override fun onPageFinished(view: WebView?, url: String?) {
                    handler.postDelayed({
                        view?.evaluateJavascript(
                            "document.documentElement ? document.documentElement.outerHTML : ''"
                        ) { html ->
                            val text = try {
                                org.json.JSONTokener(html ?: "null").nextValue() as? String ?: ""
                            } catch (_: Exception) { "" }
                            finish(Result(url ?: target, text))
                        }
                    }, 2_500)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) finish(Result(target, ""))
                }
            }
            view.loadUrl(target, mapOf("User-Agent" to UA, "Referer" to "${PeliCineMarkup.BASE}/"))
            handler.postDelayed({ finish(null) }, 30_000)
        }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
