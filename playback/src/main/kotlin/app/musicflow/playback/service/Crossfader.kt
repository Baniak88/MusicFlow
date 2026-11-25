package app.musicflow.playback.service

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spotify-style crossfade between consecutive tracks, without audio gaps.
 *
 * The [main] player owns the MediaSession (notification, lock screen, Bluetooth, Android Auto).
 * A separate *fader* player (no session) plays the end of the outgoing track:
 *
 * 1. **Prepare** ([preloadMs] before the fade): the fader loads the outgoing track, paused, at the
 *    fade position — so its buffer is ready in advance (usually straight from the disk cache).
 * 2. **Start**: at the fade point the fader starts *muted* at the main player's position.
 * 3. **Switch**: only once the fader is really playing does it take over the outgoing audio and
 *    the main player skip to the next track. If the fader is not ready in time, this transition
 *    simply plays gaplessly instead — never with a hole in the sound.
 * 4. **Ramp**: equal-power fade over [fadeMs] once the next track actually plays.
 *
 * Driven by [tick] (~every 50 ms while playing), so it is deterministic and testable.
 */
/** The few player operations the crossfader needs (adapter over ExoPlayer; faked in tests). */
interface CrossfadePlayer {
    val duration: Long
    val position: Long
    val isPlaying: Boolean
    val playbackState: Int
    val hasNext: Boolean
    val repeatOne: Boolean
    val pauseAtEnd: Boolean
    val currentItem: androidx.media3.common.MediaItem?
    val itemCount: Int
    val audioSessionId: Int
    var volume: Float
    /** Loads [item] at [positionMs], paused and muted, sharing [sessionId] and [params]. */
    fun load(item: androidx.media3.common.MediaItem, positionMs: Long, sessionId: Int, params: androidx.media3.common.PlaybackParameters?)
    val playbackParameters: androidx.media3.common.PlaybackParameters?
    fun seekTo(positionMs: Long)
    fun play()
    fun pause()
    fun stopAndClear()
    fun skipToNext()
    fun release()
}

@UnstableApi
class ExoCrossfadePlayer(val player: ExoPlayer) : CrossfadePlayer {
    override val duration get() = player.duration
    override val position get() = player.currentPosition
    override val isPlaying get() = player.isPlaying
    override val playbackState get() = player.playbackState
    override val hasNext get() = player.hasNextMediaItem()
    override val repeatOne get() = player.repeatMode == Player.REPEAT_MODE_ONE
    override val pauseAtEnd get() = player.pauseAtEndOfMediaItems
    override val currentItem get() = player.currentMediaItem
    override val itemCount get() = player.mediaItemCount
    override val audioSessionId get() = player.audioSessionId
    override var volume: Float
        get() = player.volume
        set(v) { player.volume = v }
    override val playbackParameters get() = player.playbackParameters
    override fun load(item: androidx.media3.common.MediaItem, positionMs: Long, sessionId: Int, params: androidx.media3.common.PlaybackParameters?) {
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) player.setAudioSessionId(sessionId) // shared EQ/levelling
        params?.let { player.playbackParameters = it }
        player.volume = 0f
        player.playWhenReady = false
        player.setMediaItem(item, positionMs)
        player.prepare()
    }
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun stopAndClear() { player.pause(); player.stop(); player.clearMediaItems() }
    override fun skipToNext() = player.seekToNextMediaItem()
    override fun release() = player.release()
}

