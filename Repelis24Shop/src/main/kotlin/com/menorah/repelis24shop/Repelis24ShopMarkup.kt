package com.menorah.repelis24shop

import android.util.Base64
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

internal data class Card(val title: String, val url: String, val poster: String?, val movie: Boolean)
internal data class Season(val number: Int, val url: String)
internal data class Episode(val title: String, val season: Int, val number: Int, val url: String, val poster: String?)
internal data class Player(val url: String, val language: String)

internal object Repelis24ShopMarkup {
    private fun host(url: String, expected: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme.equals("https", true) && u.host.equals(expected, true) && u.userInfo == null && u.port in listOf(-1, 443)
    }.getOrDefault(false)
    fun site(url: String) = host(url, "repelis24.shop")
    fun nupload(url: String) = host(url, "nupload.my")
    private fun image(url: String): String? = url.takeIf { site(it) }
    fun cards(doc: Document): List<Card> = doc.select(".movie-item").mapNotNull { e ->
        val a = e.selectFirst("a[href]") ?: return@mapNotNull null
        val url = a.absUrl("href"); if (!site(url)) return@mapNotNull null
        val path = URI(url).path.orEmpty(); val movie = path.startsWith("/pelicula/")
        if (!movie && !path.startsWith("/serie/")) return@mapNotNull null
        val title = a.selectFirst(".item-detail p")?.text()?.trim().orEmpty()
        if (title.isBlank()) return@mapNotNull null
        Card(title, url, image(a.selectFirst("img")?.absUrl("src").orEmpty()), movie)
    }.distinctBy { it.url }
    fun hasNext(doc: Document, page: Int) = doc.select(".pagination a[href]").any { e ->
        val url = e.absUrl("href")
        site(url) && URI(url).path.orEmpty().trimEnd('/').endsWith("/page/${page + 1}")
    }
    fun seasons(doc: Document): List<Season> = doc.select(".item-season a[href]").mapNotNull { a ->
        val url = a.absUrl("href"); if (!site(url) || !URI(url).path.orEmpty().startsWith("/temporada/")) return@mapNotNull null
        val number = Regex("(?i)temporada\\s+(\\d+)").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        Season(number, url)
    }.distinctBy { it.number }
    fun episodes(doc: Document, season: Int): List<Episode> = doc.select(".item-season-episodes a[href]").mapNotNull { a ->
        val url = a.absUrl("href"); if (!site(url)) return@mapNotNull null
        val match = Regex("-(\\d+)x(\\d+)/?$").find(URI(url).path.orEmpty()) ?: return@mapNotNull null
        val s = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val number = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        if (s != season || number < 1) return@mapNotNull null
        val title = a.selectFirst("[itemprop=name]")?.text()?.trim().orEmpty().ifBlank { "Episodio $number" }
        Episode(title, s, number, url, image(a.selectFirst("img")?.absUrl("src").orEmpty()))
    }.distinctBy { it.season to it.number }
    fun players(doc: Document): List<Player> = doc.select("#player li[data-server]").mapNotNull { e ->
        val raw = runCatching { String(Base64.decode(e.attr("data-server"), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
        if (!nupload(raw)) return@mapNotNull null
        val text = e.text().lowercase()
        val language = when {
            "latino" in text -> "Latino"
            "castellano" in text -> "Castellano"
            "subtitulado" in text -> "Subtitulado"
            else -> "Idioma no indicado"
        }
        Player(raw, language)
    }.distinctBy { it.url to it.language }
    /** Decode only the documented numeric array and session; never execute remote JavaScript. */
    fun media(html: String): String? = runCatching {
        val pattern = Regex("""(\w+)\.forEach\s*\(function\s+\w*\s*\([^)]*\)\s*\{\s*(\w+)\s*\+=\s*String\.fromCharCode\(parseInt\(atob\([^)]*\)\.replace\(/\\D/g,\s*['\"]{2}\)\)\s*-\s*(\d+)\)""")
        val m = pattern.find(html) ?: return null
        val array = m.groupValues[1]; val offset = m.groupValues[3].toInt()
        val list = Regex("""\bvar\s+${Regex.escape(array)}\s*=\s*\[(.*?)]\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        val parts = Regex("""[\"']([A-Za-z0-9+/=]+)[\"']""").findAll(list).map { it.groupValues[1] }.toList()
        if (parts.size !in 1..4096) return null
        val url = parts.joinToString("") { part ->
            val digits = String(Base64.decode(part, Base64.DEFAULT), Charsets.UTF_8).filter(Char::isDigit)
            val c = digits.toLong() - offset
            require(c in 32..126); c.toInt().toChar().toString()
        }
        val u = URI(url)
        if (!u.scheme.equals("https", true) || u.userInfo != null || u.port !in listOf(-1, 443) ||
            !(u.host.orEmpty().equals("ibra.lat", true) || u.host.orEmpty().lowercase().endsWith(".ibra.lat"))) return null
        val session = Regex("""\bsesz\s*=\s*['\"]([A-Za-z0-9]+)['\"]""").find(html)?.groupValues?.get(1) ?: return null
        url + (if (u.rawQuery == null) "?" else "&") + "s=" + URLEncoder.encode(session, "UTF-8")
    }.getOrNull()
}
