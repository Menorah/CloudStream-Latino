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

private const val STRUCTURE_JS = "(function () {   var out = {};   out.title = document.title;   out.links = Array.prototype.slice.call(document.querySelectorAll('a[href*=\"/movies/\"], a[href*=\"/series/\"]')).slice(0, 40).map(function (a) {     return a.className + ' | ' + (a.getAttribute('title') || '') + ' | ' + a.href;   });   var cards = document.querySelectorAll('article, .item, .movie, .post, .card, li');   out.cardCount = cards.length;   var sample = null;   for (var i = 0; i < cards.length; i++) {     if (cards[i].querySelector('a[href*=\"/movies/\"]')) { sample = cards[i]; break; }   }   out.cardHtml = sample ? sample.outerHTML.slice(0, 1200) : null;   var imgs = document.querySelectorAll('img');   out.images = Array.prototype.slice.call(imgs).slice(0, 25).map(function (i) {     return i.className + ' | ' + i.alt + ' | ' + (i.getAttribute('data-src') || i.getAttribute('src'));   });   out.iframes = Array.prototype.slice.call(document.querySelectorAll('iframe')).map(function (f) {     return f.getAttribute('src') + ' | ' + f.outerHTML.slice(0, 240);   });   out.scripts = Array.prototype.slice.call(document.querySelectorAll('script')).map(function (s) {     return (s.src || 'INLINE') + ' | ' + (s.textContent || '').slice(0, 200);   }).slice(0, 20);   var html = document.documentElement.outerHTML;   var hits = [];   ['data-server', 'data-url', 'data-id', 'embed', 'player', 'servers', 'option', 'data-video',    'wp-json', 'admin-ajax', 'nupload', 'streamtape', 'filemoon', 'vimeos', 'voe', 'dood', 'ok.ru'   ].forEach(function (needle) {     var index = html.indexOf(needle);     if (index >= 0) hits.push(needle + '@' + index + ': ' + html.substr(Math.max(0, index - 60), 200).replace(/\\s+/g, ' '));   });   out.hits = hits;   return JSON.stringify(out); })()"

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

    /** Instrumentation stdout lands in the JUnit XML, which the workflow can read. */
    private fun note(body: String) {
        println("CSPELI " + body.replace("\n", " "))
        Log.i(tag, body)
    }

    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "probe")
        dir.mkdirs()
        return dir
    }

    private fun post(path: String, body: String) {
        runCatching { File(outDir(), path).writeText(body) }
        note(path + " bytes=" + body.length)
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

    /** Runs JS on the main thread and waits for the value. */
    private fun eval(script: String): String {
        val out = AtomicReference<String>("")
        val latch = CountDownLatch(1)
        main {
            ready.get()?.evaluateJavascript(script) { value ->
                out.set(value ?: "")
                latch.countDown()
            }
        }
        if (!latch.await(30, TimeUnit.SECONDS)) return ""
        return decode(out.get())
    }

    /** evaluateJavascript returns a JSON-encoded string literal. */
    private fun decode(value: String): String {
        val text = value.trim()
        val unquoted = if (text.startsWith("\"") && text.endsWith("\""))
            text.substring(1, text.length - 1) else text
        val sb = StringBuilder()
        var index = 0
        while (index < unquoted.length) {
            val ch = unquoted[index]
            if (ch == '\\' && index + 1 < unquoted.length) {
                val next = unquoted[index + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        val hex = unquoted.substring(index + 2, minOf(index + 6, unquoted.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                        index += 4
                    }
                    else -> sb.append(next)
                }
                index += 2
            } else {
                sb.append(ch)
                index++
            }
        }
        return sb.toString()
    }

    private fun dump(label: String) {
        val body = eval("(function(){return document.documentElement.outerHTML})()")
        post("probe-$label.html", body)
        post("probe-$label.log", "html length=" + body.length)
        // The artifact is unreachable from the agent, so the structure goes out as annotations.
        val report = eval(STRUCTURE_JS)
        post("probe-$label.json", report)
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
