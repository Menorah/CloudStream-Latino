version = 11

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

cloudstream {
    description = "Animes, películas, temporadas y episodios de AnimeOnline (ww4.animeonline.ninja) en español latino, castellano y subtitulado."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "es-419"
    iconUrl = "https://raw.githubusercontent.com/Menorah/CloudStream-Latino/main/assets/icons/animeonlineninja.png"
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
