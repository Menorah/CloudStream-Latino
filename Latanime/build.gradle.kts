version = 1

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "Latanime — Catálogo completo de anime en español latino, castellano y subtitulado, películas, OVAs y calendario semanal de emisión."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "es-419"
    iconUrl = "https://latanime.org/img/logito.png"
}
