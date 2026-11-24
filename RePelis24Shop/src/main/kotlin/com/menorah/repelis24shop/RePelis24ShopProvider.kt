package com.menorah.repelis24shop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class RePelis24ShopProvider : MainAPI() {
    override var mainUrl = RePelis24ShopMarkup.BASE
    override var name = "RePelis24.shop"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/peliculas/" to "Películas",
        "/series/" to "Series",
        "/peliculas/estrenos" to "Estrenos",
        "/genero/accion" to "Acción",
        "/genero/animacion" to "Animación",
        "/genero/anime" to "Anime",
        "/genero/aventura" to "Aventura",
        "/genero/ciencia-ficcion" to "Ciencia ficción",
        "/genero/comedia" to "Comedia",
        "/genero/crimen" to "Crimen",
        "/genero/documental" to "Documental",
        "/genero/drama" to "Drama",
        "/genero/familia" to "Familia",
        "/genero/fantasia" to "Fantasía",
        "/genero/belica" to "Guerra",
        "/genero/marvel" to "Marvel",
        "/genero/dc" to "DC Comics",
        "/genero/disney" to "Disney+",
        "/genero/musical" to "Musical",
        "/genero/romance" to "Romance",
        "/genero/suspense" to "Suspenso",
        "/genero/terror" to "Terror"
    )

    // ------------------------------------------------------------------ http

    private suspend fun fetch(input: String, referer: String = "$mainUrl/"): Document {
        val url = URI("$mainUrl/").resolve(input).toString()
        require(RePelis24ShopMarkup.siteUrl(url)) { "Dirección fuera de RePelis24.shop" }
        val response = app.get(url, referer = referer, timeout = 20)
        if (!response.isSuccessful) throw IllegalStateException("RePelis24.shop no respondió correctamente (HTTP ${response.code}).")
        if (!RePelis24ShopMarkup.siteUrl(response.url)) throw IllegalStateException("RePelis24.shop redirigió fuera del sitio.")
        if (RePelis24ShopMarkup.isChallenge(response.text)) {
            throw ErrorLoadingException("RePelis24.shop pidió una verificación anti-bots. Inténtalo de nuevo en unos minutos.")
        }
        return Jsoup.parse(response.text, response.url)
    }

    /**
     * Every response carries the headers the CDN needs. Cloudflare serves these images only when
     * the request has a site `Referer`, and CloudStream's image loader does not send one unless a
     * response provides `posterHeaders`, so without this the posters and backdrops stay blank.
     */
    private fun responses(cards: List<Card>): List<SearchResponse> = cards.map { card ->
        if (card.movie) newMovieSearchResponse(card.title, card.url, TvType.Movie) {
            addPoster(card.poster, RePelis24ShopMarkup.posterHeaders)
        } else newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
            addPoster(card.poster, RePelis24ShopMarkup.posterHeaders)
        }
    }

    // -------------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = fetch(RePelis24ShopMarkup.pageUrl(request.data, page))
        val cards = RePelis24ShopMarkup.cards(doc)
        return newHomePageResponse(
            HomePageList(request.name, responses(cards)),
            hasNext = RePelis24ShopMarkup.hasNext(doc)
        )
    }

    // ----------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return responses(RePelis24ShopMarkup.cards(fetch(RePelis24ShopMarkup.searchUrl(query))))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val doc = fetch(RePelis24ShopMarkup.searchPageUrl(query, page))
        return newSearchResponseList(
            responses(RePelis24ShopMarkup.cards(doc)),
            hasNext = RePelis24ShopMarkup.hasNext(doc)
        )
    }

    // ------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val doc = fetch(url)
        val title = RePelis24ShopMarkup.title(doc) ?: return null
        val poster = RePelis24ShopMarkup.poster(doc)
        val plot = RePelis24ShopMarkup.plot(doc)
        val year = RePelis24ShopMarkup.year(doc)
        val tags = RePelis24ShopMarkup.genres(doc)
        val actors = RePelis24ShopMarkup.actors(doc).map { ActorData(Actor(it)) }
        val recommendations = try {
            responses(RePelis24ShopMarkup.recommendations(doc))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        if (RePelis24ShopMarkup.isMovie(doc)) {
            return newMovieLoadResponse(title, doc.location(), TvType.Movie, doc.location()) {
                addPoster(poster, RePelis24ShopMarkup.posterHeaders)
                backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = actors
                this.duration = RePelis24ShopMarkup.durationMinutes(doc)
                this.recommendations = recommendations
            }
        }

        val seasons = RePelis24ShopMarkup.seasons(doc)
        if (seasons.isEmpty()) return null
        val permits = Semaphore(4)
        val episodes = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        withTimeoutOrNull(30_000L) {
                            try {
                                RePelis24ShopMarkup.episodes(fetch(season.url), season.number)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }.orEmpty()
                    }
                }
            }.awaitAll().flatten()
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, doc.location(), TvType.TvSeries, episodes.map { item ->
            newEpisode(item.url) {
                name = item.title
                season = item.season
                episode = item.number
                posterUrl = item.poster
            }
        }) {
            addPoster(poster, RePelis24ShopMarkup.posterHeaders)
            backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    // ------------------------------------------------------------------ links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetch(data)
        val options = RePelis24ShopMarkup.players(doc).take(40)
        if (options.isEmpty()) return false
        val emitted = AtomicBoolean(false)
        val lock = Any()
        val seen = Collections.synchronizedSet(mutableSetOf<String>())

        withTimeoutOrNull(60_000L) {
            coroutineScope {
                val permits = Semaphore(4)
                options.map { option ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            withTimeoutOrNull(20_000L) {
                                for (link in resolve(option.url, doc.location(), option.language, subtitleCallback)) {
                                    // Two tabs can point at the same stream; only report it once.
                                    if (!seen.add("${link.url}|${link.name}")) continue
                                    synchronized(lock) {
                                        emitted.set(true)
                                        callback(link)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return emitted.get()
    }

    /** Resolves one player option, transparently unwrapping the site's `nupload.my` player. */
    private suspend fun resolve(
        playerUrl: String,
        referer: String,
        language: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val target = RePelis24ShopMarkup.nuploadTarget(playerUrl)
        if (target == null) {
            return extract(playerUrl, referer, language, subtitleCallback)
        }
        if (target != playerUrl) {
            // `/iframe/?url=<host>` simply embeds an external player.
            return extract(target, RePelis24ShopMarkup.NUPLOAD_REFERER, language, subtitleCallback)
        }
        // `/watch/<uid>` pages hold a JW Player setup with an obfuscated manifest URL.
        val page = try {
            app.get(target, referer = RePelis24ShopMarkup.NUPLOAD_REFERER, timeout = 20)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        if (!page.isSuccessful) return emptyList()
        val stream = RePelis24ShopMarkup.nuploadStream(page.text, target) ?: return emptyList()
        val manifest = stream.manifest()
        val type = if (manifest.substringBefore('?').endsWith(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        return listOf(
            newExtractorLink(
                source = "Nuupload",
                name = "[$language] Nuupload",
                url = manifest,
                type = type
            ) {
                this.referer = RePelis24ShopMarkup.NUPLOAD_REFERER
            }
        )
    }

    private suspend fun extract(
        url: String,
        referer: String,
        language: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(url, referer, subtitleCallback) { found.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return synchronized(found) { found.toList() }.map { label(it, language) }
    }

    private suspend fun label(link: ExtractorLink, language: String): ExtractorLink {
        val label = "[$language] ${link.name}"
        return newExtractorLink(link.source, label, link.url, link.type) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }
}
