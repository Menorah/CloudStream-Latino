package com.menorah.cinecalidadorg

import android.content.Context
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class VimeosOrgExtractor : FilemoonV2() {
    override var mainUrl = "https://vimeos.net"
    override var name = "Vimeos"
}

@CloudstreamPlugin
class CinecalidadOrgPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinecalidadOrgProvider())
        registerExtractorAPI(VimeosOrgExtractor())
    }
}
