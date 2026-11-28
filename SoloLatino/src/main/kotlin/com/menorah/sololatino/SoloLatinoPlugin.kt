package com.menorah.sololatino

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SoloLatinoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SoloLatinoProvider(context))
        registerExtractorAPI(SoloLatinoPelisSeriesHoyExtractor())
        registerExtractorAPI(SoloLatinoVoeExtractor())
        registerExtractorAPI(SoloLatinoCaseySeeExtractor())
        registerExtractorAPI(SoloLatinoMorenciusExtractor())
        registerExtractorAPI(SoloLatinoVidHideExtractor())
        registerExtractorAPI(SoloLatinoHglinkExtractor())
        registerExtractorAPI(SoloLatinoStreamWishExtractor())
        registerExtractorAPI(SoloLatinoFilemoonExtractor())
        registerExtractorAPI(SoloLatinoByseExtractor())
        registerExtractorAPI(SoloLatinoSavefilesExtractor())
        registerExtractorAPI(SoloLatinoLulustreamExtractor())
        registerExtractorAPI(SoloLatinoDoodExtractor())
        registerExtractorAPI(SoloLatinoUqloadExtractor())
        registerExtractorAPI(SoloLatinoGDriveExtractor())
    }
}
