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
        equalizer = runCatching { Equalizer(PRIORITY, sessionId).also(::watch) }.onFailure { diagnostics.w("effects", "Equalizer unavailable: ${it.message}") }.getOrNull()
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
        val e = enhancer ?: runCatching { android.media.audiofx.LoudnessEnhancer(sessionId).also(::watch) }
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
            DynamicsProcessing(PRIORITY, sessionId, config).apply {
                for (ch in 0 until 2) {
                    setInputGainbyChannel(ch, 0f)
                    setLimiterByChannelIndex(ch, DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 4f, -10f, 6f))
                }
                setEnabled(true)
                watch(this)
            }
        }.onFailure { diagnostics.w("effects", "Volume levelling unavailable: ${it.message}") }.getOrNull()
        publish()
    }

    /**
     * Another effect client (e.g. the phone maker's sound effects, which we invite via
     * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) can take control of our effects or switch them
     * off, while the settings still show them as on. Re-check when that happens.
     */
    private fun watch(effect: AudioEffect) {
        runCatching {
            effect.setControlStatusListener { _, hasControl -> if (!hasControl) restoreSoon() }
            effect.setEnableStatusListener { _, enabled -> if (!enabled) restoreSoon() }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastListenerRestore = 0L

    /** Listener-driven restores are rate-limited, so two apps can never fight in a tight loop. */
    private fun restoreSoon() {
        mainHandler.post {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastListenerRestore >= LISTENER_RESTORE_INTERVAL_MS) { lastListenerRestore = now; ensureActive() }
        }
    }

    /** Whether an effect really runs for us (swappable: Robolectric does not emulate hasControl). */
    internal var isWorking: (AudioEffect) -> Boolean = { it.hasControl() && it.enabled }

    private fun AudioEffect.working(): Boolean = runCatching { isWorking(this) }.getOrDefault(false)

    /**
     * Makes sure every effect the user switched on really runs; recreates (and so reclaims) any
     * that lost control or were disabled from outside. Called on playback start, on track
     * changes and from the effects' status listeners. Returns the number of effects restored.
     */
    fun ensureActive(): Int {
        if (sessionId == 0) return 0
        var restored = 0
        dynamics?.let { d ->
            if (wantLeveling && !d.working()) { runCatching { d.release() }; dynamics = null }
        }
        if (wantLeveling && dynamics == null) { setLeveling(true); if (dynamics != null) restored++ }
        enhancer?.let { e ->
            if (boostMb > 0 && !e.working()) { runCatching { e.release() }; enhancer = null; setNormalizationBoost(boostMb); restored++ }
        }
        equalizer?.let { eq ->
            if (savedEnabled && !eq.working()) {
                runCatching { eq.release() }
                equalizer = runCatching { Equalizer(PRIORITY, sessionId).also(::watch) }.getOrNull()?.also { n ->
                    savedLevels.forEach { (band, level) -> runCatching { n.setBandLevel(band.toShort(), level.toShort()) } }
                    runCatching { n.enabled = true }
                }
                restored++
            }
        }
        if (restored > 0) { diagnostics.i("effects", "Restored $restored audio effect(s) taken over or disabled by another app"); publish() }
        return restored
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

    companion object {
        /** Above normal (0), so system sound-effect apps attaching to our session do not win. */
        const val PRIORITY = 1
        private const val LISTENER_RESTORE_INTERVAL_MS = 5_000L
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
