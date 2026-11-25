package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * The hoster the site uses for its own copies ("Morencius") is a VidHide clone: same player
 * page, same packed JW Player setup, same signed `.m3u8` manifest. CloudStream only recognises
 * the clones it ships with, and `morencius.com` is not one of them, so it is registered here with
 * the generic VidHide extractor. `loadExtractor` matches by URL prefix, so the site's pages do
 * not need to know about it.
 *
 * The same applies to every other VidHide-domain the site rotates to: one line per clone.
 */
class Morencius : VidHidePro() {
    override var name = "Morencius"
    override var mainUrl = "https://morencius.com"
}

@CloudstreamPlugin
class PeliCineHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PeliCineHDProvider(context))
        registerExtractorAPI(Morencius())
    }
}
