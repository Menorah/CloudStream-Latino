package com.menorah.verani

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VerAniPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VerAniProvider())
        registerExtractorAPI(VerAniAnimeAv1Extractor())
        registerExtractorAPI(VerAniTurboEmbedExtractor())
        registerExtractorAPI(VerAniVidaraExtractor())
        registerExtractorAPI(VerAniZillaExtractor())
        registerExtractorAPI(VerAniVoeExtractor())
        registerExtractorAPI(VerAniMp4UploadExtractor())
        registerExtractorAPI(VerAniMxDropExtractor())
        registerExtractorAPI(VerAniByseExtractor())
        registerExtractorAPI(VerAniByseKozeExtractor())
        registerExtractorAPI(VerAniByseBuhoExtractor())
        registerExtractorAPI(VerAniByseSxExtractor())
    }
}
