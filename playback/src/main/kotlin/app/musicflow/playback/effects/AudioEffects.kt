package app.musicflow.playback.effects

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import app.musicflow.core.common.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class EqBand(val index: Int, val centerHz: Int, val levelMb: Int)

data class EqualizerState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minMb: Int = -1500,
    val maxMb: Int = 1500,
    val bands: List<EqBand> = emptyList(),
    val presets: List<String> = emptyList(),
    val currentPreset: Int = -1,
    val levelingActive: Boolean = false,
)

/**
 * Built-in equaliser (android.media.audiofx.Equalizer) and volume levelling
 * (DynamicsProcessing compressor + limiter, Android 9+), bound to the player's audio session.
 * Also announces the session so system / third-party equaliser apps can attach.
 */
@Singleton
class AudioEffects @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {
    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var dynamics: DynamicsProcessing? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var boostMb = 0
    private var wantLeveling = false
    private val savedLevels = HashMap<Int, Int>()
    private var savedEnabled = false

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    val audioSessionId: Int get() = sessionId

    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId || audioSessionId == 0) return
        release()
        sessionId = audioSessionId
        context.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        })
        equalizer = runCatching { Equalizer(0, sessionId) }.onFailure { diagnostics.w("effects", "Equalizer unavailable: ${it.message}") }.getOrNull()
        equalizer?.let { eq ->
            savedLevels.forEach { (band, level) -> runCatching { eq.setBandLevel(band.toShort(), level.toShort()) } }
            runCatching { eq.enabled = savedEnabled }
        }
        setLeveling(wantLeveling)
        setNormalizationBoost(boostMb)
        publish()
    }

    /**
     * Positive loudness-normalisation gain (quiet tracks), in millibels. LoudnessEnhancer
     * includes a limiter, so boosting cannot clip. 0 disables it.
     */
    fun setNormalizationBoost(mb: Int) {
        boostMb = mb.coerceIn(0, 1500)
        if (sessionId == 0) return
        if (boostMb == 0) { runCatching { enhancer?.enabled = false }; return }
        val e = enhancer ?: runCatching { android.media.audiofx.LoudnessEnhancer(sessionId) }
            .onFailure { diagnostics.w("effects", "LoudnessEnhancer unavailable: ${it.message}") }.getOrNull()?.also { enhancer = it }
        runCatching { e?.setTargetGain(boostMb); e?.setEnabled(true) }
    }

    private fun publish() {
        val eq = equalizer
        if (eq == null) { _state.update { it.copy(available = false, levelingActive = dynamics != null) }; return }
        runCatching {
            val range = eq.bandLevelRange
            _state.value = EqualizerState(
                available = true,
                enabled = eq.enabled,
                minMb = range[0].toInt(), maxMb = range[1].toInt(),
                bands = (0 until eq.numberOfBands).map { b -> EqBand(b, eq.getCenterFreq(b.toShort()) / 1000, eq.getBandLevel(b.toShort()).toInt()) },
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
                currentPreset = _state.value.currentPreset,
                levelingActive = dynamics != null,
            )
        }
    }

    fun setEnabled(enabled: Boolean) { savedEnabled = enabled; runCatching { equalizer?.enabled = enabled }; publish() }

    fun setBandLevel(band: Int, levelMb: Int) {
        savedLevels[band] = levelMb
        runCatching { equalizer?.setBandLevel(band.toShort(), levelMb.toShort()) }
        _state.update { it.copy(currentPreset = -1) }
        publish()
    }

    fun usePreset(index: Int) {
        val eq = equalizer ?: return
        runCatching {
            eq.usePreset(index.toShort())
            (0 until eq.numberOfBands).forEach { b -> savedLevels[b] = eq.getBandLevel(b.toShort()).toInt() }
        }
        _state.update { it.copy(currentPreset = index) }
        publish()
    }

    /** Gentle compression + limiting so quiet and loud tracks play at a similar perceived volume. */
    fun setLeveling(enabled: Boolean) {
        wantLeveling = enabled
        if (!enabled) { runCatching { dynamics?.release() }; dynamics = null; publish(); return }
        if (dynamics != null || sessionId == 0) return
        dynamics = runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, false, 0, false, 0, true,
            ).build()
            DynamicsProcessing(0, sessionId, config).apply {
                for (ch in 0 until 2) {
                    setInputGainbyChannel(ch, 0f)
                    setLimiterByChannelIndex(ch, DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 4f, -10f, 6f))
                }
                setEnabled(true)
            }
        }.onFailure { diagnostics.w("effects", "Volume levelling unavailable: ${it.message}") }.getOrNull()
        publish()
    }

    /** Intent for the system/third-party equaliser panel, or null if none is installed. */
    fun systemEqualizerIntent(): Intent? {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun release() {
        if (sessionId != 0) {
            context.sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            })
        }
        runCatching { equalizer?.release() }; equalizer = null
        runCatching { dynamics?.release() }; dynamics = null
        runCatching { enhancer?.release() }; enhancer = null
        sessionId = 0
    }
}
