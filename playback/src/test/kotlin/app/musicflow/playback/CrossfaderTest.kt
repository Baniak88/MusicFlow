package app.musicflow.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import app.musicflow.playback.service.CrossfadePlayer
import app.musicflow.playback.service.Crossfader
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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

/** Simulated player: advances in fixed steps; becomes READY [readyDelayMs] after loading. */
private class SimPlayer(val durations: List<Long>, private val readyDelayMs: Long = 100, startPlaying: Boolean = false) : CrossfadePlayer {
    var index = 0
    var pos = 0L
    var wantPlay = startPlaying
    var state = if (durations.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
    private var loadedAt = 0L
    private var simTime = 0L
    private var items: List<MediaItem> = durations.indices.map { MediaItem.Builder().setMediaId("t$it").build() }

    fun advance(ms: Long) {
        simTime += ms
        if (state == Player.STATE_BUFFERING && simTime - loadedAt >= readyDelayMs) state = Player.STATE_READY
        if (isPlaying) {
            pos += ms
            if (pos >= durations[index]) {
                if (index < durations.lastIndex) { index++; pos = 0 } else { pos = durations[index]; state = Player.STATE_ENDED }
            }
        }
    }

    override val duration get() = if (items.isEmpty()) C.TIME_UNSET else durations[index]
    override val position get() = pos
    override val isPlaying get() = wantPlay && state == Player.STATE_READY
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
    override fun seekTo(positionMs: Long) { pos = positionMs }
    override fun play() { wantPlay = true }
    override fun pause() { wantPlay = false }
    override fun stopAndClear() { wantPlay = false; items = emptyList(); state = Player.STATE_IDLE }
    override fun skipToNext() { index++; pos = 0 }
    override fun release() = Unit
}

class CrossfadeSimulationTest {
    private val step = 50L

    /** Runs the whole queue; returns true if there was always audible sound. */
    private fun run(faderReadyDelayMs: Long, fadeMs: Long = 3_000, onStep: (Long, Crossfader, SimPlayer, SimPlayer?) -> Unit = { _, _, _, _ -> }): Pair<Boolean, Crossfader> {
        val main = SimPlayer(listOf(20_000, 20_000, 20_000), startPlaying = true)
        var fader: SimPlayer? = null
        var now = 0L
        val cf = Crossfader(main, { SimPlayer(listOf(20_000), faderReadyDelayMs).also { fader = it } }, clock = { now }, preloadMs = 6_000)
        cf.fadeMs = fadeMs
        var alwaysAudible = true
        while (main.playbackState != Player.STATE_ENDED && now < 120_000) {
            now += step
            main.advance(step); fader?.advance(step)
            cf.tick()
            val mainAudible = main.isPlaying && main.volume > 0f
            val faderAudible = fader?.let { it.isPlaying && it.volume > 0f } == true
            val queueFinished = main.playbackState == Player.STATE_ENDED
            if (!mainAudible && !faderAudible && !queueFinished) alwaysAudible = false
            onStep(now, cf, main, fader)
        }
        return alwaysAudible to cf
    }

    @Test fun crossfadesWithoutEverGoingSilent() {
        var overlaps = 0
        var switchedEarly = 0
        val (audible, _) = run(faderReadyDelayMs = 300) { _, cf, main, fader ->
            if (cf.isFading && fader!!.isPlaying && main.isPlaying) overlaps++
            if (cf.consumeSelfTransition()) { switchedEarly++; assertThat(fader!!.position).isAtLeast(16_900) } // outgoing tail
        }
        assertThat(audible).isTrue()
        assertThat(switchedEarly).isEqualTo(2)        // both transitions were crossfaded
        assertThat(overlaps).isAtLeast(2 * 40)         // ≈ 3 s of real overlap each (50 ms steps)
    }

    @Test fun slowFaderFallsBackToGaplessNeverSilence() {
        var crossfaded = 0
        val (audible, _) = run(faderReadyDelayMs = 60_000) { _, cf, _, _ -> if (cf.consumeSelfTransition()) crossfaded++ }
        assertThat(audible).isTrue()
        assertThat(crossfaded).isEqualTo(0)            // fell back to normal gapless transitions
    }

    @Test fun disabledCrossfadeNeverCreatesAFader() {
        var faderCreated = false
        val (audible, _) = run(faderReadyDelayMs = 100, fadeMs = 0) { _, cf, _, f -> if (f != null || cf.isActive) faderCreated = true }
        assertThat(audible).isTrue()
        assertThat(faderCreated).isFalse()
    }
}
