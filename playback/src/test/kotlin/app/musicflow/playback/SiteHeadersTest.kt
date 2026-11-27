package app.musicflow.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.musicflow.playback.stream.PlaybackDataSources
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SiteHeadersTest {
    @Test fun openFmRequestsCarryTheSiteHeaders() {
        // HLS follow-up requests (chunk lists, segments) too, not only the first playlist.
        val spec = PlaybackDataSources.withSiteHeaders(DataSpec(Uri.parse("https://stream-cdn-1.open.fm/OFM1/ngrp:standard/chunklist_b128000.m3u8")))
        assertThat(spec.httpRequestHeaders).containsEntry("Origin", "https://open.fm")
        assertThat(spec.httpRequestHeaders).containsEntry("Referer", "https://open.fm/")
    }

    @Test fun otherHostsAreUntouched() {
        val spec = DataSpec(Uri.parse("https://rs201-krk.rmfstream.pl/rmf_fm"))
        assertThat(PlaybackDataSources.withSiteHeaders(spec)).isSameInstanceAs(spec)
        val lookalike = DataSpec(Uri.parse("https://notopen.fm/x"))
        assertThat(PlaybackDataSources.withSiteHeaders(lookalike)).isSameInstanceAs(lookalike)
    }
}
