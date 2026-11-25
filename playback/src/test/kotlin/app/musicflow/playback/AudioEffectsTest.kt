package app.musicflow.playback

import android.media.audiofx.AudioEffect
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.playback.effects.AudioEffects
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reported: compression silently stopped working until toggled off and on again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioEffectsTest {
    private fun effects() = AudioEffects(ApplicationProvider.getApplicationContext(), DiagnosticsLog()).apply { isWorking = { it.enabled } }
    private fun AudioEffects.field(name: String): AudioEffect? =
        AudioEffects::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as AudioEffect?

    @Test fun compressionDisabledFromOutsideIsRestored() {
        val fx = effects()
        fx.attach(42)
        fx.setLeveling(true)
        val first = fx.field("dynamics")!!
        assertThat(first.enabled).isTrue()
        assertThat(fx.ensureActive()).isEqualTo(0)        // healthy: nothing to do

        first.enabled = false                              // e.g. the phone's sound effects took over
        assertThat(fx.ensureActive()).isEqualTo(1)
        val second = fx.field("dynamics")!!
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.enabled).isTrue()
    }

    @Test fun switchedOffStaysOff() {
        val fx = effects()
        fx.attach(42)
        fx.setLeveling(true)
        fx.setLeveling(false)
        assertThat(fx.ensureActive()).isEqualTo(0)
        assertThat(fx.field("dynamics")).isNull()
    }

    @Test fun compressionEnabledBeforeTheSessionExistsStartsWithIt() {
        val fx = effects()
        fx.setLeveling(true)                               // settings arrive before the player session
        assertThat(fx.field("dynamics")).isNull()
        fx.attach(42)
        assertThat(fx.field("dynamics")?.enabled).isTrue()
    }
}
