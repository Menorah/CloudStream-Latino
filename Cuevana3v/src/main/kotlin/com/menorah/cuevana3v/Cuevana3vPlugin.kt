package com.menorah.cuevana3v

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Cuevana3vPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cuevana3vProvider())
        registerExtractorAPI(HyperVidHideExtractor())
        registerExtractorAPI(NebulaVidHideExtractor())
    }
}
