package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.audius.AudiusParser
import app.musicflow.data.providers.audius.AudiusProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

/** Audius, parsed from handcrafted responses in the shape of its public `/v1` API. */
class AudiusTest {
    private fun res(name: String) = javaClass.getResource("/audius/$name")!!.readText()
    private val server = MockWebServer()

    @After fun stop() = server.shutdown()

    @Test fun tracksKeepOnlyPlayableOnesWithLargestArtwork() {
        val tracks = AudiusParser.tracks(AudiusParser.data(res("tracks_search.json")))
        assertThat(tracks.map { it.title }).containsExactly("Electronic Dreams", "Small Art Only").inOrder()
        val t = tracks.first()
        assertThat(t.id).isEqualTo(MediaId("au:D7KyD"))
        assertThat(t.artistLine).isEqualTo("Example Artist")
        assertThat(t.artists.first().id).isEqualTo("aua:nlGNe")
        assertThat(t.durationMs).isEqualTo(215_000)
        assertThat(t.artworkUrl).endsWith("1000x1000.jpg")
        assertThat(tracks[1].artworkUrl).endsWith("150x150.jpg")
    }

    @Test fun malformedResponseIsAParsingError() {
        assertThat(runCatching { AudiusParser.data("<html>") }.exceptionOrNull()).isInstanceOf(ProviderError.Parsing::class.java)
    }

    @Test fun searchNamesTheAppAndStreamIsHonestAboutFormat() = runBlocking {
        server.enqueue(MockResponse().setBody(res("tracks_search.json")))
        server.start()
        val provider = AudiusProvider(OkHttpClient(), DiagnosticsLog(), base = server.url("/v1/"))
        val items = provider.search(" electronic dreams ", SearchFilter.SONGS, null).items
        assertThat(items.filterIsInstance<MediaItemSummary.TrackItem>()).hasSize(2)
        val req = server.takeRequest().requestUrl!!
        assertThat(req.encodedPath).isEqualTo("/v1/tracks/search")
        assertThat(req.queryParameter("query")).isEqualTo("electronic dreams")
        assertThat(req.queryParameter("app_name")).isEqualTo("MusicFlow")

        val stream = provider.resolve(MediaId("au:D7KyD"), AudioQuality.BEST)
        assertThat(stream.url).endsWith("/v1/tracks/D7KyD/stream?app_name=MusicFlow")
        assertThat(stream.isLossless).isFalse()
        assertThat(stream.isHls).isFalse()
        assertThat(stream.bitrateKbps).isNull() // not reported by the API, so not shown
    }

    @Test fun errorsAreClassified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        val provider = AudiusProvider(OkHttpClient(), DiagnosticsLog(), base = server.url("/v1/"))
        assertThat(runCatching { provider.search("x", SearchFilter.SONGS, null) }.exceptionOrNull()).isInstanceOf(ProviderError.RateLimited::class.java)
        assertThat(runCatching { provider.track(MediaId("au:nope")) }.exceptionOrNull()).isInstanceOf(ProviderError.Unavailable::class.java)
    }
}
