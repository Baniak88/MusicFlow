package app.musicflow.playback

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.playback.engine.MediaControllerEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** "Track loading must be faster": likely tracks are resolved in the background before a tap. */
@RunWith(RobolectricTestRunner::class)
class PrefetchTest {
    @Test fun warmsTheFirstRemoteTracksOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MusicFlowDatabase::class.java).allowMainThreadQueries().build()
        val runtime = PlaybackRuntime()
        val warmed = Collections.synchronizedList(mutableListOf<String>())
        runtime.prefetcher = { t -> warmed += t.id.value }
        val engine = MediaControllerEngine(context, CoroutineScope(SupervisorJob()), LibraryRepository(db, app.musicflow.data.repositories.SettingsRepository(context)), runtime)

        fun t(id: String) = Track(MediaId(id), id)
        engine.prefetch(listOf(t("local:1"), t("radio:x"), t("yt:a"), t("yt:a"), t("sc:soundcloud.com/u/b"), t("yt:c"), t("yt:d")))
        val deadline = System.currentTimeMillis() + 5_000
        while (warmed.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(200)
        assertThat(warmed).containsExactly("yt:a", "sc:soundcloud.com/u/b", "yt:c").inOrder()
        db.close()
    }
}
