package app.musicflow.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.Track
import app.musicflow.playback.service.Crossfader
import app.musicflow.playback.stream.PlaybackDataSources
import app.musicflow.playback.stream.StreamRepository
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

/** Two real ExoPlayers through the MusicFlow pipeline: the outgoing track overlaps the next one. */
@RunWith(RobolectricTestRunner::class)
class CrossfaderPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val wav = PlaybackPipelineTest.wav(durationMs = 4_000)
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                return MockResponse().setResponseCode(if (start > 0) 206 else 200)
                    .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${wav.size - 1}/${wav.size}") }
                    .setBody(Buffer().write(wav.copyOfRange(start, wav.size)))
            }
        }
        start()
    }
    private val players = mutableListOf<ExoPlayer>()

    @After fun tearDown() { players.forEach { it.release() }; server.shutdown() }

    @Test fun overlapsOutgoingAndIncomingTracks() {
        val streams = StreamRepository({ id, _ ->
            ResolvedStream(id, server.url("/${id.key}.wav").toString(), "audio/wav", "pcm", null, 8000, 1, null, cacheKey = id.value)
        }, { AudioQuality.HIGH }, DiagnosticsLog())
        val factory = DefaultMediaSourceFactory(PlaybackDataSources.factory(context, OkHttpClient(), null, streams))
        fun newPlayer() = TestExoPlayerBuilder(context).setMediaSourceFactory(factory).build().also { players += it }
        val main = newPlayer()
        var now = 0L
        val crossfader = Crossfader(main, ::newPlayer, clock = { now }).apply { fadeMs = 1_000 }

        main.setMediaItems(listOf(1, 2).map { MediaItems.toMediaItem(Track(MediaId("yt:t$it"), "T$it", durationMs = 4_000)) })
        main.prepare(); main.play()
        TestPlayerRunHelper.run(main).untilPosition(0, 3_200)
        crossfader.tick()

        // Main jumped to the next track; the fader continues the outgoing one at full volume.
        assertThat(crossfader.isFading).isTrue()
        assertThat(crossfader.consumeSelfTransition()).isTrue()
        assertThat(main.currentMediaItemIndex).isEqualTo(1)
        assertThat(main.volume).isEqualTo(0f)
        val fader = crossfader.faderPlayer!!
        assertThat(fader.currentMediaItem!!.mediaId).isEqualTo("yt:t1")
        assertThat(fader.currentPosition).isAtLeast(3_000)
        assertThat(fader.playWhenReady).isTrue()

        TestPlayerRunHelper.run(main).untilState(Player.STATE_READY)
        TestPlayerRunHelper.run(main).untilPosition(1, 100)
        crossfader.tick()                     // ramp starts when the next track really plays
        now += 500; crossfader.tick()         // halfway: equal power ≈ 0.707 each
        assertThat(main.volume).isWithin(0.01f).of(0.707f)
        assertThat(fader.volume).isWithin(0.01f).of(0.707f)
        now += 600; crossfader.tick()         // done
        assertThat(crossfader.isFading).isFalse()
        assertThat(main.volume).isEqualTo(1f)
        assertThat(fader.mediaItemCount).isEqualTo(0)

        TestPlayerRunHelper.run(main).untilState(Player.STATE_ENDED)
        assertThat(main.playerError).isNull()
    }

    @Test fun pausingDuringFadeEndsIt() {
        val streams = StreamRepository({ id, _ ->
            ResolvedStream(id, server.url("/${id.key}.wav").toString(), "audio/wav", "pcm", null, 8000, 1, null, cacheKey = id.value)
        }, { AudioQuality.HIGH }, DiagnosticsLog())
        val factory = DefaultMediaSourceFactory(PlaybackDataSources.factory(context, OkHttpClient(), null, streams))
        fun newPlayer() = TestExoPlayerBuilder(context).setMediaSourceFactory(factory).build().also { players += it }
        val main = newPlayer()
        var now = 0L
        val crossfader = Crossfader(main, ::newPlayer, clock = { now }).apply { fadeMs = 1_000 }
        main.setMediaItems(listOf(1, 2).map { MediaItems.toMediaItem(Track(MediaId("yt:p$it"), "P$it")) })
        main.prepare(); main.play()
        TestPlayerRunHelper.run(main).untilPosition(0, 3_200)
        crossfader.tick()
        TestPlayerRunHelper.run(main).untilPosition(1, 100)
        crossfader.tick(); now += 200; crossfader.tick()
        crossfader.onMainPlayingChanged(false)
        assertThat(crossfader.isFading).isFalse()
        assertThat(main.volume).isEqualTo(1f)
    }
}
