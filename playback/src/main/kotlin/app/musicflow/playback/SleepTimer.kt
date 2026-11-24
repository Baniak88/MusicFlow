package app.musicflow.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class At(val endsAtMillis: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/** Sleep timer requested by the UI and enforced by the playback service. */
@Singleton
class SleepTimer @Inject constructor() {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    fun start(minutes: Int, now: Long = System.currentTimeMillis()) {
        _state.value = if (minutes <= 0) SleepTimerState.Off else SleepTimerState.At(now + minutes * 60_000L)
    }

    fun endOfTrack() { _state.value = SleepTimerState.EndOfTrack }
    fun cancel() { _state.value = SleepTimerState.Off }

    /** True when the timer expired by [now]; the caller pauses playback and the timer resets. */
    fun consumeIfExpired(now: Long = System.currentTimeMillis()): Boolean {
        val s = _state.value
        if (s is SleepTimerState.At && now >= s.endsAtMillis) { _state.value = SleepTimerState.Off; return true }
        return false
    }

    fun consumeEndOfTrack(): Boolean {
        if (_state.value == SleepTimerState.EndOfTrack) { _state.value = SleepTimerState.Off; return true }
        return false
    }
}
