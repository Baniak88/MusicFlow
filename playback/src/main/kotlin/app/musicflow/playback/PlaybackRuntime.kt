package app.musicflow.playback

import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ResolvedStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Decoder-reported format of what is actually playing (not what was requested). */
data class ActiveFormat(
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
)

data class EngineStatus(
    val serviceRunning: Boolean = false,
    val audioSessionId: Int = 0,
    val buffering: Boolean = false,
    val bufferedMs: Long = 0,
    val stream: ResolvedStream? = null,
    val format: ActiveFormat? = null,
    val lastError: ProviderError? = null,
    val lastErrorMessage: String? = null,
    val lastErrorAt: Long? = null,
    val autoSkipped: Int = 0,
    /** Measured loudness of the current track and the normalisation gain applied to it. */
    val loudnessLufs: Float? = null,
    val normalizationDb: Float? = null,
    val loudnessSource: LoudnessSource? = null,
)

/**
 * In-process state shared between the playback service and the UI (same process).
 * Holds information that is not part of the MediaSession protocol, such as the real stream
 * format or classified errors.
 */
@Singleton
class PlaybackRuntime @Inject constructor() {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun update(block: (EngineStatus) -> EngineStatus) = _status.update(block)

    fun reportError(error: ProviderError?, message: String?) = _status.update {
        it.copy(lastError = error, lastErrorMessage = message, lastErrorAt = System.currentTimeMillis())
    }

    fun clearError() = _status.update { it.copy(lastError = null, lastErrorMessage = null) }
}
