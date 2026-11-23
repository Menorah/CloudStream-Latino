package com.menorah.animeonline

import org.jsoup.nodes.Document
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal data class CatalogPage(val cards: List<CatalogCard>, val hasNext: Boolean)

/** Expansions load up to two site pages; a failed prefetch is retried, not skipped. */
internal class CatalogPager(private val fetch: suspend (String) -> Document) {
    private val starts = ConcurrentHashMap<Pair<String, Int>, Int>()
    @Volatile private var homeUrls: Set<String> = emptySet()

    private fun path(section: String, page: Int): String =
        section.trimEnd('/') + if (page > 1) "/page/$page/" else "/"

    suspend fun get(section: String, page: Int, initialPageCount: Int = 2): CatalogPage {
        require(page >= 1)
        require(initialPageCount in 1..2)
        val isHome = section == "/inicio/"
        if (isHome && page == 1) {
            val items = SiteMarkup.cards(fetch("/inicio/"))
            homeUrls = items.map { it.url }.toSet()
            starts[section to 2] = 1
            return CatalogPage(items, true)
        }
        val actualSection = if (isHome) "/online/" else section
        val defaultStart = when {
            isHome -> (page - 2) * 2 + 1
            page == 1 -> 1
            else -> initialPageCount + (page - 2) * 2 + 1
        }
        val start = starts[section to page] ?: defaultStart
        val first = fetch(path(actualSection, start))
        val items = SiteMarkup.cards(first).toMutableList()
        var hasNext = SiteMarkup.hasNextPage(first, start, actualSection)
        var following = start + 1
        if (hasNext && !(page == 1 && initialPageCount == 1)) {
            val second = try { fetch(path(actualSection, following)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            if (second != null) {
                items += SiteMarkup.cards(second)
                hasNext = SiteMarkup.hasNextPage(second, following, actualSection)
                following++
            }
        }
        starts[section to (page + 1)] = following
        val unique = items.distinctBy { it.url }.let { list ->
            if (isHome) list.filterNot { it.url in homeUrls } else list
        }
        return CatalogPage(unique, hasNext)
    }
}
