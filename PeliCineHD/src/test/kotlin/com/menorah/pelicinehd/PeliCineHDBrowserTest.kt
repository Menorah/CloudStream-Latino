package com.menorah.pelicinehd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del navegador interno: el script que se ejecuta en la WebView y la lectura de su
 * respuesta. Ninguna abre una WebView ni toca la red.
 */
class PeliCineHDBrowserTest {
    private val browser = PeliCineHDBrowser()

    @Test
    fun `el resultado del script se lee como pagina`() {
        val page = browser.parse("""{"done":true,"code":200,"url":"https://pelicinehd.com/movies/x/","body":"<html>ok</html>"}""")
        assertNotNull(page)
        assertEquals(200, page!!.code)
        assertEquals("https://pelicinehd.com/movies/x/", page.url)
        assertTrue(page.body.contains("ok"))
    }

    @Test
    fun `tambien se lee cuando la webview devuelve el json entre comillas`() {
        // Lo que devuelve `evaluateJavascript`: la cadena JSON, escapada dentro de otra cadena.
        val inner = """{"done":true,"code":200,"url":"https://pelicinehd.com/","body":"<html>ok</html>"}"""
        val quoted = "\"" + inner.replace("\"", "\\\"") + "\""

        val page = browser.parse(quoted)
        assertNotNull(page)
        assertEquals(200, page!!.code)
        assertTrue(page.body.contains("ok"))
    }

    @Test
    fun `mientras la pagina carga no hay resultado`() {
        assertNull(browser.parse("""{"done":false}"""))
        assertNull(browser.parse("null"))
        assertNull(browser.parse(""))
        assertNull(browser.parse(null))
        assertNull(browser.parse("""{"done":true,"code":200,"url":"https://pelicinehd.com/","body":""}"""))
    }

    @Test
    fun `el script de GET lee el documento renderizado`() {
        val script = browser.script(null)
        assertTrue(script.contains("document.documentElement.outerHTML"))
        assertTrue(script.contains("location.href"))
        // La imagen centinela es la que cierra la WebView.
        assertTrue(script.contains("__pelicinehd_ready__"))
    }

    @Test
    fun `la webview no se cierra sin resultado`() {
        val script = browser.script(null)
        // Idempotente: si ya hay resultado se devuelve tal cual, sin volver a cargar nada.
        assertTrue(script.contains("if (window.__pcRes && window.__pcRes.done) { return window.__pcRes; }"))
        // El latido existe para que el resolver reevalúe el script mientras la página carga...
        assertTrue(script.contains("setInterval("))
        assertTrue(script.contains("__pelicinehd_beat__"))
        // ...y `finish` pide la imagen centinela, que es la única que cierra la WebView.
        assertTrue(script.contains("__pelicinehd_ready__"))
        assertTrue(script.contains("function finish("))
    }

    @Test
    fun `el script de AJAX hace el POST dentro de la pagina`() {
        val script = browser.script(
            "https://pelicinehd.com/wp-admin/admin-ajax.php" to
                mapOf("action" to "action_select_season", "season" to "2", "post" to "10866")
        )
        assertTrue(script.contains("method: 'POST'"))
        assertTrue(script.contains("credentials: 'include'"))
        assertTrue(script.contains("X-Requested-With"))
        assertTrue(script.contains("action=action_select_season"))
        assertTrue(script.contains("season=2"))
        assertTrue(script.contains("post=10866"))
    }

    @Test
    fun `las cadenas de JavaScript se escapan`() {
        assertEquals("\"a\\\"b\"", browser.jsQuote("a\"b"))
        assertEquals("\"</script>\"".replace("</script>", "\\u003c/script\\u003e"), browser.jsQuote("</script>"))
        assertEquals("\"linea\\n\"", browser.jsQuote("linea\n"))
    }
}
