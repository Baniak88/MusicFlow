package app.musicflow.core.common

/** Build information, provided by the app module (which owns BuildConfig). */
data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val sdkInt: Int,
    val device: String,
    /** "owner/repo" whose GitHub Releases provide updates. */
    val updateRepo: String = "Baniak88/MusicFlow",
    /** NewPipe Extractor version built into this APK (kept current by CI, see PROVIDERS.md). */
    val extractorVersion: String = "",
)
