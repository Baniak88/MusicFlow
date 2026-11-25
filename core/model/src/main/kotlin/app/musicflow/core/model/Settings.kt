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
    val qualityWifi: AudioQuality = AudioQuality.BEST,
    val qualityMobile: AudioQuality = AudioQuality.HIGH,
    val cacheSizeMb: Int = 512,
    val autoContinueQueue: Boolean = true,
    /** Overlap between consecutive tracks, 0 = off (gapless). */
    val crossfadeSeconds: Int = 0,
    val volumeLeveling: Boolean = false,
    /** Per-track loudness normalisation target (Spotify-like levels). */
    val normalization: NormalizationLevel = NormalizationLevel.NORMAL,
    val skipSilence: Boolean = false,
    val pauseOnDisconnect: Boolean = true,
    val resumeOnBluetooth: Boolean = false,
    val discoveryMode: Boolean = false,
    val youtubeProviderEnabled: Boolean = true,
    val soundcloudProviderEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = true,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val contentCountry: String = "",
    /** Periodically check GitHub Releases for a newer version (no account, no identifiers). */
    val checkUpdates: Boolean = true,
)

/** Configurable buffering; larger buffers survive network drops better at the cost of RAM/data. */
enum class BufferProfile(val minBufferMs: Int, val maxBufferMs: Int) {
    LOW(15_000, 30_000),
    BALANCED(30_000, 90_000),
    LARGE(60_000, 240_000),
}

/** Loudness normalisation targets, matching the levels Spotify offers. */
enum class NormalizationLevel(val targetLufs: Float?) {
    OFF(null),
    QUIET(-19f),
    NORMAL(-14f),
    LOUD(-11f),
}
