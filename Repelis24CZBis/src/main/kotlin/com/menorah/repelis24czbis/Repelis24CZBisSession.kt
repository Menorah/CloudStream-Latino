package com.menorah.repelis24czbis

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Coroutines.mainWork
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

internal object Repelis24CZSession {
    private val mutex = Mutex()

    @Volatile
    var cookieHeader: String = ""
        private set

    @Volatile
    var userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private set

    fun buildHeaders(referer: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["user-agent"] = userAgent
        h["Referer"] = referer
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        h["Accept-Language"] = "es-ES,es;q=0.9,en;q=0.8"
        if (cookieHeader.isNotBlank()) {
            h["Cookie"] = cookieHeader
        }
        return h
    }

    suspend fun fetchHtml(url: String, referer: String = "${Repelis24CZBisMarkup.DEFAULT_MAIN_URL}/"): String? {
        val first = runCatching {
            app.get(url, headers = buildHeaders(referer), timeout = 20L)
        }.getOrNull()
        if (first != null && first.isSuccessful && !Repelis24CZBisMarkup.isChallengePage(first.code, first.text)) {
            return first.text
        }
        return solveWithWebView(url)
    }

    suspend fun postAjax(
        ajaxUrl: String,
        referer: String,
        params: Map<String, String>,
    ): String? {
        val headers = LinkedHashMap<String, String>()
        headers["user-agent"] = userAgent
        headers["Referer"] = referer
        headers["Origin"] = Repelis24CZBisMarkup.DEFAULT_MAIN_URL
        headers["Accept"] = "application/json, text/html, */*; q=0.01"
        headers["X-Requested-With"] = "XMLHttpRequest"
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader

        val first = runCatching {
            app.post(ajaxUrl, headers = headers, data = params, timeout = 20L)
        }.getOrNull()
        if (first != null && first.isSuccessful && !Repelis24CZBisMarkup.isChallengePage(first.code, first.text)) {
            return first.text
        }

        // Refresh cookies via WebView against the referer/main page, then retry POST
        solveWithWebView(referer)
        val retryHeaders = LinkedHashMap(headers)
        retryHeaders["user-agent"] = userAgent
        if (cookieHeader.isNotBlank()) retryHeaders["Cookie"] = cookieHeader
        val retry = runCatching {
            app.post(ajaxUrl, headers = retryHeaders, data = params, timeout = 20L)
        }.getOrNull()
        if (retry != null && retry.isSuccessful && !Repelis24CZBisMarkup.isChallengePage(retry.code, retry.text)) {
            return retry.text
        }

        // Last resort: execute fetch() inside WebView origin so browser challenge cookies/TLS are used directly
        return postAjaxViaWebView(referer, ajaxUrl, params)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveWithWebView(targetUrl: String): String? = mutex.withLock {
        val ctx = getContext() as? Context ?: return@withLock null
        return@withLock runCatching {
            var resolvedHtml: String? = null
            val webView = mainWork {
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {}
                    userAgent = settings.userAgentString
                    loadUrl(targetUrl)
                }
            }
            try {
                for (attempt in 0 until 40) {
                    delay(400L)
                    val html = evalHtml(webView)
                    if (html.isNotBlank() && !Repelis24CZBisMarkup.isChallengePage(200, html) && html.length > 800) {
                        val cmCookie = runCatching { CookieManager.getInstance().getCookie(targetUrl).orEmpty() }.getOrDefault("")
                        if (cmCookie.isNotBlank()) cookieHeader = cmCookie
                        resolvedHtml = html
                        break
                    }
                }
            } finally {
                mainWork {
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
            resolvedHtml
        }.getOrNull()
    }

    private suspend fun evalHtml(webView: WebView): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            webView.post {
                webView.evaluateJavascript(
                    "(function(){return document.documentElement?document.documentElement.outerHTML:'';})()",
                ) { jsonResult ->
                    val unquoted = if (jsonResult != null && jsonResult.startsWith("\"") && jsonResult.endsWith("\"")) {
                        decodeJsString(jsonResult.substring(1, jsonResult.length - 1))
                    } else {
                        jsonResult.orEmpty()
                    }
                    if (cont.isActive) cont.resumeWith(Result.success(unquoted))
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun postAjaxViaWebView(
        pageUrl: String,
        ajaxUrl: String,
        params: Map<String, String>,
    ): String? {
        val ctx = getContext() as? Context ?: return null
        val encodedBody = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return runCatching {
            val webView = mainWork {
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {}
                    loadUrl(pageUrl)
                }
            }
            try {
                for (attempt in 0 until 25) {
                    delay(350L)
                    val h = evalHtml(webView)
                    if (h.isNotBlank() && !Repelis24CZBisMarkup.isChallengePage(200, h) && h.length > 800) {
                        break
                    }
                }
                val js = """
                    (function(){
                      window.__czAjaxDone = false;
                      window.__czAjaxRes = '';
                      fetch('${ajaxUrl.replace("'", "\\'")}', {
                        method: 'POST',
                        credentials: 'same-origin',
                        headers: {'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8','X-Requested-With':'XMLHttpRequest'},
                        body: '${encodedBody.replace("'", "\\'")}'
                      }).then(function(r){return r.text();}).then(function(t){
                        window.__czAjaxRes = t;
                        window.__czAjaxDone = true;
                      }).catch(function(){
                        window.__czAjaxDone = true;
                      });
                    })();
                """.trimIndent()
                evalJs(webView, js)
                repeat(20) {
                    delay(300L)
                    val done = evalJs(webView, "(function(){return window.__czAjaxDone?'1':'0';})()")
                    if (done == "1") {
                        val body = evalJs(webView, "(function(){return window.__czAjaxRes||'';})()")
                        return@runCatching body.takeIf { it.isNotBlank() }
                    }
                }
                null
            } finally {
                mainWork {
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
            }
        }.getOrNull()
    }

    private suspend fun evalJs(webView: WebView, script: String): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            webView.post {
                webView.evaluateJavascript(script) { res ->
                    val cleaned = if (res != null && res.startsWith("\"") && res.endsWith("\"")) {
                        decodeJsString(res.substring(1, res.length - 1))
                    } else {
                        res.orEmpty()
                    }
                    if (cont.isActive) cont.resumeWith(Result.success(cleaned))
                }
            }
        }

    private fun decodeJsString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (hex != null) {
                                sb.append(hex.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append("\\u")
                        i += 2
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
