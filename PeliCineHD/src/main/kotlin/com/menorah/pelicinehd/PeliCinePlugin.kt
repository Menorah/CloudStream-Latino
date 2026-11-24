package com.menorah.pelicinehd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PeliCinePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(PeliCineProvider())
    }
}
