version = 2

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Películas, series, animes y doramas en español latino de SoloLatino (sololatino.net)."
    authors = listOf("Menorah")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
    )

    language = "es-419"

    iconUrl = "https://raw.githubusercontent.com/Menorah/CloudStream-Latino/main/assets/icons/sololatino.png"
}
