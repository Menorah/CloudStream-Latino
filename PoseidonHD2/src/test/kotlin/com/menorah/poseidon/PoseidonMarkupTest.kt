package com.menorah.poseidon

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PoseidonMarkupTest {
    private fun doc(html: String) = Jsoup.parse(html, "https://www.poseidonhd2.co/peliculas")
    @Test fun cardsPaginationAndImages() {
        val d = doc("""<a href="/pelicula/1/title"><img alt="Una película" src="/_next/image?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Foriginal%2Fp.jpg&amp;w=384"></a>
           <a href="/serie/2/title"><img alt="Una serie" src="https://image.tmdb.org/a.jpg"></a>
           <a href="/serie/2/title/temporada/1/episodio/1"><img alt="Episode" src="/a.jpg"></a><a href="/peliculas/page/2">2</a>""")
        val cards = PoseidonMarkup.cards(d)
        assertEquals(2, cards.size); assertTrue(cards[0].movie); assertFalse(cards[1].movie)
        assertEquals("https://image.tmdb.org/t/p/original/p.jpg", cards[0].poster)
        assertTrue(PoseidonMarkup.hasNext(d, 1)); assertFalse(PoseidonMarkup.hasNext(d, 2))
    }
    @Test fun seasonsAndLanguageGroups() {
        val d = doc("""<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"thisSerie":{"seasons":[{"number":1,"episodes":[{"number":2,"title":"Segundo"},{"number":1,"title":"Primero"}]},{"number":2,"episodes":[]}]},"episode":{"videos":{"latino":[{"result":"https://player.poseidonhd2.co/player.php?h=abc"}],"english":[{"result":"https://evil.test/player.php?h=123"}]}}}}}</script>""")
        val state = PoseidonMarkup.state(d)!!; val series = state.path("thisSerie")
        assertEquals(listOf(1,2), PoseidonMarkup.episodes(series).map { it.number })
        assertEquals(listOf(2), PoseidonMarkup.missingSeasons(series))
        assertEquals(listOf("Latino"), PoseidonMarkup.players(state).map { it.language })
    }
    @Test fun wrapperUsesExplicitUrlNotAdFrames() {
        assertEquals("https://voe.sx/e/abc", PoseidonMarkup.destination("""<iframe src="https://ad.test"></iframe><script>var url = 'https://voe.sx/e/abc';</script>"""))
        assertNull(PoseidonMarkup.destination("<iframe src='https://ad.test'></iframe>"))
        assertFalse(PoseidonMarkup.wrapperUrl("https://player.poseidonhd2.co.evil.test/player.php?h=1"))
        assertFalse(PoseidonMarkup.siteUrl("https://www.poseidonhd2.co.evil.test/"))
    }
}
