package com.menorah.cinecalidadmy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.FilemoonV2

class FilemoonCinecalidad : FilemoonV2() {
    override var mainUrl = "https://bysedikamoum.com"
    override var name = "Filemoon"
}

@CloudstreamPlugin
class CinecalidadPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinecalidadProvider())
        registerExtractorAPI(FilemoonCinecalidad())
    }
}
