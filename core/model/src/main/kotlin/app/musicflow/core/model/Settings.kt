package app.musicflow.core.model

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class RepeatMode { OFF, ALL, ONE }

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val amoledBlack: Boolean = false,
    val dynamicColor: Boolean = false,
    val artworkColors: Boolean = true,
    /** BCP-47 language tag, empty = follow system. */
    val languageTag: String = "",
    val qualityWifi: AudioQuality = AudioQuality.HIGH,
    val qualityMobile: AudioQuality = AudioQuality.STANDARD,
    val cacheSizeMb: Int = 512,
    val autoContinueQueue: Boolean = true,
    val gaplessPlayback: Boolean = true,
    val volumeLeveling: Boolean = false,
    val skipSilence: Boolean = false,
    val pauseOnDisconnect: Boolean = true,
    val resumeOnBluetooth: Boolean = false,
    val discoveryMode: Boolean = false,
    val youtubeProviderEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = true,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val contentCountry: String = "",
)

/** Configurable buffering; larger buffers survive network drops better at the cost of RAM/data. */
enum class BufferProfile(val minBufferMs: Int, val maxBufferMs: Int) {
    LOW(15_000, 30_000),
    BALANCED(30_000, 90_000),
    LARGE(60_000, 240_000),
}
