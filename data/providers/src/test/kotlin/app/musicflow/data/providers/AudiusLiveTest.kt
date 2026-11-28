package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.audius.AudiusProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Hits the real Audius API. Opt-in:
 * `./gradlew :data:providers:testDebugUnitTest -PliveTests=true --tests '*AudiusLive*'` (CI: "Provider live tests").
 */
class AudiusLiveTest {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val provider = AudiusProvider(client, DiagnosticsLog())

    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")

    @Test fun searchArtistPlaylistAndAudio() = runBlocking {
        val tracks = provider.search("electronic", SearchFilter.SONGS, null).items.tracks()
        println("tracks: " + tracks.take(5).map { "${it.id} / ${it.title} / ${it.artistLine} / ${it.durationMs} / ${it.artworkUrl}" })
        assertThat(tracks).isNotEmpty()
        val first = tracks.first()
        assertThat(provider.track(first.id).title).isEqualTo(first.title)

        val artistId = app.musicflow.core.model.MediaId(first.artists.first().id!!)
        val artist = provider.artist(artistId)
        println("artist: ${artist.artist.name} tracks=${artist.topTracks.size}")
        assertThat(artist.topTracks).isNotEmpty()

        val lists = provider.search("chill", SearchFilter.PLAYLISTS, null).items
        println("playlists: " + lists.take(3).map { "${it.id} / ${it.title}" })
        assertThat(lists).isNotEmpty()

        // The stream address redirects to a content node that serves audio.
        val stream = provider.resolve(first.id, AudioQuality.BEST)
        client.newCall(Request.Builder().url(stream.url).header("Range", "bytes=0-4095").build()).execute().use { r ->
            println("audio: HTTP ${r.code} ${r.header("Content-Type")} from ${r.request.url.host}")
            assertThat(r.code).isAnyOf(200, 206)
            // Content nodes answer with application/octet-stream, so check the bytes: an MP3
            // (ID3 tag or MPEG frame sync), AAC/ADTS, MP4 or Ogg/FLAC container.
            val b = r.body!!.bytes()
            println("magic: " + b.take(12).joinToString(" ") { "%02x".format(it) })
            val head = String(b.copyOfRange(0, 12), Charsets.ISO_8859_1)
            val sync = (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
            assertThat(head.startsWith("ID3") || sync || head.substring(4, 8) == "ftyp" || head.startsWith("OggS") || head.startsWith("fLaC")).isTrue()
        }
    }
}
