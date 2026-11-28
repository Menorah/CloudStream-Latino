package com.menorah.sololatino

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SoloLatinoHttpResult(
    val code: Int,
    val url: String,
    val text: String,
    val dateHeader: String? = null,
)

internal data class SoloLatinoResolvedButtonOutput(
    val langLabel: String,
    val label: String,
    val token: String,
    val url: String?,
    val type: String?,
)

internal data class SoloLatinoWebPshServerResult(
    val label: String,
    val langLabel: String,
    val serverValue: String,
    val type: String?,
    val url: String?,
    val u: String?,
    val sig: String?,
)

internal object SoloLatinoSessionHelper {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun parseCookieHeader(raw: String?): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        if (raw.isNullOrBlank()) return out
        for (part in raw.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) {
                out[name] = value
            }
        }
        return out
    }

    fun mergeCookies(base: Map<String, String>, extra: Map<String, String>): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(base)
        for ((k, v) in extra) {
            if (k.isNotBlank() && v.isNotBlank()) {
                merged[k] = v
            }
        }
        return merged
    }

    fun looksLikeCloudflareChallenge(code: Int, body: String): Boolean {
        if (body.contains("_cf_chl_opt", ignoreCase = true) ||
            body.contains("challenge-platform", ignoreCase = true) ||
            body.contains("cf-turnstile", ignoreCase = true) ||
            body.contains("<title>Just a moment...</title>", ignoreCase = true) ||
            body.contains("Enable JavaScript and cookies to continue", ignoreCase = true)
        ) {
            return true
        }
        return (code == 403 || code == 503) &&
            (body.isBlank() || body.contains("Just a moment", ignoreCase = true) || body.contains("cloudflare", ignoreCase = true))
    }

    fun buildBrowserHeaders(
        userAgent: String,
        referer: String = "${SoloLatinoMarkup.MAIN_URL}/",
        isAjax: Boolean = false,
    ): LinkedHashMap<String, String> = linkedMapOf(
        "user-agent" to userAgent,
        "Accept" to if (isAjax) "application/json, text/plain, */*" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
        "Referer" to referer,
    ).apply {
        if (isAjax) {
            put("X-Requested-With", "XMLHttpRequest")
        }
    }
}

internal data class SoloLatinoWebHtmlResponse(
    val code: Int,
    val url: String,
    val text: String,
    val cookieHeader: String,
    val userAgent: String,
    val serverOffsetMs: Long?,
)

internal data class SoloLatinoWebPlayersResponse(
    val offsetMs: Long?,
    val buttons: List<SoloLatinoResolvedButtonOutput>,
    val lazyResult: SoloLatinoPlayerApiResult?,
    val cookieHeader: String,
    val userAgent: String,
)

