package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.soundcloud.SoundCloudProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hits the real SoundCloud endpoints through NewPipeExtractor.
 * Opt-in: `./gradlew :data:providers:testDebugUnitTest -PliveTests=true --tests '*SoundCloudLive*'`
 * (CI: the "Provider live tests" workflow).
 */
class SoundCloudLiveTest {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val provider = SoundCloudProvider(client, DiagnosticsLog(), { Locale.US })

    @Before fun onlyWhenEnabled() = assumeTrue(System.getProperty("musicflow.liveTests") == "true")

    @Test fun searchResolveAndFetchAudio() = runBlocking {
        val page = provider.search("lofi hip hop", SearchFilter.SONGS)
        val tracks = page.items.tracks()
        println("tracks: " + tracks.take(5).map { "${it.id} / ${it.title} / ${it.artistLine} / ${it.durationMs}" })
        assertThat(tracks).isNotEmpty()
        assertThat(page.nextPageToken).isNotNull()
        assertThat(provider.search(page.query, SearchFilter.SONGS, page.nextPageToken).items).isNotEmpty()

        // Resolve the first track that is fully playable (Go+ previews are reported as restricted).
        val stream = tracks.take(8).firstNotNullOfOrNull { t ->
            runCatching { provider.resolve(t.id, AudioQuality.BEST) }.onFailure { println("skip ${t.id}: $it") }.getOrNull()
        }
        println("stream: ${stream?.codec} ${stream?.bitrateKbps} kbps hls=${stream?.isHls} expires=${stream?.expiresAtMillis} ${stream?.url?.take(90)}")
        assertThat(stream).isNotNull()
        assertThat(stream!!.isHls).isTrue()

        // The HLS playlist downloads and lists absolute (or resolvable) segment URLs.
        val playlist = client.newCall(Request.Builder().url(stream.url).headers(okhttp3.Headers.headersOf(*stream.requestHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())).build())
            .execute().use { r -> assertThat(r.code).isEqualTo(200); r.body!!.string() }
        val segments = playlist.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        println("playlist: ${playlist.lines().take(6)} … ${segments.size} segments, first=${segments.firstOrNull()?.take(90)}")
        assertThat(playlist).startsWith("#EXTM3U")
        assertThat(segments).isNotEmpty()
        val first = stream.url.toHttpUrl().resolve(segments.first())!!
        client.newCall(Request.Builder().url(first).header("Range", "bytes=0-1023").build()).execute().use { r ->
            println("segment: HTTP ${r.code} ${r.header("Content-Type")}")
            assertThat(r.code).isIn(listOf(200, 206))
        }
    }

    /** Reported from a phone: "All" search with a Polish locale failed with an unexpected error. */
    @Test fun allFilterWithPolishLocaleAsOnThePhone() = runBlocking {
        val pl = SoundCloudProvider(client, DiagnosticsLog(), { Locale.forLanguageTag("pl-PL") })
        for (filter in SearchFilter.entries) {
            val r = runCatching { pl.search("Dynamic Waves move on baby", filter) }
            r.exceptionOrNull()?.let { e ->
                var c: Throwable? = e; var depth = 0
                while (c != null && depth < 6) { println("  [$filter] ${"  ".repeat(depth)}${c::class.java.name}: ${c.message?.take(300)}"); c = c.cause; depth++ }
            }
            println("$filter: " + (r.getOrNull()?.items?.take(5)?.map { it.title } ?: "FAILED"))
        }
        for (filter in SearchFilter.entries) assertThat(runCatching { pl.search("Dynamic Waves move on baby", filter) }.isSuccess).isTrue()
        // A query without any match is an empty result, not an error.
        assertThat(pl.search("qxzvbnmlkjhgfdsa poiuytrewq zzqqxx", SearchFilter.ARTISTS).items).isEmpty()
    }

