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

    /** MusicBrainz throttles busy cloud IPs (CI runners): that is inconclusive, not a failure. */
    private fun mbOrSkip(block: suspend () -> Unit) = runBlocking {
        runCatching { block() }.onFailure { e ->
            assumeTrue("MusicBrainz throttled this runner: $e", e !is app.musicflow.core.model.ProviderError.RateLimited)
            throw e
        }
        Unit
    }

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

    /** Reported: "Radio Fama Kielce" was not found (HTTP-only station, city in the region field). */
    @Test fun findsAndPlaysRadioFamaKielce() = runBlocking {
        val guarded = client.newBuilder().addNetworkInterceptor(app.musicflow.core.network.CleartextGuard()).build()
        val radio = RadioBrowserProvider(guarded, DiagnosticsLog(), ua, countryCode = { "PL" })
        val found = radio.search("Radio Fama Kielce", SearchFilter.ALL).items.tracks()
        println("fama search: " + found.take(5).map { "${it.title} / ${it.artistLine}" })
        assertThat(found.first().title).ignoringCase().contains("fama")
        assertThat(found.first().artistLine).contains("Kielce")
        val stream = radio.resolve(found.first().id, AudioQuality.BEST)
        println("fama stream: ${stream.url} cleartext=${stream.allowCleartext} ${stream.codec} ${stream.bitrateKbps}")
        val marker = if (stream.allowCleartext) mapOf(app.musicflow.core.network.CleartextGuard.ALLOW_HEADER to "radio") else emptyMap()
        guarded.newCall(Request.Builder().url(stream.url).headers(okhttp3.Headers.headersOf(*(stream.requestHeaders + marker).flatMap { listOf(it.key, it.value) }.toTypedArray())).build()).execute().use { r ->
            val bytes = r.body!!.source().readByteArray(4096)
            println("fama audio: HTTP ${r.code} ${r.header("Content-Type")} ${bytes.size} bytes")
            assertThat(bytes.size).isGreaterThan(1000)
        }
    }

    /** Reported: Sunshine Live and Danceradio (CZ) "do not work". */
    @Test fun findsAndPlaysSunshineLiveAndDanceRadio() = runBlocking {
        val guarded = client.newBuilder().addNetworkInterceptor(app.musicflow.core.network.CleartextGuard()).build()
        val radio = RadioBrowserProvider(guarded, DiagnosticsLog(), ua, countryCode = { "PL" })
        for ((query, expect) in listOf("sunshine live" to "sunshine live", "danceradio cz" to "dance radio")) {
            val found = radio.search(query, SearchFilter.ALL).items.tracks()
            println("$query: " + found.take(6).map { "${it.title} / ${it.artistLine}" })
            val hit = found.first()
            assertThat(app.musicflow.core.common.TextMatching.normalize(hit.title)).contains(expect)
            val stream = radio.resolve(hit.id, AudioQuality.BEST)
            val marker = if (stream.allowCleartext) mapOf(app.musicflow.core.network.CleartextGuard.ALLOW_HEADER to "radio") else emptyMap()
            guarded.newCall(Request.Builder().url(stream.url).headers(okhttp3.Headers.headersOf(*(stream.requestHeaders + marker).flatMap { listOf(it.key, it.value) }.toTypedArray())).build()).execute().use { r ->
                val bytes = r.body!!.source().readByteArray(4096)
                println("$query plays: ${hit.title} ${stream.codec} ${stream.bitrateKbps} → HTTP ${r.code} ${r.header("Content-Type")} ${bytes.size} bytes")
                assertThat(r.header("Content-Type")).startsWith("audio/")
            }
        }
    }

    @Test fun musicBrainzSearchAlbumArtistAndFacts() = mbOrSkip {
        val mb = MusicBrainzProvider(client, DiagnosticsLog(), null, ua)
        val songs = mb.search("Bohemian Rhapsody Queen", SearchFilter.SONGS).items.tracks()
        println("mb songs: " + songs.take(5).map { "${it.id} ${it.title} / ${it.artistLine} / ${it.album?.title} / ${it.durationMs}" })
        assertThat(songs.first().artistLine).isEqualTo("Queen")
        val albums = mb.search("OK Computer", SearchFilter.ALBUMS).items.filterIsInstance<MediaItemSummary.AlbumItem>()
        assertThat(albums.first().album.artistLine).isEqualTo("Radiohead") // the famous one, not a namesake
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
    @Test fun musicBrainzTrackPlaysViaYouTubeMatch() = mbOrSkip {
        val yt = YouTubeMusicProvider(client, DiagnosticsLog(), { Locale.US })
        val mb = MusicBrainzProvider(client, DiagnosticsLog(), object : app.musicflow.data.providers.api.AudioMatcher {
            override val name = "yt"
            override suspend fun candidates(query: String) = yt.search(query, SearchFilter.SONGS).items.tracks().take(10)
            override suspend fun resolve(id: app.musicflow.core.model.MediaId, quality: AudioQuality) = yt.resolve(id, quality)
        }, ua)
        val list = mb.search("Karma Police Radiohead", SearchFilter.SONGS).items.tracks()
        println("mb karma: " + list.take(6).map { "${it.title} / ${it.artistLine} / ${it.album?.title} / ${it.durationMs}" })
        val t = list.first()
        assertThat(t.artistLine).isEqualTo("Radiohead")
        val r = runCatching { mb.resolve(t.id, AudioQuality.HIGH) }
        println("mb→yt: ${t.title} / ${t.artistLine} / ${t.durationMs} → ${r.getOrNull()?.codec} ${r.getOrNull()?.bitrateKbps} kbps; error=${r.exceptionOrNull()}")
        // YouTube may rate-limit CI runners; a wrong match must never happen, a block is inconclusive.
        r.exceptionOrNull()?.let { assumeTrue("Throttled on this runner: $it", it.toString().contains("RateLimited").not()) }
        assertThat(r.isSuccess).isTrue()
    }
}

