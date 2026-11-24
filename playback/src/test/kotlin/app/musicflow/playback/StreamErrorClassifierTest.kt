package app.musicflow.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.musicflow.core.model.ProviderError
import app.musicflow.playback.stream.LoadDecision
import app.musicflow.playback.stream.StreamErrorClassifier
import app.musicflow.playback.stream.StreamResolutionException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamErrorClassifierTest {
    private val spec = DataSpec(Uri.parse("https://example.com/a"))
    private fun http(code: Int) = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), spec, ByteArray(0))

    @Test fun expiredUrlIsRenewedAtMostTwice() {
        assertThat(StreamErrorClassifier.decide(http(403), 1)).isEqualTo(LoadDecision.Retry(300, renewUrl = true))
        assertThat(StreamErrorClassifier.decide(http(410), 2)).isEqualTo(LoadDecision.Retry(300, renewUrl = true))
        assertThat(StreamErrorClassifier.decide(http(403), 3)).isEqualTo(LoadDecision.Fail)
    }

    @Test fun permanentHttpErrorsFailImmediately() {
        assertThat(StreamErrorClassifier.decide(http(404), 1)).isEqualTo(LoadDecision.Fail)
    }

    @Test fun serverErrorsAndNetworkDropsBackOff() {
        val d1 = StreamErrorClassifier.decide(http(503), 1) as LoadDecision.Retry
        val d3 = StreamErrorClassifier.decide(http(503), 3) as LoadDecision.Retry
        assertThat(d3.delayMs).isGreaterThan(d1.delayMs)
        val io = HttpDataSource.HttpDataSourceException.createForIOException(java.net.SocketTimeoutException(), spec, HttpDataSource.HttpDataSourceException.TYPE_READ)
        assertThat(StreamErrorClassifier.decide(io, 1)).isInstanceOf(LoadDecision.Retry::class.java)
        assertThat((StreamErrorClassifier.decide(io, 1, isOnline = false) as LoadDecision.Retry).delayMs).isAtLeast(5_000)
        assertThat(StreamErrorClassifier.decide(io, 8)).isEqualTo(LoadDecision.Fail)
    }

    @Test fun resolutionErrorsFollowProviderClassification() {
        assertThat(StreamErrorClassifier.decide(StreamResolutionException(ProviderError.Network()), 1)).isInstanceOf(LoadDecision.Retry::class.java)
        assertThat(StreamErrorClassifier.decide(StreamResolutionException(ProviderError.Unavailable()), 1)).isEqualTo(LoadDecision.Fail)
        assertThat(StreamErrorClassifier.decide(StreamResolutionException(ProviderError.Restricted(ProviderError.Restricted.Reason.GEOGRAPHIC)), 1)).isEqualTo(LoadDecision.Fail)
        assertThat(StreamErrorClassifier.decide(IllegalStateException("bug"), 1)).isEqualTo(LoadDecision.Fail)
    }
}
