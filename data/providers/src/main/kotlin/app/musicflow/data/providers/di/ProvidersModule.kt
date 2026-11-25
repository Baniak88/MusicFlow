package app.musicflow.data.providers.di

import android.content.Context
import app.musicflow.core.common.AppInfo
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.data.providers.api.LyricsProvider
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.providers.lyrics.LrcLibLyricsProvider
import app.musicflow.data.providers.musicbrainz.MusicBrainzProvider
import app.musicflow.data.providers.radio.RadioBrowserProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.providers.soundcloud.SoundCloudProvider
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
    fun soundCloud(client: OkHttpClient, diagnostics: DiagnosticsLog) = SoundCloudProvider(client, diagnostics)

    @Provides @IntoSet
    fun bindSoundCloud(p: SoundCloudProvider): MusicProvider = p

    @Provides @Singleton
    fun radio(client: OkHttpClient, diagnostics: DiagnosticsLog, appInfo: AppInfo) = RadioBrowserProvider(client, diagnostics, userAgent(appInfo))

    @Provides @IntoSet
    fun bindRadio(p: RadioBrowserProvider): MusicProvider = p

    @Provides @Singleton
    fun musicBrainz(
        client: OkHttpClient, diagnostics: DiagnosticsLog, appInfo: AppInfo, youTube: YouTubeMusicProvider,
        registry: javax.inject.Provider<ProviderRegistry>,
    ) = MusicBrainzProvider(
        client, diagnostics,
        audio = object : MusicBrainzProvider.AudioMatcher {
            override val name = youTube.id
            private fun check() {
                if (!registry.get().isEnabled(youTube.id)) throw app.musicflow.core.model.ProviderError.NotSupported("MusicBrainz tracks play via YouTube Music, which is turned off")
            }
            override suspend fun candidates(query: String): List<app.musicflow.core.model.Track> {
                check()
                return youTube.search(query, app.musicflow.core.model.SearchFilter.SONGS).items
                    .filterIsInstance<app.musicflow.core.model.MediaItemSummary.TrackItem>().map { it.track }.take(10)
            }
            override suspend fun resolve(id: app.musicflow.core.model.MediaId, quality: app.musicflow.core.model.AudioQuality) = check().let { youTube.resolve(id, quality) }
        },
        userAgent = userAgent(appInfo),
    )

    /** Identifying User-Agent, as MusicBrainz and Radio Browser ask API clients to send. */
    private fun userAgent(appInfo: AppInfo) = "MusicFlow/${appInfo.versionName} ( https://github.com/${appInfo.updateRepo} )"

    @Provides @IntoSet
    fun bindMusicBrainz(p: MusicBrainzProvider): MusicProvider = p

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
