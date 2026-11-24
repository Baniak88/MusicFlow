package app.musicflow.data.providers

import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.Track
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.lyrics.LrcLibLyricsProvider
import app.musicflow.data.providers.lyrics.LrcParser
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class OtherProvidersTest {
    @Test fun lrcParserHandlesMultipleStampsAndFractions() {
        val lines = LrcParser.parse("[ar:x]\n[00:12.5]Hello\n[01:02.34][00:05.123]Chorus\nno stamp")
        assertThat(lines.map { it.timeMs }).containsExactly(5_123L, 12_500L, 62_340L).inOrder()
        assertThat(lines.first().text).isEqualTo("Chorus")
    }

    @Test fun lrcLibParsesResponsesAnd404() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":1,"plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a\n[00:02.00]b","instrumental":false}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("not json"))
            val p = LrcLibLyricsProvider(OkHttpClient(), server.url("/"))
            val track = Track(MediaId("yt:x"), "Song", listOf(ArtistRef(null, "Artist")), durationMs = 200_000)
            val l = p.lyrics(track)!!
            assertThat(l.isSynced).isTrue()
            assertThat(l.synced!!.size).isEqualTo(2)
            val req = server.takeRequest()
            assertThat(req.requestUrl!!.queryParameter("duration")).isEqualTo("200")
            assertThat(req.requestUrl!!.queryParameter("artist_name")).isEqualTo("Artist")
            assertThat(p.lyrics(track)).isNull()
            assertThat(runCatching { p.lyrics(track) }.exceptionOrNull()).isInstanceOf(ProviderError.Parsing::class.java)
        }
    }

    @Test fun directUrlRequiresHttpsAndReportsOnlyKnownQuality() = runTest {
        val p = DirectUrlProvider()
        val flac = p.resolve(DirectUrlProvider.idFor("https://example.com/a.flac"), AudioQuality.BEST)
        assertThat(flac.isLossless).isTrue()
        assertThat(flac.bitrateKbps).isNull()
        val mp3 = p.resolve(DirectUrlProvider.idFor("https://example.com/a.mp3?x=1"), AudioQuality.BEST)
        assertThat(mp3.isLossless).isFalse()
        assertThat(mp3.mimeType).isEqualTo("audio/mpeg")
        assertThat(runCatching { p.resolve(DirectUrlProvider.idFor("http://example.com/a.mp3"), AudioQuality.BEST) }.exceptionOrNull())
            .isInstanceOf(ProviderError.NotSupported::class.java)
    }
}
