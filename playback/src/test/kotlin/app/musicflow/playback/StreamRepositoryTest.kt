package app.musicflow.playback

import app.musicflow.core.common.Clock
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.Track
import app.musicflow.playback.stream.StreamRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StreamRepositoryTest {
    private var now = 1_000_000L
    private val id = MediaId("yt:abc")
    private var calls = 0
    private fun stream(url: String, expires: Long?) = ResolvedStream(id, url, "audio/webm", "opus", 160, 48000, null, expires, cacheKey = "k")

    @Test fun cachesUntilExpiryThenRenews() = runTest {
        val repo = StreamRepository({ _, _ -> calls++; stream("u$calls", now + 10 * 60_000) }, { AudioQuality.HIGH }, DiagnosticsLog(), clock = Clock { now })
        assertThat(repo.resolve(id).url).isEqualTo("u1")
        assertThat(repo.resolve(id).url).isEqualTo("u1")
        assertThat(calls).isEqualTo(1)
        now += 9 * 60_000 + 1 // inside the 60 s safety margin → considered expired
        assertThat(repo.resolve(id).url).isEqualTo("u2")
        assertThat(calls).isEqualTo(2)
    }

    @Test fun qualityIsPartOfTheCacheKey() = runTest {
        val repo = StreamRepository({ _, q -> calls++; stream("u-$q", null) }, { AudioQuality.HIGH }, DiagnosticsLog())
        repo.resolve(id, q = AudioQuality.HIGH); repo.resolve(id, q = AudioQuality.DATA_SAVER)
        assertThat(calls).isEqualTo(2)
    }

    @Test fun concurrentRequestsAreDeduplicated() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = StreamRepository({ _, _ -> calls++; gate.await(); stream("u", null) }, { AudioQuality.HIGH }, DiagnosticsLog())
        val jobs = (1..5).map { async { repo.resolve(id) } }
        gate.complete(Unit)
        assertThat(jobs.awaitAll().map { it.url }.toSet()).containsExactly("u")
        assertThat(calls).isEqualTo(1)
    }

    @Test fun invalidatingAnExpiredUrlForcesRenewal() = runTest {
        val repo = StreamRepository({ _, _ -> calls++; stream("https://host/v?sig=$calls", null) }, { AudioQuality.HIGH }, DiagnosticsLog())
        val first = repo.resolve(id)
        assertThat(repo.invalidateUrl(first.url)).isEqualTo(id)
        assertThat(repo.resolve(id).url).isEqualTo("https://host/v?sig=2")
    }

    @Test fun failuresAreNotCachedAndPropagate() = runTest {
        var fail = true
        val repo = StreamRepository({ _, _ -> calls++; if (fail) throw ProviderError.Network() else stream("ok", null) }, { AudioQuality.HIGH }, DiagnosticsLog())
        assertThat(runCatching { repo.resolve(id) }.exceptionOrNull()).isInstanceOf(ProviderError.Network::class.java)
        fail = false
        assertThat(repo.resolve(id).url).isEqualTo("ok")
    }

    @Test fun alternativeIsUsedOnlyForPermanentErrors() = runTest {
        val track = Track(id, "Song", durationMs = 1000)
        var altCalls = 0
        val alt: suspend (Track) -> ResolvedStream? = { altCalls++; stream("local", null) }
        val transient = StreamRepository({ _, _ -> throw ProviderError.Network() }, { AudioQuality.HIGH }, DiagnosticsLog(), alt)
        assertThat(runCatching { transient.resolve(id, track) }.isFailure).isTrue()
        assertThat(altCalls).isEqualTo(0)
        val permanent = StreamRepository({ _, _ -> throw ProviderError.Unavailable() }, { AudioQuality.HIGH }, DiagnosticsLog(), alt)
        assertThat(permanent.resolve(id, track).url).isEqualTo("local")
        assertThat(altCalls).isEqualTo(1)
    }

    @Test fun botCheckFallsBackButOtherRateLimitsDoNot() = runTest {
        val track = Track(id, "Song", durationMs = 1000)
        val alt: suspend (Track) -> ResolvedStream? = { stream("audius", null) }
        val bot = StreamRepository({ _, _ -> throw ProviderError.RateLimited(botCheck = true) }, { AudioQuality.HIGH }, DiagnosticsLog(), alt)
        assertThat(bot.resolve(id, track).url).isEqualTo("audius")
        val limited = StreamRepository({ _, _ -> throw ProviderError.RateLimited() }, { AudioQuality.HIGH }, DiagnosticsLog(), alt)
        assertThat(runCatching { limited.resolve(id, track) }.exceptionOrNull()).isInstanceOf(ProviderError.RateLimited::class.java)
    }

    @Test fun reliableAlternativeMatching() {
        val a = Track(id, "Strobe", listOf(app.musicflow.core.model.ArtistRef(null, "deadmau5")), durationMs = 600_000)
        assertThat(StreamRepository.isReliableAlternative(a, a.copy(id = MediaId("local:1"), title = "Strobe (Original Mix)".replace(" (Original Mix)", ""), durationMs = 601_000))).isTrue()
        assertThat(StreamRepository.isReliableAlternative(a, a.copy(id = MediaId("local:2"), durationMs = 300_000))).isFalse()
    }
}
