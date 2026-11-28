package com.menorah.gnulahd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GnulaHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GnulaHDProvider())
        registerExtractorAPI(GnulaVidaraExtractor())
        registerExtractorAPI(GnulaVidaraaCcExtractor())
        registerExtractorAPI(GnulaVidaraSoExtractor())
        registerExtractorAPI(GnulaVidaraXCcExtractor())
        registerExtractorAPI(GnulaVidarawExtractor())
        registerExtractorAPI(GnulaVidaratemExtractor())
        registerExtractorAPI(GnulaVidaaraxComExtractor())
        registerExtractorAPI(GnulaVidaaraxNetExtractor())
        registerExtractorAPI(GnulaVidavacaExtractor())
        registerExtractorAPI(GnulaOdysseusaExtractor())
        registerExtractorAPI(GnulaHandfacesnapExtractor())
        registerExtractorAPI(GnulaNamefacesnapExtractor())
        registerExtractorAPI(GnulaThebesthostervExtractor())
        registerExtractorAPI(GnulaVidmatrixaExtractor())
        registerExtractorAPI(GnulaVidChampionsExtractor())
        registerExtractorAPI(GnulaAntarcticadocsExtractor())
        registerExtractorAPI(GnulaNameitwebExtractor())
        registerExtractorAPI(GnulaSavefilesExtractor())
        registerExtractorAPI(GnulaSavefilesTopExtractor())
        registerExtractorAPI(GnulaByseExtractor())
        registerExtractorAPI(GnulaByseSxExtractor())
        registerExtractorAPI(GnulaByseBuhoExtractor())
        registerExtractorAPI(GnulaByseZejataosExtractor())
        registerExtractorAPI(GnulaByseQekahoExtractor())
        registerExtractorAPI(GnulaByseKozeExtractor())
        registerExtractorAPI(GnulaByseN1mwqExtractor())
        registerExtractorAPI(GnulaFilemoonExtractor())
        registerExtractorAPI(GnulaFilemoonToExtractor())
        registerExtractorAPI(GnulaVoeExtractor())
        registerExtractorAPI(GnulaCaseySeeExtractor())
        registerExtractorAPI(GnulaVidsonicExtractor())
        registerExtractorAPI(GnulaVixeoExtractor())
    }
}
