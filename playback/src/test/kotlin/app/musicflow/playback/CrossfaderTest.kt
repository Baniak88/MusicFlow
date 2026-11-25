package app.musicflow.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import app.musicflow.playback.service.CrossfadePlayer
import app.musicflow.playback.service.Crossfader
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class CrossfadeLogicTest {
    @Test fun startsOnlyInsideTheFadeWindow() {
        fun s(pos: Long, fade: Long = 5_000, dur: Long? = 200_000, next: Boolean = true, playing: Boolean = true, one: Boolean = false, pause: Boolean = false) =
            Crossfader.shouldStart(fade, dur, pos, playing, next, one, pause)
        assertThat(s(194_999)).isFalse()
        assertThat(s(195_000)).isTrue()
        assertThat(s(199_999)).isTrue()
        assertThat(s(196_000, fade = 0)).isFalse()          // disabled
        assertThat(s(196_000, next = false)).isFalse()      // last track
        assertThat(s(196_000, one = true)).isFalse()        // repeat one
        assertThat(s(196_000, pause = true)).isFalse()      // sleep timer end-of-track
        assertThat(s(196_000, playing = false)).isFalse()
        assertThat(s(196_000, dur = null)).isFalse()        // unknown length (live)
        assertThat(s(10_000, dur = 12_000)).isFalse()       // too short to crossfade
    }

    @Test fun equalPowerCurve() {
        val (o0, i0) = Crossfader.gains(0f); val (o1, i1) = Crossfader.gains(1f); val (oh, ih) = Crossfader.gains(0.5f)
        assertThat(o0).isWithin(1e-4f).of(1f); assertThat(i0).isWithin(1e-4f).of(0f)
        assertThat(o1).isWithin(1e-4f).of(0f); assertThat(i1).isWithin(1e-4f).of(1f)
        assertThat(oh * oh + ih * ih).isWithin(1e-4f).of(1f) // constant power
    }
}

/**
 * Simulated player: advances in fixed steps; becomes READY [readyDelayMs] after loading or
 * skipping; after play()/seek its position only advances once [latencyMs] of start-up latency
 * has passed (like an AudioTrack filling up).
 */
