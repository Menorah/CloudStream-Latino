package com.menorah.repelis24shop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live checks against repelis24.shop. They only run when the instrumentation argument
 * `live=true` is passed (see scripts/run_repelis24shop_android.sh), because the site can be
 * unreachable from CI for reasons unrelated to the plugin.
 */
@RunWith(AndroidJUnit4::class)
class RePelis24ShopLiveTest {
    private fun live(): Boolean =
        InstrumentationRegistry.getArguments().getString("live") == "true"

    private fun document(path: String, referer: String = "https://repelis24.shop/"): Document {
        val connection = URL("https://repelis24.shop" + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
        )
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept-Language", "es-AR,es;q=0.9")
        try {
            assertEquals("HTTP ${connection.url}", 200, connection.responseCode)
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            return Jsoup.parse(html, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun catalogSearchAndMovieDetail() {
        if (!live()) return
        val first = document("/peliculas/")
        val second = document("/peliculas/page/2")
        assertTrue(RePelis24ShopMarkup.hasNext(first))
        val pageOne = RePelis24ShopMarkup.cards(first)
        val pageTwo = RePelis24ShopMarkup.cards(second)
        assertTrue(pageOne.isNotEmpty())
        assertTrue(pageTwo.any { card -> pageOne.none { it.url == card.url } })
        assertTrue(pageOne.all { it.poster != null })

        val series = RePelis24ShopMarkup.cards(document("/series/"))
        assertTrue(series.isNotEmpty())
        assertTrue(series.all { !it.movie })

        val search = RePelis24ShopMarkup.cards(document(RePelis24ShopMarkup.searchUrl("la casa")))
        assertTrue(search.isNotEmpty())
        val searchPageTwo = RePelis24ShopMarkup.cards(
            document(RePelis24ShopMarkup.searchPageUrl("la casa", 2))
        )
        assertTrue(searchPageTwo.none { searched -> search.take(2).any { it.url == searched.url } })

        val movie = document("/pelicula/la-odisea/")
        assertTrue(RePelis24ShopMarkup.isMovie(movie))
        assertNotNull(RePelis24ShopMarkup.title(movie))
        assertNotNull(RePelis24ShopMarkup.poster(movie))
        assertNotNull(RePelis24ShopMarkup.plot(movie))
        val players = RePelis24ShopMarkup.players(movie)
        assertTrue(players.isNotEmpty())
        assertTrue(players.all { RePelis24ShopMarkup.nuploadUrl(it.url) })
        assertTrue(players.any { it.language == "Latino" })
    }

    @Test
    fun seriesSeasonsEpisodesAndPlayerPayloads() {
        if (!live()) return
        val series = document("/serie/breaking-bad-zpya/")
        val seasons = RePelis24ShopMarkup.seasons(series)
        assertEquals(listOf(1, 2, 3, 4, 5), seasons.map { it.number })

        val last = seasons.last()
        val seasonDocument = document(pathOf(last.url))
        val episodes = RePelis24ShopMarkup.episodes(seasonDocument, last.number)
        assertTrue(episodes.isNotEmpty())
        assertEquals(episodes.size, episodes.map { it.number }.distinct().size)
        assertTrue(episodes.all { it.season == last.number })

        val episodeDocument = document(pathOf(episodes.first().url))
        val options = RePelis24ShopMarkup.players(episodeDocument)
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { RePelis24ShopMarkup.nuploadUrl(it.url) })
        assertTrue(options.all { it.language != "Idioma no indicado" })
        // The wrapper either embeds an external player or is decoded by the provider.
        for (option in options) {
            val target = RePelis24ShopMarkup.nuploadTarget(option.url)
            assertNotNull(target)
            assertFalse(target!!.contains("nupload.me"))
        }
    }

    private fun pathOf(url: String): String = java.net.URI(url).path.orEmpty()

    @Test
    fun mainPageSectionsReturnContent() {
        if (!live()) return
        for (path in listOf("/peliculas/", "/series/", "/peliculas/estrenos", "/genero/accion")) {
            val page = document(RePelis24ShopMarkup.pageUrl(path, 1))
            assertTrue("sin tarjetas en $path", RePelis24ShopMarkup.cards(page).isNotEmpty())
        }
    }
}
