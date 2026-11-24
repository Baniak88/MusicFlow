package app.musicflow.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.musicflow.core.model.AlbumRef
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track

/** Cached metadata for any track the user interacted with, so the library works offline. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val albumId: String?,
    val albumTitle: String?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val releaseYear: Int?,
    val genre: String?,
    val explicit: Boolean,
    /** Denormalised, normalised artist name; used for per-artist statistics. */
    val primaryArtistKey: String,
    val primaryArtistName: String,
    val updatedAt: Long,
) {
    fun toModel() = Track(
        id = MediaId(id), title = title, artists = artists,
        album = albumTitle?.let { AlbumRef(albumId, it) }, durationMs = durationMs,
        artworkUrl = artworkUrl, releaseYear = releaseYear, genre = genre, explicit = explicit,
    )

    companion object {
        fun from(t: Track, now: Long) = TrackEntity(
            id = t.id.value, title = t.title, artists = t.artists, albumId = t.album?.id, albumTitle = t.album?.title,
            durationMs = t.durationMs, artworkUrl = t.artworkUrl, releaseYear = t.releaseYear, genre = t.genre,
            explicit = t.explicit, primaryArtistKey = artistKey(t.primaryArtist),
            primaryArtistName = t.primaryArtist.orEmpty(), updatedAt = now,
        )

        fun artistKey(name: String?) = name?.lowercase()?.trim().orEmpty()
    }
}

@Entity(
    tableName = "favorite_tracks",
    foreignKeys = [ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class FavoriteTrackEntity(@PrimaryKey val trackId: String, val addedAt: Long)

@Entity(tableName = "saved_albums")
data class SavedAlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val year: Int?,
    val artworkUrl: String?,
    val trackCount: Int?,
    val savedAt: Long,
)

@Entity(tableName = "favorite_artists")
data class FavoriteArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artworkUrl: String?,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Public URL the playlist was imported from, if any. */
    val sourceUrl: String? = null,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playlistId", "position"]), Index("trackId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val trackId: String,
    val position: Int,
    val addedAt: Long,
)

/** One listening session of a track. Basis of history, "most played" and recommendations. */
@Entity(tableName = "play_events", indices = [Index("trackId"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(@PrimaryKey val query: String, val lastUsedAt: Long, val useCount: Int = 1)

/** Persisted playback queue, restored after process death. */
@Entity(tableName = "queue_items")
data class QueueItemEntity(@PrimaryKey val position: Int, val trackId: String)
