package app.musicflow.core.common

import app.musicflow.core.model.ProviderError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketTimeoutException
import kotlin.random.Random

class RetryTest {
    private val noJitter = RetryPolicy(maxAttempts = 5, initialDelayMs = 100, maxDelayMs = 1_000, jitterRatio = 0.0)

    @Test fun delaysGrowExponentiallyAndAreCapped() {
        assertThat((1..6).map { noJitter.delayFor(it) }).containsExactly(100L, 200L, 400L, 800L, 1000L, 1000L).inOrder()
    }

    @Test fun jitterStaysWithinBounds() {
        val p = noJitter.copy(jitterRatio = 0.2)
        repeat(100) { assertThat(p.delayFor(2, Random(it))).isIn(com.google.common.collect.Range.closed(160L, 240L)) }
    }

    @Test fun transientErrorsAreRetriedUntilSuccess() = runTest {
        val sleeps = mutableListOf<Long>()
        val result = retrying(noJitter, sleep = { sleeps += it }) { attempt ->
            if (attempt < 3) throw SocketTimeoutException("slow") else "ok"
        }
        assertThat(result).isEqualTo("ok")
        assertThat(sleeps).containsExactly(100L, 200L).inOrder()
    }

    @Test fun permanentErrorsAreNotRetried() = runTest {
        var calls = 0
        val e = runCatching {
            retrying(noJitter, sleep = {}) { calls++; throw ProviderError.Unavailable("gone") }
        }.exceptionOrNull()
        assertThat(e).isInstanceOf(ProviderError.Unavailable::class.java)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun givesUpAfterMaxAttempts() = runTest {
        var calls = 0
        val e = runCatching { retrying(noJitter, sleep = {}) { calls++; throw java.io.IOException("down") } }.exceptionOrNull()
        assertThat(e).isInstanceOf(ProviderError.Network::class.java)
        assertThat(calls).isEqualTo(5)
    }

    @Test fun rateLimitWaitsLonger() {
        assertThat(noJitter.delayFor(ProviderError.RateLimited(), 1)).isEqualTo(400L)
    }

    @Test fun classifiesIoAsNetwork() {
        assertThat(defaultClassify(java.net.UnknownHostException())).isInstanceOf(ProviderError.Network::class.java)
        assertThat(defaultClassify(IllegalStateException())).isInstanceOf(ProviderError.Unknown::class.java)
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(maxAttempts = 0) }
    }
}
