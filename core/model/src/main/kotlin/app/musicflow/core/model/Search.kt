package app.musicflow.core.model

enum class SearchFilter { ALL, SONGS, ALBUMS, ARTISTS, PLAYLISTS }

data class SearchPage(
    val query: String,
    val filter: SearchFilter,
    val items: List<MediaItemSummary>,
    val nextPageToken: String?,
    /** Spelling correction suggested by the provider ("did you mean"), if any. */
    val correctedQuery: String? = null,
)
