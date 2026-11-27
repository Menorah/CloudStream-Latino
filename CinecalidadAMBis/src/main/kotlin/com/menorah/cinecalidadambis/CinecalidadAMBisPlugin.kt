package com.menorah.cinecalidadambis

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinecalidadAMBisPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinecalidadAMBisProvider())
        registerExtractorAPI(CinecalidadAMVimeosExtractor())
        registerExtractorAPI(CinecalidadAMVideoappExtractor())
        registerExtractorAPI(CinecalidadAMVoeExtractor())
    }
}
