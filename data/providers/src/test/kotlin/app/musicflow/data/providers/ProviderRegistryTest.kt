package app.musicflow.data.providers

import app.musicflow.core.common.Clock
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.RetryPolicy
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.providers.registry.ProviderStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ProviderRegistryTest {
    private class FakeProvider(override val id: String, override val isExperimental: Boolean = false) : MusicProvider {
        override val displayName = id
    }

    private var now = 0L
    private val a = FakeProvider("a")
    private val b = FakeProvider("b", isExperimental = true)
    private lateinit var registry: ProviderRegistry

    @Before fun setUp() {
        registry = ProviderRegistry(setOf(b, a), DiagnosticsLog()).apply {
            clock = Clock { now }
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 10, jitterRatio = 0.0)
            sleeper = { now += it }
        }
    }

    @Test fun nonExperimentalProvidersComeFirst() {
        assertThat(registry.all.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test fun transientFailuresAreRetriedThenSucceed() = runTest {
        var calls = 0
        val r = registry.call(a, "op") { calls++; if (calls < 3) throw java.io.IOException("drop") else 42 }
        assertThat(r).isEqualTo(42)
        assertThat(registry.health.value["a"]!!.status).isEqualTo(ProviderStatus.AVAILABLE)
    }

    @Test fun repeatedTransientFailuresOpenCircuitAndFailFast() = runTest {
        var calls = 0
        repeat(5) { runCatching { registry.call(a, "op") { calls++; throw java.io.IOException("down") } } }
        assertThat(registry.health.value["a"]!!.status).isEqualTo(ProviderStatus.UNAVAILABLE)
        val before = calls
        val e = runCatching { registry.call(a, "op") { calls++; 1 } }.exceptionOrNull()
        assertThat(e).isInstanceOf(ProviderError.Network::class.java)
        assertThat(calls).isEqualTo(before) // fail fast, provider not hit
        // Other providers are unaffected.
        assertThat(registry.call(b, "op") { "ok" }).isEqualTo("ok")
        // After cool-down the provider is tried again.
        now += 61_000
        assertThat(registry.call(a, "op") { "back" }).isEqualTo("back")
    }

    @Test fun permanentErrorsDoNotDegradeProvider() = runTest {
        repeat(10) { runCatching { registry.call(a, "op") { throw ProviderError.Unavailable("removed") } } }
        val h = registry.health.value["a"]!!
        assertThat(h.consecutiveFailures).isEqualTo(0)
        assertThat(h.status).isNotEqualTo(ProviderStatus.UNAVAILABLE)
    }

    @Test fun unexpectedExceptionsAreWrappedNotThrownRaw() = runTest {
        val e = runCatching { registry.call(a, "op") { throw IllegalStateException("bug") } }.exceptionOrNull()
        assertThat(e).isInstanceOf(ProviderError::class.java)
    }

    @Test fun disabledProviderIsRejected() = runTest {
        registry.setEnabled("a", false)
        assertThat(registry.health.value["a"]!!.status).isEqualTo(ProviderStatus.DISABLED)
        assertThat(runCatching { registry.call(a, "op") { 1 } }.exceptionOrNull()).isInstanceOf(ProviderError.NotSupported::class.java)
        assertThat(registry.enabled().map { it.id }).containsExactly("b")
    }

    @Test fun routesMediaIdsToOwningProvider() {
        val direct = DirectUrlProvider()
        val r = ProviderRegistry(setOf(direct, a), DiagnosticsLog())
        assertThat(r.forMedia(MediaId("url:https://x/y.mp3"))).isSameInstanceAs(direct)
        assertThat(r.forMedia(MediaId("zz:1"))).isNull()
    }
}
