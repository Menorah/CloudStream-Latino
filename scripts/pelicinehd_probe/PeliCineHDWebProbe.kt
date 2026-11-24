package com.menorah.repelis24shop

import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Temporary probe (never shipped): renders pelicinehd.com in a real WebView so the Cloudflare
 * challenge is solved the way a device does, then dumps the DOM and every request it made.
 * Everything touching the WebView runs on the main thread, as Android requires.
 */
@RunWith(AndroidJUnit4::class)
class PeliCineHDWebProbe {
    private val tag: String = "CSPeliCine"
    private val requests = mutableListOf<String>()
    private val ready = AtomicReference<WebView?>(null)

    private fun note(body: String) {
        body.chunked(350).forEach { Log.i(tag, it) }
    }

    private fun post(path: String, body: String) {
        runCatching { File(path).writeText(body) }
        note(body)
    }

    private fun main(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun create() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        main {
            val web = WebView(ctx)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.cacheMode = WebSettings.LOAD_DEFAULT
            web.settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    synchronized(requests) {
                        if (requests.size < 800 && !requests.contains(url)) requests.add(url)
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    note("FINISHED " + (url ?: "?"))
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    note("ERROR " + (request?.url?.toString() ?: "?") + " " + (error?.errorCode ?: 0))
                }
            }
            ready.set(web)
        }
    }

    private fun load(url: String) {
        main { ready.get()?.loadUrl(url) }
    }

    private fun waitForPage(seconds: Long) {
        SystemClock.sleep(seconds * 1000)
    }

    private fun dump(label: String) {
        val html = AtomicReference<String>("")
        val latch = CountDownLatch(1)
        main {
            ready.get()?.evaluateJavascript(
                "(function(){return document.documentElement.outerHTML})()"
            ) { value ->
                html.set(value ?: "")
                latch.countDown()
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        val body = html.get()
        post(
            "probe-$label.html",
            body.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\n", "\n")
        )
        post("probe-$label.log", "length=" + body.length)
    }

    @Test
    fun probe() {
        create()
        load("https://pelicinehd.com/movies/pacifico/")
        waitForPage(45)
        dump("detail")
        load("https://pelicinehd.com/peliculas/")
        waitForPage(35)
        dump("catalog")
        load("https://pelicinehd.com/series/")
        waitForPage(35)
        dump("series")
        post("probe-requests.txt", synchronized(requests) { requests.joinToString("\n") })
        post("probe-done.log", "done")
    }
}
