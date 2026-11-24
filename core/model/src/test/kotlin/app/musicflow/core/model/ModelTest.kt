package app.musicflow.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelTest {
    @Test fun mediaIdSplitsProviderAndKey() {
        val id = MediaId("url:https://example.com/a.mp3")
        assertThat(id.providerId).isEqualTo("url")
        assertThat(id.key).isEqualTo("https://example.com/a.mp3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun mediaIdRejectsMissingProvider() {
        MediaId("nope")
    }

    @Test fun streamExpiryUsesSafetyMargin() {
        val s = ResolvedStream(MediaId("yt:a"), "u", null, null, null, null, null, expiresAtMillis = 100_000, cacheKey = "k")
        assertThat(s.isExpired(nowMillis = 30_000)).isFalse()
        assertThat(s.isExpired(nowMillis = 50_000)).isTrue()
        assertThat(s.copy(expiresAtMillis = null).isExpired(Long.MAX_VALUE / 2)).isFalse()
    }

    @Test fun describeOnlyReportsKnownValues() {
        val s = ResolvedStream(MediaId("yt:a"), "u", "audio/webm", "opus", 160, 48_000, null, expiresAtMillis = null, cacheKey = "k")
        assertThat(s.describe()).isEqualTo("OPUS · 160 kbps · 48 kHz")
        assertThat(s.describe()).doesNotContain("Lossless")
    }

    @Test fun onlyTransientErrorsAreRetryable() {
        assertThat(ProviderError.Network().isTransient).isTrue()
        assertThat(ProviderError.StreamExpired().isTransient).isTrue()
        assertThat(ProviderError.Unavailable().isTransient).isFalse()
        assertThat(ProviderError.Authentication().isTransient).isFalse()
        assertThat(ProviderError.Restricted(ProviderError.Restricted.Reason.GEOGRAPHIC).isTransient).isFalse()
    }
}
