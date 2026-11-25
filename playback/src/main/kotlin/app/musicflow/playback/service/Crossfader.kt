package app.musicflow.playback.service

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.abs
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
 * 3. **Switch**: only once the fader is really producing sound (its position advances, and it is
 *    in sync with the main player) does it take over the outgoing audio and the main player skip
 *    to the next track. If the fader is not ready in time, this transition simply plays
 *    gaplessly instead — never with a hole in the sound.
 * 4. **Ramp**: equal-power curves tied to *media positions*, not wall-clock time: the outgoing
 *    track always reaches silence exactly at its own end (never cut off mid-fade, however long
 *    the next track takes to start), and the incoming track fades in over its first [fadeMs]
 *    (a buffering stall pauses its fade instead of ending it).
 *
 * Driven by [tick] (~every 50 ms while playing), so it is deterministic and testable.
 */
/** The few player operations the crossfader needs (adapter over ExoPlayer; faked in tests). */
interface CrossfadePlayer {
    val duration: Long
    val position: Long
    val isPlaying: Boolean
    /** False once the user paused (unlike [isPlaying], which is also false while buffering). */
    val playWhenReady: Boolean
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
    override val playWhenReady get() = player.playWhenReady
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
    /** Gain of the main (incoming) player; the service multiplies it with normalisation etc. */
    private val setMainGain: (Float) -> Unit = { main.volume = it },
    private val preloadMs: Long = 6_000,
    /** Diagnostics sink (switches, fallbacks, early endings), so reports from devices are explainable. */
    private val log: (String) -> Unit = {},
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
    private var faderBase = 1f
    private var outgoingDuration = 0L
    private var faderPlayFrom = 0L
    private var realigned = false
    private var faderDone = false
    private var mainStarted = false
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
                        faderPlayFrom = main.position
                        realigned = false
                        f.seekTo(faderPlayFrom)
                        f.play()
                        phase = Phase.STARTING
                    } else {
                        // Not buffered in time: play this transition gaplessly rather than with a hole.
                        log("Crossfade skipped (outgoing tail not buffered in time): gapless transition")
                        skippedFor = currentId
                        cancel()
                    }
                }
            }
            Phase.STARTING -> {
                val f = fader ?: return cancel()
                if (currentId != preparedFor) { cancel(); return } // user skipped meanwhile
                val flowing = f.isPlaying && f.playbackState == Player.STATE_READY && f.position >= faderPlayFrom + FLOW_MS
                if (flowing) {
                    // Positions reflect what is audible, so the difference is the real offset.
                    val lag = main.position - f.position
                    if (abs(lag) > SYNC_TOLERANCE_MS && !realigned && remaining() > fadeMs / 2) {
                        realigned = true
                        faderPlayFrom = main.position + lag.coerceAtLeast(0)
                        f.seekTo(faderPlayFrom) // compensates the start-up latency once
                    } else switchToNext(f)
                } else if (remaining() <= 150) { log("Crossfade skipped (fader did not start in time): gapless transition"); skippedFor = currentId; cancel() }
            }
            Phase.RAMP -> advance()
        }
    }

    private fun prepare(itemId: String) {
        val item = main.currentItem ?: return
        val f = fader ?: createFader().also { fader = it }
        outgoingDuration = main.duration
        f.load(item, (main.duration - fadeMs).coerceAtLeast(0), main.audioSessionId, main.playbackParameters)
        preparedFor = itemId
        phase = Phase.PREPARED
    }

    private fun switchToNext(f: CrossfadePlayer) {
        faderBase = main.volume // the outgoing track keeps its own (normalised) level
        log("Crossfade start: ${remaining()} ms of the outgoing track left, fade ${fadeMs} ms, offset ${main.position - f.position} ms")
        f.volume = faderBase
        selfTransition = true
        setMainGain(0f)
        main.skipToNext()
        faderDone = false
        mainStarted = false
        phase = Phase.RAMP
    }

    private fun advance() {
        val f = fader ?: return finish()
        if (!main.playWhenReady) { log("Crossfade stopped: paused by the user"); return finish() } // no resume surprises
        // Outgoing: by its remaining audio → silent exactly at its end, never cut mid-fade.
        if (!faderDone) {
            val left = outgoingDuration - f.position
            if (f.playbackState == Player.STATE_ENDED || left <= 0) {
                val gain = gains(1f - left.coerceAtLeast(0).toFloat() / fadeMs.coerceAtLeast(1)).first
                if (gain > 0.1f) log("Outgoing track ended early: $left ms before its reported end (gain ${"%.2f".format(gain)})")
                faderDone = true
                f.stopAndClear()
            } else {
                val (out, _) = gains(1f - left.toFloat() / fadeMs.coerceAtLeast(1))
                f.volume = out * faderBase
            }
        }
        // Incoming: by its own position, once it really plays; a stall pauses the ramp.
        if (main.isPlaying && main.playbackState == Player.STATE_READY) mainStarted = true
        val tIn = if (mainStarted) main.position.toFloat() / fadeMs.coerceAtLeast(1) else 0f
        setMainGain(gains(tIn).second)
        if (faderDone && tIn >= 1f) finish()
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
    }

    /** True exactly once after the crossfade's own skip, so it is not counted as a user skip. */
    fun consumeSelfTransition(): Boolean = selfTransition.also { selfTransition = false }

    fun onMainPlayingChanged(playing: Boolean) {
        if (playing) return
        when (phase) {
            Phase.RAMP -> if (!main.playWhenReady) finish() // paused by the user, not buffering
            Phase.PREPARED, Phase.STARTING -> cancel()
            Phase.IDLE -> Unit
        }
    }

    fun release() { fader?.release(); fader = null; phase = Phase.IDLE }

    companion object {
        /** Minimum track length for crossfading: shorter tracks play gaplessly. */
        private const val MIN_TRACK_FACTOR = 3
        /** The fader counts as audible once its position has advanced this far. */
        private const val FLOW_MS = 100L
        /** Larger offsets between the two copies of the outgoing track are realigned once. */
        private const val SYNC_TOLERANCE_MS = 40L

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
