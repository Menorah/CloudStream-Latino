package com.menorah.repelis24

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class RepelisMarkupTest {
    private fun doc(html: String) = Jsoup.parse(html, "https://repelis24.cz/series/game-of-thrones/")
    @Test fun parsesCardsAndPaginationWithoutAds() {
        val doc = Jsoup.parse("""
          <article><a class="dpu-archive-card" href="/peliculas/iron-man/" title="Iron Man"><div class="dpu-archive-c-title">Iron Man</div><img class="dpu-archive-img" src="https://image.tmdb.org/t/p/w500/iron.jpg"><img src="/flag.png"></a></article>
          <article><a class="dpu-archive-card" href="/series/game-of-thrones/"><div class="dpu-archive-c-title">Juego de Tronos</div></a></article>
          <a class="dpu-archive-card" href="https://other.example/peliculas/fake/" title="fake"></a><a href="/series/page/2/">2</a>
        """.trimIndent(), "https://repelis24.cz/series/")
        val cards = RepelisMarkup.cards(doc)
        assertEquals(2, cards.size); assertTrue(cards[0].movie); assertFalse(cards[1].movie)
        assertEquals("https://image.tmdb.org/t/p/w500/iron.jpg", cards[0].image)
        assertTrue(RepelisMarkup.hasNext(doc, 1)); assertFalse(RepelisMarkup.hasNext(doc, 2))
    }
    @Test fun episodesAreSortedAndUseEpisodeIdsNotSlugs() {
        val doc = doc("""
          <div class="dpu-episode-card" data-episode="74122" data-season-number="2" data-episode-number="1"><h3 class="dpu-ep-title">Episodio T2</h3></div>
          <div class="dpu-episode-card" data-episode="74111" data-season-number="1" data-episode-number="1"><h3 class="dpu-ep-title">Invierno</h3><img src="https://image.tmdb.org/a.jpg"></div>
          <div class="dpu-episode-card" data-episode="javascript:evil" data-season-number="1" data-episode-number="2"></div>
          <div class="dpu-episode-card" data-episode="74111" data-season-number="1" data-episode-number="1"></div>
        """.trimIndent())
        val eps = RepelisMarkup.episodes(doc)
        assertEquals(2, eps.size); assertEquals(listOf(1, 2), eps.map { it.season })
        assertEquals("Invierno", eps.first().title); assertEquals("https://repelis24.cz/?p=74111", RepelisMarkup.episodeUrl(eps.first().id))
    }
    @Test fun playerLanguageComesFromItsOwnDropdown() {
        val doc = doc("""
          <div data-lang="en"><ul><li class="dooplay_player_option" data-post="1" data-type="movie" data-nume="1">Voe</li></ul></div>
          <div data-lang="mx"><ul><li class="dooplay_player_option" data-post="1" data-type="movie" data-nume="2">Voe</li></ul></div>
          <div data-lang="es"><ul><li class="dooplay_player_option" data-post="1" data-type="movie" data-nume="trailer">Trailer</li></ul></div>
        """.trimIndent())
        val options = RepelisMarkup.options(doc)
        assertEquals(listOf("Inglés", "Latino"), options.map { it.language })
        assertEquals(listOf("1", "2"), options.map { it.number })
    }
    @Test fun episodeAjaxPlayerUsesTheRealLanguagePerSlot() {
        val html = """<div data-lang="mx"><li class="dooplay_player_option" data-post="74111" data-type="tv" data-nume="1">Streamwish</li></div><div data-lang="en"><li class="dooplay_player_option" data-post="74111" data-type="tv" data-nume="4">Netu</li></div><div data-lang="es"><li class="dooplay_player_option" data-post="999" data-type="tv" data-nume="5">Other</li></div>"""
        val json = org.json.JSONObject().put("success", true).put("data", html).toString()
        val opts = RepelisMarkup.episodePlayerOptions(json, "74111")
        assertEquals(listOf("Latino", "Inglés"), opts.map { it.language })
        assertEquals(listOf("1", "4"), opts.map { it.number })
        assertTrue(RepelisMarkup.episodePlayerOptions(json, "74112").isEmpty())
        assertTrue(RepelisMarkup.episodePlayerOptions("{}", "74111").isEmpty())
        assertTrue(RepelisMarkup.episodePlayerOptions("bad", "74111").isEmpty())
    }
    @Test fun categoriesPreserveFiltersAndDetectCorrectPagination() {
        assertEquals("/tendencias/?get=tv", RepelisMarkup.pageUrl("/tendencias/?get=tv", 1))
        assertEquals("/tendencias/page/2/?get=tv", RepelisMarkup.pageUrl("/tendencias/?get=tv", 2))
        assertEquals("/genero/accion/page/2/", RepelisMarkup.pageUrl("/genero/accion/", 2))
        val wrong = Jsoup.parse("""<a href="/tendencias/page/2/?get=movies">Películas</a><a href="/genero/drama/page/2/">Drama</a>""", "https://repelis24.cz/tendencias/?get=tv")
        assertFalse(RepelisMarkup.hasNext(wrong, 1))
        wrong.body().append("""<a href="/tendencias/page/2/?get=tv">Series</a>""")
        assertTrue(RepelisMarkup.hasNext(wrong, 1))
        assertFalse(RepelisMarkup.hasNext(wrong, 2))
    }
    @Test fun detailImageUsesHigherResolutionAndMovieBackdrop() {
        val detail = doc("""<img itemprop="image" src="https://image.tmdb.org/t/p/w185/portrait.jpg"><div class="playbox"><img class="cover" src="https://image.tmdb.org/t/p/original/background.jpg"></div>""")
        assertEquals("https://image.tmdb.org/t/p/w500/portrait.jpg", RepelisMarkup.poster(detail))
        assertEquals("https://image.tmdb.org/t/p/original/background.jpg", RepelisMarkup.backdrop(detail))
    }
    @Test fun episodeFallbackDoesNotGuessLanguage() {
        val opts = RepelisMarkup.fallbackEpisodeOptions("https://repelis24.cz/?p=74111")
        assertEquals(12, opts.size); assertTrue(opts.all { it.type == "tv" && it.language == "Idioma no indicado" })
        assertTrue(RepelisMarkup.fallbackEpisodeOptions("https://other.example/?p=74111").isEmpty())
        assertTrue(RepelisMarkup.fallbackEpisodeOptions("https://repelis24.cz/?p=1&p=2").isEmpty())
    }
    @Test fun rejectForeignAndMalformedSiteUrls() {
        assertTrue(RepelisMarkup.siteUrl("https://repelis24.cz/?p=74111"))
        assertFalse(RepelisMarkup.siteUrl("https://repelis24.cz.evil.test/"))
        assertFalse(RepelisMarkup.siteUrl("http://repelis24.cz/"))
        assertFalse(RepelisMarkup.siteUrl("https://evil@repelis24.cz/"))
    }
}
