package app.musicflow.data.repositories

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.BufferProfile
import app.musicflow.core.model.ThemeMode
import app.musicflow.core.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

/** User preferences, persisted with Jetpack DataStore. */
@Singleton
class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.settingsStore)

    private object K {
        val theme = stringPreferencesKey("theme")
        val amoled = booleanPreferencesKey("amoled")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val artworkColors = booleanPreferencesKey("artwork_colors")
        val language = stringPreferencesKey("language")
        val qWifi = stringPreferencesKey("quality_wifi")
        val qMobile = stringPreferencesKey("quality_mobile")
        val cache = intPreferencesKey("cache_mb")
        val autoContinue = booleanPreferencesKey("auto_continue")
        val crossfade = intPreferencesKey("crossfade_seconds")
        val leveling = booleanPreferencesKey("volume_leveling")
        val normalization = stringPreferencesKey("normalization")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val pauseOnDisconnect = booleanPreferencesKey("pause_on_disconnect")
        val resumeBt = booleanPreferencesKey("resume_bt")
        val discovery = booleanPreferencesKey("discovery_mode")
        val yt = booleanPreferencesKey("provider_yt")
        val sc = booleanPreferencesKey("provider_sc")
        val history = booleanPreferencesKey("history_enabled")
        val diagnostics = booleanPreferencesKey("diagnostics_enabled")
        val buffer = stringPreferencesKey("buffer_profile")
        val country = stringPreferencesKey("content_country")
        val updates = booleanPreferencesKey("check_updates")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<UserSettings> = store.data.map { p ->
        val d = UserSettings()
        UserSettings(
            themeMode = p.enum(K.theme, d.themeMode),
            amoledBlack = p[K.amoled] ?: d.amoledBlack,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            artworkColors = p[K.artworkColors] ?: d.artworkColors,
            languageTag = p[K.language] ?: d.languageTag,
            qualityWifi = p.enum(K.qWifi, d.qualityWifi),
            qualityMobile = p.enum(K.qMobile, d.qualityMobile),
            cacheSizeMb = p[K.cache] ?: d.cacheSizeMb,
            autoContinueQueue = p[K.autoContinue] ?: d.autoContinueQueue,
            crossfadeSeconds = p[K.crossfade] ?: d.crossfadeSeconds,
            volumeLeveling = p[K.leveling] ?: d.volumeLeveling,
            normalization = p.enum(K.normalization, d.normalization),
            skipSilence = p[K.skipSilence] ?: d.skipSilence,
            pauseOnDisconnect = p[K.pauseOnDisconnect] ?: d.pauseOnDisconnect,
            resumeOnBluetooth = p[K.resumeBt] ?: d.resumeOnBluetooth,
            discoveryMode = p[K.discovery] ?: d.discoveryMode,
            youtubeProviderEnabled = p[K.yt] ?: d.youtubeProviderEnabled,
            soundcloudProviderEnabled = p[K.sc] ?: d.soundcloudProviderEnabled,
            historyEnabled = p[K.history] ?: d.historyEnabled,
            diagnosticsEnabled = p[K.diagnostics] ?: d.diagnosticsEnabled,
            bufferProfile = p.enum(K.buffer, d.bufferProfile),
            contentCountry = p[K.country] ?: d.contentCountry,
            checkUpdates = p[K.updates] ?: d.checkUpdates,
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setThemeMode(v: ThemeMode) { store.edit { it[K.theme] = v.name } }
    suspend fun setAmoled(v: Boolean) { store.edit { it[K.amoled] = v } }
    suspend fun setDynamicColor(v: Boolean) { store.edit { it[K.dynamic] = v } }
    suspend fun setArtworkColors(v: Boolean) { store.edit { it[K.artworkColors] = v } }
    suspend fun setLanguage(tag: String) { store.edit { it[K.language] = tag } }
    suspend fun setQualityWifi(v: AudioQuality) { store.edit { it[K.qWifi] = v.name } }
    suspend fun setQualityMobile(v: AudioQuality) { store.edit { it[K.qMobile] = v.name } }
    suspend fun setCacheSizeMb(v: Int) { store.edit { it[K.cache] = v.coerceIn(64, 4096) } }
    suspend fun setAutoContinue(v: Boolean) { store.edit { it[K.autoContinue] = v } }
    suspend fun setCrossfadeSeconds(v: Int) { store.edit { it[K.crossfade] = v.coerceIn(0, MAX_CROSSFADE) } }
    suspend fun setNormalization(v: app.musicflow.core.model.NormalizationLevel) { store.edit { it[K.normalization] = v.name } }
    suspend fun setCheckUpdates(v: Boolean) { store.edit { it[K.updates] = v } }
    suspend fun setVolumeLeveling(v: Boolean) { store.edit { it[K.leveling] = v } }
    suspend fun setSkipSilence(v: Boolean) { store.edit { it[K.skipSilence] = v } }
    suspend fun setPauseOnDisconnect(v: Boolean) { store.edit { it[K.pauseOnDisconnect] = v } }
    suspend fun setResumeOnBluetooth(v: Boolean) { store.edit { it[K.resumeBt] = v } }
    suspend fun setDiscoveryMode(v: Boolean) { store.edit { it[K.discovery] = v } }
    suspend fun setYouTubeEnabled(v: Boolean) { store.edit { it[K.yt] = v } }
    suspend fun setSoundCloudEnabled(v: Boolean) { store.edit { it[K.sc] = v } }
    suspend fun setHistoryEnabled(v: Boolean) { store.edit { it[K.history] = v } }
    suspend fun setDiagnosticsEnabled(v: Boolean) { store.edit { it[K.diagnostics] = v } }
    suspend fun setBufferProfile(v: BufferProfile) { store.edit { it[K.buffer] = v.name } }
    suspend fun setContentCountry(v: String) { store.edit { it[K.country] = v.uppercase().take(2) } }

    companion object {
        const val MAX_CROSSFADE = 12
    }
}
