package app.musicflow.core.common

/** Build information, provided by the app module (which owns BuildConfig). */
data class AppInfo(val versionName: String, val versionCode: Long, val buildType: String, val sdkInt: Int, val device: String)
