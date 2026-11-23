package com.menorah.verani

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Fetches same-origin images in a browser session; transfers at most one image per callback. */
internal class BrowserPosters(
    private val context: Context,
    private val testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
) : PosterFetcher {
    private val slots = Semaphore(4)
    override suspend fun get(urls: List<String>): Map<String, ByteArray> = slots.withPermit {
        val safe = urls.filter(PosterCache::isProtectedPoster).distinct().take(64)
        if (safe.isEmpty()) emptyMap() else load(safe)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(urls: List<String>): Map<String, ByteArray> = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val result = mutableMapOf<String, ByteArray>()
        val allowedUrls = urls.toSet()
        var view: WebView? = null
        var started = false
        var fallbackStarted = false
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            handler.post { view?.stopLoading(); view?.destroy(); view = null }
            if (continuation.isActive) continuation.resume(result.toMap())
        }
        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val current = view ?: return@Runnable
            current.evaluateJavascript("JSON.stringify({item:(window.__posterQueue||[]).shift()||null,done:window.__posterDone===true && (window.__posterQueue||[]).length===0})") { raw ->
                if (finished.get()) return@evaluateJavascript
                val json = try {
                    (JSONTokener(raw ?: "null").nextValue() as? String)?.let(::JSONObject)
                } catch (_: Exception) { null }
                val item = json?.optJSONObject("item")
                if (item != null) {
                    val url = item.optString("url")
                    val encoded = item.optString("data")
                    if (url in allowedUrls && encoded.length <= (PosterCache.MAX_IMAGE_BYTES * 4 / 3 + 256)) {
                        val bytes = runCatching { Base64.decode(encoded.substringAfter(',', ""), Base64.DEFAULT) }.getOrNull()
                        if (bytes != null && bytes.size <= PosterCache.MAX_IMAGE_BYTES && PosterCache.isImage(bytes)) result[url] = bytes
                    }
                }
                if (json?.optBoolean("done") == true) {
                    if (result.isEmpty() && !fallbackStarted) {
                        // Some protected networks need an actual site navigation to
                        // establish their browser session before image fetches work.
                        fallbackStarted = true
                        started = false
                        current.loadUrl("https://verani.me/")
                    } else finish()
                } else handler.postDelayed(poll, if (item == null) 120L else 10L)
            }
        }
        val timeout = Runnable { finish() }
        continuation.invokeOnCancellation { finish() }
        handler.post {
            if (finished.get()) return@post
            try {
                val web = WebView(context); view = web
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.blockNetworkImage = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.settings.javaScriptCanOpenWindowsAutomatically = false
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isSiteUrl(request.url.toString())
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val allowed = isSiteUrl(uri.toString()) || (uri.scheme == "https" && uri.host == "challenges.cloudflare.com")
                        return if (allowed) testResponse?.invoke(request) else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (finished.get() || started || !url.startsWith("https://verani.me/")) return
                        started = true
                        val targets = JSONArray(urls).toString()
                        val script = """(function(){window.__posterQueue=[];window.__posterDone=false;let a=$targets;let n=0;async function worker(){while(n<a.length){let u=a[n++];try{let c=new AbortController();let timer=setTimeout(()=>c.abort(),4000);let r;try{r=await fetch(u,{credentials:'same-origin',signal:c.signal});}finally{clearTimeout(timer);}if(!r.ok)continue;let b=await r.blob();if(!b.type.startsWith('image/')||b.size>${PosterCache.MAX_IMAGE_BYTES})continue;let d=await new Promise((ok,fail)=>{let f=new FileReader();f.onload=()=>ok(f.result);f.onerror=fail;f.readAsDataURL(b);});window.__posterQueue.push({url:u,data:d});}catch(e){}}}Promise.all(Array.from({length:10},worker)).then(()=>{window.__posterDone=true;});})();"""
                        view.evaluateJavascript(script, null)
                        handler.postDelayed(poll, 120L)
                    }
                }
                handler.postDelayed(timeout, 12_000L)
                // Establish the same origin without downloading the heavy homepage
                // a second time for every category. Cookies remain shared by WebView.
                web.loadDataWithBaseURL("https://verani.me/", "<html><body></body></html>", "text/html", "UTF-8", null)
            } catch (_: Exception) { finish() }
        }
    }
}
