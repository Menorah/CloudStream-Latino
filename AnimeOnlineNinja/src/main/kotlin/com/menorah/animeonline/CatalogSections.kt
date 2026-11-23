package com.menorah.animeonline

/** Pairs are site path to the label shown in CloudStream. */
internal object CatalogSections {
    val existing = listOf(
        "/inicio/" to "Inicio",
        "/online/" to "Animes",
        "/pelicula/" to "Películas",
        "/genero/audio-latino/" to "Audio latino",
        "/genero/anime-castellano/" to "Castellano",
    )
    val extra = listOf(
        "/genero/en-emision-1/" to "En emisión",
        "/genero/blu-ray-dvd-2/" to "Blu-Ray / DVD",
        "/tendencias/" to "Tendencias",
        "/ratings/" to "Mejores valorados",
    )
    val all = existing + extra

    // Load the added rows with one site page initially; subsequent expansions
    // still fetch two. This limits the extra work on CloudStream's homepage.
    fun initialPageCount(section: String) = if (extra.any { it.first == section }) 1 else 2
}
