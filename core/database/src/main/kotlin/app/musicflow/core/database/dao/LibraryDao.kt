package app.musicflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.musicflow.core.database.entity.FavoriteArtistEntity
import app.musicflow.core.database.entity.FavoriteTrackEntity
import app.musicflow.core.database.entity.SavedAlbumEntity
import app.musicflow.core.database.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT t.* FROM tracks t JOIN favorite_tracks f ON f.trackId = t.id ORDER BY f.addedAt DESC")
    fun favoriteTracks(): Flow<List<TrackEntity>>

    @Query("SELECT trackId FROM favorite_tracks")
    fun favoriteIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE trackId = :id)")
    fun isFavorite(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addFavorite(f: FavoriteTrackEntity)
    @Query("DELETE FROM favorite_tracks WHERE trackId = :id") suspend fun removeFavorite(id: String)

    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC") fun savedAlbums(): Flow<List<SavedAlbumEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE id = :id)") fun isAlbumSaved(id: String): Flow<Boolean>
    @Upsert suspend fun saveAlbum(a: SavedAlbumEntity)
    @Query("DELETE FROM saved_albums WHERE id = :id") suspend fun removeAlbum(id: String)

    @Query("SELECT * FROM favorite_artists ORDER BY addedAt DESC") fun favoriteArtists(): Flow<List<FavoriteArtistEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM favorite_artists WHERE id = :id)") fun isArtistFavorite(id: String): Flow<Boolean>
    @Upsert suspend fun addArtist(a: FavoriteArtistEntity)
    @Query("DELETE FROM favorite_artists WHERE id = :id") suspend fun removeArtist(id: String)

    @Query("SELECT * FROM favorite_artists") suspend fun allFavoriteArtists(): List<FavoriteArtistEntity>
    @Query("SELECT trackId FROM favorite_tracks") suspend fun allFavoriteIds(): List<String>
}
