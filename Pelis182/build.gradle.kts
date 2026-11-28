version = 1

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Películas, series y anime en español latino de pelis182.net."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AnimeMovie", "Anime")
    language = "es-419"
    iconUrl = "https://raw.githubusercontent.com/Menorah/CloudStream-Latino/main/assets/icons/pelis182.png"
}
