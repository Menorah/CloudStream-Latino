package com.menorah.sololatino

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Temporary, local-only connection check. It contains no video catalog or playback. */
class SoloLatinoAccessDiagnostic(private val context: Context) : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino Diagnóstico"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage = mainPageOf("local" to "Resultados de conexión (no reproduce video)")
    private var report = listOf("Todavía no se ejecutó la prueba.")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        report = inspect()
        val items = report.mapIndexed { index, message ->
            newMovieSearchResponse(message, "$mainUrl/diagnostico-$index", TvType.Movie) { }
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = false)
    }
    override suspend fun search(query: String): List<SearchResponse> = emptyList()
    override suspend fun load(url: String): LoadResponse = newMovieLoadResponse("Resultado de conexión", mainUrl, TvType.Movie, "") {
        plot = "Comprobación temporal: no es el complemento final ni reproduce video.\n\n" + report.joinToString("\n") +
            "\n\nSe muestran solo códigos y cantidades. No se muestran cookies, tokens ni direcciones privadas."
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean = false

    internal suspend fun inspect(): List<String> {
        val home = checkHttp("https://sololatino.net/", "Portada")
        val episode = checkHttp("https://sololatino.net/serie/black-clover/temporada-1/episodio-1", "Episodio")
        val embed = checkHttp("https://embed69.org/f/tt7441658-1x01", "Embed69 (ruta estimada)")
        val browser = browserEpisode()
        return listOf(home, episode, embed, browser)
    }

    private suspend fun checkHttp(url: String, label: String): String = withContext(Dispatchers.IO) {
        try {
            val response = app.get(url, timeout = 15)
            val host = java.net.URI(response.url).host.orEmpty()
            val expected = java.net.URI(url).host.orEmpty()
            if (host != expected) return@withContext "$label: redirección externa"
            val body = response.text
            val challenge = body.contains("anubis_challenge", true) || body.contains("cf-chl-", true) || body.contains("<title>Just a moment", true)
            if (challenge) return@withContext "$label: HTTP ${response.code}, verificación"
            if (!response.isSuccessful) return@withContext "$label: HTTP ${response.code}"
            val doc = org.jsoup.Jsoup.parse(body, response.url)
            val detail = when (label) {
                "Portada" -> "${doc.select("div.card").size} fichas"
                "Episodio" -> "${doc.select("button.server-btn[data-player-token]").size} botones"
                else -> if (body.contains("dataLink")) "lista detectada" else "sin lista detectada"
            }
            "$label: HTTP ${response.code}, $detail"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "$label: error ${e.javaClass.simpleName}" }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun browserEpisode(): String = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var web: WebView? = null
        var lastStatus = 0
        fun finish(message: String?) {
            if (!finished.compareAndSet(false, true)) return
            handler.post {
                web?.stopLoading()
                web?.destroy()
                web = null
            }
            if (message != null && continuation.isActive) continuation.resume(message)
        }
        continuation.invokeOnCancellation { finish(null) }
        handler.post {
            if (finished.get()) return@post
            try {
                val view = WebView(context); web = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.javaScriptCanOpenWindowsAutomatically = false
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.loadsImagesAutomatically = false
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https" || request.url.host != "sololatino.net"
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        if (uri.scheme == "https" && (uri.host == "sololatino.net" || uri.host == "challenges.cloudflare.com")) return null
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) lastStatus = response.statusCode
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (finished.get()) return
                        view.evaluateJavascript("document.querySelectorAll('button.server-btn[data-player-token]').length") { value ->
                            val count = value.toIntOrNull() ?: 0
                            if (count > 0) finish("WebView episodio: $count botones")
                            else if (!finished.get()) view.postDelayed(this, 800)
                        }
                    }
                }
                handler.postDelayed({ finish(if (lastStatus != 0) "WebView episodio: HTTP $lastStatus, sin botones" else "WebView episodio: sin botones en 20 s") }, 20_000)
                view.loadUrl("https://sololatino.net/serie/black-clover/temporada-1/episodio-1")
                view.postDelayed(poll, 1200)
            } catch (e: Exception) { finish("WebView episodio: error ${e.javaClass.simpleName}") }
        }
    }
}
