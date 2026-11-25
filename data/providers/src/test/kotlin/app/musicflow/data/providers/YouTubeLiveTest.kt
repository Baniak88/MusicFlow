package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.youtube.YouTubeMusicProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hits the real YouTube Music endpoints through NewPipeExtractor.
 * Opt-in: `./gradlew :data:providers:testDebugUnitTest -PliveTests=true`.
 */
class YouTubeLiveTest {
    private val provider = YouTubeMusicProvider(
        OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build(), DiagnosticsLog(), { Locale.US },
    )

    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")

    @Test fun searchSongsAndPaginate() = runBlocking {
        val page = provider.search("daft punk one more time", SearchFilter.SONGS)
        val tracks = page.items.tracks()
        println("songs: " + tracks.take(5).map { "${it.title} / ${it.artistLine} / ${it.durationMs} / ${it.artworkUrl}" })
        assertThat(tracks).isNotEmpty()
        assertThat(tracks.first().artistLine).isNotEmpty()
        assertThat(page.nextPageToken).isNotNull()
        val next = provider.search(page.query, SearchFilter.SONGS, page.nextPageToken)
        println("page2: ${next.items.size}")
        assertThat(next.items).isNotEmpty()
    }

    @Test fun searchAlbumsArtistsPlaylistsAndAll() = runBlocking {
        val albums = provider.search("random access memories", SearchFilter.ALBUMS).items
        // Licensed albums are withheld by YouTube Music on some networks (e.g. data-centre IPs).
        println("albums: " + albums.take(3) + if (albums.isEmpty()) " (region-withheld)" else "")
        val artists = provider.search("daft punk", SearchFilter.ARTISTS).items
        println("artists: " + artists.take(3))
        assertThat(artists.filterIsInstance<MediaItemSummary.ArtistItem>()).isNotEmpty()
        val playlists = provider.search("techno", SearchFilter.PLAYLISTS).items
        println("playlists: " + playlists.take(3))
        assertThat(playlists.filterIsInstance<MediaItemSummary.PlaylistItem>()).isNotEmpty()
        val all = provider.search("avicii", SearchFilter.ALL).items
        println("all: " + all.map { it::class.simpleName })
        assertThat(all.tracks()).isNotEmpty()
    }

    @Test fun albumAndArtistPages() = runBlocking {
        val album = provider.search("discovery daft punk", SearchFilter.ALBUMS).items
            .filterIsInstance<MediaItemSummary.AlbumItem>().firstOrNull()?.album
        val playlist = provider.search("daft punk discovery", SearchFilter.PLAYLISTS).items
            .filterIsInstance<MediaItemSummary.PlaylistItem>().first().playlist
        val page = provider.album(album?.id ?: playlist.id)
        println("album ${page.title} by ${page.subtitle}: ${page.tracks.size} tracks, art=${page.artworkUrl}")
        assertThat(page.tracks).isNotEmpty()
        val artist = provider.search("daft punk", SearchFilter.ARTISTS).items
            .filterIsInstance<MediaItemSummary.ArtistItem>().first().artist
        val ap = provider.artist(artist.id)
        println("artist ${ap.artist.name}: top=${ap.topTracks.size} albums=${ap.albums.size} singles=${ap.singles.size}")
        assertThat(ap.topTracks.size + ap.albums.size + ap.singles.size).isGreaterThan(0)
    }

    @Test fun resolvesAudioStream() = runBlocking {
        val track = provider.search("daft punk around the world", SearchFilter.SONGS).items.tracks().first()
        val stream = provider.resolve(track.id, AudioQuality.HIGH)
        println("stream ${stream.describe()} mime=${stream.mimeType} chunked=${stream.requiresChunkedPost} expires=${stream.expiresAtMillis} len=${stream.contentLength}")
        assertThat(stream.url).startsWith("https://")
        assertThat(stream.isLossless).isFalse()
        assertThat(stream.expiresAtMillis).isGreaterThan(System.currentTimeMillis())
        val low = provider.resolve(track.id, AudioQuality.DATA_SAVER)
        println("low ${low.describe()}")
        assertThat(stream.bitrateKbps).isGreaterThan(64)
        assertThat(low.bitrateKbps).isAtMost(64)
    }

    @Test fun relatedAndTrending() = runBlocking {
        val seed = provider.search("avicii levels", SearchFilter.SONGS).items.tracks().first()
        val related = provider.related(seed.id, 20)
        println("related: " + related.take(5).map { "${it.title} / ${it.artistLine}" })
        assertThat(related).isNotEmpty()
        val trending = runCatching { provider.trending() }
        println("trending: ${trending.map { it.size }} ${trending.exceptionOrNull()}")
    }

    @Test fun findsUploadsMissingFromTheCatalogue() = runBlocking {
        // Reported by a user: fan upload of "D-Bomb - Powiedz mi co czujesz" was not found.
        val songs = provider.search("D-Bomb Powiedz mi co czujesz", SearchFilter.SONGS).items.tracks()
        println("upload search: " + songs.take(3).map { it.title })
        assertThat(songs.map { it.id.key }).contains("I5uH5-JHh3k")
        val viaLink = provider.search("https://youtu.be/I5uH5-JHh3k?is=kPN3R9CeGB_FzP6t", SearchFilter.ALL).items.tracks()
        assertThat(viaLink.single().id.key).isEqualTo("I5uH5-JHh3k")
        val stream = provider.resolve(viaLink.single().id, AudioQuality.BEST)
        println("upload stream: ${stream.describe()}")
        assertThat(stream.bitrateKbps).isAtLeast(128)
    }

    @Test fun suggestions() = runBlocking {
        val s = runCatching { provider.suggestions("daft pu") }
        println("suggestions: $s")
    }
}