private class SimPlayer(
    val durations: List<Long>,
    private val readyDelayMs: Long = 100,
    startPlaying: Boolean = false,
    private val latencyMs: Long = 0,
) : CrossfadePlayer {
    var index = 0
    var pos = 0L
    var wantPlay = startPlaying
    var state = if (durations.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
    /** Set by tests to simulate a buffering stall until this sim time. */
    var stallUntil = -1L
    private var loadedAt = 0L
    private var simTime = 0L
    private var silentFor = 0L
    private var items: List<MediaItem> = durations.indices.map { MediaItem.Builder().setMediaId("t$it").build() }

    fun advance(ms: Long) {
        simTime += ms
        if (state == Player.STATE_BUFFERING && simTime - loadedAt >= readyDelayMs && simTime >= stallUntil) state = Player.STATE_READY
        if (state == Player.STATE_READY && simTime < stallUntil) state = Player.STATE_BUFFERING
        if (isPlaying) {
            if (silentFor > 0) { silentFor -= ms; return }
            pos += ms
            if (pos >= durations[index]) {
                if (index < durations.lastIndex) { index++; pos = 0 } else { pos = durations[index]; state = Player.STATE_ENDED }
            }
        }
    }

    override val duration get() = if (items.isEmpty()) C.TIME_UNSET else durations[index]
    override val position get() = pos
    override val isPlaying get() = wantPlay && state == Player.STATE_READY
    override val playWhenReady get() = wantPlay
    override val playbackState get() = state
    override val hasNext get() = index < items.lastIndex
    override val repeatOne = false
    override val pauseAtEnd = false
    override val currentItem get() = items.getOrNull(index)
    override val itemCount get() = items.size
    override val audioSessionId = 7
    override var volume = 1f
    override val playbackParameters: PlaybackParameters? = null
    override fun load(item: MediaItem, positionMs: Long, sessionId: Int, params: PlaybackParameters?) {
        items = listOf(item); index = 0; pos = positionMs; volume = 0f; wantPlay = false
        state = Player.STATE_BUFFERING; loadedAt = simTime
    }
    override fun seekTo(positionMs: Long) { pos = positionMs; silentFor = latencyMs }
    override fun play() { wantPlay = true; silentFor = latencyMs }
    override fun pause() { wantPlay = false }
    override fun stopAndClear() { wantPlay = false; items = emptyList(); state = Player.STATE_IDLE }
    override fun skipToNext() { index++; pos = 0; state = Player.STATE_BUFFERING; loadedAt = simTime }
    override fun release() = Unit
}

class CrossfadeSimulationTest {
    private val step = 50L

    private class Sim(val main: SimPlayer, val cf: Crossfader) {
        var fader: SimPlayer? = null
        var now = 0L
    }

    /** Runs the whole queue; returns true if there was always audible sound. */
    private fun run(
        faderReadyDelayMs: Long,
        fadeMs: Long = 3_000,
        nextStartDelayMs: Long = 100,
        faderLatencyMs: Long = 0,
        onStep: (Sim) -> Unit = {},
    ): Pair<Boolean, Crossfader> {
        val main = SimPlayer(listOf(20_000, 20_000, 20_000), readyDelayMs = nextStartDelayMs, startPlaying = true)
        lateinit var sim: Sim
        val cf = Crossfader(main, { SimPlayer(listOf(20_000), faderReadyDelayMs, latencyMs = faderLatencyMs).also { sim.fader = it } }, preloadMs = 6_000)
        sim = Sim(main, cf)
        cf.fadeMs = fadeMs
        var alwaysAudible = true
        while (main.playbackState != Player.STATE_ENDED && sim.now < 120_000) {
            sim.now += step
            main.advance(step); sim.fader?.advance(step)
            cf.tick()
            val mainAudible = main.isPlaying && main.volume > 0f
            val faderAudible = sim.fader?.let { it.isPlaying && it.volume > 0f } == true
            val queueFinished = main.playbackState == Player.STATE_ENDED
            if (!mainAudible && !faderAudible && !queueFinished) alwaysAudible = false
            onStep(sim)
        }
        return alwaysAudible to cf
    }

    @Test fun crossfadesWithoutEverGoingSilent() {
        var overlaps = 0
        var switchedEarly = 0
        val (audible, _) = run(faderReadyDelayMs = 300) { s ->
            if (s.cf.isFading && s.fader!!.isPlaying && s.main.isPlaying) overlaps++
            if (s.cf.consumeSelfTransition()) { switchedEarly++; assertThat(s.fader!!.position).isAtLeast(16_900) } // outgoing tail
        }
        assertThat(audible).isTrue()
        assertThat(switchedEarly).isEqualTo(2)        // both transitions were crossfaded
        assertThat(overlaps).isAtLeast(2 * 50)         // ≈ 2.7 s of real overlap each (50 ms steps)
    }

    /**
     * The reported bug: the next YouTube track needs ~2 s to start. The outgoing track must still
     * fade smoothly to silence at its own end instead of being cut off mid-fade.
     */
    @Test fun slowNextTrackNeverChopsTheOutgoingTrack() {
        var lastFaderVolume = 0f
        var maxStep = 0f
        var maxMainStep = 0f
        var lastMain = 1f
        var cutVolume = 0f
        var overlaps = 0
        var wasFading = false
        val (audible, _) = run(faderReadyDelayMs = 300, fadeMs = 6_000, nextStartDelayMs = 2_000) { s ->
            val f = s.fader ?: return@run
            // The first fading step is the hand-off itself (main muted, fader takes over the same audio).
            val ramping = s.cf.isFading && wasFading
            if (f.isPlaying) {
                if (ramping) maxStep = maxOf(maxStep, abs(lastFaderVolume - f.volume))
                lastFaderVolume = f.volume
            } else if (lastFaderVolume > 0f) { cutVolume = maxOf(cutVolume, lastFaderVolume); lastFaderVolume = 0f }
            if (ramping) maxMainStep = maxOf(maxMainStep, abs(s.main.volume - lastMain))
            if (s.cf.isFading && f.isPlaying && s.main.isPlaying) overlaps++
            lastMain = s.main.volume
            wasFading = s.cf.isFading
        }
        assertThat(audible).isTrue()
        assertThat(cutVolume).isLessThan(0.05f)   // outgoing was practically silent when it stopped
        assertThat(maxStep).isLessThan(0.08f)     // no jumps in the outgoing fade (per 50 ms)
        assertThat(maxMainStep).isLessThan(0.08f) // incoming fades in smoothly too
        assertThat(overlaps).isAtLeast(2 * 60)    // ≈ 3.8 s real overlap per transition despite the slow start
    }

    @Test fun bufferingStallDuringFadeDoesNotEndTheCrossfade() {
        var stalled = false
        var tailCut = false
        var lastFaderVolume = 0f
        val (audible, _) = run(faderReadyDelayMs = 300, fadeMs = 6_000) { s ->
            val f = s.fader ?: return@run
            if (s.cf.isFading && !stalled && s.main.isPlaying && s.main.position in 1_000..1_100) {
                stalled = true; s.main.stallUntil = s.now + 700
            }
            if (!f.isPlaying && lastFaderVolume > 0.05f) tailCut = true
            lastFaderVolume = if (f.isPlaying) f.volume else 0f
        }
        assertThat(stalled).isTrue()
        assertThat(audible).isTrue()
        assertThat(tailCut).isFalse()
    }

    @Test fun startupLatencyIsCompensatedBeforeTheSwitch() {
        var maxOffsetAtSwitch = Long.MAX_VALUE
        var switches = 0
        var mainPosBeforeSwitch = 0L
        val (audible, _) = run(faderReadyDelayMs = 300, fadeMs = 6_000, faderLatencyMs = 150) { s ->
            if (s.cf.consumeSelfTransition()) {
                switches++
                val offset = abs(mainPosBeforeSwitch + 50 - s.fader!!.position)
                maxOffsetAtSwitch = if (switches == 1) offset else maxOf(maxOffsetAtSwitch, offset)
            }
            mainPosBeforeSwitch = s.main.position
        }
        assertThat(audible).isTrue()
        assertThat(switches).isEqualTo(2)
        assertThat(maxOffsetAtSwitch).isAtMost(50) // the two copies of the outgoing track line up
    }

    @Test fun pauseDuringFadeStopsTheOutgoingTail() {
        var paused = false
        var faderPlayingWhilePaused = false
        run(faderReadyDelayMs = 300, fadeMs = 6_000) { s ->
            if (s.cf.isFading && !paused && s.main.isPlaying && s.main.position > 1_000) { paused = true; s.main.pause(); s.cf.onMainPlayingChanged(false) }
            else if (paused && !s.main.wantPlay) {
                if (s.fader?.isPlaying == true) faderPlayingWhilePaused = true
                s.main.play() // resume on the next step; the old tail must not come back
            }
        }
        assertThat(paused).isTrue()
        assertThat(faderPlayingWhilePaused).isFalse()
    }

    @Test fun slowFaderFallsBackToGaplessNeverSilence() {
        var crossfaded = 0
        val (audible, _) = run(faderReadyDelayMs = 60_000) { s -> if (s.cf.consumeSelfTransition()) crossfaded++ }
        assertThat(audible).isTrue()
        assertThat(crossfaded).isEqualTo(0)            // fell back to normal gapless transitions
    }

    @Test fun disabledCrossfadeNeverCreatesAFader() {
        var faderCreated = false
        val (audible, _) = run(faderReadyDelayMs = 100, fadeMs = 0) { s -> if (s.fader != null || s.cf.isActive) faderCreated = true }
        assertThat(audible).isTrue()
        assertThat(faderCreated).isFalse()
    }
}
