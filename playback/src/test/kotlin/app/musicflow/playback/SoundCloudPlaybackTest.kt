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

    @Test fun radioStationsAreQueuedByStreamType() {
        val hls = MediaItems.toMediaItem(Track(MediaId("radio:hls/cccc"), "Station"))
        assertThat(hls.localConfiguration?.mimeType).isEqualTo(MimeTypes.APPLICATION_M3U8)
        val icecast = MediaItems.toMediaItem(Track(MediaId("radio:aaaa"), "Station"))
        assertThat(icecast.localConfiguration?.mimeType).isNull() // progressive (MP3/AAC/Ogg)
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

/** Live radio: HLS playlists are never served from the disk cache; HTTP follow-ups only when allowed. */
@RunWith(RobolectricTestRunner::class)
class LiveRoutingTest {
    private val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private fun read(factory: androidx.media3.datasource.DataSource.Factory, spec: DataSpec): String {
        val ds = factory.createDataSource()
        ds.open(spec)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (true) { val n = ds.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
        ds.close()
        return out.toString()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun livePlaylistsAreRefetchedAndHttpNeedsTheSetting() {
        okhttp3.mockwebserver.MockWebServer().use { s ->
            var n = 0
            s.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setBody("#EXTM3U\n#v${++n}\n")
            }
            val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor(app.musicflow.core.network.CleartextGuard()).build()
            val cache = androidx.media3.datasource.cache.SimpleCache(tmp.newFolder(), androidx.media3.datasource.cache.NoOpCacheEvictor(), androidx.media3.database.StandaloneDatabaseProvider(context))
            val streams = app.musicflow.playback.stream.StreamRepository({ _, _ -> error("unused") }, { app.musicflow.core.model.AudioQuality.BEST }, app.musicflow.core.common.DiagnosticsLog())
            var allow = true
            val factory = PlaybackDataSources.factory(context, client, cache, streams, allowCleartextMedia = { allow })
            val playlist = DataSpec(Uri.parse(s.url("/live.m3u8").toString()))
            assertThat(read(factory, playlist)).contains("#v1")
            assertThat(read(factory, playlist)).contains("#v2") // refreshed, not a stale cached copy
            allow = false
            assertThat(runCatching { read(factory, playlist) }.isFailure).isTrue() // plain HTTP refused without the setting
            cache.release()
        }
    }
}
