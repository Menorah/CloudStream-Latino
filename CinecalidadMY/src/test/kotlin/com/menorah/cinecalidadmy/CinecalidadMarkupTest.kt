package com.menorah.cinecalidadmy
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CinecalidadMarkupTest {
    private fun doc(html: String, base: String = "https://www.cinecalidad.my/") = Jsoup.parse(html, base)
    @Test fun filmCardsAndPagination() {
        val d = doc("""<div class="home_post_cont post_box"><a href="/pelicula/iron/"><img title="Iron Man" src="/poster.jpg"></a></div><div class="home_post_cont post_box"><a href="https://evil.test/pelicula/x/"><img title="Ad" src="/ad.jpg"></a></div><a href="/page/2/">Next</a>""")
        assertEquals(listOf("Iron Man"), CinecalidadMarkup.cards(d).map { it.title }); assertTrue(CinecalidadMarkup.hasNext(d,1)); assertFalse(CinecalidadMarkup.hasNext(d,2))
        val search = doc("""<a href="/page/2/?s=iron">Next</a>""", "https://www.cinecalidad.my/?s=thor")
        assertFalse(CinecalidadMarkup.hasNext(search,1))
        val category = doc("""<a href="/genero-peliculas/accion/page/2/">Next</a>""", "https://www.cinecalidad.my/genero-peliculas/accion/")
        assertTrue(CinecalidadMarkup.hasNext(category,1))
        val searchMatch = doc("""<a href="/page/2/?s=iron">Next</a>""", "https://www.cinecalidad.my/?s=iron")
        assertTrue(CinecalidadMarkup.hasNext(searchMatch,1))
    }
    @Test fun knownServicesAreMappedWithoutTrailersOrDownloads() {
        val d = doc("""<div id="panel_online" class="pane"><div class="pane_descripcion">Audio latino</div><a class="onlinelink" service="OnlineFilemoon" data="abc123"></a><a class="onlinelink" service="OnlineVoe" data="def456"></a><a class="onlinelink" service="Trailer" data="aaa"></a><a class="onlinelink" service="OnlineVoe" data="../evil"></a></div><div id="panel_descarga"><a class="onlinelink" service="OnlineVoe" data="zzz"></a></div>""")
        val p = CinecalidadMarkup.players(d)
        assertEquals(listOf("https://bysedikamoum.com/e/abc123", "https://voe.sx/e/def456"), p.map { it.url }); assertTrue(p.all { it.language == "Latino" })
        assertFalse(CinecalidadMarkup.siteUrl("https://www.cinecalidad.my.evil.test/"))
    }
    @Test fun detail() {
        val d=doc("""<div class="single_left"><h1>Película</h1><table><tr><td><img src="/image.jpg"></td><td><p>Sinopsis.</p></td></tr></table></div>""")
        assertEquals("Película", CinecalidadMarkup.title(d)); assertEquals("Sinopsis.", CinecalidadMarkup.plot(d)); assertEquals("https://www.cinecalidad.my/image.jpg", CinecalidadMarkup.poster(d))
    }
}
