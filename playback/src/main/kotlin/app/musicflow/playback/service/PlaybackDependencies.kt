package app.musicflow.playback.service

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import app.musicflow.data.repositories.SettingsRepository
import app.musicflow.playback.stream.PlaybackDataSources
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single on-disk audio cache. A [SimpleCache] directory may only be opened once per
 * process, so it lives in the singleton component rather than in the service.
 * The size limit is read at creation; changing it takes effect after the app restarts.
 */
@Singleton
@UnstableApi
class AudioCacheHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    val cache: SimpleCache by lazy {
        val mb = runBlocking { settings.current().cacheSizeMb }
        PlaybackDataSources.createCache(context, mb.toLong() * 1024 * 1024)
    }

    fun usedBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /** Removes every cached audio span (keeps the cache instance usable). */
    fun clear() {
        runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {
    @Provides
    @Singleton
    @androidx.annotation.OptIn(UnstableApi::class)
    fun engine(impl: app.musicflow.playback.engine.MediaControllerEngine): app.musicflow.playback.engine.PlaybackEngine = impl
}