/** Diagnosis for a reported miss: "Radio Fama Kielce". Prints what the directory holds. */
class RadioSearchDiagnosisLiveTest {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")

    @Test fun printDirectoryEntries() {
        fun q(params: String): String = client.newCall(Request.Builder().url("https://de1.api.radio-browser.info/json/stations/search?limit=30&$params")
            .header("User-Agent", "MusicFlow/diagnosis").build()).execute().use { it.body!!.string() }
        val re = Regex(""""name":"([^"]*)".*?"url_resolved":"([^"]*)".*?"tags":"([^"]*)".*?"countrycode":"([^"]*)".*?"state":"([^"]*)".*?"lastcheckok":(\d)""")
        for (p in listOf("name=fama", "name=radio%20fama%20kielce", "name=kielce", "tag=kielce", "state=kielce&countrycode=PL", "name=fama&is_https=true", "countrycode=PL&limit=1000&hidebroken=true")) {
            val body = q(p)
            val rows = re.findAll(body).map { m -> val g = m.groupValues; "${g[1]} | ${g[2].take(60)} | tags=${g[3]} | ${g[4]}/${g[5]} | ok=${g[6]}" }.toList()
            println("[$p] ${rows.size} stations" + if (p.startsWith("countrycode")) " (https: ${rows.count { "| https://" in it }})" else "")
            if (!p.startsWith("countrycode")) rows.take(12).forEach { println("    $it") }
        }
    }
}

/** Diagnosis: Sunshine Live, Danceradio (CZ), Open FM — listing, formats, bitrates, what the URL returns. */
class RadioStationsDiagnosisLiveTest {
    private val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")

    @Test fun printStations() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        for (name in listOf("sunshine live", "danceradio", "dance radio", "open fm", "openfm")) {
            val body = client.newCall(Request.Builder().url("https://de1.api.radio-browser.info/json/stations/search?limit=15&order=clickcount&reverse=true&hidebroken=false&name=" + java.net.URLEncoder.encode(name, "UTF-8"))
                .header("User-Agent", "MusicFlow/diagnosis").build()).execute().use { it.body!!.string() }
            val list = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(app.musicflow.data.providers.radio.RadioStation.serializer()), body)
            println("[$name] ${list.size}")
            list.take(10).forEach { s ->
                val probe = runCatching {
                    client.newCall(Request.Builder().url(s.urlResolved.ifBlank { s.url }).header("User-Agent", "MusicFlow/diagnosis").header("Icy-MetaData", "1").build()).execute().use { r ->
                        val bytes = r.body!!.source().let { src -> src.request(512); src.buffer.snapshot(minOf(src.buffer.size, 512L).toInt()).toByteArray() }
                        val head = String(bytes, Charsets.ISO_8859_1).take(40).replace(Regex("[^\\x20-\\x7E]"), ".")
                        "HTTP ${r.code} ${r.header("Content-Type")} first='$head'"
                    }
                }.getOrElse { "ERR ${it::class.simpleName}: ${it.message?.take(80)}" }
                println("    ${s.name} | ${s.codec} ${s.bitrate}k hls=${s.hls} ok=${s.lastCheckOk} | ${s.countryCode} | url=${s.url.take(70)} | resolved=${s.urlResolved.take(70)} | $probe")
            }
        }
    }
}
