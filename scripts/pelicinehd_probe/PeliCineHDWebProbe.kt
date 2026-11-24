package com.menorah.repelis24shop

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Temporary probe (never shipped): renders pelicinehd.com in a real WebView so the Cloudflare
 * challenge is solved the way a device does, then dumps the DOM and every request the page makes.
 */
@RunWith(AndroidJUnit4::class)
class PeliCineHDWebProbe {
    private val tag = "CSPeliCine"
    private val requests = mutableListOf<String>()

    private fun post(path: String, body: String) {
        runCatching { File(path).writeText(body) }
        body.chunked(350).forEach { Log.i(tag, it) }
    }

    private fun dump(webView: WebView, label: String, file: String) = runBlocking {
        val js = "(function(){return document.documentElement.outerHTML})()"
        val result = CompletableDeferred<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(js) { value -> result.complete(value) }
        }
        val html = withTimeoutOrNull(20_000) { result.await() } ?: ""
        post("probe-$label.log", "DUMP $label length=${html.length}")
        runCatching {
            File("probe-$file.html").writeText(
                html.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\n", "\n")
            )
        }
        html
    }

    @Test
    fun probe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var web: WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (requests.size < 500 && requests.none { it == url }) requests.add(url)
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.i(tag, "FINISHED $url")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        Log.i(tag, "ERROR ${request.url} ${error.errorCode}")
                    }
                }
            }
        }
        runBlocking {
            val w = web!!
            w.loadUrl("https://pelicinehd.com/movies/pacifico/")
            delay(45_000)
            dump(w, "detail", "detail")
            w.loadUrl("https://pelicinehd.com/peliculas/")
            delay(35_000)
            dump(w, "catalog", "catalog")
            w.loadUrl("https://pelicinehd.com/series/")
            delay(35_000)
            dump(w, "series", "series")
        }
        post("probe-requests.json", requests.joinToString("\n"))
    }
}
