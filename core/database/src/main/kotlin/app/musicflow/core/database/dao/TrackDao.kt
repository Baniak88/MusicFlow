package app.musicflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.musicflow.core.database.entity.TrackEntity

@Dao
interface TrackDao {
    @Upsert suspend fun upsert(track: TrackEntity)
    @Upsert suspend fun upsertAll(tracks: List<TrackEntity>)
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun get(id: String): TrackEntity?
    @Query("SELECT * FROM tracks WHERE id IN (:ids)") suspend fun getAll(ids: List<String>): List<TrackEntity>
}
