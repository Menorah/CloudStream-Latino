version = 3

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

cloudstream {
    description = "Películas, series y episodios recientes de PoseidonHD 2 (poseidonhd2.co) en español latino, castellano y subtitulado."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "es-419"
    iconUrl = "https://www.poseidonhd2.co/apple-touch-icon.png"
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
