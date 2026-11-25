package app.musicflow.playback.service

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spotify-style crossfade between consecutive tracks.
 *
 * The [main] player owns the MediaSession (notification, lock screen, Bluetooth, Android Auto).
 * [fadeMs] before the current track ends, a separate *fader* player (not attached to any session)
 * continues the outgoing track from the same position — usually straight from the disk cache —
 * while [main] skips to the next track. Volumes then follow an equal-power curve for [fadeMs].
 *
 * Driven by [tick] (about every 100 ms while playing), so it is deterministic and testable.
 * Pausing or skipping during a fade ends the fade immediately.
 */
@UnstableApi
class Crossfader(
    private val main: ExoPlayer,
    private val createFader: () -> ExoPlayer,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Gain of the main (incoming) player; the service multiplies it with normalisation etc. */
    private val setMainGain: (Float) -> Unit = { main.volume = it },
) {
    private var faderBase = 1f
    /** Crossfade length in ms; 0 disables crossfading. */
    var fadeMs: Long = 0

    /** Extra veto, e.g. the sleep timer's "end of track" mode. */
    var blocked: () -> Boolean = { false }

    private var fader: ExoPlayer? = null
    private var fadeStartedAt: Long? = null
    private var selfTransition = false

    var isFading: Boolean = false
        private set

    /** The fader player, exposed for tests and diagnostics. */
    val faderPlayer: ExoPlayer? get() = fader

    fun tick() {
        if (isFading) { advance(); return }
        val duration = main.duration
        if (shouldStart(
                fadeMs = fadeMs,
                durationMs = if (duration == C.TIME_UNSET) null else duration,
                positionMs = main.currentPosition,
                isPlaying = main.isPlaying,
                hasNext = main.hasNextMediaItem(),
                repeatOne = main.repeatMode == Player.REPEAT_MODE_ONE,
                pauseAtEnd = main.pauseAtEndOfMediaItems || blocked(),
            )
        ) start()
    }

    private fun start() {
        val item = main.currentMediaItem ?: return
        val f = fader ?: createFader().also { fader = it }
        if (main.audioSessionId != C.AUDIO_SESSION_ID_UNSET) f.setAudioSessionId(main.audioSessionId) // shared EQ/levelling
        f.playbackParameters = main.playbackParameters
        faderBase = main.volume // the outgoing track keeps its own (normalised) level
        f.volume = faderBase
        f.setMediaItem(item, main.currentPosition)
        f.prepare()
        f.play()
        isFading = true
        fadeStartedAt = null
        selfTransition = true
        setMainGain(0f)
        main.seekToNextMediaItem()
    }

    private fun advance() {
        val f = fader ?: return finish()
        val started = fadeStartedAt
        if (started == null) {
            // Ramp only once the next track actually plays; until then the old track continues.
            if (main.playbackState == Player.STATE_READY && main.isPlaying) fadeStartedAt = clock()
            else if (f.playbackState == Player.STATE_ENDED) finish()
            return
        }
        val t = ((clock() - started).toFloat() / fadeMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val (out, inn) = gains(t)
        f.volume = out * faderBase
        setMainGain(inn)
        if (t >= 1f || f.playbackState == Player.STATE_ENDED) finish()
    }

    /** Ends any running fade: the outgoing track stops, the main player returns to full volume. */
    fun finish() {
        fader?.let { it.stop(); it.clearMediaItems() }
        setMainGain(1f)
        isFading = false
        fadeStartedAt = null
    }

    /** True exactly once after the crossfade's own skip, so it is not counted as a user skip. */
    fun consumeSelfTransition(): Boolean = selfTransition.also { selfTransition = false }

    fun onMainPlayingChanged(playing: Boolean) { if (!playing && isFading && fadeStartedAt != null) finish() }

    fun release() { fader?.release(); fader = null; isFading = false }

    companion object {
        /** Minimum track length for crossfading: shorter tracks play gaplessly. */
        private const val MIN_TRACK_FACTOR = 3

        fun shouldStart(
            fadeMs: Long, durationMs: Long?, positionMs: Long, isPlaying: Boolean,
            hasNext: Boolean, repeatOne: Boolean, pauseAtEnd: Boolean,
        ): Boolean {
            if (fadeMs <= 0 || durationMs == null || !isPlaying || !hasNext || repeatOne || pauseAtEnd) return false
            if (durationMs < fadeMs * MIN_TRACK_FACTOR) return false
            val remaining = durationMs - positionMs
            return remaining in 1..fadeMs
        }

        /** Equal-power curve: perceived loudness stays constant through the overlap. */
        fun gains(t: Float): Pair<Float, Float> {
            val x = t.coerceIn(0f, 1f) * (PI / 2)
            return cos(x).toFloat() to sin(x).toFloat()
        }
    }
}