@UnstableApi
class Crossfader(
    private val main: CrossfadePlayer,
    private val createFader: () -> CrossfadePlayer,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Gain of the main (incoming) player; the service multiplies it with normalisation etc. */
    private val setMainGain: (Float) -> Unit = { main.volume = it },
    private val preloadMs: Long = 6_000,
) {
    private enum class Phase { IDLE, PREPARED, STARTING, RAMP }

    /** Crossfade length in ms; 0 disables crossfading. */
    var fadeMs: Long = 0

    /** Extra veto, e.g. the sleep timer's "end of track" mode. */
    var blocked: () -> Boolean = { false }

    private var fader: CrossfadePlayer? = null
    private var phase = Phase.IDLE
    private var preparedFor: String? = null
    private var skippedFor: String? = null
    private var fadeStartedAt: Long? = null
    private var faderBase = 1f
    private var selfTransition = false

    /** True while the fader is audible (switch done, ramp running). */
    val isFading: Boolean get() = phase == Phase.RAMP

    /** True while any crossfade work is pending (prepared, starting or ramping). */
    val isActive: Boolean get() = phase != Phase.IDLE

    /** The fader player, exposed for tests and diagnostics. */
    val faderPlayer: CrossfadePlayer? get() = fader

    private fun conditionsOk(): Boolean {
        val duration = main.duration
        return fadeMs > 0 && duration != C.TIME_UNSET && duration >= fadeMs * MIN_TRACK_FACTOR &&
            main.isPlaying && main.hasNext && !main.repeatOne && !main.pauseAtEnd && !blocked()
    }

    private fun remaining(): Long = main.duration - main.position

    fun tick() {
        val currentId = main.currentItem?.mediaId
        when (phase) {
            Phase.IDLE -> {
                if (currentId == null || currentId == skippedFor) return
                if (conditionsOk() && remaining() in 1..(fadeMs + preloadMs)) prepare(currentId)
            }
            Phase.PREPARED -> {
                if (currentId != preparedFor || !conditionsOk()) { cancel(); return }
                if (remaining() <= fadeMs) {
                    val f = fader ?: return cancel()
                    if (f.playbackState == Player.STATE_READY) {
                        f.volume = 0f // muted until it really plays: no doubled audio, no gap
                        f.seekTo(main.position)
                        f.play()
                        phase = Phase.STARTING
                    } else {
                        // Not buffered in time: play this transition gaplessly rather than with a hole.
                        skippedFor = currentId
                        cancel()
                    }
                }
            }
            Phase.STARTING -> {
                val f = fader ?: return cancel()
                if (currentId != preparedFor) { cancel(); return } // user skipped meanwhile
                if (f.isPlaying && f.playbackState == Player.STATE_READY) switchToNext(f)
                else if (remaining() <= 150) { skippedFor = currentId; cancel() } // too late: gapless
            }
            Phase.RAMP -> advance()
        }
    }

    private fun prepare(itemId: String) {
        val item = main.currentItem ?: return
        val f = fader ?: createFader().also { fader = it }
        f.load(item, (main.duration - fadeMs).coerceAtLeast(0), main.audioSessionId, main.playbackParameters)
        preparedFor = itemId
        phase = Phase.PREPARED
    }

    private fun switchToNext(f: CrossfadePlayer) {
        faderBase = main.volume // the outgoing track keeps its own (normalised) level
        f.volume = faderBase
        selfTransition = true
        setMainGain(0f)
        main.skipToNext()
        fadeStartedAt = null
        phase = Phase.RAMP
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

    private fun cancel() {
        fader?.stopAndClear()
        preparedFor = null
        phase = Phase.IDLE
    }

    /** Ends any crossfade work: the outgoing track stops, the main player returns to full gain. */
    fun finish() {
        cancel()
        setMainGain(1f)
        fadeStartedAt = null
    }

    /** True exactly once after the crossfade's own skip, so it is not counted as a user skip. */
    fun consumeSelfTransition(): Boolean = selfTransition.also { selfTransition = false }

    fun onMainPlayingChanged(playing: Boolean) {
        if (playing) return
        when (phase) {
            Phase.RAMP -> if (fadeStartedAt != null) finish()
            Phase.PREPARED, Phase.STARTING -> cancel()
            Phase.IDLE -> Unit
        }
    }

    fun release() { fader?.release(); fader = null; phase = Phase.IDLE }

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
