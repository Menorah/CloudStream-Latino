package com.menorah.cinecalidadmy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinecalidadMYPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinecalidadMYProvider())
        registerExtractorAPI(CinecalidadMYBysedikamoumExtractor())
        registerExtractorAPI(CinecalidadMYVoeExtractor())
    }
}
