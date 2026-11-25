package app.musicflow

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the real MainActivity (Hilt graph, Room, DataStore) and walks the main navigation. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun inject() = hilt.inject()

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun launchesOnHomeAndNavigatesThroughAllTabs() {
        waitForText("Welcome to MusicFlow")
        compose.onNodeWithTag("tab_explore").performClick()
        waitForText("Electronic")
        // Title and bottom-bar label.
        compose.onAllNodesWithText("Explore").assertCountEquals(2)

        compose.onNodeWithTag("tab_search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search_field").assertIsDisplayed()

        compose.onNodeWithTag("tab_library").performClick()
        waitForText("Liked songs")
        waitForText("No playlists yet")

        compose.onNodeWithTag("tab_home").performClick()
        waitForText("Welcome to MusicFlow")
    }

    @Test fun opensCategoryAndSettingsAndGoesBack() {
        compose.onNodeWithTag("tab_explore").performClick()
        waitForText("Electronic")
        compose.onNodeWithText("Electronic").performClick()
        // No online provider in tests → the category shows an honest empty state, never fake data.
        waitForText("Nothing found for this category right now.")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("Electronic")

        compose.onNodeWithTag("tab_home").performClick()
        waitForText("Welcome to MusicFlow")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Settings")).performClick()
        waitForText("Theme")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("Welcome to MusicFlow")
    }
}

/** Mini player appears when something plays, expands to the full player and collapses again. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class PlayerNavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @javax.inject.Inject lateinit var engine: app.musicflow.playback.engine.PlaybackEngine
    @javax.inject.Inject lateinit var settings: app.musicflow.data.repositories.SettingsRepository

    @Before fun inject() = hilt.inject()

    @Test fun miniPlayerOpensFullPlayerAndControlsWork() {
        val tracks = (1..3).map { app.musicflow.core.model.Track(app.musicflow.core.model.MediaId("url:https://example.com/$it.mp3"), "Song $it", durationMs = 200_000) }
        compose.runOnUiThread { engine.play(tracks, 0) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Song 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mini_player").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("full_player")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("play_pause").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertFalse(engine.state.value.isPlaying)
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Next track"))[1].performClick()
        compose.waitUntil(5_000) { engine.state.value.current?.title == "Song 2" }
        compose.onNodeWithTag("full_player").assertExists()

        // Quality picker in the player writes the setting for the current network type.
        compose.onNodeWithTag("quality_line").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Data saver").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Data saver").performClick()
        repeat(5) { compose.waitForIdle(); Thread.sleep(200) }
        val q = kotlinx.coroutines.runBlocking { settings.current() }
        org.junit.Assert.assertTrue(
            q.qualityWifi == app.musicflow.core.model.AudioQuality.DATA_SAVER || q.qualityMobile == app.musicflow.core.model.AudioQuality.DATA_SAVER,
        )
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("full_player")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("mini_player").assertExists()
    }
}

/** Regression tests for bottom-bar behaviour reported by a tester ("navigation doesn't work properly"). */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class TabNavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun inject() = hilt.inject()

    private fun waitForText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun tab(name: String) = compose.onNodeWithTag("tab_$name")

    @Test fun reselectingCurrentTabReturnsToItsRootAndTabStaysHighlighted() {
        waitForText("Welcome to MusicFlow")
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Settings")).performClick()
        waitForText("Theme")
        tab("home").assertIsSelected() // sub-screen still belongs to the Home tab
        tab("home").performClick()     // previously: nothing happened
        waitForText("Welcome to MusicFlow")
        compose.onAllNodesWithText("Theme").assertCountEquals(0)
    }

    @Test fun switchingTabsKeepsEachTabsStackAndHighlight() {
        tab("explore").performClick()
        waitForText("Electronic")
        compose.onNodeWithText("Electronic").performClick()
        waitForText("Nothing found for this category right now.")
        tab("explore").assertIsSelected()
        tab("library").performClick()
        waitForText("Liked songs")
        tab("library").assertIsSelected()
        tab("explore").performClick()   // returns to the open category (own stack)
        waitForText("Nothing found for this category right now.")
        tab("explore").performClick()   // second tap: back to the Explore grid
        waitForText("Dance")
        tab("explore").assertIsSelected()
    }

    @Test fun searchTabFromHomeAndBackReturnsHome() {
        waitForText("Welcome to MusicFlow")
        tab("search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search_field").fetchSemanticsNodes().isNotEmpty() }
        tab("search").assertIsSelected()
        tab("home").assertIsNotSelected()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("Welcome to MusicFlow")
        tab("home").assertIsSelected()
    }

    @Test fun doubleTapOpensAScreenOnlyOnce() {
        tab("explore").performClick()
        waitForText("Electronic")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Electronic").performClick()
        compose.onNodeWithText("Electronic").performClick() // second tap before any frame
        compose.mainClock.autoAdvance = true
        waitForText("Nothing found for this category right now.")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitForText("Dance") // one Back is enough: there was only one copy of the screen
        compose.onAllNodesWithText("Nothing found for this category right now.").assertCountEquals(0)
    }

    @Test fun doubleTapOnBackArrowGoesBackOnlyOnce() {
        tab("explore").performClick()
        waitForText("Electronic")
        compose.onNodeWithText("Electronic").performClick()
        waitForText("Nothing found for this category right now.")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.autoAdvance = true
        waitForText("Dance") // still in Explore, not thrown out to Home
        tab("explore").assertIsSelected()
        compose.onAllNodesWithText("Welcome to MusicFlow").assertCountEquals(0)
    }

    @Test fun statusBarHasAScrimOverScrollingContent() {
        compose.onNodeWithTag("status_bar_scrim").assertExists()
    }
}
