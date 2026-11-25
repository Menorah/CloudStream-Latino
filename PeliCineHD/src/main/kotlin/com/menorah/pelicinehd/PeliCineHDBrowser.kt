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
 * Pide las páginas **desde dentro de la WebView**, que es la única forma de que este sitio
 * conteste: Cloudflare lo bloquea por la huella de cliente (no por cookies), de modo que okhttp
 * recibe 403 o la conexión cerrada aunque lleve `cf_clearance`, mientras un navegador entra sin
 * reto. Es el mismo recurso que usa la app *skystream* (`sendFromPage`): la petición la hace el
 * navegador y el complemento solo recibe el HTML.
 *
 * CloudStream no ofrece esa función, así que se arma con `WebViewResolver`:
 *
 * 1. el `script` corre dentro de la página y, cuando el documento está listo, devuelve el HTML
 *    (o hace el `fetch` del AJAX) en un objeto de JavaScript;
 * 2. `scriptCallback` recibe ese objeto ya en JSON y se queda con el primero válido;
 * 3. el script pide una imagen centinela con [READY_FLAG] en la URL para que `WebViewResolver`
 *    corte en ese punto (`interceptUrl`) y devuelva el control sin esperar al timeout.
 */
internal class PeliCineHDBrowser(private val origin: String = PeliCineHDMarkup.BASE) {

    private companion object {
        /** Tope por página: si el navegador no termina, se sigue con el resto del complemento. */
        const val TIMEOUT_MS = 25_000L

        /** Marca de la petición centinela que cierra la WebView. */
        const val READY_FLAG = "__pelicinehd_ready__"
    }

    /** Carga [url] en el navegador y devuelve su HTML ya renderizado. */
    suspend fun get(url: String, referer: String? = null): BrowserPage? =
        resolve(url, referer, post = null)

    /**
     * Carga [page] en el navegador y, desde dentro de ella, hace el POST AJAX a [ajax] con [form].
     * Así se conserva el origen, las cookies y la huella del navegador.
     */
    suspend fun post(page: String, ajax: String, form: Map<String, String>): BrowserPage? =
        resolve(page, page, post = ajax to form)

    private suspend fun resolve(
        load: String,
        referer: String?,
        post: Pair<String, Map<String, String>>?,
    ): BrowserPage? {
        // Primero sin más: si el navegador recibe el reto, la cookie queda guardada y el segundo
        // intento ya entra en la página buena. Nunca se devuelve una página de verificación.
        var page = attempt(load, referer, post)
        if (page == null || PeliCineHDMarkup.isChallenge(page.code, page.body)) {
            page = attempt(load, referer, post)
        }
        return page?.takeUnless { PeliCineHDMarkup.isChallenge(it.code, it.body) }
    }

    private suspend fun attempt(
        load: String,
        referer: String?,
        post: Pair<String, Map<String, String>>?,
    ): BrowserPage? {
        val captured = AtomicReference<BrowserPage?>(null)
        runCatching {
            WebViewResolver(
                // La petición centinela cierra la WebView en cuanto hay resultado.
                interceptUrl = Regex(READY_FLAG),
                // `null` deja el User-Agent real de la WebView: es el que Cloudflare acepta.
                userAgent = null,
                // El navegador carga la página entera por su cuenta.
                useOkhttp = false,
                script = script(post),
                scriptCallback = { raw ->
                    parse(raw)?.let { captured.compareAndSet(null, it) }
                },
                timeout = TIMEOUT_MS,
            ).resolveUsingWebView(load, referer = referer)
        }
        return captured.get()
    }

    /** Objeto `{done, code, url, body}`: `done=false` mientras la página aún carga. */
    internal fun parse(raw: String?): BrowserPage? = runCatching {
        if (raw.isNullOrBlank() || raw == "null") return null
        // `evaluateJavascript` puede devolver el objeto ya en JSON o la cadena JSON entre comillas.
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
        val ready = jsQuote("$origin/?$READY_FLAG=")
        val body = if (post == null) {
            // GET: basta con leer el documento ya renderizado (mismo origen, sin CORS).
            "done(200, String(location.href), document.documentElement.outerHTML);"
        } else {
            val (ajax, form) = post
            val encoded = form.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
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
                if (window.__pcRes && window.__pcRes.done) {
                    try { new Image().src = $ready + Date.now(); } catch (e) {}
                    return window.__pcRes;
                }
                if (window.__pcStarted) { return { done: false }; }
                window.__pcStarted = true;
                function done(code, url, text) {
                    window.__pcRes = { done: true, code: code, url: String(url || ''), body: String(text || '') };
                    try { new Image().src = $ready + Date.now(); } catch (e) {}
                }
                function go() {
                    // `interactive` ya tiene el DOM completo; `complete` puede no llegar nunca si
                    // la página deja anuncios o sondeos abiertos.
                    if (document.readyState === 'loading') { return setTimeout(go, 300); }
                    $body
                }
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
