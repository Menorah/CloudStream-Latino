version = 6

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Películas y series en HD con audio latino, castellano y subtitulado de repelishd.hair."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "es-419"
    iconUrl = "https://raw.githubusercontent.com/Menorah/CloudStream-Latino/main/assets/icons/repelishd.png"
}
