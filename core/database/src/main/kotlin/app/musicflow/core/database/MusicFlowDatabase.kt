package app.musicflow.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import app.musicflow.core.database.dao.HistoryDao
import app.musicflow.core.database.dao.LibraryDao
import app.musicflow.core.database.dao.PlaylistDao
import app.musicflow.core.database.dao.TrackDao
import app.musicflow.core.database.entity.FavoriteArtistEntity
import app.musicflow.core.database.entity.FavoriteTrackEntity
import app.musicflow.core.database.entity.PlayEventEntity
import app.musicflow.core.database.entity.PlaylistEntity
import app.musicflow.core.database.entity.PlaylistEntryEntity
import app.musicflow.core.database.entity.QueueItemEntity
import app.musicflow.core.database.entity.SavedAlbumEntity
import app.musicflow.core.database.entity.SearchHistoryEntity
import app.musicflow.core.database.entity.TrackEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        TrackEntity::class, FavoriteTrackEntity::class, SavedAlbumEntity::class, FavoriteArtistEntity::class,
        PlaylistEntity::class, PlaylistEntryEntity::class, PlayEventEntity::class, SearchHistoryEntity::class,
        QueueItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MusicFlowDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun library(): LibraryDao
    abstract fun playlists(): PlaylistDao
    abstract fun history(): HistoryDao

    suspend fun <R> transaction(block: suspend () -> R): R = withTransaction(block)

    companion object {
        fun build(context: Context): MusicFlowDatabase =
            Room.databaseBuilder(context, MusicFlowDatabase::class.java, "musicflow.db").build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun db(@ApplicationContext context: Context) = MusicFlowDatabase.build(context)
    @Provides fun tracks(db: MusicFlowDatabase) = db.tracks()
    @Provides fun library(db: MusicFlowDatabase) = db.library()
    @Provides fun playlists(db: MusicFlowDatabase) = db.playlists()
    @Provides fun history(db: MusicFlowDatabase) = db.history()
}
