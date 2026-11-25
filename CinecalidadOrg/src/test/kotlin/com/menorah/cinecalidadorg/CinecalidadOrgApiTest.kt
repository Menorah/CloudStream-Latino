package com.menorah.cinecalidadorg

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CinecalidadOrgApiTest {

    @Test
    fun urlValidationAndRouting() {
        assertTrue(CinecalidadOrgApi.siteUrl("https://cinecalidad.org/peliculas/madden-2026/"))
        assertTrue(CinecalidadOrgApi.siteUrl("https://www.cinecalidad.org/series/csi-miami/"))
        assertFalse(CinecalidadOrgApi.siteUrl("http://cinecalidad.org/peliculas/madden/"))
        assertFalse(CinecalidadOrgApi.siteUrl("https://cinecalidad.org.evil.test/peliculas/madden/"))
        assertFalse(CinecalidadOrgApi.siteUrl("https://user@cinecalidad.org/peliculas/madden/"))

        assertEquals("https://cinecalidad.org/peliculas/madden-2026/", CinecalidadOrgApi.postUrl("movies", "madden-2026"))
        assertEquals("https://cinecalidad.org/series/csi-miami/", CinecalidadOrgApi.postUrl("tvshows", "csi-miami"))
        assertEquals("https://cinecalidad.org/animes/dragon-ball/", CinecalidadOrgApi.postUrl("animes", "dragon-ball"))
        assertEquals("https://cinecalidad.org/novelas/cafe/", CinecalidadOrgApi.postUrl("novels", "cafe"))
        assertEquals("https://cinecalidad.org/wwe/raw/", CinecalidadOrgApi.postUrl("wwe", "raw"))
        assertNull(CinecalidadOrgApi.postUrl("movies", "bad/slug"))

        assertEquals("movies" to "madden-2026", CinecalidadOrgApi.parseContentUrl("https://cinecalidad.org/peliculas/madden-2026/"))
        assertEquals("animes" to "dragon-ball", CinecalidadOrgApi.parseContentUrl("https://cinecalidad.org/animes/dragon-ball"))
        assertEquals("episodes" to "csi-1x1", CinecalidadOrgApi.parseContentUrl("https://cinecalidad.org/episodio/csi-1x1/"))
        assertNull(CinecalidadOrgApi.parseContentUrl("https://evil.test/peliculas/madden-2026/"))

        assertEquals(74657L, CinecalidadOrgApi.parsePlayerPostId("https://cinecalidad.org/wp-api/v1/player?postId=74657&demo=0"))
        assertNull(CinecalidadOrgApi.parsePlayerPostId("https://evil.test/wp-api/v1/player?postId=74657&demo=0"))
        assertEquals("2025", CinecalidadOrgApi.yearQuery(" 2025 "))
        assertNull(CinecalidadOrgApi.yearQuery("2025x"))
    }

    @Test
    fun parseSiteConfigTaxonomiesExtractsNestedJsObjects() {
        val html = """
            <head><script>
            window.siteConfig = {
                imageUrl: 'https://cinecalidad.org/wp-content/uploads',
                datas: {
                    genres: {"26":{"name":"Acci\u00f3n","slug":"accion","tax":"genres"},"1203":{"name":"Action &amp; Adventure","slug":"action-adventure","tax":"genres"}},
                    langs: {"89581":{"name":"latino","slug":"latino","tax":"lang"},"89583":{"name":"castellano","slug":"castellano","tax":"lang"}},
                    years: {"19805":{"name":"2026","slug":"2026","tax":"years"},"19999":{"name":"2027","slug":"2027","tax":"years"}}
                }
            };
            </script></head>
        """.trimIndent()

        val catalog = CinecalidadOrgApi.parseSiteConfigTaxonomies(html)
        assertEquals(26, catalog.genresBySlug["accion"]?.id)
        assertEquals("Acción", catalog.genresById[26]?.name)
        assertEquals("Action & Adventure", catalog.genresById[1203]?.name)
        assertEquals(89583, catalog.langsBySlug["castellano"]?.id)
        assertEquals(19999, catalog.yearsBySlug["2027"]?.id)
    }

    @Test
    fun parseListingAndImages() {
        val json = """
            {
              "data": {
                "posts": [
                  {
                    "_id": 73394,
                    "title": "Madden (2026)",
                    "slug": "madden-2026",
                    "type": "movies",
                    "release_date": "2026-11-18",
                    "vote_average": "7.4",
                    "poster_path": null,
                    "images": {
                      "poster": "/thumbs/abc_hd.webp",
                      "backdrop": "/backdrops/abc.webp"
                    }
                  },
                  {
                    "_id": 1001,
                    "title": "Dragon Ball &amp; Friends",
                    "slug": "dragon-ball",
                    "type": "animes",
                    "release_date": "",
                    "poster_path": "/tmdbPoster.jpg",
                    "images": null
                  }
                ],
                "pagination": {
                  "current_page": 1,
                  "last_page": 5,
                  "next_page_url": "https://cinecalidad.org/wp-api/v1/listing/movies?page=2"
                }
              }
            }
        """.trimIndent()

        val listing = CinecalidadOrgApi.parseListing(json)
        assertTrue(listing.hasNext)
        assertEquals(2, listing.items.size)
        assertEquals("https://cinecalidad.org/wp-content/uploads/thumbs/abc_hd.webp", listing.items[0].poster)
        assertEquals("https://cinecalidad.org/wp-content/uploads/backdrops/abc.webp", listing.items[0].backdrop)
        assertEquals(2026, listing.items[0].year)
        assertEquals(7.4, listing.items[0].rating!!, 0.01)

        assertEquals("Dragon Ball & Friends", listing.items[1].title)
        assertEquals("https://image.tmdb.org/t/p/w342/tmdbPoster.jpg", listing.items[1].poster)
        assertEquals("https://cinecalidad.org/animes/dragon-ball/", listing.items[1].url)
    }

    @Test
    fun parseListingHandlesArrayPagination() {
        val json = """
            {
              "data": {
                "posts": [
                  {"_id": 1, "title": "Uno", "slug": "uno", "type": "movies"}
                ],
                "pagination": []
              }
            }
        """.trimIndent()
        val listing = CinecalidadOrgApi.parseListing(json)
        assertEquals(1, listing.items.size)
        assertFalse(listing.hasNext)
    }

    @Test
    fun parseSingleAndCastAndEpisodes() {
        val singleJson = """
            {
              "data": {
                "_id": 73394,
                "title": "Madden (2026)",
                "slug": "madden-2026",
                "type": "movies",
                "overview": "Historia &amp; deporte",
                "images": {"poster": "/thumbs/p.webp", "backdrop": "/backdrops/b.webp"},
                "release_date": "2026-11-18",
                "runtime": "112.0",
                "vote_average": null,
                "trailer": "P3uI5sLosKU",
                "genres": [122, 123]
              }
            }
        """.trimIndent()
        val detail = CinecalidadOrgApi.parseSingle(singleJson)!!
        assertEquals(73394L, detail.id)
        assertEquals("Historia & deporte", detail.overview)
        assertEquals(112, detail.durationMinutes)
        assertNull(detail.rating)
        assertEquals("https://www.youtube.com/watch?v=P3uI5sLosKU", detail.trailerUrl)
        assertEquals(listOf(122, 123), detail.genreIds)

        val castJson = """
            {
              "data": [
                {"term_name": "Nicolas Cage", "meta": {"profile_path": "/cage.jpg", "character": "John Madden"}},
                {"term_name": "Sin Foto", "meta": {"profile_path": null, "character": ""}}
              ]
            }
        """.trimIndent()
        val cast = CinecalidadOrgApi.parseCast(castJson)
        assertEquals(2, cast.size)
        assertEquals("https://image.tmdb.org/t/p/w185/cage.jpg", cast[0].imageUrl)
        assertEquals("John Madden", cast[0].role)
        assertNull(cast[1].imageUrl)
        assertNull(cast[1].role)

        val epJson = """
            {
              "data": {
                "seasons": ["10", "2", "1"],
                "posts": [
                  {
                    "_id": 5001,
                    "title": "Piloto",
                    "slug": "csi-1x1",
                    "season_number": 1,
                    "episode_number": 1,
                    "still_path": "/still1.jpg",
                    "overview": "Inicio",
                    "runtime": "45"
                  },
                  {
                    "_id": 5002,
                    "title": "",
                    "slug": "csi-1x2",
                    "season_number": 1,
                    "episode_number": 2,
                    "still_path": null
                  }
                ],
                "pagination": {"current_page": 1, "last_page": 1, "next_page_url": null}
              }
            }
        """.trimIndent()
        val epPage = CinecalidadOrgApi.parseEpisodesPage(epJson)
        assertEquals(listOf(1, 2, 10), epPage.seasons)
        assertFalse(epPage.hasNext)
        assertEquals(2, epPage.episodes.size)
        assertEquals("Piloto", epPage.episodes[0].title)
        assertEquals(45, epPage.episodes[0].durationMinutes)
        assertEquals("https://image.tmdb.org/t/p/w342/still1.jpg", epPage.episodes[0].poster)
        assertEquals("https://cinecalidad.org/wp-api/v1/player?postId=5001&demo=0", epPage.episodes[0].dataUrl)
        assertEquals("Episodio 2", epPage.episodes[1].title)
    }

    @Test
    fun parsePlayerEmbedsAndWrapperUnwrap() {
        val playerJson = """
            {
              "data": {
                "embeds": [
                  {"url": "https://vimeos.net/embed-abc.html", "server": "vimeos", "lang": "latino - inglés", "quality": "Dual 1080p"},
                  {"url": "https://videoapp.zip/e/movie/123", "server": "vimeos", "lang": "castellano", "quality": "Full HD"},
                  {"url": "https://voe.sx/e/xyz", "server": "voe", "lang": "subtitulado", "quality": ""},
                  {"url": "javascript:alert(1)", "server": "bad", "lang": "latino", "quality": ""}
                ]
              }
            }
        """.trimIndent()

        val embeds = CinecalidadOrgApi.parsePlayerEmbeds(playerJson)
        assertEquals(3, embeds.size)
        assertEquals("[Latino · Dual 1080p] Vimeos", CinecalidadOrgApi.formatSourceLabel(embeds[0], "Vimeos"))
        assertEquals("[Castellano · Full HD] Vimeos", CinecalidadOrgApi.formatSourceLabel(embeds[1], "Vimeos"))
        assertEquals("[Subtitulado] Voe", CinecalidadOrgApi.formatSourceLabel(embeds[2], "Voe"))

        val badWrapper = Jsoup.parse("""<iframe src="https://videoapp.zip/nested"></iframe>""", "https://videoapp.zip/e/movie/1")
        assertNull(CinecalidadOrgApi.unwrapWrapperIframe(badWrapper))

        val goodWrapper = Jsoup.parse("""<iframe src="https://vimeos.net/embed-real.html?cf=1"></iframe>""", "https://videoapp.zip/e/movie/1")
        assertEquals("https://vimeos.net/embed-real.html?cf=1", CinecalidadOrgApi.unwrapWrapperIframe(goodWrapper))

        val encodedFilterUrl = CinecalidadOrgApi.buildFilterListingUrl("genres", 26, 2)
        assertTrue(encodedFilterUrl.contains("filter=%7B%22genres%22%3A%5B26%5D%7D"))
    }
}
