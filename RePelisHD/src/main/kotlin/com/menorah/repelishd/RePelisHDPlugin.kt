package com.menorah.repelishd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RePelisHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RePelisHDProvider())
        registerExtractorAPI(RePelisHDDr0pstreamExtractor())
        registerExtractorAPI(RePelisHDDroploadIoExtractor())
        registerExtractorAPI(RePelisHDSuperplayerExtractor())
        registerExtractorAPI(RePelisHDSupervideoExtractor())
        registerExtractorAPI(RePelisHDUnlimPlayExtractor())
        registerExtractorAPI(RePelisHDVoeExtractor())
    }
}
