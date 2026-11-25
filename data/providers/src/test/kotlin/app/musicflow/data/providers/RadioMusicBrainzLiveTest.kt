package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.musicbrainz.MusicBrainzProvider
import app.musicflow.data.providers.radio.RadioBrowserProvider
import app.musicflow.data.providers.youtube.YouTubeMusicProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Real Radio Browser and MusicBrainz requests. Opt-in (`-PliveTests=true`); run in CI via the live-tests workflow. */
class RadioMusicBrainzLiveTest {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val ua = "MusicFlow/live-test ( https://github.com/Baniak88/MusicFlow )"

    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")

    @Test fun radioSearchTopAndStream() = runBlocking {
        val radio = RadioBrowserProvider(client, DiagnosticsLog(), ua, countryCode = { "PL" })
        val found = radio.search("rmf", SearchFilter.ALL).items.tracks()
        println("radio search: " + found.take(5).map { "${it.id} ${it.title} / ${it.artistLine}" })
        assertThat(found).isNotEmpty()
        val top = radio.top("PL", 10)
        println("radio top PL: " + top.map { it.title })
        assertThat(top).isNotEmpty()
        val jazz = radio.byTag("jazz", 5)
        println("radio jazz: " + jazz.map { it.title })

        val stream = (top + found).take(10).firstNotNullOfOrNull { t ->
            runCatching { radio.resolve(t.id, AudioQuality.BEST) }.onFailure { println("skip ${t.title}: $it") }.getOrNull()
        }!!
        println("radio stream: ${stream.codec} ${stream.bitrateKbps} kbps hls=${stream.isHls} live=${stream.isLive} ${stream.url.take(80)}")
        assertThat(stream.isLive).isTrue()
        assertThat(stream.url).startsWith("https://")
        client.newCall(Request.Builder().url(stream.url).header("User-Agent", ua).header("Icy-MetaData", "1").build()).execute().use { r ->
            val bytes = r.body!!.source().readByteArray(4096L.coerceAtMost(Long.MAX_VALUE))
            println("radio audio: HTTP ${r.code} ${r.header("Content-Type")} icy-metaint=${r.header("icy-metaint")} first=${bytes.size} bytes")
            assertThat(r.code).isEqualTo(200)
            assertThat(bytes.size).isGreaterThan(1000)
        }
    }

    @Test fun musicBrainzSearchAlbumArtistAndFacts() = runBlocking {
        val mb = MusicBrainzProvider(client, DiagnosticsLog(), null, ua)
        val songs = mb.search("Bohemian Rhapsody Queen", SearchFilter.SONGS).items.tracks()
        println("mb songs: " + songs.take(5).map { "${it.id} ${it.title} / ${it.artistLine} / ${it.album?.title} / ${it.durationMs}" })
        assertThat(songs).isNotEmpty()
        val albums = mb.search("OK Computer", SearchFilter.ALBUMS).items.filterIsInstance<MediaItemSummary.AlbumItem>()
        println("mb albums: " + albums.take(3).map { "${it.album.id} ${it.album.title} / ${it.album.artistLine} / ${it.album.year}" })
        val album = mb.album(albums.first().album.id)
        println("mb tracklist: ${album.title} (${album.year}): " + album.tracks.map { it.title })
        assertThat(album.tracks.size).isAtLeast(8)
        val cover = client.newCall(Request.Builder().url(album.artworkUrl!!).header("User-Agent", ua).build()).execute().use { it.code }
        println("cover art: HTTP $cover ${album.artworkUrl}")

        val facts = mb.factsFor("Radiohead")!!
        println("mb facts: $facts")
        assertThat(facts.area).isNotNull()
        val artist = mb.artist(app.musicflow.core.model.MediaId.of("mba", facts.mbid))
        println("mb artist: ${artist.artist.name} albums=${artist.albums.map { it.title }.take(10)} singles=${artist.singles.size} | ${artist.description}")
        assertThat(artist.albums).isNotEmpty()
    }

    /** End to end: a MusicBrainz recording played through a reliable YouTube Music match. */
    @Test fun musicBrainzTrackPlaysViaYouTubeMatch() = runBlocking {
        val yt = YouTubeMusicProvider(client, DiagnosticsLog(), { Locale.US })
        val mb = MusicBrainzProvider(client, DiagnosticsLog(), object : MusicBrainzProvider.AudioMatcher {
            override val name = "yt"
            override suspend fun candidates(query: String) = yt.search(query, SearchFilter.SONGS).items.tracks().take(10)
            override suspend fun resolve(id: app.musicflow.core.model.MediaId, quality: AudioQuality) = yt.resolve(id, quality)
        }, ua)
        val t = mb.search("Karma Police Radiohead", SearchFilter.SONGS).items.tracks().first { (it.durationMs ?: 0) in 250_000..280_000 }
        val r = runCatching { mb.resolve(t.id, AudioQuality.HIGH) }
        println("mb→yt: ${t.title} / ${t.artistLine} / ${t.durationMs} → ${r.getOrNull()?.codec} ${r.getOrNull()?.bitrateKbps} kbps; error=${r.exceptionOrNull()}")
        // YouTube may rate-limit CI runners; a wrong match must never happen, a block is inconclusive.
        r.exceptionOrNull()?.let { assumeTrue("YouTube blocked this runner: $it", it.toString().contains("RateLimited").not()) }
        assertThat(r.isSuccess).isTrue()
    }
}
