package app.musicflow

import android.content.Context
import app.musicflow.core.model.Lyrics
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.LyricsProvider
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.di.ProvidersModule
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.providers.youtube.YouTubeMusicProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Replaces online providers with offline ones so UI tests are hermetic (no network). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ProvidersModule::class])
object TestProvidersModule {
    @Provides @Singleton fun local(@ApplicationContext c: Context) = LocalMusicProvider(c)
    @Provides @Singleton fun direct() = DirectUrlProvider()
    @Provides @IntoSet fun bindLocal(p: LocalMusicProvider): MusicProvider = p
    @Provides @IntoSet fun bindDirect(p: DirectUrlProvider): MusicProvider = p
    @Provides @Singleton fun lyrics(): LyricsProvider = object : LyricsProvider {
        override val name = "test"
        override suspend fun lyrics(track: Track): Lyrics? = null
    }
    // Not part of the set, but some code paths may request it by type.
    @Provides @Singleton fun youTube(client: okhttp3.OkHttpClient, d: app.musicflow.core.common.DiagnosticsLog) = YouTubeMusicProvider(client, d)
}
