package com.menorah.pelicinehd

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Off-screen WebView used to pierce Cloudflare's managed challenge: a real WebView
 * passes it like any browser, plain HTTP clients get a ~5 KB interstitial
 * (`window._cf_chl_opt`) instead of the page. The captured HTML is the DOM after
 * JavaScript ran, so player buttons injected at runtime are present too.
 */
class PeliCineBrowser(private val context: Context) {
    /** HTML produced by the off-screen WebView pass. */
    data class Result(val url: String, val text: String)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(target: String): Result? {
        val main = Handler(Looper.getMainLooper())
        return suspendCancellableCoroutine { cont ->
            var done = false
            lateinit var web: WebView
            val deadline = System.currentTimeMillis() + 28_000

            fun finish(result: Result?) {
                if (done || !cont.isActive) return
                done = true
                main.post {
                    runCatching { web.stopLoading() }
                    runCatching { web.destroy() }
                }
                cont.resume(result ?: Result(target, ""))
            }

            fun grab() {
                web.evaluateJavascript(
                    "document.documentElement ? document.documentElement.outerHTML : ''"
                ) { html ->
                    val text = try {
                        org.json.JSONTokener(html ?: "null").nextValue() as? String ?: ""
                    } catch (_: Exception) { "" }
                    val challenged = text.contains("_cf_chl_opt") || text.contains("Just a moment", true)
                    when {
                        text.isNotBlank() && text.length > 2500 && !challenged -> finish(Result(web.url ?: target, text))
                        System.currentTimeMillis() >= deadline -> finish(Result(web.url ?: target, text))
                        else -> main.postDelayed({ if (!done && cont.isActive) grab() }, 1_000)
                    }
                }
            }

            main.post {
                web = WebView(context)
                with(web.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                web.webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Give lazy players a moment, then poll until the challenge is gone.
                        main.postDelayed({ if (!done && cont.isActive) grab() }, 1_500)
                    }
                }
                web.loadUrl(target)
            }
            cont.invokeOnCancellation {
                if (!done) {
                    done = true
                    main.post { runCatching { web.destroy() } }
                }
            }
        }
    }

    /** Cookie jar of the WebView session (Cloudflare clearance), for plain HTTP calls. */
    fun cookieHeader(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
