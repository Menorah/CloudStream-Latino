version = 5

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

cloudstream {
    description = "VerAni.me — Catálogo completo de animes, estrenos semanales, audio latino, castellano, subtitulado y películas de verani.me con extractores nativos."
    authors = listOf("Menorah")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "es-419"
    iconUrl = "https://verani.me/wp-content/uploads/2026/01/3cbecfeb-f59b-459d-ab8e-7dff9eecbce9_removalai_preview-300x300.png"
}
