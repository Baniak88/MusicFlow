package app.musicflow.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The radio "RDS" update replaces the current item with new metadata. Media3 updates it in place:
 * no media-item transition (so it is not a new listen, and nothing must wait for one) and the
 * new title is what controllers see.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class NowPlayingUpdateTest {
    @Test fun replacingMetadataUpdatesInPlaceWithoutATransition() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        val item = MediaItem.Builder().setMediaId("radio:net/rmf/5").setUri("https://example.invalid/stream")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("RMF FM").build()).build()
        player.setMediaItem(item)
        val reasons = mutableListOf<Int>()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { reasons += reason }
        })
        val updated = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle("Everlasting Love").setArtist("Sandra · RMF FM").build()).build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
        assertThat(reasons).isEmpty()
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("Everlasting Love")
        assertThat(player.currentMediaItem?.mediaId).isEqualTo("radio:net/rmf/5")
        player.release()
    }
}
