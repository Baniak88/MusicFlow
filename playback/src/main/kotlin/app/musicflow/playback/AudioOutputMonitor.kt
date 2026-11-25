package app.musicflow.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class OutputKind { PHONE, WIRED, BLUETOOTH, CAR, USB }

/** Where audio is currently heard ("Playing on …"). [name] is null for the phone itself. */
data class AudioOutput(val kind: OutputKind, val name: String?)

/** Tracks the active audio output (speaker, wired/USB headset, Bluetooth device, car). */
@Singleton
class AudioOutputMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _output = MutableStateFlow(AudioOutput(OutputKind.PHONE, null))
    val output: StateFlow<AudioOutput> = _output.asStateFlow()

    init {
        refresh()
        runCatching {
            audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun refresh() {
        val devices = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        _output.value = pick(devices.map { it.type to it.productName?.toString() })
    }

    companion object {
        private val carWords = Regex(
            """\b(car|auto|carplay|bmw|audi|vw|volkswagen|skoda|škoda|seat|cupra|toyota|lexus|ford|kia|hyundai|mercedes|benz|opel|renault|dacia|peugeot|citro[eë]n|volvo|mazda|honda|nissan|tesla|fiat|jeep|mitsubishi|suzuki|subaru|porsche|mini|uconnect|sync|mbux|my\s?car)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Chooses the output Android routes media to: Bluetooth > wired/USB > phone speaker. */
        fun pick(devices: List<Pair<Int, String?>>): AudioOutput {
            devices.firstOrNull { it.first == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { (_, name) ->
                val clean = name?.trim()?.takeIf { it.isNotEmpty() }
                return AudioOutput(if (clean != null && carWords.containsMatchIn(clean)) OutputKind.CAR else OutputKind.BLUETOOTH, clean)
            }
            devices.firstOrNull { it.first == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.first == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                ?.let { return AudioOutput(OutputKind.WIRED, null) }
            devices.firstOrNull { it.first == AudioDeviceInfo.TYPE_USB_HEADSET || it.first == AudioDeviceInfo.TYPE_USB_DEVICE }
                ?.let { (_, name) -> return AudioOutput(OutputKind.USB, name?.takeIf { it.isNotBlank() }) }
            return AudioOutput(OutputKind.PHONE, null)
        }
    }
}
