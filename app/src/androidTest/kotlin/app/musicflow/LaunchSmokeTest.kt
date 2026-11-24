package app.musicflow

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicflow.playback.service.MusicService
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Runs on a real device / emulator (CI: .github/workflows/android-device-tests.yml).
 * Verifies the production app starts, its navigation works and the media session service is reachable.
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appLaunchesAndAllTabsOpen() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("tab_home").fetchSemanticsNodes().isNotEmpty() }
        listOf("tab_explore", "tab_search", "tab_library", "tab_home").forEach { tag ->
            compose.onNodeWithTag(tag).performClick()
            compose.waitForIdle()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Test fun mediaSessionServiceAcceptsControllers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = SessionToken(context, android.content.ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = future.get(30, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(controller.isConnected)
            assertTrue(controller.availableCommands.contains(androidx.media3.common.Player.COMMAND_PLAY_PAUSE))
            controller.release()
        }
    }
}
