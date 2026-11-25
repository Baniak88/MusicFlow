package app.musicflow.data.providers

import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.ProviderError
import app.musicflow.data.providers.youtube.AudioCandidate
import app.musicflow.data.providers.youtube.StreamSelector
import app.musicflow.data.providers.youtube.YouTubeErrors
import app.musicflow.data.providers.youtube.YouTubeIds
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException

class YouTubeHelpersTest {
    @Test fun extractsIdsFromUrls() {
        assertThat(YouTubeIds.videoIdFromUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM")).isEqualTo("dQw4w9WgXcQ")
        assertThat(YouTubeIds.videoIdFromUrl("https://youtu.be/dQw4w9WgXcQ?t=3")).isEqualTo("dQw4w9WgXcQ")
        assertThat(YouTubeIds.listIdFromUrl("https://music.youtube.com/playlist?list=OLAK5uy_abc-DEF")).isEqualTo("OLAK5uy_abc-DEF")
        assertThat(YouTubeIds.channelIdFromUrl("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv")).isEqualTo("UCabcdefghijklmnopqrstuv")
        assertThat(YouTubeIds.isYouTubeUrl("https://evil.com/?u=youtube.com")).isFalse()
        assertThat(YouTubeIds.isYouTubeUrl("https://music.youtube.com/watch?v=x")).isTrue()
    }

    @Test fun parsesExpiryAndLengthFromSignedUrl() {
        val url = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1767225600&itag=251&clen=3456789&c=VISIONOS"
        assertThat(YouTubeIds.expiryMillis(url)).isEqualTo(1_767_225_600_000L)
        assertThat(YouTubeIds.contentLength(url)).isEqualTo(3_456_789L)
        assertThat(YouTubeIds.expiryMillis("https://example.com/a.mp3")).isNull()
    }

    @Test fun upscalesArtwork() {
        assertThat(YouTubeIds.upscaleArtwork("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"))
            .isEqualTo("https://lh3.googleusercontent.com/abc=w720-h720-l90-rj")
        assertThat(YouTubeIds.upscaleArtwork("https://yt3.ggpht.com/abc=s88-c-k")).isEqualTo("https://yt3.ggpht.com/abc=s720")
        assertThat(YouTubeIds.upscaleArtwork("https://i.ytimg.com/vi/x/hqdefault.jpg")).isEqualTo("https://i.ytimg.com/vi/x/hqdefault.jpg")
        assertThat(YouTubeIds.cleanArtistName("Daft Punk - Topic")).isEqualTo("Daft Punk")
    }

    private fun c(itag: Int, kbps: Int?, codec: String, original: Boolean = true) =
        AudioCandidate(itag, "u$itag", null, codec, kbps, 48_000, 2, null, original)

    @Test fun selectsStreamForQuality() {
        val all = listOf(c(139, 48, "mp4a"), c(249, 50, "opus"), c(250, 70, "opus"), c(140, 129, "mp4a"), c(251, 135, "opus"))
        assertThat(StreamSelector.select(all, AudioQuality.DATA_SAVER)?.formatId).isEqualTo(249)
        assertThat(StreamSelector.select(all, AudioQuality.STANDARD)?.formatId).isEqualTo(250)
        assertThat(StreamSelector.select(all, AudioQuality.HIGH)?.formatId).isEqualTo(251)
        assertThat(StreamSelector.select(all, AudioQuality.BEST)?.formatId).isEqualTo(251)
        // Nothing under the cap → lowest available rather than nothing.
        assertThat(StreamSelector.select(listOf(c(251, 135, "opus")), AudioQuality.DATA_SAVER)?.formatId).isEqualTo(251)
        assertThat(StreamSelector.select(emptyList(), AudioQuality.BEST)).isNull()
    }

    @Test fun prefersOriginalAudioTrack() {
        val dubbed = c(251, 135, "opus", original = false)
        val original = c(140, 129, "mp4a", original = true)
        assertThat(StreamSelector.select(listOf(dubbed, original), AudioQuality.BEST)).isEqualTo(original)
    }

    @Test fun classifiesExtractorErrors() {
        assertThat(YouTubeErrors.classify(ReCaptchaException("x", "u"))).isInstanceOf(ProviderError.RateLimited::class.java)
        assertThat(YouTubeErrors.classify(AgeRestrictedContentException("x"))).isInstanceOf(ProviderError.Restricted::class.java)
        assertThat((YouTubeErrors.classify(GeographicRestrictionException("x")) as ProviderError.Restricted).reason)
            .isEqualTo(ProviderError.Restricted.Reason.GEOGRAPHIC)
        assertThat((YouTubeErrors.classify(YoutubeMusicPremiumContentException()) as ProviderError.Restricted).reason)
            .isEqualTo(ProviderError.Restricted.Reason.PAID)
        assertThat(YouTubeErrors.classify(ContentNotAvailableException("x"))).isInstanceOf(ProviderError.Unavailable::class.java)
        assertThat(YouTubeErrors.classify(ParsingException("x"))).isInstanceOf(ProviderError.Parsing::class.java)
        assertThat(YouTubeErrors.classify(ParsingException("x", java.net.SocketTimeoutException()))).isInstanceOf(ProviderError.Network::class.java)
        assertThat(YouTubeErrors.classify(java.io.IOException("reset"))).isInstanceOf(ProviderError.Network::class.java)
    }
}

class DerivativeFilterTest {
    @Test fun detectsDerivativeUploads() {
        val r = app.musicflow.data.providers.youtube.YouTubeMusicProvider.DERIVATIVE
        listOf("Avicii - Heaven (432Hz)", "Song (Sped Up)", "Song slowed + reverb", "Nightcore - X", "Song 10 Hours")
            .forEach { com.google.common.truth.Truth.assertWithMessage(it).that(r.containsMatchIn(it)).isTrue() }
        val p = app.musicflow.data.providers.youtube.YouTubeMusicProvider
        com.google.common.truth.Truth.assertThat(p.isDerivative("waiting for love - avicii (s l o w e d + r e v e r b)")).isTrue()
        com.google.common.truth.Truth.assertThat(p.isDerivative("Heroes x Levels (Mashup)")).isTrue()
        com.google.common.truth.Truth.assertThat(p.isDerivative("Wake Me Up")).isFalse()
        com.google.common.truth.Truth.assertThat(p.isDerivative("Reverberation (Original Mix)")).isFalse()
        listOf("Levels", "One More Time", "Hertz Song").forEach {
            com.google.common.truth.Truth.assertWithMessage(it).that(r.containsMatchIn(it)).isFalse()
        }
    }
}

class SongMergeTest {
    private fun t(id: String, title: String, artist: String) =
        app.musicflow.core.model.MediaItemSummary.TrackItem(app.musicflow.core.model.Track(app.musicflow.core.model.MediaId("yt:$id"), title, listOf(app.musicflow.core.model.ArtistRef(null, artist))))

    @Test fun uploadsMissingFromCatalogueAreIncludedAndExactMatchFirst() {
        val songs = listOf(t("s1", "Powiedz mi", "Inny Zespół"), t("s2", "Coś innego", "Ktoś"))
        val uploads = listOf(t("I5uH5-JHh3k", "D-Bomb Powiedz mi co czujesz", "dbombbartek"), t("v2", "Powiedz mi (Official Video)", "Inny Zespół"))
        val out = app.musicflow.data.providers.youtube.SongMerge.merge("D-Bomb Powiedz mi co czujesz", songs, uploads)
        com.google.common.truth.Truth.assertThat(out.first().id.value).isEqualTo("yt:I5uH5-JHh3k")
        // "Powiedz mi (Official Video)" by the same artist duplicates catalogue song s1.
        com.google.common.truth.Truth.assertThat(out.map { it.id.value }).containsExactly("yt:I5uH5-JHh3k", "yt:s1", "yt:s2")
    }

    @Test fun emptyCatalogueStillReturnsUploads() {
        val out = app.musicflow.data.providers.youtube.SongMerge.merge("x", emptyList(), listOf(t("a", "A", "B")))
        com.google.common.truth.Truth.assertThat(out).hasSize(1)
    }
}
