package app.musicflow.data.repositories

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateChecksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun findsTheChecksumOfTheDownloadedApkOnly() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val sums = "$a  MusicFlow-1.0.0-beta.9-beta.apk\n$b *MusicFlow-1.0.0.apk\nnot a line\n"
        assertThat(UpdateChecks.checksumFor(sums, "MusicFlow-1.0.0-beta.9-beta.apk")).isEqualTo(a)
        assertThat(UpdateChecks.checksumFor(sums, "MusicFlow-1.0.0.apk")).isEqualTo(b) // binary-mode marker
        assertThat(UpdateChecks.checksumFor(sums, "other.apk")).isNull()
        assertThat(UpdateChecks.checksumFor("xyz  MusicFlow-1.0.0.apk", "MusicFlow-1.0.0.apk")).isNull() // not a hash
    }

    @Test fun sha256MatchesTheStandardValue() {
        val f = tmp.newFile().apply { writeText("abc") }
        assertThat(UpdateChecks.sha256(f)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
