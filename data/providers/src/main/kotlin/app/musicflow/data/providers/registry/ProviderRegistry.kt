package app.musicflow.data.providers.registry

import app.musicflow.core.common.Clock
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.RetryPolicy
import app.musicflow.core.common.defaultClassify
import app.musicflow.core.common.retrying
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.data.providers.api.MusicProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ProviderStatus { AVAILABLE, DEGRADED, UNAVAILABLE, DISABLED, UNKNOWN }

data class ProviderHealth(
    val providerId: String,
    val displayName: String,
    val experimental: Boolean,
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val openUntil: Long? = null,
)

/**
 * Routes calls to providers and isolates their failures.
 *
 * - Every call is retried for transient errors with exponential backoff.
 * - Health is tracked per provider; after [failureThreshold] consecutive *transient* failures the
 *   provider's circuit opens for [coolDownMs] and calls fail fast instead of piling up requests.
 * - Permanent errors (e.g. a single unavailable track) never open the circuit, because they say
 *   nothing about the provider's overall health.
 * - No exception other than [ProviderError] escapes, so one provider can't crash the app.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards MusicProvider>,
    private val diagnostics: DiagnosticsLog,
) {
    var clock: Clock = Clock.System
    var retryPolicy: RetryPolicy = RetryPolicy.Default
    var sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    private val failureThreshold = 5
    private val coolDownMs = 60_000L

    val all: List<MusicProvider> = providers.sortedWith(compareBy({ it.isExperimental }, { it.id }))
    private val disabled = MutableStateFlow<Set<String>>(emptySet())

    private val _health = MutableStateFlow(
        all.associate { it.id to ProviderHealth(it.id, it.displayName, it.isExperimental) },
    )
    val health: StateFlow<Map<String, ProviderHealth>> = _health.asStateFlow()

    fun setEnabled(providerId: String, enabled: Boolean) {
        disabled.update { if (enabled) it - providerId else it + providerId }
        _health.update { map ->
            val h = map[providerId] ?: return@update map
            map + (providerId to h.copy(status = if (enabled) ProviderStatus.UNKNOWN else ProviderStatus.DISABLED))
        }
    }

    fun isEnabled(providerId: String) = providerId !in disabled.value

    fun enabled(): List<MusicProvider> = all.filter { isEnabled(it.id) }

    fun byId(providerId: String): MusicProvider? = all.firstOrNull { it.id == providerId }

    fun forMedia(id: MediaId): MusicProvider? = all.firstOrNull { it.owns(id) }

    /**
     * The online catalogue used for search and discovery: the first enabled online catalogue
     * whose circuit is closed, else any enabled one. `null` when no online catalogue is enabled
     * (callers then fall back to on-device music).
     */
    fun primaryCatalog(): MusicProvider? {
        val online = enabled().filter { it.isOnlineCatalog && it.search != null }
        return online.firstOrNull { !isOpen(it.id) } ?: online.firstOrNull()
    }

    /** Online sources the user can search, main catalogue first (e.g. YouTube Music, SoundCloud). */
    fun searchSources(): List<MusicProvider> {
        val primary = primaryCatalog()
        return listOfNotNull(primary) + enabled().filter { it.isSearchSource && it.search != null && it !== primary }
    }

    private fun isOpen(providerId: String): Boolean {
        val until = _health.value[providerId]?.openUntil ?: return false
        return clock.now() < until
    }

    suspend fun <T> call(
        provider: MusicProvider,
        operation: String,
        policy: RetryPolicy = retryPolicy,
        block: suspend (MusicProvider) -> T,
    ): T {
        if (!isEnabled(provider.id)) throw ProviderError.NotSupported("${provider.displayName} is disabled")
        if (isOpen(provider.id)) {
            throw ProviderError.Network("${provider.displayName} is temporarily unavailable; retrying later")
        }
        return try {
            val result = retrying(
                policy = policy,
                classify = ::defaultClassify,
                onRetry = { attempt, error, wait ->
                    diagnostics.w("provider", "${provider.id}.$operation attempt $attempt failed (${error::class.simpleName}); retry in ${wait}ms")
                },
                sleep = sleeper,
            ) { block(provider) }
            recordSuccess(provider.id)
            result
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            val error = t as? ProviderError ?: defaultClassify(t)
            recordFailure(provider.id, operation, error)
            throw error
        }
    }

    /** Calls the provider that owns [id]. */
    suspend fun <T> callFor(id: MediaId, operation: String, block: suspend (MusicProvider) -> T): T {
        val provider = forMedia(id) ?: throw ProviderError.NotSupported("No provider for ${id.providerId}")
        return call(provider, operation, block = block)
    }

    private fun recordSuccess(providerId: String) {
        _health.update { map ->
            val h = map[providerId] ?: return@update map
            map + (providerId to h.copy(status = ProviderStatus.AVAILABLE, consecutiveFailures = 0, lastSuccessAt = clock.now(), openUntil = null))
        }
    }

    private fun recordFailure(providerId: String, operation: String, error: ProviderError) {
        diagnostics.e("provider", "$providerId.$operation failed: ${error::class.simpleName}: ${error.message}")
        _health.update { map ->
            val h = map[providerId] ?: return@update map
            if (!error.isTransient && error !is ProviderError.Parsing) {
                // Per-item problem (unavailable/restricted track); provider itself is fine.
                return@update map + (providerId to h.copy(lastError = error.message, lastErrorAt = clock.now()))
            }
            val failures = h.consecutiveFailures + 1
            val open = failures >= failureThreshold
            map + (providerId to h.copy(
                status = if (open) ProviderStatus.UNAVAILABLE else ProviderStatus.DEGRADED,
                consecutiveFailures = failures,
                lastError = "${error::class.simpleName}: ${error.message}",
                lastErrorAt = clock.now(),
                openUntil = if (open) clock.now() + coolDownMs else h.openUntil,
            ))
        }
    }
}
