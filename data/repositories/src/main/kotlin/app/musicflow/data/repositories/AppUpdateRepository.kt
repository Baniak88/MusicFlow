package app.musicflow.data.repositories

import android.content.Context
import app.musicflow.core.common.AppInfo
import app.musicflow.core.common.Clock
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.defaultClassify
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Semantic version with an optional beta number; a final release sorts after its betas. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int?) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this, other, { it.major }, { it.minor }, { it.patch }, { it.beta ?: Int.MAX_VALUE },
    )

    override fun toString() = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    companion object {
        private val pattern = Regex("""(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?""")
        fun parse(text: String): AppVersion? = pattern.find(text)?.destructured?.let { (a, b, c, d) ->
            AppVersion(a.toInt(), b.toInt(), c.toInt(), d.toIntOrNull())
        }
    }
}

data class UpdateInfo(
    val version: AppVersion,
    val apkUrl: String,
    val pageUrl: String,
    val sizeBytes: Long?,
    /** The release's SHA256SUMS.txt, used to verify the downloaded APK. */
    val checksumUrl: String? = null,
)

/**
 * Checks GitHub Releases of the project for a newer build of the installed channel.
 * Only the public releases list is requested (no account, no identifiers), at most every
 * [intervalMs], and only when the user keeps "Check for updates" enabled.
 */
@Singleton
class AppUpdateRepository internal constructor(
    private val client: OkHttpClient,
    private val appInfo: AppInfo,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
    private val prefs: android.content.SharedPreferences,
    private val apiBase: HttpUrl,
    private val clock: Clock,
) {
    @Inject constructor(
        client: OkHttpClient, appInfo: AppInfo, settings: SettingsRepository, diagnostics: DiagnosticsLog,
        @ApplicationContext context: Context,
    ) : this(
        client, appInfo, settings, diagnostics,
        context.getSharedPreferences("updates", Context.MODE_PRIVATE), "https://api.github.com/".toHttpUrl(), Clock.System,
    )

    private val intervalMs = 6L * 3600 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long? = null)
    @Serializable private data class Release(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        @SerialName("html_url") val htmlUrl: String,
        val assets: List<Asset> = emptyList(),
    )

    private val _available = MutableStateFlow(restore())
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    val installed: AppVersion? get() = AppVersion.parse(appInfo.versionName)

    /** Test builds (debug/beta) follow beta APKs; release builds follow final APKs only. */
    private fun wantsAsset(name: String): Boolean = when (appInfo.buildType) {
        "release" -> name.endsWith(".apk") && !name.contains("-beta") && !name.contains("-debug")
        else -> name.endsWith("-beta.apk") || name.endsWith("-debug.apk")
    }

    suspend fun check(force: Boolean = false): Result<UpdateInfo?> {
        if (!force && !settings.current().checkUpdates) return Result.success(null)
        val now = clock.now()
        if (!force && now - prefs.getLong(KEY_LAST, 0) < intervalMs) return Result.success(_available.value)
        return runCatching {
            val releases = withContext(Dispatchers.IO) {
                val url = apiBase.newBuilder().addPathSegments("repos/${appInfo.updateRepo}/releases").addQueryParameter("per_page", "15").build()
                val req = Request.Builder().url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MusicFlow-update-check")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    json.decodeFromString(ListSerializer(Release.serializer()), r.body?.string().orEmpty())
                }
            }
            prefs.edit().putLong(KEY_LAST, now).apply()
            val current = installed
            releases.asSequence()
                .filter { !it.draft }
                .mapNotNull { rel ->
                    val v = AppVersion.parse(rel.tag) ?: return@mapNotNull null
                    val asset = rel.assets.firstOrNull { wantsAsset(it.name) } ?: return@mapNotNull null
                    UpdateInfo(v, asset.url, rel.htmlUrl, asset.size, rel.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.url)
                }
                .maxByOrNull { it.version }
                ?.takeIf { current != null && it.version > current }
                .also { _available.value = it; persist(it); diagnostics.i("update", "Update check: installed=$current newest=${it?.version}") }
        }.onFailure { diagnostics.w("update", "Update check failed: ${defaultClassify(it).message}") }
    }

    /** The last found update survives restarts, so the throttle never hides a known update. */
    private fun restore(): UpdateInfo? {
        val v = AppVersion.parse(prefs.getString(KEY_VERSION, null) ?: return null) ?: return null
        val apk = prefs.getString(KEY_APK, null) ?: return null
        val installed = AppVersion.parse(appInfo.versionName) ?: return null
        if (v <= installed) return null
        return UpdateInfo(v, apk, prefs.getString(KEY_PAGE, null).orEmpty(), prefs.getLong(KEY_SIZE, -1).takeIf { it >= 0 }, prefs.getString(KEY_SUMS, null))
    }

    private fun persist(u: UpdateInfo?) {
        prefs.edit().apply {
            if (u == null) remove(KEY_VERSION).remove(KEY_APK).remove(KEY_PAGE).remove(KEY_SIZE).remove(KEY_SUMS)
            else putString(KEY_VERSION, u.version.toString()).putString(KEY_APK, u.apkUrl).putString(KEY_PAGE, u.pageUrl).putLong(KEY_SIZE, u.sizeBytes ?: -1).putString(KEY_SUMS, u.checksumUrl)
        }.apply()
    }

    companion object {
        private const val KEY_LAST = "last_check"
        private const val KEY_VERSION = "found_version"
        private const val KEY_APK = "found_apk"
        private const val KEY_PAGE = "found_page"
        private const val KEY_SIZE = "found_size"
        private const val KEY_SUMS = "found_sums"
    }
}
