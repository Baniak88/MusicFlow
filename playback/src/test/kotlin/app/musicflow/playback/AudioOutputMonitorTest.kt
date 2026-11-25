package app.musicflow.playback

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioOutputMonitorTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Pixel"
    @Test fun picksRoutedOutput() {
        assertThat(AudioOutputMonitor.pick(listOf(speaker))).isEqualTo(AudioOutput(OutputKind.PHONE, null))
        assertThat(AudioOutputMonitor.pick(listOf(speaker, AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "x")).kind).isEqualTo(OutputKind.WIRED)
        assertThat(AudioOutputMonitor.pick(listOf(speaker, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Galaxy Buds2"))).isEqualTo(AudioOutput(OutputKind.BLUETOOTH, "Galaxy Buds2"))
        assertThat(AudioOutputMonitor.pick(listOf(speaker, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "BMW 12345"))).isEqualTo(AudioOutput(OutputKind.CAR, "BMW 12345"))
        assertThat(AudioOutputMonitor.pick(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "My Car")).kind).isEqualTo(OutputKind.CAR)
        assertThat(AudioOutputMonitor.pick(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Scarlet Speaker")).kind).isEqualTo(OutputKind.BLUETOOTH) // "car" only as a word
    }
}
