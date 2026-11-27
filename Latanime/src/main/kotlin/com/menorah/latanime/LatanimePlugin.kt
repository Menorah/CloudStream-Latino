package com.menorah.latanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LatanimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LatanimeProvider())
        registerExtractorAPI(LatanimeByseExtractor())
        registerExtractorAPI(LatanimeByseN1mwqExtractor())
        registerExtractorAPI(LatanimeByseSxExtractor())
        registerExtractorAPI(LatanimeByseKozeExtractor())
        registerExtractorAPI(LatanimeByseBuhoExtractor())
        registerExtractorAPI(LatanimeFilemoonExtractor())
        registerExtractorAPI(LatanimeFilemoonToExtractor())
        registerExtractorAPI(LatanimeVoeExtractor())
        registerExtractorAPI(LatanimeCaseySeeExtractor())
        registerExtractorAPI(LatanimeMp4UploadExtractor())
        registerExtractorAPI(LatanimeMp4UploadPlainExtractor())
        registerExtractorAPI(LatanimeMixDropExtractor())
        registerExtractorAPI(LatanimeMxDropToExtractor())
        registerExtractorAPI(LatanimeMxDropClickExtractor())
        registerExtractorAPI(LatanimeMixDropAgExtractor())
        registerExtractorAPI(LatanimeMixDropCoExtractor())
        registerExtractorAPI(LatanimeHexloadExtractor())
        registerExtractorAPI(LatanimeSavefilesExtractor())
        registerExtractorAPI(LatanimeSavefilesTopExtractor())
        registerExtractorAPI(LatanimeUqloadCxExtractor())
        registerExtractorAPI(LatanimeUqloadVcExtractor())
        registerExtractorAPI(LatanimeUqloadComExtractor())
        registerExtractorAPI(LatanimeDo7goExtractor())
        registerExtractorAPI(LatanimePlaymogoExtractor())
        registerExtractorAPI(LatanimeDsvplayExtractor())
        registerExtractorAPI(LatanimeLulustreamExtractor())
        registerExtractorAPI(LatanimeLuluvidExtractor())
        registerExtractorAPI(LatanimeLuluvdoExtractor())
        registerExtractorAPI(LatanimeLuluStExtractor())
        registerExtractorAPI(LatanimeListeamedExtractor())
        registerExtractorAPI(LatanimePixelDrainExtractor())
        registerExtractorAPI(LatanimePixelDrainDevExtractor())
        registerExtractorAPI(LatanimeMediafireExtractor())
    }
}
