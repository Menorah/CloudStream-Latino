package com.menorah.animeonline2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeOnline2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeOnline2Provider(context))
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
