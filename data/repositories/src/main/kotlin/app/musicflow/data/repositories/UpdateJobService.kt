package app.musicflow.data.repositories

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.musicflow.core.common.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Background update: every ~6 h (when online) checks GitHub Releases and, if the user allows
 * automatic installation, downloads, verifies and installs the newer MusicFlow build — this is
 * how YouTube fixes (new NewPipe Extractor versions, bumped automatically in CI) reach the phone.
 * Uses the system JobScheduler; no extra library and no extra permission for scheduling.
 */
@dagger.hilt.android.AndroidEntryPoint
class UpdateJobService : JobService() {
    @Inject lateinit var updates: AppUpdateRepository
    @Inject lateinit var installer: AppUpdateInstaller
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var diagnostics: DiagnosticsLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val s = settings.current()
            val update = updates.check().getOrNull()
            if (update != null && s.autoInstallUpdates && installer.canInstall()) {
                diagnostics.i("update", "Background update to ${update.version}")
                installer.install(update, interactive = false)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true // retry later
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 0x4D46_5550 // "MFUP"
        private const val PERIOD_MS = 6L * 3600 * 1000

        /** Idempotent: (re)schedules the periodic job, or cancels it when checks are off. */
        fun schedule(context: Context, enabled: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                    .setPeriodic(PERIOD_MS, 60L * 60 * 1000)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
        }
    }
}
