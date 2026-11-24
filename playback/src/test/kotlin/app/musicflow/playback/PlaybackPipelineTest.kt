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
import app.musicflow.playback.stream.PlaybackDataSources
import app.musicflow.playback.stream.StreamErrorPolicy
import app.musicflow.playback.stream.StreamRepository
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end playback through the real MusicFlow pipeline on ExoPlayer (Robolectric):
 * placeholder URI → lazy stream resolution → routing → disk cache → HTTP → decoder.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private val wav = wav(durationMs = 600)
    private val expiredHits = AtomicInteger()
    private lateinit var player: ExoPlayer

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/expired") -> { expiredHits.incrementAndGet(); MockResponse().setResponseCode(403) }
                    request.path!!.startsWith("/gone") -> MockResponse().setResponseCode(404)
                    else -> serveRange(request)
                }
            }
            start()
        }
    }

    @After fun tearDown() { if (::player.isInitialized) player.release(); server.shutdown() }

    private fun serveRange(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
        val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
        val body = wav.copyOfRange(start, wav.size)
        return MockResponse().setResponseCode(if (range != null) 206 else 200)
            .setHeader("Content-Type", "audio/wav")
            .apply { if (range != null) setHeader("Content-Range", "bytes $start-${wav.size - 1}/${wav.size}") }
            .setBody(Buffer().write(body))
    }

    private fun stream(id: MediaId, path: String) = ResolvedStream(
        id, server.url(path).toString(), "audio/wav", "pcm", null, 8000, 1, expiresAtMillis = null, cacheKey = "$id:$path",
    )

    private fun buildPlayer(streams: StreamRepository): ExoPlayer {
        val factory = PlaybackDataSources.factory(context, OkHttpClient(), cache = null, streams = streams)
        val policy = StreamErrorPolicy(streams, DiagnosticsLog()) { true }
        return TestExoPlayerBuilder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory).setLoadErrorHandlingPolicy(policy))
            .build()
    }

    private fun track(n: Int) = Track(MediaId("yt:track$n"), "Track $n", durationMs = 600)

    @Test fun playsQueueToTheEndResolvingEachItemLazily() {
        val resolved = mutableListOf<String>()
        val streams = StreamRepository({ id, _ -> resolved += id.value; stream(id, "/audio/${id.key}.wav") }, { AudioQuality.HIGH }, DiagnosticsLog())
        player = buildPlayer(streams)
        player.setMediaItems(listOf(track(1), track(2), track(3)).map(MediaItems::toMediaItem))
        player.prepare()
        player.play()
        TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
        assertThat(player.playerError).isNull()
        assertThat(resolved).containsExactly("yt:track1", "yt:track2", "yt:track3").inOrder()
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
    }

    @Test fun expiredStreamUrlIsRenewedTransparently() {
        var calls = 0
        val streams = StreamRepository({ id, _ -> calls++; if (calls == 1) stream(id, "/expired.wav") else stream(id, "/fresh.wav") }, { AudioQuality.HIGH }, DiagnosticsLog())
        player = buildPlayer(streams)
        player.setMediaItem(MediaItems.toMediaItem(track(1)))
        player.prepare(); player.play()
        // The 403 is reported as a non-fatal load error; playback must still complete.
        TestPlayerRunHelper.run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED)
        assertThat(player.playerError).isNull()
        assertThat(expiredHits.get()).isAtLeast(1)
        assertThat(calls).isEqualTo(2)
    }

    @Test fun permanentErrorStopsWithoutEndlessRetries() {
        var calls = 0
        val streams = StreamRepository({ id, _ -> calls++; stream(id, "/gone.wav") }, { AudioQuality.HIGH }, DiagnosticsLog())
        player = buildPlayer(streams)
        player.setMediaItem(MediaItems.toMediaItem(track(1)))
        player.prepare(); player.play()
        val error = TestPlayerRunHelper.run(player).untilPlayerError()
        assertThat(error).isNotNull()
        assertThat(calls).isEqualTo(1)
    }

    @Test fun queueOperationsShuffleAndRepeat() {
        val streams = StreamRepository({ id, _ -> stream(id, "/audio/${id.key}.wav") }, { AudioQuality.HIGH }, DiagnosticsLog())
        player = buildPlayer(streams)
        player.setMediaItems((1..5).map { MediaItems.toMediaItem(track(it)) })
        player.prepare()
        player.moveMediaItem(4, 0)
        assertThat(player.getMediaItemAt(0).mediaId).isEqualTo("yt:track5")
        player.removeMediaItem(1)
        assertThat((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            .containsExactly("yt:track5", "yt:track2", "yt:track3", "yt:track4").inOrder()
        // track1 was current; removing it makes track2 (index 1) current.
        assertThat(player.currentMediaItem!!.mediaId).isEqualTo("yt:track2")
        player.seekToNextMediaItem()
        assertThat(player.currentMediaItemIndex).isEqualTo(2)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.seekToDefaultPosition(3)
        assertThat(player.hasNextMediaItem()).isTrue() // wraps around
        player.repeatMode = Player.REPEAT_MODE_OFF
        assertThat(player.hasNextMediaItem()).isFalse()
        player.shuffleModeEnabled = true
        val order = generateSequence(player.currentTimeline.getFirstWindowIndex(true)) { i ->
            player.currentTimeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true).takeIf { it != androidx.media3.common.C.INDEX_UNSET }
        }.toList()
        assertThat(order.sorted()).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test fun mediaItemRoundTripKeepsTrackMetadata() {
        val t = Track(MediaId("yt:x"), "Title", listOf(app.musicflow.core.model.ArtistRef("ytch:a", "Artist")), app.musicflow.core.model.AlbumRef("ytpl:b", "Album"), 123_000, "https://img")
        val item = MediaItems.toMediaItem(t)
        assertThat(MediaItems.toTrack(item)).isEqualTo(t)
        // Over IPC the URI is stripped; ensurePlayable restores it.
        val stripped = item.buildUpon().setUri(null as android.net.Uri?).build()
        assertThat(MediaItems.ensurePlayable(stripped)!!.localConfiguration!!.uri.scheme).isEqualTo("musicflow")
        assertThat(MediaItems.mediaIdFromUri(item.localConfiguration!!.uri)).isEqualTo(t.id)
    }

    companion object {
        fun wav(durationMs: Int, sampleRate: Int = 8000): ByteArray {
            val samples = sampleRate * durationMs / 1000
            val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(samples) { i -> data.putShort((Math.sin(2 * Math.PI * 440 * i / sampleRate) * 8000).toInt().toShort()) }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples * 2)
            }
            return header.array() + data.array()
        }
    }
}
