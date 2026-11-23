package com.menorah.cinecalidadam
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.FilemoonV2

class VimeosExtractor : FilemoonV2() {
    override var mainUrl = "https://vimeos.net"
    override var name = "Vimeos"
}
@CloudstreamPlugin
class CinecalidadAMPlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(CinecalidadAMProvider()); registerExtractorAPI(VimeosExtractor()) }
}
