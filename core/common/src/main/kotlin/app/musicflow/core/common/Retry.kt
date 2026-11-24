package app.musicflow.core.common

import app.musicflow.core.model.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with optional jitter.
 *
 * Only [ProviderError.isTransient] failures are retried; permanent errors (unavailable,
 * restricted, authentication, parsing) fail immediately so we never loop forever.
 */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 15_000,
    val factor: Double = 2.0,
    val jitterRatio: Double = 0.2,
) {
    init { require(maxAttempts >= 1) }

    /** Delay before retry number [attempt] (1-based: the delay after the first failure is attempt 1). */
    fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val base = initialDelayMs * factor.pow((attempt - 1).toDouble())
        val capped = min(base, maxDelayMs.toDouble())
        val jitter = if (jitterRatio > 0) capped * jitterRatio * (random.nextDouble() * 2 - 1) else 0.0
        return (capped + jitter).toLong().coerceIn(0, maxDelayMs)
    }

    /** Rate limiting deserves a longer wait than a dropped packet. */
    fun delayFor(error: ProviderError, attempt: Int, random: Random = Random.Default): Long =
        if (error is ProviderError.RateLimited) min(maxDelayMs, delayFor(attempt, random) * 4) else delayFor(attempt, random)

    companion object {
        val Default = RetryPolicy()
        val None = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * Runs [block], retrying transient [ProviderError]s according to [policy].
 * Any other exception is classified via [classify] first.
 */
suspend fun <T> retrying(
    policy: RetryPolicy = RetryPolicy.Default,
    classify: (Throwable) -> ProviderError = ::defaultClassify,
    onRetry: (attempt: Int, error: ProviderError, delayMs: Long) -> Unit = { _, _, _ -> },
    sleep: suspend (Long) -> Unit = { delay(it) },
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val error = t as? ProviderError ?: classify(t)
            if (!error.isTransient || attempt >= policy.maxAttempts) throw error
            val wait = policy.delayFor(error, attempt)
            onRetry(attempt, error, wait)
            sleep(wait)
            attempt++
        }
    }
}

fun defaultClassify(t: Throwable): ProviderError = when (t) {
    is ProviderError -> t
    is java.net.SocketTimeoutException,
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketException,
    is javax.net.ssl.SSLException,
    is java.io.InterruptedIOException -> ProviderError.Network(t.message ?: "Network error", t)
    is java.io.IOException -> ProviderError.Network(t.message ?: "I/O error", t)
    else -> ProviderError.Unknown(t.message ?: t::class.java.simpleName, t)
}
