package com.menorah.cinecalidadam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinecalidadAMPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinecalidadAMProvider())
        registerExtractorAPI(CinecalidadAMVimeosExtractor())
        registerExtractorAPI(CinecalidadAMVideoappExtractor())
        registerExtractorAPI(CinecalidadAMVoeExtractor())
    }
}
