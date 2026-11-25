package app.musicflow.data.providers

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.SearchProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.providers.soundcloud.SoundCloudIds
import app.musicflow.data.providers.soundcloud.SoundCloudProvider
import app.musicflow.data.providers.youtube.YouTubeErrors
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class SoundCloudIdsTest {
    @Test fun parsesTrackPlaylistAndUserLinks() {
        assertThat(SoundCloudIds.trackFromUrl("https://soundcloud.com/artist-x/my-song?si=abc&utm_source=x")?.value)
            .isEqualTo("sc:soundcloud.com/artist-x/my-song")
        assertThat(SoundCloudIds.trackFromUrl("https://m.soundcloud.com/artist-x/my-song/")?.value)
            .isEqualTo("sc:soundcloud.com/artist-x/my-song")
        assertThat(SoundCloudIds.playlistFromUrl("https://soundcloud.com/artist-x/sets/best-of")?.value)
            .isEqualTo("scpl:soundcloud.com/artist-x/sets/best-of")
        assertThat(SoundCloudIds.userFromUrl("https://soundcloud.com/artist-x")?.value).isEqualTo("scu:soundcloud.com/artist-x")
        // A set or profile is not a track; SoundCloud's own pages are not users.
        assertThat(SoundCloudIds.trackFromUrl("https://soundcloud.com/artist-x/sets/best-of")).isNull()
        assertThat(SoundCloudIds.trackFromUrl("https://soundcloud.com/artist-x")).isNull()
        assertThat(SoundCloudIds.trackFromUrl("https://soundcloud.com/artist-x/tracks")).isNull()
        assertThat(SoundCloudIds.userFromUrl("https://soundcloud.com/discover")).isNull()
        assertThat(SoundCloudIds.trackFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).isNull()
        // Round trip to the URL NewPipe loads.
        assertThat(SoundCloudIds.url(SoundCloudIds.trackFromUrl("soundcloud.com/a/b")!!)).isEqualTo("https://soundcloud.com/a/b")
    }

    @Test fun artworkAndExpiry() {
        assertThat(SoundCloudIds.upscaleArtwork("https://i1.sndcdn.com/artworks-000123-abc-large.jpg"))
            .isEqualTo("https://i1.sndcdn.com/artworks-000123-abc-t500x500.jpg")
        assertThat(SoundCloudIds.expiryMillis("https://x/playlist.m3u8?expires=1764000000&Signature=y")).isEqualTo(1_764_000_000_000L)
        assertThat(SoundCloudIds.expiryMillis("https://x/playlist.m3u8")).isNull() // not reported → not guessed
    }
}

class SoundCloudStreamSelectionTest {
    private fun hls(id: String, format: MediaFormat, kbps: Int) = AudioStream.Builder()
        .setId(id).setContent("https://example.com/$id.m3u8", true).setMediaFormat(format)
        .setDeliveryMethod(DeliveryMethod.HLS).setAverageBitrate(kbps).build()

    // What SoundCloud offers for a typical track today (see SoundcloudStreamExtractor).
    private val offered = listOf(hls("aac160", MediaFormat.M4A, 160), hls("mp3", MediaFormat.MP3, 128), hls("opus", MediaFormat.OPUS, 64), hls("aac96", MediaFormat.M4A, 96))

    @Test fun picksHighestBitrateWithinTheQualityLimit() {
        assertThat(SoundCloudProvider.select(offered, AudioQuality.BEST)?.id).isEqualTo("aac160")
        assertThat(SoundCloudProvider.select(offered, AudioQuality.HIGH)?.id).isEqualTo("aac160")
        assertThat(SoundCloudProvider.select(offered, AudioQuality.STANDARD)?.id).isEqualTo("mp3")
        assertThat(SoundCloudProvider.select(offered, AudioQuality.DATA_SAVER)?.id).isEqualTo("opus")
    }

    @Test fun fallsBackToTheSmallestWhenEverythingExceedsTheLimit() {
        assertThat(SoundCloudProvider.select(listOf(hls("mp3", MediaFormat.MP3, 128)), AudioQuality.DATA_SAVER)?.id).isEqualTo("mp3")
        assertThat(SoundCloudProvider.select(emptyList(), AudioQuality.BEST)).isNull()
    }

    @Test fun goPlusPreviewsAreRestrictedNotPlayedAsFullTracks() {
        val e = YouTubeErrors.classify(SoundCloudGoPlusContentException())
        assertThat(e).isInstanceOf(ProviderError.Restricted::class.java)
        assertThat((e as ProviderError.Restricted).reason).isEqualTo(ProviderError.Restricted.Reason.PAID)
    }
}

class SearchSourcesTest {
    private open class Source(override val id: String, val online: Boolean, val extra: Boolean) : MusicProvider, SearchProvider {
        override val displayName = id
        override val isExperimental = true
        override val isOnlineCatalog get() = online
        override val isSearchSource get() = online || extra
        override val search: SearchProvider get() = this
        override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage = error("unused")
        override suspend fun suggestions(query: String) = emptyList<String>()
    }

    @Test fun mainCatalogueFirstThenExtraSourcesNeverLocalMusic() {
        val yt = Source("yt", online = true, extra = false)
        val sc = Source("sc", online = false, extra = true) // sorts before "yt" by id, must still come second
        val local = Source("local", online = false, extra = false)
        val r = ProviderRegistry(setOf(local, sc, yt), DiagnosticsLog())
        assertThat(r.searchSources().map { it.id }).containsExactly("yt", "sc").inOrder()
        assertThat(r.primaryCatalog()?.id).isEqualTo("yt") // Home/Explore stay on YouTube Music
        r.setEnabled("sc", false)
        assertThat(r.searchSources().map { it.id }).containsExactly("yt")
    }
}
