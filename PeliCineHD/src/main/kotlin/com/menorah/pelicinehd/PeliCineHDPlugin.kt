package com.menorah.pelicinehd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PeliCineHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PeliCineHDProvider(context))
    }
}
