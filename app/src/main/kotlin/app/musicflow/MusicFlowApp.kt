package app.musicflow

import android.app.Application
import android.os.Build
import app.musicflow.core.common.AppInfo
import app.musicflow.core.common.ApplicationScope
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.repositories.SettingsRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class MusicFlowApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var registry: ProviderRegistry
    @Inject lateinit var diagnostics: DiagnosticsLog
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        diagnostics.i("app", "MusicFlow ${BuildConfig.VERSION_NAME} started on SDK ${Build.VERSION.SDK_INT}")
        // Apply provider toggles before any screen needs them.
        scope.launch {
            settings.settings.collect { s ->
                registry.setEnabled("yt", s.youtubeProviderEnabled)
                registry.setEnabled("sc", s.soundcloudProviderEnabled)
                registry.setEnabled("radio", s.radioProviderEnabled)
                (registry.byId("radio") as? app.musicflow.data.providers.radio.RadioBrowserProvider)?.allowHttp = s.radioAllowHttp
                registry.setEnabled("mbr", s.musicbrainzEnabled)
                app.musicflow.data.repositories.UpdateJobService.schedule(this@MusicFlowApp, s.checkUpdates)
                diagnostics.enabled = s.diagnosticsEnabled
            }
        }
    }

    /** Efficient artwork loading: shared OkHttp client, bounded memory and disk caches. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun appInfo(): AppInfo = AppInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE,
        sdkInt = Build.VERSION.SDK_INT,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        updateRepo = BuildConfig.UPDATE_REPO,
        extractorVersion = BuildConfig.NEWPIPE_EXTRACTOR,
    )
}