internal object SoloLatinoWebTransport {
    private const val WEBVIEW_TIMEOUT_MS = 25_000L
    private const val MAX_BODY_CHARS = 2_500_000
    private val slots = Semaphore(2)
    private val challengeHosts = listOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "cloudflareinsights.com",
        "cdnjs.cloudflare.com",
    )

    private fun isAllowedInfrastructureHost(host: String): Boolean {
        val h = host.lowercase()
        return challengeHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun parseJsonObject(raw: String?): JSONObject? = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String ?: return null
        JSONObject(s)
    } catch (_: Exception) {
        null
    }

    suspend fun getHtml(
        context: Context,
        url: String,
        referer: String,
        allowedHost: (String) -> Boolean = { SoloLatinoMarkup.isSiteUrl(it) },
        userAgent: String? = null,
        fetchSameOriginEndpoint: Boolean = false,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebHtmlResponse = slots.withPermit {
        loadSiteOrEndpoint(
            context = context,
            targetUrl = url,
            referer = referer,
            allowedHost = allowedHost,
            userAgent = userAgent,
            fetchSameOriginEndpoint = fetchSameOriginEndpoint,
            testResponse = testResponse,
        )
    }

    suspend fun resolveSitePlayers(
        context: Context,
        pageUrl: String,
        userAgent: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): SoloLatinoWebPlayersResponse = slots.withPermit {
        resolveSitePlayersInternal(context, pageUrl, userAgent, testResponse)
    }

    suspend fun resolvePelisSeriesHoy(
        context: Context,
        embedUrl: String,
        referer: String,
        userAgent: String? = null,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    ): List<SoloLatinoWebPshServerResult> = slots.withPermit {
        resolvePelisSeriesHoyInternal(context, embedUrl, referer, userAgent, testResponse)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadSiteOrEndpoint(
        context: Context,
        targetUrl: String,
        referer: String,
        allowedHost: (String) -> Boolean,
        userAgent: String?,
        fetchSameOriginEndpoint: Boolean,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebHtmlResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var httpStatus = 200
        var fetchStarted = false
        var generation = 0
        var resolvedUa = userAgent.orEmpty()

        val initialLoadUrl = if (fetchSameOriginEndpoint && referer.startsWith("http", ignoreCase = true) && allowedHost(referer)) {
            referer
        } else {
            targetUrl
        }

        fun finish(result: Result<SoloLatinoWebHtmlResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            val enriched = result?.map { res ->
                val cmCookie = runCatching { CookieManager.getInstance().getCookie(res.url).orEmpty() }.getOrDefault("")
                res.copy(cookieHeader = cmCookie.ifBlank { res.cookieHeader })
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

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webView ?: return@Runnable
            view.evaluateJavascript("window.__slFetchResult || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val obj = parseJsonObject(raw)
                if (obj != null) {
                    val code = obj.optInt("status", httpStatus).let { if (it > 0) it else httpStatus }
                    val resUrl = obj.optString("url").ifBlank { targetUrl }
                    val text = obj.optString("text")
                    val offsetVal = if (obj.has("offsetMs") && !obj.isNull("offsetMs")) obj.optLong("offsetMs") else null
                    finish(Result.success(SoloLatinoWebHtmlResponse(code, resUrl, text, "", resolvedUa, offsetVal)))
                } else {
                    handler.postDelayed(poll, 220L)
                }
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo cargando SoloLatino")))
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
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                if (!userAgent.isNullOrBlank()) {
                    view.settings.userAgentString = userAgent
                }
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(userAgent.orEmpty())
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 1080, 1920)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !allowedHost(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        val host = uri.host.orEmpty()
                        if (scheme in listOf("blob", "data", "about")) {
                            return testResponse?.invoke(request)
                        }
                        val allowed = allowedHost(uri.toString()) ||
                            (scheme == "https" && isAllowedInfrastructureHost(host))
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

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) httpStatus = 0
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        val inspect = "JSON.stringify({url:location.href,ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,${MAX_BODY_CHARS + 1}):'')})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != expectedGen) return@evaluateJavascript
                            val page = parseJsonObject(raw) ?: run {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                                return@evaluateJavascript
                            }
                            val pageUrl = page.optString("url").ifBlank { url }
                            val pageText = page.optString("text")
                            val readyState = page.optString("ready")
                            if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                return@evaluateJavascript
                            }
                            if (httpStatus == 0) {
                                finish(Result.failure(IllegalStateException("Error de red en WebView")))
                                return@evaluateJavascript
                            }
                            if (!fetchSameOriginEndpoint && readyState != "complete") {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                return@evaluateJavascript
                            }
                            if (!fetchSameOriginEndpoint) {
                                finish(Result.success(SoloLatinoWebHtmlResponse(200, pageUrl, pageText, "", resolvedUa, null)))
                                return@evaluateJavascript
                            }
                            if (!fetchStarted) {
                                fetchStarted = true
                                val quotedTarget = JSONObject.quote(targetUrl)
                                val js = """
                                    window.__slFetchResult=null;
                                    fetch($quotedTarget,{credentials:'same-origin',headers:{'X-Requested-With':'XMLHttpRequest','Accept':'application/json, text/html, */*'}})
                                      .then(async r=>{
                                        const d=r.headers.get('date');
                                        const offsetMs=d&&!isNaN(Date.parse(d))?(Date.parse(d)-Date.now()):null;
                                        const t=await r.text();
                                        window.__slFetchResult=JSON.stringify({status:r.status,url:r.url||$quotedTarget,text:t.slice(0,${MAX_BODY_CHARS + 1}),offsetMs});
                                      })
                                      .catch(()=>{
                                        window.__slFetchResult=JSON.stringify({status:0,url:location.href,text:'',offsetMs:null});
                                      });
                                """.trimIndent()
                                view.evaluateJavascript(js, null)
                                handler.postDelayed(poll, 220L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                val navHeaders = linkedMapOf<String, String>()
                if (referer.startsWith("http", ignoreCase = true)) {
                    navHeaders["Referer"] = referer
                }
                if (navHeaders.isNotEmpty()) view.loadUrl(initialLoadUrl, navHeaders) else view.loadUrl(initialLoadUrl)
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveSitePlayersInternal(
        context: Context,
        pageUrl: String,
        userAgent: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): SoloLatinoWebPlayersResponse = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var httpStatus = 200
        var resolveStarted = false
        var generation = 0
        var resolvedUa = userAgent.orEmpty()

        fun finish(result: Result<SoloLatinoWebPlayersResponse>?) {
            if (!finished.compareAndSet(false, true)) return
            val enriched = result?.map { res ->
                val cmCookie = runCatching { CookieManager.getInstance().getCookie(pageUrl).orEmpty() }.getOrDefault("")
                res.copy(cookieHeader = cmCookie.ifBlank { res.cookieHeader })
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

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webView ?: return@Runnable
            view.evaluateJavascript("window.__slPlayerResolveOut || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val obj = parseJsonObject(raw)
                if (obj != null) {
                    val offsetMs = if (obj.has("offsetMs") && !obj.isNull("offsetMs")) obj.optLong("offsetMs") else null
                    val buttonsArr = obj.optJSONArray("buttons") ?: JSONArray()
                    val buttons = mutableListOf<SoloLatinoResolvedButtonOutput>()
                    for (i in 0 until buttonsArr.length()) {
                        val b = buttonsArr.optJSONObject(i) ?: continue
                        val url = b.optString("url").takeIf { it.startsWith("http", ignoreCase = true) }
                        val type = b.optString("type").takeIf { it.isNotBlank() }
                        buttons.add(
                            SoloLatinoResolvedButtonOutput(
                                langLabel = SoloLatinoMarkup.normalizeLanguageLabel(b.optString("lang")),
                                label = b.optString("label").ifBlank { "Servidor" },
                                token = b.optString("token"),
                                url = url,
                                type = type,
                            ),
                        )
                    }
                    val lazyObj = obj.optJSONObject("lazy")
                    val lazyRes = lazyObj?.let {
                        val u = it.optString("url").takeIf { s -> s.startsWith("http", ignoreCase = true) }
                        val tp = it.optString("type").takeIf { s -> s.isNotBlank() }
                        if (u != null || tp != null) SoloLatinoPlayerApiResult(u, tp) else null
                    }
                    finish(Result.success(SoloLatinoWebPlayersResponse(offsetMs, buttons, lazyRes, "", resolvedUa)))
                } else {
                    handler.postDelayed(poll, 250L)
                }
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo resolviendo reproductores de SoloLatino")))
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
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                if (!userAgent.isNullOrBlank()) {
                    view.settings.userAgentString = userAgent
                }
                resolvedUa = runCatching { view.settings.userAgentString }.getOrDefault(userAgent.orEmpty())
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 1080, 1920)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !SoloLatinoMarkup.isSiteUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        val host = uri.host.orEmpty()
                        if (scheme in listOf("blob", "data", "about")) return testResponse?.invoke(request)
                        val allowed = SoloLatinoMarkup.isSiteUrl(uri.toString()) ||
                            (scheme == "https" && isAllowedInfrastructureHost(host))
                        return if (allowed) {
                            testResponse?.invoke(request)
                        } else {
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        httpStatus = 200
                        resolveStarted = false
                        generation++
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) httpStatus = 0
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        val inspect = "JSON.stringify({ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,20000):''),hasPlayer:Boolean(document.querySelector('button[data-player-token],#player-frame'))})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != expectedGen) return@evaluateJavascript
                            val page = parseJsonObject(raw) ?: run {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                                return@evaluateJavascript
                            }
                            val pageText = page.optString("text")
                            val readyState = page.optString("ready")
                            if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                return@evaluateJavascript
                            }
                            if (httpStatus == 0) {
                                finish(Result.failure(IllegalStateException("Error de red resolviendo reproductores")))
                                return@evaluateJavascript
                            }
                            if (readyState != "complete" && !page.optBoolean("hasPlayer")) {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                return@evaluateJavascript
                            }
                            if (!resolveStarted) {
                                resolveStarted = true
                                val secretQuoted = JSONObject.quote(SoloLatinoMarkup.RENDER_SHIELD_SECRET)
                                val js = """
                                    (async () => {
                                      window.__slPlayerResolveOut = null;
                                      const getCookie = (n) => {
                                        const m = document.cookie.match(new RegExp('(?:^|; )' + n + '=([^;]*)'));
                                        return m ? decodeURIComponent(m[1]) : '';
                                      };
                                      let offsetMs = typeof window._slOff === 'number' ? window._slOff : 0;
                                      try {
                                        const cr = await fetch('/sanctum/csrf-cookie', { credentials: 'same-origin' });
                                        const dh = cr.headers.get('date');
                                        if (dh && !isNaN(Date.parse(dh))) offsetMs = Date.parse(dh) - Date.now();
                                      } catch (_) {}
                                      const csrf = (document.querySelector('meta[name="csrf-token"]') || {}).content || '';
                                      const xsrf = getCookie('XSRF-TOKEN');
                                      async function shield(token) {
                                        const slot = Math.floor((Date.now() + offsetMs) / 60000);
                                        const data = new TextEncoder().encode(token + ':' + slot + ':' + $secretQuoted);
                                        const digest = await crypto.subtle.digest('SHA-256', data);
                                        return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
                                      }
                                      async function postToken(endpoint, token) {
                                        try {
                                          const sh = await shield(token);
                                          const h = {
                                            'Content-Type': 'application/json',
                                            'Accept': 'application/json',
                                            'X-Requested-With': 'XMLHttpRequest',
                                            'X-SL-Render': sh
                                          };
                                          if (csrf) h['X-CSRF-TOKEN'] = csrf;
                                          if (xsrf) h['X-XSRF-TOKEN'] = xsrf;
                                          const r = await fetch(endpoint, {
                                            method: 'POST',
                                            credentials: 'same-origin',
                                            headers: h,
                                            body: JSON.stringify({ t: token })
                                          });
                                          if (!r.ok) return null;
                                          return await r.json();
                                        } catch (_) {
                                          return null;
                                        }
                                      }
                                      const langLabels = {};
                                      document.querySelectorAll('[data-lang-tab]').forEach(el => {
                                        const k = (el.getAttribute('data-lang-tab') || '').trim();
                                        const v = (el.textContent || '').trim();
                                        if (k && v) langLabels[k] = v;
                                      });
                                      const btns = Array.from(document.querySelectorAll('button[data-player-token], [data-player-token]'));
                                      const seen = new Set();
                                      const jobs = [];
                                      for (const btn of btns) {
                                        const tok = (btn.getAttribute('data-player-token') || '').trim();
                                        const preUrl = (btn.getAttribute('data-resolved-url') || '').trim();
                                        const preType = (btn.getAttribute('data-resolved-type') || '').trim();
                                        if (!tok && !preUrl) continue;
                                        const key = tok || ('u:' + preUrl);
                                        if (seen.has(key)) continue;
                                        seen.add(key);
                                        const grp = btn.closest('[data-lang-group]');
                                        const langKey = grp ? (grp.getAttribute('data-lang-group') || '').trim() : (btn.getAttribute('data-lang') || 'lat');
                                        const lang = langLabels[langKey] || langKey || 'Latino';
                                        const label = (btn.textContent || '').trim().replace(/\s+/g, ' ') || 'Servidor';
                                        jobs.push((async () => {
                                          if (preUrl && preUrl.startsWith('http')) {
                                            return { lang, label, token: tok, url: preUrl, type: preType || 'iframe' };
                                          }
                                          const res = tok ? await postToken('/api/player-url', tok) : null;
                                          return { lang, label, token: tok, url: res ? res.url : null, type: res ? res.type : null };
                                        })());
                                      }
                                      const pf = document.querySelector('#player-frame');
                                      const lazyTok = pf ? (pf.getAttribute('data-lazy-embed') || '').trim() : '';
                                      const [buttons, lazy] = await Promise.all([
                                        Promise.all(jobs),
                                        lazyTok ? postToken('/api/player-embed', lazyTok) : Promise.resolve(null)
                                      ]);
                                      window.__slPlayerResolveOut = JSON.stringify({ offsetMs, buttons, lazy });
                                    })();
                                """.trimIndent()
                                view.evaluateJavascript(js, null)
                                handler.postDelayed(poll, 260L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                view.loadUrl(pageUrl, mapOf("Referer" to "${SoloLatinoMarkup.MAIN_URL}/"))
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolvePelisSeriesHoyInternal(
        context: Context,
        embedUrl: String,
        referer: String,
        userAgent: String?,
        testResponse: ((WebResourceRequest) -> WebResourceResponse?)?,
    ): List<SoloLatinoWebPshServerResult> = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var webView: WebView? = null
        var httpStatus = 200
        var resolveStarted = false
        var generation = 0

        fun finish(result: Result<List<SoloLatinoWebPshServerResult>>?) {
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

        lateinit var poll: Runnable
        poll = Runnable {
            if (finished.get()) return@Runnable
            val view = webView ?: return@Runnable
            view.evaluateJavascript("window.__slPshOut || null") { raw ->
                if (finished.get()) return@evaluateJavascript
                val obj = parseJsonObject(raw)
                if (obj != null) {
                    val out = mutableListOf<SoloLatinoWebPshServerResult>()
                    val arr = obj.optJSONArray("servers") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        out.add(
                            SoloLatinoWebPshServerResult(
                                label = s.optString("label").ifBlank { "Servidor" },
                                langLabel = SoloLatinoMarkup.normalizeLanguageLabel(s.optString("lang")),
                                serverValue = s.optString("serverValue"),
                                type = s.optString("type").takeIf { it.isNotBlank() },
                                url = s.optString("url").takeIf { it.isNotBlank() },
                                u = s.optString("u").takeIf { it.isNotBlank() },
                                sig = s.optString("sig").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                    finish(Result.success(out))
                } else {
                    handler.postDelayed(poll, 260L)
                }
            }
        }

        val timeoutTask = Runnable {
            finish(Result.failure(IllegalStateException("WebView agotó el tiempo en player.pelisserieshoy.com")))
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
                        !SoloLatinoMarkup.isPelisSeriesHoyUrl(request.url.toString())

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        val host = uri.host.orEmpty()
                        if (scheme in listOf("blob", "data", "about")) return testResponse?.invoke(request)
                        val allowed = SoloLatinoMarkup.isPelisSeriesHoyUrl(uri.toString()) ||
                            (scheme == "https" && isAllowedInfrastructureHost(host))
                        return if (allowed) {
                            testResponse?.invoke(request)
                        } else {
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        httpStatus = 200
                        resolveStarted = false
                        generation++
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) httpStatus = errorResponse.statusCode
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) httpStatus = 0
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        checkReady(view, url, generation)
                    }

                    private fun checkReady(view: WebView, url: String, expectedGen: Int) {
                        if (finished.get() || generation != expectedGen) return
                        val inspect = "JSON.stringify({ready:document.readyState,text:(document.documentElement?document.documentElement.outerHTML.slice(0,25000):''),hasInit:typeof _t!=='undefined'&&typeof _qk!=='undefined'})"
                        view.evaluateJavascript(inspect) { raw ->
                            if (finished.get() || generation != expectedGen) return@evaluateJavascript
                            val page = parseJsonObject(raw) ?: run {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 220L)
                                return@evaluateJavascript
                            }
                            val pageText = page.optString("text")
                            if (SoloLatinoSessionHelper.looksLikeCloudflareChallenge(httpStatus, pageText)) {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 350L)
                                return@evaluateJavascript
                            }
                            if (httpStatus == 0) {
                                finish(Result.failure(IllegalStateException("Error de red en PelisSeriesHoy")))
                                return@evaluateJavascript
                            }
                            if (!page.optBoolean("hasInit") && page.optString("ready") != "complete") {
                                handler.postDelayed({ checkReady(view, url, expectedGen) }, 200L)
                                return@evaluateJavascript
                            }
                            if (!resolveStarted) {
                                resolveStarted = true
                                val js = """
                                    (async () => {
                                      window.__slPshOut = null;
                                      if (typeof _t === 'undefined' || typeof _qc === 'undefined' || typeof _qk === 'undefined') {
                                        window.__slPshOut = JSON.stringify({ servers: [] });
                                        return;
                                      }
                                      function fnv(s) {
                                        let h = 2166136261;
                                        for (let i = 0; i < s.length; i++) {
                                          h ^= s.charCodeAt(i);
                                          h = Math.imul(h, 16777619);
                                        }
                                        return (h >>> 0).toString(16).padStart(8, '0');
                                      }
                                      const rt = _qk[1] + _qk[3] + _qk[0] + _qk[2];
                                      const tk = fnv(rt + '|clk|' + _qc);
                                      const originAo = (typeof ao !== 'undefined' && ao) ? ao : 'https://sololatino.net';
                                      async function postS(params) {
                                        const body = new URLSearchParams(params);
                                        const r = await fetch('/s.php', {
                                          method: 'POST',
                                          credentials: 'same-origin',
                                          headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
                                          body
                                        });
                                        return await r.json();
                                      }
                                      try {
                                        const s1 = await postS({ a: '1', tok: _t, rt, tk, ao: originAo });
                                        const flat = [];
                                        const seen = new Set();
                                        if (s1 && s1.langs_s) {
                                          for (const [lang, arr] of Object.entries(s1.langs_s)) {
                                            if (!Array.isArray(arr)) continue;
                                            for (const item of arr) {
                                              if (Array.isArray(item) && item.length >= 2 && !seen.has(item[1])) {
                                                seen.add(item[1]);
                                                flat.push({ label: item[0], serverValue: item[1], lang });
                                              }
                                            }
                                          }
                                        }
                                        if (s1 && Array.isArray(s1.s)) {
                                          for (const item of s1.s) {
                                            if (Array.isArray(item) && item.length >= 2 && !seen.has(item[1])) {
                                              seen.add(item[1]);
                                              flat.push({ label: item[0], serverValue: item[1], lang: 'LAT' });
                                            }
                                          }
                                        }
                                        const resolved = [];
                                        for (const srv of flat) {
                                          try {
                                            await postS({ a: 'click', tok: _t, ts: '1', v: srv.serverValue, rt, tk, ao: originAo });
                                            const a2 = await postS({ a: '2', v: srv.serverValue, tok: _t, rt, tk, ao: originAo });
                                            resolved.push({
                                              label: srv.label,
                                              lang: srv.lang,
                                              serverValue: srv.serverValue,
                                              type: a2 && a2.type ? a2.type : '',
                                              url: a2 && a2.url ? a2.url : '',
                                              u: a2 && a2.u ? a2.u : '',
                                              sig: a2 && a2.sig ? a2.sig : ''
                                            });
                                          } catch (_) {}
                                        }
                                        window.__slPshOut = JSON.stringify({ servers: resolved });
                                      } catch (_) {
                                        window.__slPshOut = JSON.stringify({ servers: [] });
                                      }
                                    })();
                                """.trimIndent()
                                view.evaluateJavascript(js, null)
                                handler.postDelayed(poll, 300L)
                            }
                        }
                    }
                }
                handler.postDelayed(timeoutTask, WEBVIEW_TIMEOUT_MS)
                val effReferer = referer.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${SoloLatinoMarkup.MAIN_URL}/"
                view.loadUrl(embedUrl, mapOf("Referer" to effReferer))
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
    }
}

internal class SoloLatinoClient(
    private val appContext: Context? = null,
) {
    private val siteCookies = ConcurrentHashMap<String, String>()
    private val pshCookies = ConcurrentHashMap<String, String>()
    private val serverTimeOffsetMs = AtomicLong(0L)
    private val cfMutex = Mutex()
    private val siteGate = Semaphore(4)
    private val cfKiller by lazy { CloudflareKiller() }

    @Volatile
    private var preferredUserAgent: String = ""

    @Volatile
    internal var testWebResponse: ((WebResourceRequest) -> WebResourceResponse?)? = null

    fun currentUserAgent(): String {
        WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
        if (preferredUserAgent.isNotBlank()) return preferredUserAgent
        val sysUa = appContext?.let { ctx -> runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return sysUa ?: SoloLatinoSessionHelper.DEFAULT_USER_AGENT
    }

    private fun readMergedCookies(url: String, localStore: ConcurrentHashMap<String, String>): LinkedHashMap<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(localStore)
        val host = SoloLatinoMarkup.hostOf(url)
        runCatching {
            cfKiller.savedCookies[host]?.let { merged.putAll(it) }
            if (host.startsWith("www.")) {
                cfKiller.savedCookies[host.removePrefix("www.")]?.let { merged.putAll(it) }
            }
        }
        if (appContext != null) {
            runCatching {
                val cm = CookieManager.getInstance()
                SoloLatinoSessionHelper.parseCookieHeader(cm.getCookie(url)).forEach { (k, v) -> merged[k] = v }
            }
        }
        return merged
    }

    private fun saveCookiesFromResponse(
        localStore: ConcurrentHashMap<String, String>,
        resCookies: Map<String, String>,
        cookieHeader: String = "",
    ) {
        for ((k, v) in resCookies) {
            if (k.isNotBlank() && v.isNotBlank()) localStore[k] = v
        }
        if (cookieHeader.isNotBlank()) {
            SoloLatinoSessionHelper.parseCookieHeader(cookieHeader).forEach { (k, v) ->
                localStore[k] = v
            }
        }
    }

    private fun updateServerOffset(dateHeader: String?) {
        val serverMs = SoloLatinoMarkup.parseHttpDateMillis(dateHeader) ?: return
        serverTimeOffsetMs.set(serverMs - System.currentTimeMillis())
    }

    suspend fun fetchSite(
        url: String,
        referer: String = "${SoloLatinoMarkup.MAIN_URL}/",
        isAjax: Boolean = false,
    ): SoloLatinoHttpResult = siteGate.withPermit {
        val activeTestResponse = testWebResponse
        var code = 0
        var finalUrl = url
        var text = ""
        var dateHeader: String? = null

        if (activeTestResponse == null) {
            val ua = currentUserAgent()
            val cookies = readMergedCookies(url, siteCookies)
            val headers = SoloLatinoSessionHelper.buildBrowserHeaders(ua, referer, isAjax)
            val direct = runCatching {
                app.get(url, headers = headers, cookies = cookies, timeout = 16L)
            }.getOrNull()
            if (direct != null) {
                code = direct.code
                finalUrl = direct.url
                text = direct.text
                dateHeader = direct.headers["date"] ?: direct.headers["Date"]
                updateServerOffset(dateHeader)
                saveCookiesFromResponse(siteCookies, direct.cookies)
            }

            if (code == 0 || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text)) {
                cfMutex.withLock {
                    val uaAfter = currentUserAgent()
                    val cookiesAfter = readMergedCookies(url, siteCookies)
                    val cfRes = runCatching {
                        app.get(
                            url,
                            headers = SoloLatinoSessionHelper.buildBrowserHeaders(uaAfter, referer, isAjax),
                            cookies = cookiesAfter,
                            interceptor = cfKiller,
                            timeout = 22L,
                        )
                    }.getOrNull()
                    if (cfRes != null) {
                        code = cfRes.code
                        finalUrl = cfRes.url
                        text = cfRes.text
                        dateHeader = cfRes.headers["date"] ?: cfRes.headers["Date"]
                        updateServerOffset(dateHeader)
                        WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
                        saveCookiesFromResponse(siteCookies, cfRes.cookies)
                    }
                }
            }
        } else {
            code = 403
        }

        val ctx = appContext
        if (ctx != null && (code !in 200..299 || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(code, text))) {
            val webRes = runCatching {
                SoloLatinoWebTransport.getHtml(
                    context = ctx,
                    url = url,
                    referer = referer,
                    allowedHost = { SoloLatinoMarkup.isSiteUrl(it) },
                    userAgent = currentUserAgent(),
                    fetchSameOriginEndpoint = isAjax,
                    testResponse = activeTestResponse,
                )
            }.getOrNull()
            if (webRes != null) {
                code = webRes.code
                finalUrl = webRes.url
                text = webRes.text
                if (webRes.userAgent.isNotBlank()) preferredUserAgent = webRes.userAgent
                webRes.serverOffsetMs?.let { serverTimeOffsetMs.set(it) }
                saveCookiesFromResponse(siteCookies, emptyMap(), webRes.cookieHeader)
            }
        }

        SoloLatinoHttpResult(code = code, url = finalUrl, text = text, dateHeader = dateHeader)
    }

    suspend fun resolvePlayerSection(
        pageUrl: String,
        section: SoloLatinoPlayerSection,
    ): Pair<List<SoloLatinoResolvedButtonOutput>, SoloLatinoPlayerApiResult?> {
        val activeTestResponse = testWebResponse
        val allHavePreResolved = section.buttons.isNotEmpty() &&
            section.buttons.all { !it.resolvedUrl.isNullOrBlank() } &&
            section.lazyEmbedToken.isNullOrBlank()
        if (allHavePreResolved) {
            return section.buttons.map {
                SoloLatinoResolvedButtonOutput(it.langLabel, it.label, it.token, it.resolvedUrl, it.resolvedType)
            } to null
        }

        if (activeTestResponse == null) {
            val httpRes = tryResolvePlayersViaHttp(pageUrl, section)
            val resolvedCount = httpRes.first.count { !it.url.isNullOrBlank() }
            if (resolvedCount > 0 || !httpRes.second?.url.isNullOrBlank()) {
                return httpRes
            }
        }

        val ctx = appContext
        if (ctx != null) {
            val webOut = runCatching {
                SoloLatinoWebTransport.resolveSitePlayers(
                    context = ctx,
                    pageUrl = pageUrl,
                    userAgent = currentUserAgent(),
                    testResponse = activeTestResponse,
                )
            }.getOrNull()
            if (webOut != null) {
                webOut.offsetMs?.let { serverTimeOffsetMs.set(it) }
                if (webOut.userAgent.isNotBlank()) preferredUserAgent = webOut.userAgent
                saveCookiesFromResponse(siteCookies, emptyMap(), webOut.cookieHeader)
                return webOut.buttons to webOut.lazyResult
            }
        }

        val preOnly = section.buttons.map {
            SoloLatinoResolvedButtonOutput(it.langLabel, it.label, it.token, it.resolvedUrl, it.resolvedType)
        }
        return preOnly to null
    }

    private suspend fun tryResolvePlayersViaHttp(
        pageUrl: String,
        section: SoloLatinoPlayerSection,
    ): Pair<List<SoloLatinoResolvedButtonOutput>, SoloLatinoPlayerApiResult?> {
        val origin = "${SoloLatinoMarkup.MAIN_URL}"
        val ua = currentUserAgent()

        // Step 1: refresh Sanctum CSRF cookie and synchronize Date header offset
        val csrfUrl = "$origin/sanctum/csrf-cookie"
        val cookiesBefore = readMergedCookies(pageUrl, siteCookies)
        val csrfRes = runCatching {
            app.get(
                csrfUrl,
                headers = SoloLatinoSessionHelper.buildBrowserHeaders(ua, pageUrl, isAjax = true),
                cookies = cookiesBefore,
                timeout = 12L,
            )
        }.getOrNull()
        if (csrfRes != null) {
            val dateHeader = csrfRes.headers["date"] ?: csrfRes.headers["Date"]
            updateServerOffset(dateHeader)
            saveCookiesFromResponse(siteCookies, csrfRes.cookies)
        }

        val mergedCookies = readMergedCookies(pageUrl, siteCookies)
        val xsrfRaw = mergedCookies.entries.firstOrNull { it.key.equals("XSRF-TOKEN", ignoreCase = true) }?.value
        val xsrfToken = SoloLatinoMarkup.decodeCookieValue(xsrfRaw)
        val metaCsrf = section.csrfToken

        suspend fun postEndpoint(endpoint: String, token: String): SoloLatinoPlayerApiResult? {
            if (token.isBlank()) return null
            val shield = SoloLatinoMarkup.computeRenderShieldHeader(token, serverTimeOffsetMs.get())
            val reqHeaders = linkedMapOf(
                "user-agent" to ua,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
                "Content-Type" to "application/json",
                "Origin" to origin,
                "Referer" to pageUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "X-SL-Render" to shield,
            )
            if (xsrfToken.isNotBlank()) reqHeaders["X-XSRF-TOKEN"] = xsrfToken
            if (!metaCsrf.isNullOrBlank()) reqHeaders["X-CSRF-TOKEN"] = metaCsrf
            val payload = SoloLatinoMarkup.jsonMapper.writeValueAsString(mapOf("t" to token))
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val res = try {
                app.post(
                    "$origin$endpoint",
                    headers = reqHeaders,
                    cookies = mergedCookies,
                    requestBody = body,
                    timeout = 12L,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return null
            }
            if (!res.isSuccessful) return null
            saveCookiesFromResponse(siteCookies, res.cookies)
            return SoloLatinoMarkup.parsePlayerApiResponse(res.text)
        }

        return coroutineScope {
            val btnDeferred = section.buttons.map { btn ->
                async(Dispatchers.IO) {
                    if (!btn.resolvedUrl.isNullOrBlank()) {
                        SoloLatinoResolvedButtonOutput(btn.langLabel, btn.label, btn.token, btn.resolvedUrl, btn.resolvedType)
                    } else {
                        val apiRes = postEndpoint("/api/player-url", btn.token)
                        SoloLatinoResolvedButtonOutput(btn.langLabel, btn.label, btn.token, apiRes?.url, apiRes?.type)
                    }
                }
            }
            val lazyDeferred = async(Dispatchers.IO) {
                val tok = section.lazyEmbedToken
                if (!tok.isNullOrBlank()) postEndpoint("/api/player-embed", tok) else null
            }
            btnDeferred.awaitAll() to lazyDeferred.await()
        }
    }

    suspend fun resolvePelisSeriesHoy(
        embedUrl: String,
        referer: String?,
    ): List<SoloLatinoWebPshServerResult> {
        val activeTestResponse = testWebResponse
        val effReferer = referer?.takeIf { it.startsWith("http", ignoreCase = true) } ?: "${SoloLatinoMarkup.MAIN_URL}/"

        if (activeTestResponse == null) {
            val httpAttempt = tryResolvePelisSeriesHoyViaHttp(embedUrl, effReferer)
            if (httpAttempt.isNotEmpty()) return httpAttempt
        }

        val ctx = appContext
        if (ctx != null) {
            val webAttempt = runCatching {
                SoloLatinoWebTransport.resolvePelisSeriesHoy(
                    context = ctx,
                    embedUrl = embedUrl,
                    referer = effReferer,
                    userAgent = currentUserAgent(),
                    testResponse = activeTestResponse,
                )
            }.getOrNull()
            if (!webAttempt.isNullOrEmpty()) return webAttempt
        }
        return emptyList()
    }

    private suspend fun tryResolvePelisSeriesHoyViaHttp(
        embedUrl: String,
        effReferer: String,
    ): List<SoloLatinoWebPshServerResult> {
        val ua = currentUserAgent()
        val cookies = readMergedCookies(embedUrl, pshCookies)
        val getHeaders = SoloLatinoSessionHelper.buildBrowserHeaders(ua, effReferer, isAjax = false)
        var pageRes = runCatching {
            app.get(embedUrl, headers = getHeaders, cookies = cookies, timeout = 15L)
        }.getOrNull()
        if (pageRes != null) {
            saveCookiesFromResponse(pshCookies, pageRes.cookies)
        }
        if (pageRes == null || SoloLatinoSessionHelper.looksLikeCloudflareChallenge(pageRes.code, pageRes.text)) {
            val cfPage = runCatching {
                app.get(
                    embedUrl,
                    headers = getHeaders,
                    cookies = readMergedCookies(embedUrl, pshCookies),
                    interceptor = cfKiller,
                    timeout = 20L,
                )
            }.getOrNull()
            if (cfPage != null) {
                pageRes = cfPage
                saveCookiesFromResponse(pshCookies, cfPage.cookies)
                WebViewResolver.webViewUserAgent?.takeIf { it.isNotBlank() }?.let { preferredUserAgent = it }
            }
        }
        val finalPage = pageRes ?: return emptyList()
        if (!finalPage.isSuccessful) return emptyList()
        val init = SoloLatinoMarkup.parsePelisSeriesHoyInit(finalPage.text, finalPage.url) ?: return emptyList()

        val activeUa = currentUserAgent()
        val postCookies = readMergedCookies(finalPage.url, pshCookies)
        val postHeaders = mapOf(
            "user-agent" to activeUa,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "es-419,es;q=0.9,en;q=0.8",
            "Origin" to init.originUrl,
            "Referer" to finalPage.url,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val sEndpoint = "${init.originUrl}/s.php"

        val step1Res = runCatching {
            app.post(
                sEndpoint,
                headers = postHeaders,
                cookies = postCookies,
                data = mapOf(
                    "a" to "1",
                    "tok" to init.token,
                    "rt" to init.qv,
                    "tk" to init.qt,
                    "ao" to init.ao,
                ),
                timeout = 12L,
            )
        }.getOrNull() ?: return emptyList()
        if (!step1Res.isSuccessful) return emptyList()
        saveCookiesFromResponse(pshCookies, step1Res.cookies)

        val servers = SoloLatinoMarkup.parsePelisSeriesHoyStep1(step1Res.text)
        if (servers.isEmpty()) return emptyList()

        val results = mutableListOf<SoloLatinoWebPshServerResult>()
        for (srv in servers) {
            runCatching {
                app.post(
                    sEndpoint,
                    headers = postHeaders,
                    cookies = readMergedCookies(finalPage.url, pshCookies),
                    data = mapOf(
                        "a" to "click",
                        "tok" to init.token,
                        "ts" to "1",
                        "v" to srv.url,
                        "rt" to init.qv,
                        "tk" to init.qt,
                        "ao" to init.ao,
                    ),
                    timeout = 8L,
                )
            }
            val step2Res = runCatching {
                app.post(
                    sEndpoint,
                    headers = postHeaders,
                    cookies = readMergedCookies(finalPage.url, pshCookies),
                    data = mapOf(
                        "a" to "2",
                        "v" to srv.url,
                        "tok" to init.token,
                        "rt" to init.qv,
                        "tk" to init.qt,
                        "ao" to init.ao,
                    ),
                    timeout = 12L,
                )
            }.getOrNull() ?: continue
            if (!step2Res.isSuccessful) continue
            val parsed = SoloLatinoMarkup.parsePelisSeriesHoyStep2(step2Res.text) ?: continue
            results.add(
                SoloLatinoWebPshServerResult(
                    label = srv.label,
                    langLabel = srv.langLabel,
                    serverValue = srv.url,
                    type = parsed.type,
                    url = parsed.url,
                    u = parsed.u,
                    sig = parsed.sig,
                ),
            )
        }
        return results
    }
}
