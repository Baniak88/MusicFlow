package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.Track
import app.musicflow.core.model.ArtistRef
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.musicbrainz.MbIds
import app.musicflow.data.providers.musicbrainz.MusicBrainzProvider
import app.musicflow.data.providers.radio.RadioBrowserProvider
import app.musicflow.data.providers.radio.RadioIds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test

class RadioBrowserTest {
    private val stations = """[
      {"stationuuid":"aaaa","name":"Radio HTTPS","url":"https://s1/live","url_resolved":"https://s1/live.mp3","favicon":"https://s1/f.png","tags":"pop,rock,hits,x","country":"Poland","countrycode":"PL","codec":"MP3","bitrate":128,"hls":0},
      {"stationuuid":"bbbb","name":"Radio Plain HTTP","url":"http://s2/live","url_resolved":"http://s2/live","favicon":"http://s2/f.png","codec":"AAC","bitrate":64,"hls":0},
      {"stationuuid":"cccc","name":"Radio HLS","url":"https://s3/a.m3u8","url_resolved":"https://s3/a.m3u8","codec":"AAC","bitrate":0,"hls":1,"unknown_field":true}
    ]"""

    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
    }

    @Test fun searchKeepsHttpsStationsAndMarksHlsInTheId() = runBlocking {
        server { MockResponse().setBody(stations) }.use { s ->
            val p = RadioBrowserProvider(OkHttpClient(), DiagnosticsLog(), "UA-test", listOf(s.url("/").toString())).apply { allowHttp = false }
            val page = p.search("radio", SearchFilter.ALL)
            val tracks = page.items.tracks()
            assertThat(tracks.map { it.id.value }).containsExactly("radio:aaaa", "radio:hls/cccc").inOrder()
            val first = tracks.first()
            assertThat(first.title).isEqualTo("Radio HTTPS")
            assertThat(first.artistLine).isEqualTo("Poland · pop, rock, hits")
            assertThat(first.durationMs).isNull() // live
            assertThat(first.artworkUrl).isEqualTo("https://s1/f.png")
            val req = s.takeRequest()
            assertThat(req.path).contains("is_https=true")
            assertThat(req.path).contains("hidebroken=true")
            assertThat(req.getHeader("User-Agent")).isEqualTo("UA-test")
            assertThat(RadioIds.isHls(MediaId("radio:hls/cccc"))).isTrue()
            // Stations are not artists or albums.
            assertThat(p.search("radio", SearchFilter.ARTISTS).items).isEmpty()
        }
    }

    @Test fun fallsBackToTheNextMirror() = runBlocking {
        val dead = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)) }
        server { MockResponse().setBody(stations) }.use { good ->
            dead.use {
                val p = RadioBrowserProvider(OkHttpClient(), DiagnosticsLog(), "UA", listOf(dead.url("/").toString(), good.url("/").toString()))
                assertThat(p.top("PL").map { it.title }).contains("Radio HTTPS")
                assertThat(good.takeRequest().path).contains("countrycode=PL")
            }
        }
    }

    @Test fun resolvesALiveStreamAndCountsTheClick() = runBlocking {
        server { r ->
            when {
                r.path!!.startsWith("/json/stations/byuuid/aaaa") -> MockResponse().setBody("[" + stations.substringAfter("[").substringBefore("},") + "}]")
                r.path!!.startsWith("/json/url/aaaa") -> MockResponse().setBody("""{"ok":true,"url":"https://s1/counted.mp3"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }.use { s ->
            val p = RadioBrowserProvider(OkHttpClient(), DiagnosticsLog(), "UA", listOf(s.url("/").toString()))
            val stream = p.resolve(MediaId("radio:aaaa"), AudioQuality.BEST)
            assertThat(stream.url).isEqualTo("https://s1/counted.mp3")
            assertThat(stream.isLive).isTrue()
            assertThat(stream.isLossless).isFalse()
            assertThat(stream.codec).isEqualTo("mp3")
            assertThat(stream.bitrateKbps).isEqualTo(128)
        }
    }

    @Test fun plainHttpStationsAreRefusedNotPlayedInsecurely() = runBlocking {
        val plain = """[{"stationuuid":"bbbb","name":"Plain","url":"http://s2/live","url_resolved":"http://s2/live","hls":0}]"""
        server { r -> if (r.path!!.startsWith("/json/url/")) MockResponse().setBody("""{"ok":true,"url":"http://s2/live"}""") else MockResponse().setBody(plain) }.use { s ->
            val p = RadioBrowserProvider(OkHttpClient(), DiagnosticsLog(), "UA", listOf(s.url("/").toString())).apply { allowHttp = false }
            val e = runCatching { p.resolve(MediaId("radio:bbbb"), AudioQuality.BEST) }.exceptionOrNull()
            assertThat(e).isInstanceOf(ProviderError.NotSupported::class.java)
            // Allowed by the user: the stream is marked so that only it may use HTTP.
            p.allowHttp = true
            val stream = p.resolve(MediaId("radio:bbbb"), AudioQuality.BEST)
            assertThat(stream.url).isEqualTo("http://s2/live")
            assertThat(stream.allowCleartext).isTrue()
        }
    }
}

class MusicBrainzTest {
    private var now = 0L
    private fun provider(s: MockWebServer, audio: app.musicflow.data.providers.api.AudioMatcher? = null) =
        MusicBrainzProvider(OkHttpClient(), DiagnosticsLog(), audio, "UA-test", s.url("/ws/2/"), minIntervalMs = 1_000, clock = { now })

    private val recordings = """{"count":2,"offset":0,"recordings":[
      {"id":"r1","score":100,"title":"Karma Police","length":264066,"first-release-date":"1997-08-25",
       "artist-credit":[{"name":"Radiohead","joinphrase":"","artist":{"id":"a1","name":"Radiohead"}}],
       "releases":[{"id":"rel2","title":"Karma Police","status":"Promotion","date":"1997-08-01","release-group":{"id":"rg2","title":"Karma Police","primary-type":"Single"}},
                   {"id":"rel1","title":"OK Computer","status":"Official","date":"1997-05-21","release-group":{"id":"rg1","title":"OK Computer","primary-type":"Album"}}]},
      {"id":"r2","score":90,"title":"Karma Police (video)","video":true,"artist-credit":[]}]}"""

    @Test fun recordingsMapToTracksWithAlbumAndCover() = runBlocking {
        MockWebServer().apply { enqueue(MockResponse().setBody(recordings)) }.use { s ->
            val page = provider(s).search("karma police", SearchFilter.SONGS)
            val t = page.items.tracks().single() // music videos skipped
            assertThat(t.id.value).isEqualTo("mbr:r1")
            assertThat(t.artists.single()).isEqualTo(ArtistRef("mba:a1", "Radiohead"))
            assertThat(t.album?.title).isEqualTo("OK Computer") // official release preferred
            assertThat(t.album?.id).isEqualTo("mbrg:rg1")
            assertThat(t.artworkUrl).isEqualTo(MbIds.cover("rg1"))
            assertThat(t.durationMs).isEqualTo(264_066)
            assertThat(t.releaseYear).isEqualTo(1997)
            val r = s.takeRequest()
            assertThat(r.path).contains("fmt=json")
            assertThat(r.getHeader("User-Agent")).isEqualTo("UA-test")
        }
    }

    @Test fun albumTracklistComesFromTheOfficialRelease() = runBlocking {
        MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"id":"rg1","title":"OK Computer","first-release-date":"1997-05-21","artist-credit":[{"name":"Radiohead","joinphrase":"","artist":{"id":"a1","name":"Radiohead"}}],
              "releases":[{"id":"bootleg","status":"Bootleg","date":"1996"},{"id":"rel1","status":"Official","date":"1997-05-21"}]}"""))
            enqueue(MockResponse().setBody("""{"id":"rel1","title":"OK Computer","media":[{"position":1,"tracks":[
              {"id":"t1","position":1,"title":"Airbag","length":284000,"recording":{"id":"r10","title":"Airbag"}},
              {"id":"t2","position":2,"title":"Paranoid Android","length":383000,"recording":{"id":"r11","title":"Paranoid Android"}}]}],
              "artist-credit":[{"name":"Radiohead","joinphrase":"","artist":{"id":"a1","name":"Radiohead"}}]}"""))
        }.use { s ->
            val page = provider(s).album(MediaId("mbrg:rg1"))
            assertThat(page.title).isEqualTo("OK Computer")
            assertThat(page.subtitle).isEqualTo("Radiohead")
            assertThat(page.year).isEqualTo(1997)
            assertThat(page.tracks.map { it.id.value }).containsExactly("mbr:r10", "mbr:r11").inOrder()
            assertThat(page.tracks.first().artistLine).isEqualTo("Radiohead")
            s.takeRequest()
            assertThat(s.takeRequest().path).startsWith("/ws/2/release/rel1")
        }
    }

    @Test fun requestsAreSpacedToOnePerSecond() = runBlocking {
        val times = mutableListOf<Long>()
        MockWebServer().apply { repeat(2) { enqueue(MockResponse().setBody(recordings)) } }.use { s ->
            val p = MusicBrainzProvider(OkHttpClient(), DiagnosticsLog(), null, "UA", s.url("/ws/2/"), minIntervalMs = 300, clock = { System.currentTimeMillis().also { times += it } })
            val start = System.currentTimeMillis()
            p.search("a", SearchFilter.SONGS); p.search("b", SearchFilter.SONGS)
            assertThat(System.currentTimeMillis() - start).isAtLeast(300)
        }
    }

    private fun track(title: String, artist: String, ms: Long?) = Track(MediaId("yt:x${title.hashCode()}"), title, listOf(ArtistRef(null, artist)), durationMs = ms)

    @Test fun playsOnlyAReliableMatch() {
        val mb = Track(MediaId("mbr:r1"), "Karma Police", listOf(ArtistRef("mba:a1", "Radiohead")), durationMs = 264_066)
        val cover = track("Karma Police (cover)", "Some Band", 262_000)
        val live = track("Karma Police", "Radiohead", 330_000) // live version: too long
        val video = track("Radiohead - Karma Police (Official Video)", "Radiohead", 266_000)
        assertThat(MusicBrainzProvider.bestMatch(mb, listOf(cover, live, video))).isEqualTo(video)
        assertThat(MusicBrainzProvider.bestMatch(mb, listOf(cover, live))).isNull()
    }

    @Test fun noReliableMatchIsReportedNotGuessed() = runBlocking {
        MockWebServer().apply { enqueue(MockResponse().setBody("""{"id":"r1","title":"Karma Police","length":264066,"artist-credit":[{"name":"Radiohead","joinphrase":"","artist":{"id":"a1","name":"Radiohead"}}]}""")) }.use { s ->
            var resolved = false
            val audio = object : app.musicflow.data.providers.api.AudioMatcher {
                override val name = "yt"
                override suspend fun candidates(query: String) = listOf(track("Karma Police (cover)", "Some Band", 262_000))
                override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream { resolved = true; error("must not be called") }
            }
            val e = runCatching { provider(s, audio).resolve(MediaId("mbr:r1"), AudioQuality.BEST) }.exceptionOrNull()
            assertThat(e).isInstanceOf(ProviderError.Unavailable::class.java)
            assertThat(resolved).isFalse()
        }
    }

    @Test fun artistFactsOnlyForAConfidentExactNameMatch() = runBlocking {
        MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"artists":[{"id":"a9","name":"Radiohead Tribute","score":96},{"id":"a1","name":"Radiohead","score":100}]}"""))
            enqueue(MockResponse().setBody("""{"id":"a1","name":"Radiohead","type":"Group","area":{"name":"United Kingdom"},"life-span":{"begin":"1991","ended":false},
              "genres":[{"name":"alternative rock","count":30},{"name":"art rock","count":20}],"disambiguation":""}"""))
            enqueue(MockResponse().setBody("""{"artists":[{"id":"z","name":"Somebody Else","score":100}]}"""))
        }.use { s ->
            val p = provider(s)
            val f = p.factsFor("Radiohead")!!
            assertThat(f.mbid).isEqualTo("a1")
            assertThat(f.type).isEqualTo("Group")
            assertThat(f.area).isEqualTo("United Kingdom")
            assertThat(f.genres).containsExactly("alternative rock", "art rock").inOrder()
            assertThat(f.disambiguation).isNull()
            assertThat(p.factsFor("Radiohead")).isNull() // next response names someone else → no guess
        }
    }
}

class MusicBrainzRetryTest {
    @Test fun busyServerIsRetriedThenSucceeds() = runBlocking {
        MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            enqueue(MockResponse().setBody("""{"count":0,"offset":0,"recordings":[]}"""))
            enqueue(MockResponse().setBody("""{"count":0,"offset":0,"recordings":[]}""")) // broad fallback after an empty studio search
        }.use { s ->
            val p = MusicBrainzProvider(OkHttpClient(), DiagnosticsLog(), null, "UA", s.url("/ws/2/"), minIntervalMs = 0, clock = { 0L })
            assertThat(p.search("x", SearchFilter.SONGS).items).isEmpty()
            assertThat(s.requestCount).isEqualTo(3)
        }
    }
}

class MusicBrainzRankingTest {
    @Test fun studioRecordingBeatsLiveVersionsOnManyLiveAlbums() = runBlocking {
        fun rec(id: String, groupJson: String, releases: Int) = """{"id":"$id","score":100,"title":"Karma Police","length":264000,
           "artist-credit":[{"name":"Radiohead","joinphrase":"","artist":{"id":"a1","name":"Radiohead"}}],
           "releases":[${(1..releases).joinToString(",") { """{"id":"r$id$it","title":"x","status":"Official","release-group":$groupJson}""" }}]}"""
        val live = rec("live", """{"id":"g1","primary-type":"Album","secondary-types":["Live"]}""", 30)
        val studio = rec("studio", """{"id":"g2","primary-type":"Album","secondary-types":[]}""", 5)
        MockWebServer().apply { enqueue(MockResponse().setBody("""{"count":2,"offset":0,"recordings":[$live,$studio]}""")) }.use { s ->
            val p = MusicBrainzProvider(OkHttpClient(), DiagnosticsLog(), null, "UA", s.url("/ws/2/"), minIntervalMs = 0, clock = { 0L })
            assertThat(p.search("karma police radiohead", SearchFilter.SONGS).items.first().id.value).isEqualTo("mbr:studio")
        }
    }

    @Test fun namedArtistAndWidelyReleasedVersionsComeFirst() = runBlocking {
        fun rec(id: String, artist: String, releases: Int) = """{"id":"$id","score":100,"title":"Bohemian Rhapsody","length":354000,
           "artist-credit":[{"name":"$artist","joinphrase":"","artist":{"id":"a$id","name":"$artist"}}],
           "releases":[${(1..releases).joinToString(",") { """{"id":"r$id$it","title":"x","status":"Official"}""" }}]}"""
        val medley = rec("medley", "Queen", 60).replace("\"title\":\"Bohemian Rhapsody\"", "\"title\":\"Queen Medley: Bohemian Rhapsody / Killer Queen\"")
        val body = """{"count":4,"offset":0,"recordings":[${rec("cover", "Brooklyn Duo", 1)},$medley,${rec("live", "Queen", 3)},${rec("orig", "Queen", 40)}]}"""
        MockWebServer().apply { enqueue(MockResponse().setBody(body)); enqueue(MockResponse().setBody(body)) }.use { s ->
            assertThat(app.musicflow.data.providers.musicbrainz.MusicBrainzProvider::class.java).isNotNull()
            val p = MusicBrainzProvider(OkHttpClient(), DiagnosticsLog(), null, "UA", s.url("/ws/2/"), minIntervalMs = 0, clock = { 0L })
            assertThat(p.search("bohemian rhapsody queen", SearchFilter.SONGS).items.map { it.id.value })
                .containsExactly("mbr:orig", "mbr:live", "mbr:medley", "mbr:cover").inOrder() // non-fitting ones by popularity
            val sent = okhttp3.HttpUrl.Companion.run { s.takeRequest().requestUrl!!.queryParameter("query") }
            assertThat(sent).isEqualTo("(recording:(bohemian rhapsody queen)^2 OR artist:(bohemian rhapsody queen)) AND primarytype:(album OR single OR ep) AND NOT secondarytype:live")
            // Without an artist in the query, the most released version still wins over a one-off cover.
            assertThat(p.search("bohemian rhapsody", SearchFilter.SONGS).items.first().id.value).isEqualTo("mbr:orig")
        }
    }
}

class RadioSearchRankingTest {
    private fun st(uuid: String, name: String, state: String = "", tags: String = "", clicks: Int = 0) =
        app.musicflow.data.providers.radio.RadioStation(uuid, name, url = "http://x/$uuid", state = state, tags = tags, country = "Poland", clickCount = clicks)

    @Test fun stationMatchingNameAndCityComesFirst() {
        val ranked = app.musicflow.data.providers.radio.RadioSearch.rank("radio fama kielce", listOf(
            st("1", "Radio Kielce", clicks = 900),
            st("2", "Radio Fama Slupsk", state = "pomorskie", clicks = 300),
            st("3", "Radio FAMA", state = "Kielce", clicks = 10), // the reported station
            st("4", "Meloradio Kielce", state = "Świętokrzyskie", clicks = 500),
        ))
        assertThat(ranked.first().uuid).isEqualTo("3")
        assertThat(app.musicflow.data.providers.radio.RadioSearch.words("Radio FAMA Kielce")).containsExactly("kielce", "fama").inOrder()
    }
}

class CleartextGuardTest {
    private val client = OkHttpClient.Builder().addNetworkInterceptor(app.musicflow.core.network.CleartextGuard()).build()

    @Test fun plainHttpIsRefusedUnlessMarkedAndTheMarkerNeverLeaves() {
        MockWebServer().use { s -> // MockWebServer speaks plain HTTP
            s.enqueue(MockResponse().setBody("ok"))
            val plain = runCatching { client.newCall(okhttp3.Request.Builder().url(s.url("/a")).build()).execute().close() }
            assertThat(plain.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
            assertThat(s.requestCount).isEqualTo(0) // nothing was sent

            client.newCall(okhttp3.Request.Builder().url(s.url("/radio")).header(app.musicflow.core.network.CleartextGuard.ALLOW_HEADER, "radio").build()).execute().use {
                assertThat(it.code).isEqualTo(200)
            }
            assertThat(s.takeRequest().getHeader(app.musicflow.core.network.CleartextGuard.ALLOW_HEADER)).isNull()
        }
    }
}
