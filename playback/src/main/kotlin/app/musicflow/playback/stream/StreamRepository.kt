package app.musicflow.playback.stream

import app.musicflow.core.common.Clock
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.TextMatching
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves playable stream URLs with:
 *  - a cache keyed by (media, quality) that honours the URL's expiry time,
 *  - de-duplication of concurrent requests for the same media (one network call),
 *  - explicit invalidation when a URL turns out to be expired (HTTP 403/410),
 *  - an optional alternative source, used only for a *reliable* metadata match.
 */
class StreamRepository(
    private val resolveRemote: suspend (MediaId, AudioQuality) -> ResolvedStream,
    private val quality: () -> AudioQuality,
    private val diagnostics: DiagnosticsLog,
    private val alternative: (suspend (Track) -> ResolvedStream?)? = null,
    private val clock: Clock = Clock.System,
) {
    private data class Key(val id: MediaId, val quality: AudioQuality)

    private val mutex = Mutex()
    private val cache = HashMap<Key, ResolvedStream>()
    private val inFlight = HashMap<Key, CompletableDeferred<ResolvedStream>>()
    private val byUrl = HashMap<String, Key>()

    var resolutions = 0
        private set

    suspend fun resolve(id: MediaId, track: Track? = null, q: AudioQuality = quality()): ResolvedStream {
        val key = Key(id, q)
        val (deferred, owner) = mutex.withLock {
            cache[key]?.let { cached ->
                if (!cached.isExpired(clock.now())) return cached
                diagnostics.i("stream", "Cached URL for $id expired; renewing")
                cache.remove(key); byUrl.remove(cached.url)
            }
            inFlight[key]?.let { return@withLock it to false }
            CompletableDeferred<ResolvedStream>().also { inFlight[key] = it } to true
        }
        if (!owner) return deferred.await()
        try {
            val stream = try {
                resolutions++
                resolveRemote(id, q)
            } catch (e: ProviderError) {
                val alt = if (!e.isTransient && track != null) alternative?.invoke(track) else null
                if (alt != null) {
                    diagnostics.w("stream", "Using reliably matched alternative source for ${track?.title}")
                    alt
                } else throw e
            }
            mutex.withLock { cache[key] = stream; byUrl[stream.url] = key; inFlight.remove(key) }
            diagnostics.d("stream", "Resolved $id → ${stream.describe()}")
            deferred.complete(stream)
            return stream
        } catch (t: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            deferred.completeExceptionally(t)
            throw t
        }
    }

    /** Called when a request with this URL got 403/410: the signature or expiry no longer holds. */
    suspend fun invalidateUrl(url: String): MediaId? = mutex.withLock {
        val key = byUrl.remove(url) ?: byUrl.entries.firstOrNull { url.startsWith(it.key.substringBefore('?')) }?.let { byUrl.remove(it.key) }
        key?.let { cache.remove(it); it.id }
    }

    suspend fun invalidate(id: MediaId) = mutex.withLock {
        cache.keys.filter { it.id == id }.forEach { k -> cache.remove(k)?.let { byUrl.remove(it.url) } }
    }

    suspend fun cached(id: MediaId): ResolvedStream? = mutex.withLock { cache.entries.firstOrNull { it.key.id == id }?.value }

    suspend fun clear() = mutex.withLock { cache.clear(); byUrl.clear() }

    companion object {
        /** Alternative-source match policy: same recording by title, artist and duration. */
        fun isReliableAlternative(original: Track, candidate: Track) = TextMatching.isReliableMatch(
            original.title, original.primaryArtist, original.durationMs,
            candidate.title, candidate.primaryArtist, candidate.durationMs,
        )
    }
}

/** Wraps a classified provider error so it can travel through ExoPlayer as an IOException. */
class StreamResolutionException(val error: ProviderError) : java.io.IOException(error.message, error)
