package com.menorah.repelishd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.FilemoonV2

class DropStreamExtractor : FilemoonV2() {
    override var name = "Dropload"
    override var mainUrl = "https://dr0pstream.com"
}
class SuperPlayerExtractor : FilemoonV2() {
    override var name = "Supervideo"
    override var mainUrl = "https://superplayer.ws"
}
@CloudstreamPlugin
class RePelisHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RePelisHDProvider())
        registerExtractorAPI(DropStreamExtractor())
        registerExtractorAPI(SuperPlayerExtractor())
    }
}
