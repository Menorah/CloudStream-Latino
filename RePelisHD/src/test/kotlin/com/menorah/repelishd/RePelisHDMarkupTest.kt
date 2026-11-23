package com.menorah.repelishd
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class RePelisHDMarkupTest {
    private fun doc(html: String) = Jsoup.parse(html, "https://repelishd.surf/explorar?t=tv")
    @Test fun catalogueAndPaging() {
        val d = doc("""<a class="cv" href="/ver-serie/example"><img src="https://image.tmdb.org/a.jpg"><div class="cv-t">Serie</div></a><a class="cv" href="https://evil.test/ver-pelicula/x"><div class="cv-t">No</div></a><a href="/explorar?t=tv&amp;page=2">Más</a>""")
        assertEquals(1, RePelisHDMarkup.cards(d).size); assertFalse(RePelisHDMarkup.cards(d).first().movie)
        assertTrue(RePelisHDMarkup.hasNext(d, 1)); assertFalse(RePelisHDMarkup.hasNext(d, 2))
    }
    @Test fun details() {
        val d = doc("""<div class="chh"><img src="https://image.tmdb.org/poster.jpg"><div class="chh-dati"><ul class="chh-badge"><li>2008</li></ul><div class="chh-gen"><a>Acción</a><a>Drama</a></div><p class="sin-t">Sinopsis</p></div></div>""")
        assertEquals("https://image.tmdb.org/poster.jpg", RePelisHDMarkup.poster(d)); assertEquals("Sinopsis", RePelisHDMarkup.plot(d))
        assertEquals(2008, RePelisHDMarkup.year(d)); assertEquals(listOf("Acción", "Drama"), RePelisHDMarkup.genres(d))
    }
    @Test fun seasonState() {
        val data = """[1,"x:\\u007b\"iniziale\":{\"imdb\":\"tt123\",\"stagione\":2,\"stagioni\":[{\"season_number\":1,\"disponibili\":2},{\"season_number\":2,\"disponibili\":1}],\"episodi\":[{\"season_number\":2,\"episode_number\":1,\"name\":\"Piloto\",\"stream_available\":1}]}}"]"""
        val d = doc("<script>self.__next_f.push($data)</script>")
        val x = RePelisHDMarkup.initial(d)!!
        assertEquals(listOf(1 to 2, 2 to 1), RePelisHDMarkup.seasons(x)); assertEquals(2, RePelisHDMarkup.episodes(x).single().season); assertEquals("tt123", RePelisHDMarkup.imdb(d))
    }
    @Test fun playerDataAndSafeSources() {
        val token = RePelisHDMarkup.token("""JSON.stringify({ type: 'movie', id: "tt123", token: "a.b_C-2" })""")!!
        assertEquals("tt123",token.id)
        val movie=RePelisHDMarkup.parseJson("""{"sources":[{"url":"https://dr0pstream.com/e/abc","version":"latino"},{"url":"https://dr0pstream.com.evil.test/e/x","version":"latino"}]}""")
        assertEquals(listOf(Source("https://dr0pstream.com/e/abc","Latino")),RePelisHDMarkup.sources(movie,null,null))
        val tv=RePelisHDMarkup.parseJson("""{"tv":{"version":"subtitulado","seasons":[{"season_number":2,"episodes":[{"episode_number":1,"sources":[{"url":"https://superplayer.ws/e/xyz"}]}]}]}}""")
        assertEquals("Subtitulado",RePelisHDMarkup.sources(tv,2,1).single().language); assertTrue(RePelisHDMarkup.sources(tv,2,2).isEmpty())
    }
}