    /** Reported: "Move On Baby" (Dynamic Waves) found but "content unavailable" on play. */
    @Test fun reportedTrackStreamsAsOnThePhone() = runBlocking {
        val t = provider.search("Dynamic Waves move on baby", SearchFilter.SONGS).items.tracks().first()
        println("reported track: ${t.id} ${t.title} / ${t.artistLine}")
        val r = runCatching { provider.resolve(t.id, AudioQuality.BEST) }
        println("reported resolve: ${r.getOrNull()?.codec} ${r.getOrNull()?.bitrateKbps} error=${r.exceptionOrNull()}")
        // What SoundCloud itself offers (NewPipe skips encrypted / DRM variants).
        val cid = org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper.clientId()
        val raw = client.newCall(Request.Builder().url("https://api-v2.soundcloud.com/resolve?url=https://${t.id.key}&client_id=$cid").build()).execute().use { it.body!!.string() }
        Regex("""\{"url":"[^"]*","preset":"([^"]*)","duration":\d+,"snipped":(\w+),"format":\{"protocol":"([^"]*)","mime_type":"([^"]*)"\}""").findAll(raw).forEach {
            println("  transcoding: preset=${it.groupValues[1]} snipped=${it.groupValues[2]} protocol=${it.groupValues[3]} mime=${it.groupValues[4]}")
        }
        println("  policy=" + Regex(""""policy":"([^"]*)"""").find(raw)?.groupValues?.get(1) + " monetization=" + Regex(""""monetization_model":"([^"]*)"""").find(raw)?.groupValues?.get(1))
        // With the YouTube fallback, a DRM-only track plays the identical recording (if one exists).
        val yt = app.musicflow.data.providers.youtube.YouTubeMusicProvider(client, DiagnosticsLog(), { Locale.US })
        val withFallback = SoundCloudProvider(client, DiagnosticsLog(), { Locale.US }, fallback = object : app.musicflow.data.providers.api.AudioMatcher {
            override val name = "YouTube Music"
            override suspend fun candidates(query: String) = yt.search(query, SearchFilter.SONGS).items.tracks().take(10)
                .also { c -> println("reported candidates for '$query' (SC ${t.durationMs} ms): " + c.map { "${it.title} / ${it.artistLine} / ${it.durationMs}" }) }
            override suspend fun resolve(id: app.musicflow.core.model.MediaId, quality: AudioQuality) = yt.resolve(id, quality)
        })
        val viaYt = runCatching { withFallback.resolve(t.id, AudioQuality.BEST) }
        println("reported fallback: ${viaYt.getOrNull()?.codec} ${viaYt.getOrNull()?.bitrateKbps} error=${viaYt.exceptionOrNull()}")
    }

    @Test fun metadataPlaylistsArtistsAndRelated() = runBlocking {
        val track = provider.search("odesza say my name", SearchFilter.SONGS).items.tracks().first()
        val full = provider.track(track.id)
        println("track: ${full.title} / ${full.artistLine} / ${full.genre} / ${full.artworkUrl}")
        assertThat(full.title).isNotEmpty()
        val related = provider.related(track.id, 10)
        println("related: ${related.map { it.title }}")

        val set = provider.search("chill playlist", SearchFilter.PLAYLISTS).items.filterIsInstance<MediaItemSummary.PlaylistItem>().first()
        val page = provider.playlist(set.id)
        println("set: ${page.title} (${page.tracks.size} tracks)")
        assertThat(page.tracks).isNotEmpty()

        val user = provider.search("odesza", SearchFilter.ARTISTS).items.filterIsInstance<MediaItemSummary.ArtistItem>().first()
        val artist = provider.artist(user.id)
        println("artist: ${artist.artist.name}, top=${artist.topTracks.size}, albums=${artist.albums.size + artist.singles.size}")
        assertThat(artist.topTracks).isNotEmpty()

        val viaLink = provider.search("https://soundcloud.com/" + track.id.key.removePrefix("soundcloud.com/"), SearchFilter.ALL).items
        assertThat(viaLink.single().id).isEqualTo(track.id)
    }
}
