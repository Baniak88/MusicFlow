package app.musicflow

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicflow.playback.service.MusicService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Browses the media library the way Android Auto does (a MediaBrowser on the session):
 * the four tabs and their folders must be there, without needing network access.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AutoBrowseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    @Test fun libraryTreeHasEverything() {
        val token = SessionToken(context, android.content.ComponentName(context, MusicService::class.java))
        val browser = onMain { MediaBrowser.Builder(context, token).buildAsync() }.get(30, TimeUnit.SECONDS)
        try {
            val root = onMain { browser.getLibraryRoot(null) }.get(30, TimeUnit.SECONDS).value!!
            fun children(id: String): List<MediaItem> =
                onMain { browser.getChildren(id, 0, 200, null) }.get(60, TimeUnit.SECONDS).value.orEmpty().toList()
            val tabs = children(root.mediaId)
            assertEquals(listOf("home", "library", "radio_tab", "explore"), tabs.map { it.mediaId })
            assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true })

            assertEquals(listOf("continue", "recent", "most_played", "trending"), children("home").map { it.mediaId })
            assertEquals(listOf("favorites", "playlists", "albums", "artists", "local_music"), children("library").map { it.mediaId })
            val radio = children("radio_tab").map { it.mediaId }
            assertTrue(radio.toString(), radio.take(2) == listOf("category|RADIO_RMF_ON", "category|RADIO_ESKA"))
            val explore = children("explore").map { it.mediaId }
            assertEquals(4, explore.size)
            val genres = children(explore.first()).map { it.mediaId }
            assertTrue(genres.toString(), genres.contains("category|ROCK"))
            // History lists load from the local database (empty on a fresh install, but no error).
            children("recent")
        } finally {
            onMain { browser.release() }
        }
    }
}
