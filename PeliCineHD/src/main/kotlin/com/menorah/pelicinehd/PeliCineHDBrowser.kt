package com.menorah.pelicinehd

import com.lagradost.cloudstream3.network.WebViewResolver
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Respuesta obtenida con el navegador del dispositivo. */
internal data class BrowserPage(
    val code: Int,
    val url: String,
    val body: String,
)

/**
 * Respaldo para cuando el cliente HTTP normal no recibe la página: la pide **desde dentro de la
 * WebView** (como el `sendFromPage` de *skystream*) y devuelve su HTML.
 *
 * CloudStream no tiene una función para eso, así que se arma con `WebViewResolver`, cuyo `script`
 * se evalúa dentro de la página en cada petición que hace. El HTML se recoge así:
 *
 * 1. el script guarda el resultado en `window.__pcRes` y pide una imagen *latido* cada 600 ms; su
 *    petición hace que el resolver vuelva a evaluar el script y entregue el objeto por
 *    `scriptCallback`;
 * 2. en cuanto hay resultado, el script pide la imagen con [FLAG] en la URL, y `interceptUrl` la
 *    intercepta y **cierra la WebView**.
 *
 * La clave es que el latido **solo empieza cuando la página ya está cargada y solo pide la imagen
 * centinela cuando hay resultado**: la versión anterior la pedía al arrancar, con lo que la WebView
 * se cerraba antes de cargar y no se obtenía nada (tampoco en *skystream*). Las dos peticiones
 * apuntan a `/favicon.ico`, que el resolver sirve localmente: no generan tráfico al sitio.
 */
internal class PeliCineHDBrowser(private val origin: String = PeliCineHDMarkup.BASE) {

    private companion object {
        /** Tope por página: si el navegador no termina, el complemento sigue sin ella. */
        const val TIMEOUT_MS = 30_000L

        /** Marca de la imagen que cierra la WebView: nunca coincide con una URL real del sitio. */
        const val FLAG = "__pelicinehd_ready__"

        /** Marca de la imagen de latido: hace que el resolver reevalúe el script. */
        const val BEAT = "__pelicinehd_beat__"
    }

    /** Carga [url] en el navegador y devuelve su HTML ya renderizado. */
    suspend fun get(url: String, referer: String? = null): BrowserPage? =
        resolve(url, referer, post = null)

    /**
     * Carga [page] en el navegador y, desde dentro de ella, hace el POST AJAX a [ajax] con [form].
     * Así se conservan el origen, las cookies y la identidad del navegador.
     */
    suspend fun post(page: String, ajax: String, form: Map<String, String>): BrowserPage? =
        resolve(page, page, post = ajax to form)

    private suspend fun resolve(
        load: String,
        referer: String?,
        post: Pair<String, Map<String, String>>?,
    ): BrowserPage? {
        val captured = AtomicReference<BrowserPage?>(null)
        runCatching {
            WebViewResolver(
                // La imagen centinela cierra la WebView, ya con el HTML capturado.
                interceptUrl = Regex(FLAG),
                additionalUrls = listOf(Regex(BEAT)),
                // `null` deja el User-Agent real de la WebView.
                userAgent = null,
                // El navegador carga la página entera por su cuenta.
                useOkhttp = false,
                script = script(post),
                scriptCallback = { raw ->
                    parse(raw)?.let { page -> captured.compareAndSet(null, page) }
                    Unit
                },
                timeout = TIMEOUT_MS,
            ).resolveUsingWebView(load, referer = referer) { request ->
                // Solo cierra si ya hay HTML: la imagen de latido mantiene la página viva hasta eso.
                captured.get() != null
            }
        }
        return captured.get()
    }

    /** Objeto `{done, code, url, body}`: `done=false` mientras la página aún carga. */
    internal fun parse(raw: String?): BrowserPage? = runCatching {
        if (raw.isNullOrBlank() || raw == "null") return null
        // `evaluateJavascript` entrega el objeto en JSON o la cadena JSON entre comillas.
        val text = raw.trim().removeSurrounding("\"").replace("\\\"", "\"")
        val json = JSONObject(text)
        if (!json.optBoolean("done")) return null
        val body = json.optString("body")
        if (body.isBlank()) return null
        BrowserPage(
            code = json.optInt("code", 0),
            url = json.optString("url").ifBlank { origin },
            body = body,
        )
    }.getOrNull()

    internal fun script(post: Pair<String, Map<String, String>>?): String {
        val beat = jsQuote("$origin/favicon.ico?$BEAT=")
        val ready = jsQuote("$origin/favicon.ico?$FLAG=")
        val body = if (post == null) {
            // GET: el documento ya renderizado, sin salir de la página (mismo origen, sin CORS).
            "done(200, String(location.href), document.documentElement.outerHTML);"
        } else {
            val (ajax, form) = post
            val encoded = form.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
            """
            fetch(${jsQuote(ajax)}, {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: ${jsQuote(encoded)}
            }).then(function (r) {
                return r.text().then(function (t) { done(r.status, String(r.url), t); });
            }).catch(function (e) { done(0, '', String(e)); });
            """.trimIndent()
        }

        return """
            (function () {
                if (window.__pcRes && window.__pcRes.done) { return window.__pcRes; }
                if (window.__pcStarted) { return { done: false }; }
                window.__pcStarted = true;

                function image(url) { try { (new Image()).src = url + Date.now(); } catch (e) {} }
                function beat() { image($beat); }

                function finish(code, url, text) {
                    if (!window.__pcRes || !window.__pcRes.done) {
                        window.__pcRes = { done: true, code: code, url: String(url || ''), body: String(text || '') };
                    }
                    // Se pide el HTML una vez más para que el resolver lo reciba, y se cierra.
                    image($ready);
                }
                function done(code, url, text) { finish(code, url, text); }

                function go() {
                    // `loading` es lo único que indica que el DOM aún no está; esperar a `complete`
                    // podía no llegar nunca si la página deja anuncios o sondeos abiertos.
                    if (document.readyState === 'loading') { return setTimeout(go, 300); }
                    $body
                }

                // El latido empieza AHORA, no antes: pedirlo al arrancar cerraba la WebView con la
                // página aún en blanco y no se capturaba nada.
                setInterval(function () {
                    if (window.__pcRes && window.__pcRes.done) { image($ready); } else { beat(); }
                }, 600);

                setTimeout(go, 300);
                return { done: false };
            })()
        """.trimIndent()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** Cadena literal de JavaScript, escapada como JSON (que es un subconjunto válido). */
    internal fun jsQuote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
