package com.menorah.cinecalidadam
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
@RunWith(AndroidJUnit4::class)
class CinecalidadAMLiveTest {
    private fun doc(path:String): org.jsoup.nodes.Document {
        val c=URL("https://www.cinecalidad.am$path").openConnection() as HttpURLConnection
        c.connectTimeout=15000; c.readTimeout=15000; c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        try { assertEquals("HTTP $path",200,c.responseCode); return Jsoup.parse(c.inputStream.bufferedReader().use { it.readText() },c.url.toString()) } finally {c.disconnect()}
    }
    @Test fun requestedSectionsAndYears() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        val featured = CinecalidadAMMarkup.featured(doc("/"))
        assertTrue("Destacados vacío", featured.isNotEmpty()); assertTrue(featured.all { it.poster != null })
        val series1 = doc("/ver-serie/"); val series2 = doc("/ver-serie/page/2/")
        val a = CinecalidadAMMarkup.cards(series1); val b = CinecalidadAMMarkup.cards(series2)
        assertTrue(a.isNotEmpty() && a.all { !it.movie }); assertTrue(CinecalidadAMMarkup.hasNext(series1,1)); assertTrue(b.any { x -> a.none { it.url == x.url } })
        val genres = listOf("aventura", "belica", "ciencia-ficcion", "crimen", "documental", "familia", "fantasia", "historia", "musica", "misterio", "terror", "suspense", "romance")
        for (genre in genres) assertTrue("Género $genre vacío", CinecalidadAMMarkup.cards(doc("/genero-de-la-pelicula/$genre/")).isNotEmpty())
        val g1=doc("/genero-de-la-pelicula/aventura/"); val g2=doc("/genero-de-la-pelicula/aventura/page/2/")
        assertTrue(CinecalidadAMMarkup.hasNext(g1,1)); assertTrue(CinecalidadAMMarkup.cards(g2).any { x -> CinecalidadAMMarkup.cards(g1).none { it.url == x.url } })
        val year1=doc("/release/2025/"); val year2=doc("/release/2025/page/2/")
        assertTrue(CinecalidadAMMarkup.hasNext(year1,1)); assertTrue(CinecalidadAMMarkup.cards(year2).any { x -> CinecalidadAMMarkup.cards(year1).none { it.url == x.url } })
        assertTrue(CinecalidadAMMarkup.cards(doc("/release/1915/")).isNotEmpty())
    }
    @Test fun reportedSeriesHaveAllListedEpisodes() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        val samples = listOf(
            "jeffrey-epstein-asquerosamente-rico" to 1,
            "vivir-100-anos-los-secretos-de-las-zonas-azules" to 1,
            "pinky-y-cerebro" to 3,
            "la-ninera" to 6
        )
        val pathPattern = Regex("/ver-el-episodio/[^/]+-[0-9]+x[0-9]+/?$")
        for ((slug, seasons) in samples) {
            val page = doc("/ver-serie/$slug/")
            val listed = page.select("a[href*=/ver-el-episodio/]").map { it.absUrl("href") }.filter {
                CinecalidadAMMarkup.siteUrl(it) && pathPattern.matches(java.net.URI(it).path)
            }.toSet()
            val parsed = CinecalidadAMMarkup.episodes(page)
            assertTrue("$slug sin episodios en el sitio", listed.size >= 4)
            assertEquals("Episodios incompletos en $slug", listed, parsed.map { it.url }.toSet())
            assertEquals("Temporadas incompletas en $slug", (1..seasons).toList(), parsed.map { it.season }.distinct())
            assertTrue("Falta $slug 1x1", parsed.any { it.season == 1 && it.number == 1 && it.poster != null })
        }
    }
    @Test fun catalogMovieAndSeries() {
        if (InstrumentationRegistry.getArguments().getString("live") != "true") return
        val first=doc("/"); val second=doc("/page/2/"); val a=CinecalidadAMMarkup.cards(first); val b=CinecalidadAMMarkup.cards(second)
        assertTrue(a.isNotEmpty()); assertTrue(a.any { it.movie }); assertTrue(a.any { !it.movie }); assertTrue(CinecalidadAMMarkup.hasNext(first,1)); assertTrue(b.any { x -> a.none { it.url == x.url } })
        assertTrue(CinecalidadAMMarkup.cards(doc("/?s=iron")).isNotEmpty())
        val movie=doc("/ver-pelicula/madden/"); assertEquals("Madden", CinecalidadAMMarkup.title(movie)); assertNotNull(CinecalidadAMMarkup.poster(movie)); assertNotNull(CinecalidadAMMarkup.plot(movie)); assertTrue(CinecalidadAMMarkup.players(movie).any { it.url.startsWith("https://vimeos.net/") })
        val series=doc("/ver-serie/el-espectacular-hombre-arana/"); val episodes=CinecalidadAMMarkup.episodes(series)
        assertEquals(26,episodes.size); assertEquals(listOf(1,2),episodes.map { it.season }.distinct())
        val ep=doc("/ver-el-episodio/tmdb-3854-s1-e1/"); assertTrue(CinecalidadAMMarkup.players(ep).any { it.language == "Latino" })
    }
}
