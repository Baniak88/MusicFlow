package app.musicflow.data.repositories

import app.musicflow.core.common.Clock
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.database.entity.FavoriteArtistEntity
import app.musicflow.core.database.entity.FavoriteTrackEntity
import app.musicflow.core.database.entity.PlayEventEntity
import app.musicflow.core.database.entity.SavedAlbumEntity
import app.musicflow.core.database.entity.TrackEntity
import app.musicflow.core.model.Album
import app.musicflow.core.model.Artist
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class HistoryEntry(val eventId: Long, val playedAt: Long, val track: Track)

/** User-owned library data. Everything here is local and works offline. */
@Singleton
class LibraryRepository @Inject constructor(
    private val db: MusicFlowDatabase,
    private val settings: SettingsRepository,
) {
    var clock: Clock = Clock.System

    suspend fun remember(track: Track) = db.tracks().upsert(TrackEntity.from(track, clock.now()))
    suspend fun rememberAll(tracks: List<Track>) = db.tracks().upsertAll(tracks.map { TrackEntity.from(it, clock.now()) })
    suspend fun cachedTrack(id: MediaId): Track? = db.tracks().get(id.value)?.toModel()

    val favoriteTracks: Flow<List<Track>> = db.library().favoriteTracks().map { l -> l.map { it.toModel() } }
    val favoriteIds: Flow<Set<String>> = db.library().favoriteIds().map { it.toSet() }
    fun isFavorite(id: MediaId): Flow<Boolean> = db.library().isFavorite(id.value)

    suspend fun setFavorite(track: Track, favorite: Boolean) {
        if (favorite) {
            remember(track)
            db.library().addFavorite(FavoriteTrackEntity(track.id.value, clock.now()))
        } else {
            db.library().removeFavorite(track.id.value)
        }
    }

    suspend fun toggleFavorite(track: Track): Boolean {
        val now = !db.library().isFavorite(track.id.value).first()
        setFavorite(track, now)
        return now
    }

    val savedAlbums: Flow<List<Album>> = db.library().savedAlbums().map { l ->
        l.map { Album(MediaId(it.id), it.title, it.artists, it.year, it.artworkUrl, it.trackCount) }
    }
    fun isAlbumSaved(id: MediaId) = db.library().isAlbumSaved(id.value)
    suspend fun setAlbumSaved(album: Album, saved: Boolean) {
        if (saved) db.library().saveAlbum(SavedAlbumEntity(album.id.value, album.title, album.artists, album.year, album.artworkUrl, album.trackCount, clock.now()))
        else db.library().removeAlbum(album.id.value)
    }

    val favoriteArtists: Flow<List<Artist>> = db.library().favoriteArtists().map { l ->
        l.map { Artist(MediaId(it.id), it.name, it.artworkUrl) }
    }
    fun isArtistFavorite(id: MediaId) = db.library().isArtistFavorite(id.value)
    suspend fun setArtistFavorite(artist: Artist, fav: Boolean) {
        if (fav) db.library().addArtist(FavoriteArtistEntity(artist.id.value, artist.name, artist.artworkUrl, clock.now()))
        else db.library().removeArtist(artist.id.value)
    }

    fun recentlyPlayed(limit: Int = 30): Flow<List<Track>> = db.history().recentlyPlayed(limit).map { l -> l.map { it.toModel() } }
    fun mostPlayed(limit: Int = 30): Flow<List<Track>> = db.history().mostPlayed(limit).map { l -> l.map { it.toModel() } }
    fun continueListening(limit: Int = 12): Flow<List<Track>> =
        db.history().unfinished(clock.now() - 14L * 24 * 3600 * 1000, limit).map { l -> l.map { it.toModel() } }
    fun history(limit: Int = 300): Flow<List<HistoryEntry>> =
        db.history().history(limit).map { l -> l.map { HistoryEntry(it.eventId, it.startedAt, it.track.toModel()) } }

    /**
     * Records a finished listening session. A play counts as "skipped" when the user moved on
     * within 30 s (or < 25 % of a short track), and "completed" when ≥ 90 % was heard.
     */
    suspend fun recordPlay(track: Track, startedAt: Long, listenedMs: Long, userSkipped: Boolean) {
        if (!settings.current().historyEnabled) return
        if (listenedMs < 5_000 && !userSkipped) return
        remember(track)
        val duration = track.durationMs
        val completed = duration != null && listenedMs >= duration * 0.9
        val skipped = userSkipped && (listenedMs < 30_000 || (duration != null && listenedMs < duration * 0.25))
        db.history().insert(PlayEventEntity(0, track.id.value, startedAt, listenedMs, duration, completed, skipped))
    }

    suspend fun clearHistory() = db.history().clearHistory()

    suspend fun saveQueue(ids: List<Track>) = db.transaction {
        rememberAll(ids)
        db.history().clearQueue()
        db.history().insertQueue(ids.mapIndexed { i, t -> app.musicflow.core.database.entity.QueueItemEntity(i, t.id.value) })
    }

    suspend fun restoreQueue(): List<Track> {
        val items = db.history().queue()
        val byId = db.tracks().getAll(items.map { it.trackId }).associateBy { it.id }
        return items.mapNotNull { byId[it.trackId]?.toModel() }
    }
}
