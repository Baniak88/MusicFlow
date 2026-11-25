package app.musicflow.feature.player

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.model.Lyrics
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.LyricsProvider
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.data.repositories.SettingsRepository
import app.musicflow.playback.EngineStatus
import app.musicflow.playback.PlaybackRuntime
import app.musicflow.playback.SleepTimer
import app.musicflow.playback.SleepTimerState
import app.musicflow.playback.effects.AudioEffects
import app.musicflow.playback.effects.EqualizerState
import app.musicflow.playback.engine.PlaybackEngine
import app.musicflow.playback.engine.PlaybackState
import app.musicflow.playback.engine.Progress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Loaded(val lyrics: Lyrics) : LyricsState
    data object NotFound : LyricsState
    data class Failed(val error: Throwable) : LyricsState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val engine: PlaybackEngine,
    private val library: LibraryRepository,
    private val lyricsProvider: LyricsProvider,
    private val runtime: PlaybackRuntime,
    private val sleepTimer: SleepTimer,
    private val effects: AudioEffects,
    private val settings: SettingsRepository,
    private val connectivity: app.musicflow.core.network.ConnectivityMonitor,
    audioOutput: app.musicflow.playback.AudioOutputMonitor,
) : ViewModel() {
    val output: StateFlow<app.musicflow.playback.AudioOutput> = audioOutput.output
    val state: StateFlow<PlaybackState> = engine.state
    val progress: Flow<Progress> = engine.progress
    val engineStatus: StateFlow<EngineStatus> = runtime.status
    val sleep: StateFlow<SleepTimerState> = sleepTimer.state
    val equalizer: StateFlow<EqualizerState> = effects.state
    val artworkColors: StateFlow<Boolean> = settings.settings.map { it.artworkColors }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val isFavorite: StateFlow<Boolean> = state.map { it.current?.id }.distinctUntilChanged()
        .flatMapLatest { id -> id?.let { library.isFavorite(it) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics: StateFlow<LyricsState> = _lyrics
    private var lyricsFor: String? = null

    fun loadLyrics(track: Track) {
        if (lyricsFor == track.id.value && _lyrics.value !is LyricsState.Failed) return
        lyricsFor = track.id.value
        _lyrics.value = LyricsState.Loading
        viewModelScope.launch {
            _lyrics.value = runCatching { lyricsProvider.lyrics(track) }.fold(
                onSuccess = { l -> if (l == null || (l.plain.isNullOrBlank() && !l.isSynced)) LyricsState.NotFound else LyricsState.Loaded(l) },
                onFailure = { LyricsState.Failed(it) },
            )
        }
    }

    /** Quality used on the network the phone is on right now (Wi-Fi vs mobile data). */
    val isMetered: Boolean get() = connectivity.current().isMetered

    val activeQuality: StateFlow<app.musicflow.core.model.AudioQuality> = settings.settings
        .map { if (connectivity.current().isMetered) it.qualityMobile else it.qualityWifi }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.musicflow.core.model.AudioQuality.HIGH)

    /** Saves the quality for the current network type; the service reloads the song in place. */
    fun setQuality(q: app.musicflow.core.model.AudioQuality) = viewModelScope.launch {
        if (isMetered) settings.setQualityMobile(q) else settings.setQualityWifi(q)
    }

    fun togglePlay() = engine.togglePlayPause()
    fun next() = engine.next()
    fun previous() = engine.previous()
    fun seek(ms: Long) = engine.seekTo(ms)
    fun toggleShuffle() = engine.setShuffle(!state.value.shuffle)
    fun cycleRepeat() = engine.cycleRepeat()
    fun setSpeed(s: Float) = engine.setSpeed(s)
    fun skipTo(i: Int) = engine.skipToQueueItem(i)
    fun remove(i: Int) = engine.removeFromQueue(i)
    fun move(from: Int, to: Int) = engine.moveQueueItem(from, to)
    fun clearQueue() = engine.clearQueue()
    fun toggleFavorite() = viewModelScope.launch { state.value.current?.let { library.toggleFavorite(it) } }
    fun dismissError() = runtime.clearError()

    fun startSleep(minutes: Int) = sleepTimer.start(minutes)
    fun sleepEndOfTrack() = sleepTimer.endOfTrack()
    fun cancelSleep() = sleepTimer.cancel()

    fun setEqEnabled(v: Boolean) = effects.setEnabled(v)
    fun setBand(band: Int, level: Int) = effects.setBandLevel(band, level)
    fun usePreset(i: Int) = effects.usePreset(i)
    fun systemEqualizer(): Intent? = effects.systemEqualizerIntent()
}
