package app.musicflow.data.repositories

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.common.AppInfo
import app.musicflow.core.common.Clock
import app.musicflow.core.common.DiagnosticsLog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000_000L
    private var stores = 0

    private val releases = """[
      {"tag_name":"v1.0.0-beta.10","draft":false,"html_url":"https://x/r10","assets":[{"name":"MusicFlow-1.0.0-beta.10-beta.apk","browser_download_url":"https://x/b10.apk","size":9500000}]},
      {"tag_name":"v1.0.0-beta.6","draft":false,"html_url":"https://x/r6","assets":[{"name":"MusicFlow-1.0.0-beta.6-beta.apk","browser_download_url":"https://x/b6.apk"}]},
      {"tag_name":"v1.0.1-beta.1","draft":true,"html_url":"https://x/draft","assets":[{"name":"MusicFlow-1.0.1-beta.1-beta.apk","browser_download_url":"https://x/draft.apk"}]},
      {"tag_name":"v1.0.0","draft":false,"html_url":"https://x/final","assets":[{"name":"MusicFlow-1.0.0.apk","browser_download_url":"https://x/final.apk"}]},
      {"tag_name":"v0.9.0-beta.1","draft":false,"html_url":"https://x/old","assets":[]}
    ]"""

    private fun repo(server: MockWebServer, version: String, buildType: String = "beta", enabled: Boolean = true): AppUpdateRepository {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(PreferenceDataStoreFactory.create { java.io.File(tmp.root, "s${++stores}.preferences_pb") })
        if (!enabled) kotlinx.coroutines.runBlocking { settings.setCheckUpdates(false) }
        return AppUpdateRepository(
            OkHttpClient(), AppInfo(version, 1, buildType, 34, "test", "owner/app"), settings, DiagnosticsLog(),
            ctx.getSharedPreferences("u${tmp.root.name}", Context.MODE_PRIVATE), server.url("/"), Clock { now },
        )
    }

    @Test fun versionOrdering() {
        val v = { s: String -> AppVersion.parse(s)!! }
        assertThat(v("1.0.0-beta.10")).isGreaterThan(v("1.0.0-beta.9"))   // numeric, not text
        assertThat(v("1.0.0")).isGreaterThan(v("1.0.0-beta.99"))          // final after its betas
        assertThat(v("1.0.1-beta.1")).isGreaterThan(v("1.0.0"))
        assertThat(v("1.0.0-beta.5-beta")).isEqualTo(v("1.0.0-beta.5"))   // beta build-type suffix ignored
        assertThat(AppVersion.parse("garbage")).isNull()
    }

    @Test fun betaBuildFindsNewestBetaApkIgnoringDrafts() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(releases))
            val u = repo(s, "1.0.0-beta.5-beta").check().getOrThrow()!!
            assertThat(u.version.toString()).isEqualTo("1.0.0-beta.10")
            assertThat(u.apkUrl).isEqualTo("https://x/b10.apk")
            assertThat(s.takeRequest().path).isEqualTo("/repos/owner/app/releases?per_page=15")
        }
    }

    @Test fun releaseBuildFollowsFinalApksOnly() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(releases))
            val u = repo(s, "0.9.0", buildType = "release").check().getOrThrow()!!
            assertThat(u.apkUrl).isEqualTo("https://x/final.apk")
        }
    }

    @Test fun upToDateOrNewerReportsNothing() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(releases))
            assertThat(repo(s, "1.0.0-beta.10-beta").check().getOrThrow()).isNull()
        }
    }

    @Test fun throttledAndRespectsOffSwitch() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(releases))
            val r = repo(s, "1.0.0-beta.5")
            r.check().getOrThrow()
            r.check().getOrThrow()                 // within 6 h → cached, no request
            assertThat(s.requestCount).isEqualTo(1)
            s.enqueue(MockResponse().setBody(releases))
            r.check(force = true).getOrThrow()      // "Check now" bypasses the interval
            assertThat(s.requestCount).isEqualTo(2)
            val off = repo(s, "1.0.0-beta.5", enabled = false)
            assertThat(off.check().getOrThrow()).isNull()
            assertThat(s.requestCount).isEqualTo(2) // disabled → no network at all
        }
    }

    @Test fun foundUpdateSurvivesRestartWithoutNetwork() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody(releases))
            repo(s, "1.0.0-beta.5").check().getOrThrow()
            val restarted = repo(s, "1.0.0-beta.5")           // same prefs, new process
            assertThat(restarted.available.value?.version.toString()).isEqualTo("1.0.0-beta.10")
            assertThat(restarted.check().getOrThrow()?.apkUrl).isEqualTo("https://x/b10.apk")
            assertThat(s.requestCount).isEqualTo(1)
            assertThat(repo(s, "1.0.0-beta.10").available.value).isNull() // already installed
        }
    }

    @Test fun networkErrorsAreReportedNotThrown() = runTest {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(503))
            assertThat(repo(s, "1.0.0-beta.5").check().isFailure).isTrue()
        }
    }
}
