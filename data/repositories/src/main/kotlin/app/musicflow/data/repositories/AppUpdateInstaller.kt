package app.musicflow.data.repositories

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.musicflow.core.common.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of an in-app update, shown on Home and in Settings. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val version: String) : InstallState
    data class Installing(val version: String) : InstallState
    /** Android asks the user to confirm (first update, or "install unknown apps" not allowed yet). */
    data class NeedsConfirmation(val version: String) : InstallState
    data class Failed(val version: String, val message: String) : InstallState
}

/**
 * Downloads a newer MusicFlow APK from the project's GitHub release and installs it with
 * Android's [PackageInstaller]. Nothing is executed from the download except through the system
 * installer, and only after these checks:
 *
 * 1. SHA-256 matches the release's `SHA256SUMS.txt` (when the release publishes one);
 * 2. it is the same package, with a higher version code;
 * 3. it is signed with **the same certificate** as the installed app (Android would refuse
 *    otherwise anyway; checking first gives a clear message and avoids a useless prompt).
 *
 * On Android 12+ an app may update itself without a prompt (`UPDATE_PACKAGES_WITHOUT_USER_ACTION`)
 * once the user has allowed it to install apps; otherwise Android shows its confirmation.
 */
@Singleton
class AppUpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    /** Whether the user allowed MusicFlow to install apps ("Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows MusicFlow to install updates. */
    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * @param interactive true when the user tapped "Install" (Android's confirmation may be shown
     * right away); false from the background job (a needed confirmation is left for the banner).
     */
    suspend fun install(update: UpdateInfo, interactive: Boolean): Result<Unit> = runCatching {
        val version = update.version.toString()
        _state.value = InstallState.Downloading(version)
        val apk = withContext(Dispatchers.IO) { download(update) }
        withContext(Dispatchers.IO) { verify(apk, update) }
        _state.value = InstallState.Installing(version)
        withContext(Dispatchers.IO) { commit(apk, version, interactive) }
    }.onFailure { e ->
        val msg = e.message ?: e::class.java.simpleName
        diagnostics.w("update", "Update ${update.version} not installed: $msg")
        _state.value = InstallState.Failed(update.version.toString(), msg)
    }

    private fun dir() = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun download(update: UpdateInfo): File {
        val target = File(dir(), "MusicFlow-${update.version}.apk")
        val part = File(dir(), target.name + ".part")
        dir().listFiles()?.filter { it != target && it != part }?.forEach { it.delete() } // old downloads
        if (target.exists() && update.sizeBytes != null && target.length() == update.sizeBytes) return target
        client.newCall(Request.Builder().url(update.apkUrl).header("User-Agent", "MusicFlow-updater").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            val body = r.body ?: throw IOException("Download failed: empty response")
            part.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (update.sizeBytes != null && part.length() != update.sizeBytes) {
            part.delete(); throw IOException("Download incomplete (${part.length()} of ${update.sizeBytes} bytes)")
        }
        if (!part.renameTo(target)) throw IOException("Could not save the update")
        return target
    }

    private fun verify(apk: File, update: UpdateInfo) {
        update.checksumUrl?.let { url ->
            val sums = client.newCall(Request.Builder().url(url).header("User-Agent", "MusicFlow-updater").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Checksum download failed: HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            val name = Uri.parse(update.apkUrl).lastPathSegment.orEmpty()
            val expected = UpdateChecks.checksumFor(sums, name) ?: throw SecurityException("No checksum published for $name")
            if (!UpdateChecks.sha256(apk).equals(expected, ignoreCase = true)) { apk.delete(); throw SecurityException("Checksum mismatch: the download is damaged or was altered") }
        }
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: run { apk.delete(); throw SecurityException("The download is not a valid APK") }
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) throw SecurityException("The update is for another app (${archive.packageName})")
        if (archive.longVersionCode <= installed.longVersionCode) throw IllegalStateException("The update is not newer than the installed version")
        if (UpdateChecks.signers(archive) != UpdateChecks.signers(installed)) {
            throw SecurityException("The update is signed with a different key; install it manually after uninstalling this version")
        }
    }

    private fun commit(apk: File, version: String, interactive: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
                .putExtra(UpdateInstallReceiver.EXTRA_VERSION, version)
                .putExtra(UpdateInstallReceiver.EXTRA_INTERACTIVE, interactive)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        diagnostics.i("update", "Installing MusicFlow $version (session $id, interactive=$interactive)")
    }

    internal fun onStatus(version: String, status: Int, message: String?, confirm: Intent?, interactive: Boolean) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _state.value = InstallState.NeedsConfirmation(version)
                // Activities can only be started while the user is in the app.
                if (interactive && confirm != null) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                diagnostics.i("update", "Update $version waits for the user's confirmation")
            }
            PackageInstaller.STATUS_SUCCESS -> { _state.value = InstallState.Idle; diagnostics.i("update", "Updated to $version") }
            else -> {
                _state.value = InstallState.Failed(version, message ?: "Installation failed ($status)")
                diagnostics.w("update", "Installing $version failed: $status $message")
            }
        }
    }
}

/** Pure helpers, unit-tested. */
object UpdateChecks {
    /** Finds `<sha256>  <name>` in a `sha256sum` listing. */
    fun checksumFor(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("""\s+\*?"""), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].trim() == fileName && it[0].matches(Regex("[0-9a-fA-F]{64}")) }
        ?.get(0)

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun signers(info: PackageInfo): Set<String> {
        val s = info.signingInfo ?: return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return certs.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}

/** Receives the installer's result for a session started by [AppUpdateInstaller]. */
@dagger.hilt.android.AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var installer: AppUpdateInstaller

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        installer.onStatus(
            version = intent.getStringExtra(EXTRA_VERSION).orEmpty(),
            status = status,
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm = confirm,
            interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false),
        )
    }

    companion object {
        const val EXTRA_VERSION = "app.musicflow.update.VERSION"
        const val EXTRA_INTERACTIVE = "app.musicflow.update.INTERACTIVE"
    }
}
