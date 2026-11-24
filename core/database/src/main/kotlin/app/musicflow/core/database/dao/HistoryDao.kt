package app.musicflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.musicflow.core.database.entity.PlayEventEntity
import app.musicflow.core.database.entity.QueueItemEntity
import app.musicflow.core.database.entity.SearchHistoryEntity
import app.musicflow.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/** Aggregated listening statistics per track, used by the recommendation engine. */
data class TrackStats(
    val trackId: String,
    val plays: Int,
    val completions: Int,
    val skips: Int,
    val lastPlayedAt: Long,
    val totalListenedMs: Long,
)

data class ArtistStats(val artistKey: String, val artistName: String, val plays: Int, val skips: Int, val lastPlayedAt: Long)

data class HistoryRow(val eventId: Long, val startedAt: Long, @androidx.room.Embedded val track: TrackEntity)

@Dao
interface HistoryDao {
    @Insert suspend fun insert(e: PlayEventEntity): Long

    @Query(
        """SELECT t.* FROM tracks t JOIN (SELECT trackId, MAX(startedAt) AS lastAt FROM play_events GROUP BY trackId) h
           ON h.trackId = t.id ORDER BY h.lastAt DESC LIMIT :limit""",
    )
    fun recentlyPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query(
        """SELECT t.* FROM tracks t JOIN (SELECT trackId, COUNT(*) AS c FROM play_events WHERE skipped = 0 GROUP BY trackId) h
           ON h.trackId = t.id ORDER BY h.c DESC LIMIT :limit""",
    )
    fun mostPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query(
        """SELECT e.id AS eventId, e.startedAt AS startedAt, t.* FROM play_events e JOIN tracks t ON t.id = e.trackId
           ORDER BY e.startedAt DESC LIMIT :limit""",
    )
    fun history(limit: Int): Flow<List<HistoryRow>>

    /** Tracks started but not finished recently, for "Continue listening". */
    @Query(
        """SELECT t.* FROM tracks t JOIN (SELECT trackId, MAX(startedAt) AS lastAt, MAX(completed) AS done, MAX(skipped) AS sk
              FROM play_events WHERE startedAt > :since GROUP BY trackId) h
           ON h.trackId = t.id WHERE h.done = 0 AND h.sk = 0 ORDER BY h.lastAt DESC LIMIT :limit""",
    )
    fun unfinished(since: Long, limit: Int): Flow<List<TrackEntity>>

    @Query(
        """SELECT trackId, COUNT(*) AS plays, SUM(completed) AS completions, SUM(skipped) AS skips,
                  MAX(startedAt) AS lastPlayedAt, SUM(listenedMs) AS totalListenedMs
           FROM play_events WHERE startedAt > :since GROUP BY trackId""",
    )
    suspend fun trackStats(since: Long): List<TrackStats>

    @Query(
        """SELECT t.primaryArtistKey AS artistKey, MAX(t.primaryArtistName) AS artistName,
                  COUNT(*) AS plays, SUM(e.skipped) AS skips, MAX(e.startedAt) AS lastPlayedAt
           FROM play_events e JOIN tracks t ON t.id = e.trackId
           WHERE e.startedAt > :since AND t.primaryArtistKey != '' GROUP BY t.primaryArtistKey ORDER BY plays DESC""",
    )
    suspend fun artistStats(since: Long): List<ArtistStats>

    @Query("SELECT COUNT(*) FROM play_events") suspend fun eventCount(): Int
    @Query("DELETE FROM play_events") suspend fun clearHistory()

    // Search history
    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit") fun searches(limit: Int): Flow<List<SearchHistoryEntity>>
    @Query("SELECT * FROM search_history WHERE `query` LIKE :prefix || '%' ORDER BY useCount DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun searchesStartingWith(prefix: String, limit: Int): List<SearchHistoryEntity>
    @Query("SELECT * FROM search_history WHERE `query` = :q") suspend fun search(q: String): SearchHistoryEntity?
    @Upsert suspend fun upsertSearch(e: SearchHistoryEntity)
    @Query("DELETE FROM search_history WHERE `query` = :q") suspend fun deleteSearch(q: String)
    @Query("DELETE FROM search_history") suspend fun clearSearches()

    // Queue persistence
    @Query("SELECT * FROM queue_items ORDER BY position") suspend fun queue(): List<QueueItemEntity>
    @Query("DELETE FROM queue_items") suspend fun clearQueue()
    @Insert suspend fun insertQueue(items: List<QueueItemEntity>)
}
