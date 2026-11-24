package app.musicflow.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ArtistRef(val id: String?, val name: String)

@Serializable
data class AlbumRef(val id: String?, val title: String)

/** A single playable track. Only fields reported by the source are populated. */
@Serializable
data class Track(
    val id: MediaId,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val releaseYear: Int? = null,
    val genre: String? = null,
    val explicit: Boolean = false,
) {
    val artistLine: String get() = artists.joinToString(", ") { it.name }
    val primaryArtist: String? get() = artists.firstOrNull()?.name
}

@Serializable
data class Album(
    val id: MediaId,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: Int? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
) {
    val artistLine: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
data class Artist(
    val id: MediaId,
    val name: String,
    val artworkUrl: String? = null,
    val subscriberCount: Long? = null,
)

@Serializable
data class PlaylistSummary(
    val id: MediaId,
    val title: String,
    val owner: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
)

/** Detailed collection (album or remote playlist) with an optional continuation token. */
data class CollectionPage(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val tracks: List<Track>,
    val nextPageToken: String? = null,
    val year: Int? = null,
)

data class ArtistPage(
    val artist: Artist,
    val topTracks: List<Track>,
    val albums: List<Album>,
    val singles: List<Album>,
    val description: String? = null,
)

/** Anything that can appear in a mixed list such as search results or home shelves. */
sealed interface MediaItemSummary {
    val id: MediaId
    val title: String

    data class TrackItem(val track: Track) : MediaItemSummary {
        override val id get() = track.id
        override val title get() = track.title
    }

    data class AlbumItem(val album: Album) : MediaItemSummary {
        override val id get() = album.id
        override val title get() = album.title
    }

    data class ArtistItem(val artist: Artist) : MediaItemSummary {
        override val id get() = artist.id
        override val title get() = artist.name
    }

    data class PlaylistItem(val playlist: PlaylistSummary) : MediaItemSummary {
        override val id get() = playlist.id
        override val title get() = playlist.title
    }
}
