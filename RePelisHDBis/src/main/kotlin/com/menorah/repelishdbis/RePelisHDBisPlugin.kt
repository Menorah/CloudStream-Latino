package com.menorah.repelishdbis

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RePelisHDBisPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RePelisHDBisProvider())
        registerExtractorAPI(RePelisHDDr0pstreamExtractor())
        registerExtractorAPI(RePelisHDDroploadIoExtractor())
        registerExtractorAPI(RePelisHDSuperplayerExtractor())
        registerExtractorAPI(RePelisHDSupervideoExtractor())
        registerExtractorAPI(RePelisHDUnlimPlayExtractor())
        registerExtractorAPI(RePelisHDVoeExtractor())
    }
}
