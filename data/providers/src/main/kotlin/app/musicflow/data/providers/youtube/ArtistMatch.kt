package app.musicflow.data.providers.youtube

import app.musicflow.core.common.TextMatching

/** Decides whether a search result belongs to a given artist (for artist pages). */
object ArtistMatch {
    fun creditedTo(artistLine: String, title: String, artistName: String, artistId: String, itemArtistIds: List<String>): Boolean {
        if (artistId in itemArtistIds) return true
        val want = TextMatching.normalize(artistName)
        if (want.isEmpty()) return false
        // "A, B & C" credits: any credited artist matching is enough.
        val credited = artistLine.split(',', '&', '/').map { TextMatching.normalize(it) }.filter { it.isNotEmpty() }
        if (credited.any { it == want || TextMatching.similarity(it, want) > 0.85 }) return true
        // Uploads by other channels titled "Artist - Song".
        val t = TextMatching.normalize(title)
        return t.startsWith("$want ")
    }
}
