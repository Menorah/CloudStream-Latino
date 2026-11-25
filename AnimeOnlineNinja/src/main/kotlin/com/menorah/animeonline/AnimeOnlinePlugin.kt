package com.menorah.animeonline

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeOnlinePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeOnlineProvider(context))
        registerExtractorAPI(GUploadSiteExtractor())
        registerExtractorAPI(SavefilesExtractor())
        registerExtractorAPI(UqloadVcExtractor())
        registerExtractorAPI(PeeplayerExtractor())
        registerExtractorAPI(MxdropSxExtractor())
        registerExtractorAPI(MxdropClickExtractor())
        registerExtractorAPI(MixdropJmkExtractor())
        registerExtractorAPI(Filemoon0TopExtractor())
        registerExtractorAPI(FilemooonLinkExtractor())
        registerExtractorAPI(LuluvidExtractor())
    }
}
