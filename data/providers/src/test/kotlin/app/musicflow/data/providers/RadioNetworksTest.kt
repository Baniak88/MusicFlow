package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.radio.NetworkParsers
import app.musicflow.data.providers.radio.RadioBrowserProvider
import app.musicflow.data.providers.radio.RadioIds
import app.musicflow.data.providers.radio.RadioNetwork
import app.musicflow.data.providers.radio.RadioNetworks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** RMF ON / ESKA, parsed from copies of their pages saved on 2026-11-27. */
class RadioNetworksTest {
    private fun res(name: String) = javaClass.getResource("/radio/$name")!!.readText()

    @Test fun rmfOnListAndServers() {
        val list = NetworkParsers.rmfOn(res("rmfon_stations.json"))
        assertThat(list.map { it.name }).containsExactly("RMF FM", "RMF MAXX", "RMF Classic", "RADIO RMF24").inOrder()
        assertThat(list.first().streamUrl).isNull()
        assertThat(list.first().logoUrl).isEqualTo("https://www.rmfon.pl/img/z/s5.jpg")
        val urls = NetworkParsers.rmfStreams(res("rmfon_streams_9.xml"))
        assertThat(urls.first()).isEqualTo("https://rs201-krk.rmfstream.pl/rmf_polskie_przeboje") // MP3 128 kbps first
        assertThat(urls).contains("https://rs201-krk.rmfstream.pl/PPRZEBOJE") // AAC as fallback
        assertThat(urls).containsNoDuplicates()
    }

    @Test fun eskaChannels() {
        val list = NetworkParsers.eska(res("eska_player.html"))
        assertThat(list.map { it.name }).containsExactly("Radio ESKA", "ESKA TOY STORY Radio", "ESKA GORĄCA 20", "ESKA’00").inOrder()
        assertThat(list.first().id).isEqualTo("2980-1")
        assertThat(list.first().streamUrl).isEqualTo("https://liveradio.timesa.pl/2980-1.aac/playlist.m3u8")
        assertThat(list.first().logoUrl).startsWith("https://cdn.music.smcloud.net/")
    }

    @Test fun matching() {
        val all = NetworkParsers.rmfOn(res("rmfon_stations.json")) + NetworkParsers.eska(res("eska_player.html"))
        assertThat(NetworkParsers.match("rmf", all).map { it.network }.toSet()).containsExactly(RadioNetwork.RMF_ON)
        assertThat(NetworkParsers.match("RMF FM", all).first().name).isEqualTo("RMF FM")
        assertThat(NetworkParsers.match("radio eska", all).map { it.name }).contains("ESKA GORĄCA 20")
        assertThat(NetworkParsers.match("eska gorąca", all).map { it.name }).containsExactly("ESKA GORĄCA 20")
        assertThat(NetworkParsers.match("radio fama kielce", all)).isEmpty()
    }

    @Test fun mediaIdsRoundTrip() {
        val s = NetworkParsers.eska(res("eska_player.html")).first()
        val id = RadioNetworks.mediaId(s)
        assertThat(id.value).isEqualTo("radio:hls/net/eska/2980-1")
        assertThat(RadioIds.isHls(id)).isTrue()
        assertThat(RadioNetworks.parse(id)).isEqualTo(RadioNetwork.ESKA to "2980-1")
        assertThat(RadioNetworks.parse(MediaId("radio:96a5d4f2-0000"))).isNull()
        assertThat(RadioNetworks.toTrack(s).artistLine).isEqualTo("ESKA")
    }

    /** Serves the saved pages for the networks' hosts and an empty Radio Browser. */
    private val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url
        val body = when {
            url.host == "player.eska.pl" -> res("eska_player.html")
            url.encodedPath == "/json/stations.txt" -> res("rmfon_stations.json")
            url.encodedPath.startsWith("/stacje/flash_aac_") -> res("rmfon_streams_9.xml")
            url.host.endsWith("radio-browser.info") -> "[]"
            else -> null
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200).message("x")
            .body((body ?: "").toResponseBody("text/plain".toMediaType())).build()
    }).build()

    @Test fun radioSearchAndPlaybackIncludeNetworks() = runBlocking {
        val provider = RadioBrowserProvider(client, DiagnosticsLog(), networks = RadioNetworks(client, DiagnosticsLog()))
        val found = provider.search("rmf fm", SearchFilter.ALL, null).items.filterIsInstance<MediaItemSummary.TrackItem>().map { it.track }
        assertThat(found.first().title).isEqualTo("RMF FM")
        val stream = provider.resolve(found.first().id, AudioQuality.HIGH)
        assertThat(stream.url).isEqualTo("https://rs201-krk.rmfstream.pl/rmf_polskie_przeboje")
        assertThat(stream.isLive).isTrue()
        assertThat(stream.isLossless).isFalse()

        val eska = provider.network(RadioNetwork.ESKA)
        assertThat(eska).hasSize(4)
        val hls = provider.resolve(eska.first().id, AudioQuality.HIGH)
        assertThat(hls.isHls).isTrue()
        assertThat(provider.track(eska.first().id).title).isEqualTo("Radio ESKA")
    }
}

class RadioNowPlayingTest {
    private fun res(name: String) = javaClass.getResource("/radio/$name")!!.readText()

    @Test fun rmfOnWithCover() {
        val np = NetworkParsers.rmfNowPlaying(res("rmfon_playing.json"), "5")!!
        assertThat(np.title).isEqualTo("Everlasting Love")
        assertThat(np.artist).isEqualTo("Sandra")
        assertThat(np.artworkUrl).isEqualTo("https://i.static.rmf.pl/82/512_512_plyta_5587.jpg")
    }

    @Test fun eskaCurrentOrNothing() {
        val np = NetworkParsers.eskaNowPlaying(res("eska_now_2980.json"))!!
        assertThat(np.title).isEqualTo("Daj Mi Znać")
        assertThat(np.artist).isEqualTo("Sobel & Michał Szczygieł")
        assertThat(np.artworkUrl).isNull() // empty image: keep the station logo
        assertThat(NetworkParsers.eskaNowPlaying(res("eska_now_6130.json"))).isNull() // between songs
        assertThat(NetworkParsers.eskaNowPlaying("not json")).isNull()
    }
}
