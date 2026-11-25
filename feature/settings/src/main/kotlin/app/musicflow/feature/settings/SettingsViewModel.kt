package app.musicflow.feature.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.common.AppInfo
import app.musicflow.core.common.DiagnosticEvent
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.Sanitizer
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.BufferProfile
import app.musicflow.core.model.NetworkStatus
import app.musicflow.core.model.ThemeMode
import app.musicflow.core.model.UserSettings
import app.musicflow.core.network.ConnectivityMonitor
import app.musicflow.data.providers.registry.ProviderHealth
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.data.repositories.PlaylistRepository
import app.musicflow.data.repositories.SearchRepository
import app.musicflow.data.repositories.SettingsRepository
import app.musicflow.playback.EngineStatus
import app.musicflow.playback.PlaybackRuntime
import app.musicflow.playback.engine.PlaybackEngine
import app.musicflow.playback.engine.PlaybackState
import app.musicflow.playback.service.AudioCacheHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SettingsEvent {
    data class Message(val res: Int, val arg: Any? = null) : SettingsEvent
    data class Error(val error: Throwable) : SettingsEvent
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val registry: ProviderRegistry,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val search: SearchRepository,
    private val cache: AudioCacheHolder,
    val appInfo: AppInfo,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val providers: StateFlow<Map<String, ProviderHealth>> = registry.health
    val cacheUsedBytes = MutableStateFlow(0L)
    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SettingsEvent> = _events

    init { refreshCache() }

    private fun refreshCache() = viewModelScope.launch(Dispatchers.IO) { cacheUsedBytes.value = cache.usedBytes() }

    fun setTheme(v: ThemeMode) = viewModelScope.launch { repo.setThemeMode(v) }
    fun setAmoled(v: Boolean) = viewModelScope.launch { repo.setAmoled(v) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { repo.setDynamicColor(v) }
    fun setArtworkColors(v: Boolean) = viewModelScope.launch { repo.setArtworkColors(v) }
    fun setQualityWifi(v: AudioQuality) = viewModelScope.launch { repo.setQualityWifi(v) }
    fun setQualityMobile(v: AudioQuality) = viewModelScope.launch { repo.setQualityMobile(v) }
    fun setBuffer(v: BufferProfile) = viewModelScope.launch { repo.setBufferProfile(v); _events.emit(SettingsEvent.Message(R.string.set_applies_after_restart)) }
    fun setCacheSize(mb: Int) = viewModelScope.launch { repo.setCacheSizeMb(mb); _events.emit(SettingsEvent.Message(R.string.set_applies_after_restart)) }
    fun setNormalization(v: app.musicflow.core.model.NormalizationLevel) = viewModelScope.launch { repo.setNormalization(v) }
    fun setCrossfade(seconds: Int) = viewModelScope.launch { repo.setCrossfadeSeconds(seconds) }
    fun setAutoContinue(v: Boolean) = viewModelScope.launch { repo.setAutoContinue(v) }
    fun setSkipSilence(v: Boolean) = viewModelScope.launch { repo.setSkipSilence(v) }
    fun setLeveling(v: Boolean) = viewModelScope.launch { repo.setVolumeLeveling(v) }
    fun setPauseOnDisconnect(v: Boolean) = viewModelScope.launch { repo.setPauseOnDisconnect(v) }
    fun setDiscovery(v: Boolean) = viewModelScope.launch { repo.setDiscoveryMode(v) }
    fun setYouTube(v: Boolean) = viewModelScope.launch { repo.setYouTubeEnabled(v); registry.setEnabled("yt", v) }
    fun setHistory(v: Boolean) = viewModelScope.launch { repo.setHistoryEnabled(v) }
    fun setDiagnostics(v: Boolean) = viewModelScope.launch { repo.setDiagnosticsEnabled(v) }
    fun setCountry(v: String) = viewModelScope.launch { repo.setContentCountry(v); _events.emit(SettingsEvent.Message(R.string.set_applies_after_restart)) }

    fun setLanguage(tag: String) = viewModelScope.launch {
        repo.setLanguage(tag)
        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
    }

    fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
        cache.clear(); refreshCache(); _events.emit(SettingsEvent.Message(R.string.set_cache_cleared))
    }
    fun clearHistory() = viewModelScope.launch { library.clearHistory(); _events.emit(SettingsEvent.Message(R.string.set_history_cleared)) }
    fun clearSearchHistory() = viewModelScope.launch { search.clearHistory(); _events.emit(SettingsEvent.Message(R.string.set_search_cleared)) }

    fun backup(uri: Uri) = viewModelScope.launch {
        runCatching {
            val json = playlists.backup(library.favoriteTracks.first())
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } }
        }.onSuccess { _events.emit(SettingsEvent.Message(R.string.set_backup_done)) }.onFailure { _events.emit(SettingsEvent.Error(it)) }
    }

    fun restore(uri: Uri) = viewModelScope.launch {
        runCatching {
            val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.orEmpty()
            playlists.restore(text) { library.setFavorite(it, true) }
        }.onSuccess { _events.emit(SettingsEvent.Message(R.string.set_restore_done, it)) }.onFailure { _events.emit(SettingsEvent.Error(it)) }
    }
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: DiagnosticsLog,
    registry: ProviderRegistry,
    connectivity: ConnectivityMonitor,
    runtime: PlaybackRuntime,
    engine: PlaybackEngine,
    val appInfo: AppInfo,
) : ViewModel() {
    val events: StateFlow<List<DiagnosticEvent>> = log.events
    val providers: StateFlow<Map<String, ProviderHealth>> = registry.health
    val activeProvider: String? = registry.primaryCatalog()?.displayName
    val network: StateFlow<NetworkStatus> = connectivity.status
    val engine: StateFlow<EngineStatus> = runtime.status
    val playback: StateFlow<PlaybackState> = engine.state
    private val _exported = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val exported: SharedFlow<Boolean> = _exported

    fun clear() = log.clear()

    /** Builds the report; every line passes through [Sanitizer] (tokens, cookies, signed URLs removed). */
    fun report(): String {
        val e = engine.value
        val header = buildString {
            appendLine("MusicFlow diagnostics")
            appendLine("Version: ${appInfo.versionName} (${appInfo.versionCode}) ${appInfo.buildType}")
            appendLine("Android SDK: ${appInfo.sdkInt}, device: ${appInfo.device}")
            appendLine("Active provider: ${activeProvider ?: "none"}")
            providers.value.values.forEach { appendLine("Provider ${it.providerId}: ${it.status} failures=${it.consecutiveFailures} lastError=${it.lastError}") }
            appendLine("Network: ${network.value}")
            appendLine("Engine: running=${e.serviceRunning} buffering=${e.buffering} autoSkipped=${e.autoSkipped}")
            appendLine("Stream: ${e.stream?.describe()} mime=${e.stream?.mimeType}")
            appendLine("Decoder: ${e.format}")
            appendLine("Last error: ${e.lastError?.let { it::class.simpleName }} ${e.lastErrorMessage}")
        }
        return Sanitizer.sanitize(log.export(header))
    }

    fun export(uri: Uri) = viewModelScope.launch {
        val ok = runCatching {
            val text = report()
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } }
        }.isSuccess
        _exported.emit(ok)
    }
}
