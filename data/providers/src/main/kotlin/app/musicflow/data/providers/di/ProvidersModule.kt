package app.musicflow.data.providers.di

import android.content.Context
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.data.providers.api.LyricsProvider
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.providers.lyrics.LrcLibLyricsProvider
import app.musicflow.data.providers.youtube.YouTubeMusicProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProvidersModule {
    @Provides @Singleton
    fun youTube(client: OkHttpClient, diagnostics: DiagnosticsLog) = YouTubeMusicProvider(client, diagnostics)

    @Provides @Singleton
    fun local(@ApplicationContext context: Context) = LocalMusicProvider(context)

    @Provides @Singleton
    fun direct() = DirectUrlProvider()

    @Provides @IntoSet
    fun bindYouTube(p: YouTubeMusicProvider): MusicProvider = p

    @Provides @IntoSet
    fun bindLocal(p: LocalMusicProvider): MusicProvider = p

    @Provides @IntoSet
    fun bindDirect(p: DirectUrlProvider): MusicProvider = p

    @Provides @Singleton
    fun lyrics(client: OkHttpClient): LyricsProvider = LrcLibLyricsProvider(client)
}
