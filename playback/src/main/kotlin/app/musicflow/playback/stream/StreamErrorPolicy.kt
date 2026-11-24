package app.musicflow.playback.stream

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.RetryPolicy
import app.musicflow.core.model.ProviderError
import kotlinx.coroutines.runBlocking

/** What to do with a failed load. Pure function of the error, so it is unit-testable. */
sealed interface LoadDecision {
    data class Retry(val delayMs: Long, val renewUrl: Boolean = false) : LoadDecision
    data object Fail : LoadDecision
}

object StreamErrorClassifier {
    private val network = RetryPolicy(maxAttempts = 8, initialDelayMs = 1_000, maxDelayMs = 20_000, jitterRatio = 0.1)

    fun decide(error: Throwable, attempt: Int, isOnline: Boolean = true): LoadDecision {
        val root = generateSequence(error) { it.cause }.toList()
        root.firstNotNullOfOrNull { it as? StreamResolutionException }?.let { e ->
            return if (e.error.isTransient && attempt < network.maxAttempts) LoadDecision.Retry(network.delayFor(e.error, attempt)) else LoadDecision.Fail
        }
        root.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let { e ->
            return when (e.responseCode) {
                // Signed URL expired or was revoked: resolve a fresh URL, at most twice.
                403, 410 -> if (attempt <= 2) LoadDecision.Retry(300, renewUrl = true) else LoadDecision.Fail
                404 -> LoadDecision.Fail
                416 -> LoadDecision.Fail
                408, 429, in 500..599 -> if (attempt < network.maxAttempts) LoadDecision.Retry(network.delayFor(ProviderError.RateLimited(), attempt)) else LoadDecision.Fail
                else -> LoadDecision.Fail
            }
        }
        if (root.any { it is HttpDataSource.HttpDataSourceException || it is java.io.IOException && it !is java.io.FileNotFoundException }) {
            if (attempt >= network.maxAttempts) return LoadDecision.Fail
            // Offline: wait longer; recovery is also triggered by the connectivity monitor.
            val d = network.delayFor(attempt)
            return LoadDecision.Retry(if (isOnline) d else maxOf(d, 5_000))
        }
        return LoadDecision.Fail
    }
}

/**
 * ExoPlayer policy built on [StreamErrorClassifier]: retries transient failures with exponential
 * backoff, renews expired URLs, and never retries permanent errors.
 */
@UnstableApi
class StreamErrorPolicy(
    private val streams: StreamRepository,
    private val diagnostics: DiagnosticsLog,
    private val isOnline: () -> Boolean,
) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val decision = StreamErrorClassifier.decide(info.exception, info.errorCount, isOnline())
        return when (decision) {
            is LoadDecision.Retry -> {
                if (decision.renewUrl) {
                    val url = info.loadEventInfo.uri.toString()
                    val id = runBlocking { streams.invalidateUrl(url) }
                    diagnostics.w("playback", "Stream URL rejected (expired?) for $id; renewing")
                } else {
                    diagnostics.w("playback", "Load error #${info.errorCount}: ${info.exception::class.simpleName}; retry in ${decision.delayMs}ms")
                }
                decision.delayMs
            }
            LoadDecision.Fail -> {
                diagnostics.e("playback", "Giving up on load", info.exception)
                C.TIME_UNSET
            }
        }
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
}
