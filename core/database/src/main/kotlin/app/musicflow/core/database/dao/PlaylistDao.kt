package app.musicflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.musicflow.core.database.entity.PlaylistEntity
import app.musicflow.core.database.entity.PlaylistEntryEntity
import app.musicflow.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUrl: String?,
    val trackCount: Int,
    val artworkUrl: String?,
)

data class PlaylistEntryWithTrack(
    val entryId: Long,
    val position: Int,
    val id: String,
    val title: String,
    val artists: List<app.musicflow.core.model.ArtistRef>,
    val albumId: String?,
    val albumTitle: String?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val releaseYear: Int?,
    val genre: String?,
    val explicit: Boolean,
    val primaryArtistKey: String,
    val primaryArtistName: String,
    val updatedAt: Long,
) {
    fun track() = TrackEntity(id, title, artists, albumId, albumTitle, durationMs, artworkUrl, releaseYear, genre, explicit, primaryArtistKey, primaryArtistName, updatedAt).toModel()
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.*, (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount,
           (SELECT t.artworkUrl FROM playlist_entries e JOIN tracks t ON t.id = e.trackId
             WHERE e.playlistId = p.id ORDER BY e.position LIMIT 1) AS artworkUrl
           FROM playlists p ORDER BY p.updatedAt DESC""",
    )
    fun playlists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :id") fun playlist(id: Long): Flow<PlaylistEntity?>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun getPlaylist(id: Long): PlaylistEntity?
    @Query("SELECT * FROM playlists") suspend fun allPlaylists(): List<PlaylistEntity>

    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Update suspend fun update(p: PlaylistEntity)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)

    @Query(
        """SELECT e.entryId, e.position, t.* FROM playlist_entries e JOIN tracks t ON t.id = e.trackId
           WHERE e.playlistId = :playlistId ORDER BY e.position""",
    )
    fun entries(playlistId: Long): Flow<List<PlaylistEntryWithTrack>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun rawEntries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun countOf(playlistId: Long, trackId: String): Int

    @Insert suspend fun insertEntries(entries: List<PlaylistEntryEntity>)
    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId") suspend fun deleteEntry(entryId: Long)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId") suspend fun clear(playlistId: Long)
    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id") suspend fun touch(id: Long, now: Long)

    /** Rewrites positions 0..n-1 in the given order (used after removal and drag-and-drop). */
    @Transaction
    suspend fun rewriteOrder(playlistId: Long, orderedEntryIds: List<Long>, now: Long) {
        val existing = rawEntries(playlistId).associateBy { it.entryId }
        clear(playlistId)
        insertEntries(orderedEntryIds.mapIndexedNotNull { i, id -> existing[id]?.copy(position = i) })
        touch(playlistId, now)
    }
}
