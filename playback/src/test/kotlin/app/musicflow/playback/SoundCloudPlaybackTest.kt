package app.musicflow.playback

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import app.musicflow.playback.stream.PlaybackDataSources
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SoundCloudPlaybackTest {
    @Test fun soundCloudTracksAreQueuedAsHls() {
        val sc = MediaItems.toMediaItem(Track(MediaId("sc:soundcloud.com/a/b"), "t"))
        assertThat(sc.localConfiguration?.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8)
        val yt = MediaItems.toMediaItem(Track(MediaId("yt:dQw4w9WgXcQ"), "t"))
        assertThat(yt.localConfiguration?.mimeType).isNull() // progressive
        assertThat(MediaItems.isHlsSource(MediaId("url:https://x/live.m3u8?a=1"))).isTrue()
        // The placeholder survives the permalink key with slashes.
        assertThat(MediaItems.mediaIdFromUri(sc.localConfiguration!!.uri)?.value).isEqualTo("sc:soundcloud.com/a/b")
    }

    @Test fun soundCloudSegmentsStayCachedAcrossReSignedUrls() {
        fun key(url: String) = PlaybackDataSources.cacheKey(DataSpec(Uri.parse(url)))
        val a = key("https://cf-hls-media.sndcdn.com/media/0/31762/abc.128.mp3?Policy=AAA&Signature=1")
        val b = key("https://cf-hls-media.sndcdn.com/media/0/31762/abc.128.mp3?Policy=BBB&Signature=2")
        assertThat(a).isEqualTo(b)
        assertThat(a).doesNotContain("Signature")
        // Other hosts keep the full URL (the query can select different content).
        assertThat(key("https://example.com/a.mp3?v=1")).isNotEqualTo(key("https://example.com/a.mp3?v=2"))
        // A resolver-provided key always wins.
        assertThat(PlaybackDataSources.cacheKey(DataSpec.Builder().setUri("https://x/y").setKey("yt:abc:251").build())).isEqualTo("yt:abc:251")
    }
}
