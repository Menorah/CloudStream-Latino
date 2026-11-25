package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PeliCineHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PeliCineHDProvider())
        // El host que el sitio usa con más frecuencia tiene su propio extractor.
        registerExtractorAPI(MorenciusExtractor())
    }
}
