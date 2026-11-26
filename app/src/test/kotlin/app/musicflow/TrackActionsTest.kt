package app.musicflow

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.PlaylistRepository
import app.musicflow.playback.engine.PlaybackEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Reported: "adding to a playlist and to the queue doesn't work". Exercises the real UI path. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class TrackActionsTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var engine: PlaybackEngine
    @Inject lateinit var playlists: PlaylistRepository

    @Before fun inject() = hilt.inject()

    private val tracks = (1..3).map { Track(MediaId("url:https://example.com/$it.mp3"), "Song $it", durationMs = 200_000) }

    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun openTrackOptionsInPlayer() {
        compose.onNodeWithTag("mini_player").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("full_player")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("More options"))[0].performClick()
        waitText("Track options")
        compose.onAllNodesWithText("Track options")[0].performClick()
    }

    @Test fun addToQueueFromThePlayerMenu() {
        compose.runOnUiThread { engine.play(tracks.take(2), 0) }
        waitText("Song 1")
        openTrackOptionsInPlayer()
        waitText("Add to queue")
        compose.onAllNodesWithText("Add to queue")[0].performClick()
        compose.waitUntil(5_000) { engine.state.value.queue.size == 3 }
        assertEquals("Song 1", engine.state.value.queue.last().track.title)
        waitText("Added to queue: Song 1")
    }

    @Test fun addToANewPlaylistFromThePlayerMenu() {
        compose.runOnUiThread { engine.play(tracks, 1) }
        waitText("Song 2")
        openTrackOptionsInPlayer()
        waitText("Add to playlist")
        compose.onAllNodesWithText("Add to playlist")[0].performClick()
        waitText("New playlist")
        compose.onAllNodesWithText("New playlist")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Moja")
        compose.onAllNodes(hasText("Create"))[0].performClick()
        // The confirmation is shown on top of the open player.
        waitText("Added to Moja")
        val p = runBlocking { playlists.playlists.first().first { it.name == "Moja" } }
        val entries = runBlocking { playlists.entries(p.id).first() }
        assertEquals(listOf("Song 2"), entries.map { it.track.title })
    }
}
