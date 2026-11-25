package app.musicflow.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.radio.RadioBrowserProvider
import app.musicflow.data.providers.soundcloud.SoundCloudProvider
import app.musicflow.playback.stream.PlaybackDataSources
import app.musicflow.playback.stream.StreamErrorPolicy
import app.musicflow.playback.stream.StreamRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Real SoundCloud / radio audio through MusicFlow's exact playback pipeline (resolution →
 * routing → disk cache → HLS / progressive → extractor → decoder) on ExoPlayer under Robolectric.
 * Opt-in (`-PliveTests=true`); run on GitHub with the "Provider live tests" workflow.
 */
@RunWith(RobolectricTestRunner::class)
class LivePlaybackTest {
    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(app.musicflow.core.network.CleartextGuard()).build()
    private var player: ExoPlayer? = null

    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")
    @After fun tearDown() { player?.release() }

    private fun play(streams: StreamRepository, track: app.musicflow.core.model.Track, untilMs: Long): ExoPlayer {
        val cache = PlaybackDataSources.createCache(context, 50L * 1024 * 1024)
        val factory = PlaybackDataSources.factory(context, client, cache, streams)
        // Real time: network downloads are real, so a fake auto-advancing clock would race them.
        val p = TestExoPlayerBuilder(context)
            .setClock(androidx.media3.common.util.Clock.DEFAULT)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory).setLoadErrorHandlingPolicy(StreamErrorPolicy(streams, DiagnosticsLog()) { true }))
            .build().also { player = it }
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadCompleted(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo, m: androidx.media3.exoplayer.source.MediaLoadData) =
                println("  load ok: type=${m.dataType} ${l.bytesLoaded} B ${l.uri.toString().take(110)}")
            override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo, m: androidx.media3.exoplayer.source.MediaLoadData, e: java.io.IOException, c: Boolean) =
                println("  load ERROR: ${l.uri.toString().take(110)} $e cause=${e.cause}")
            override fun onPlaybackStateChanged(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) = println("  state=$state at ${p.currentPosition} ms")
        })
        p.setMediaItem(MediaItems.toMediaItem(track))
        p.prepare(); p.play()
        val deadline = System.currentTimeMillis() + 60_000
        while (p.playbackState != Player.STATE_READY && p.playerError == null && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50)
        }
        assertThat(p.playerError).isNull()
        assertThat(p.playbackState).isEqualTo(Player.STATE_READY)
        println("ready: ${track.title} duration=${p.duration} format=${p.audioFormat?.sampleMimeType} ${p.audioFormat?.sampleRate} Hz ch=${p.audioFormat?.channelCount}")
        val playDeadline = System.currentTimeMillis() + untilMs + 30_000
        while (p.currentPosition < untilMs && p.playerError == null && System.currentTimeMillis() < playDeadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50)
        }
        println("played to ${p.currentPosition} ms, error=${p.playerError}")
        return p
    }

    @Test fun soundCloudTrackPlaysThroughThePipeline() = runBlocking {
        val sc = SoundCloudProvider(client, DiagnosticsLog(), { Locale.US })
        val track = sc.search("lofi hip hop", SearchFilter.SONGS).items.tracks().first { t ->
            runCatching { sc.resolve(t.id, AudioQuality.BEST) }.isSuccess
        }
        val streams = StreamRepository({ id, q -> sc.resolve(id, q) }, { AudioQuality.BEST }, DiagnosticsLog())
        val p = play(streams, track, 8_000)
        assertThat(p.playerError).isNull()
        assertThat(p.currentPosition).isAtLeast(8_000)
    }

    @Test fun httpRadioStationPlaysThroughThePipeline() = runBlocking {
        val radio = RadioBrowserProvider(client, DiagnosticsLog(), "MusicFlow/live-test", countryCode = { "PL" })
        val station = radio.search("radio fama kielce", SearchFilter.ALL).items.tracks().first()
        val streams = StreamRepository({ id, q -> radio.resolve(id, q) }, { AudioQuality.BEST }, DiagnosticsLog())
        val p = play(streams, station, 5_000)
        assertThat(p.playerError).isNull()
    }
}
