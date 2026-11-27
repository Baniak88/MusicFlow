package app.musicflow

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens at a phone size to PNG files for a visual review (no device needed).
 * Opt-in: `MF_SCREENSHOTS=<dir> ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @javax.inject.Inject lateinit var engine: app.musicflow.playback.engine.PlaybackEngine
    @javax.inject.Inject lateinit var playlists: app.musicflow.data.repositories.PlaylistRepository

    private val dir = System.getenv("MF_SCREENSHOTS")

    @Before fun setUp() {
        assumeTrue("screenshots not requested", !dir.isNullOrBlank())
        hilt.inject()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mainScreens() {
        kotlinx.coroutines.runBlocking { listOf("RADIO", "Rock", "Polski Eurodance", "Set Club Retro").forEach { playlists.create(it) } }
        val tracks = listOf("Rysa na szkle" to "Urszula", "Everlasting Love" to "Sandra", "Daj Mi Znać" to "Sobel").mapIndexed { i, (t, a) ->
            app.musicflow.core.model.Track(app.musicflow.core.model.MediaId("url:https://example.com/$i.mp3"), t,
                artists = listOf(app.musicflow.core.model.ArtistRef(null, a)), durationMs = 200_000)
        }
        compose.runOnUiThread { engine.play(tracks, 0) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rysa na szkle").fetchSemanticsNodes().isNotEmpty() }
        shot("01-home")
        compose.onNodeWithTag("tab_explore").performClick(); shot("02-explore")
        compose.onNodeWithTag("tab_search").performClick(); shot("03-search")
        compose.onNodeWithTag("tab_library").performClick(); shot("04-library")
    }
}
