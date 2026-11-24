package app.musicflow.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.musicflow.playback.stream.YouTubeChunkedDataSource
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChunkedDataSourceTest {
    private val content = ByteArray(2_500) { (it % 251).toByte() }

    private fun server(status403: Boolean = false) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (status403) return MockResponse().setResponseCode(403)
                val range = request.requestUrl!!.queryParameter("range")!!.split('-').map { it.toInt() }
                val end = minOf(range[1], content.size - 1)
                return MockResponse().setBody(Buffer().write(content.copyOfRange(range[0], end + 1)))
            }
        }
    }

    @Test fun stitchesRangeChunksWithPostRequests() {
        server().use { s ->
            val url = s.url("/videoplayback?c=VISIONOS&clen=${content.size}").toString()
            val ds = YouTubeChunkedDataSource(OkHttpClient(), chunkSize = 1000)
            val spec = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(100).setHttpRequestHeaders(mapOf("User-Agent" to "UA")).build()
            assertThat(ds.open(spec)).isEqualTo(content.size - 100L)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(333)
            while (true) { val n = ds.read(buf, 0, buf.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(buf, 0, n) }
            ds.close()
            assertThat(out.toByteArray()).isEqualTo(content.copyOfRange(100, content.size))
            val reqs = (0 until s.requestCount).map { s.takeRequest() }
            assertThat(reqs.map { it.method }.toSet()).containsExactly("POST")
            assertThat(reqs.map { it.requestUrl!!.queryParameter("range") }).containsExactly("100-1099", "1100-2099", "2100-2499").inOrder()
            assertThat(reqs.map { it.requestUrl!!.queryParameter("rn") }).containsExactly("0", "1", "2").inOrder()
            assertThat(reqs.first().getHeader("User-Agent")).isEqualTo("UA")
        }
    }

    @Test fun expiredUrlSurfacesAs403() {
        server(status403 = true).use { s ->
            val ds = YouTubeChunkedDataSource(OkHttpClient())
            val e = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
                ds.open(DataSpec(Uri.parse(s.url("/v?clen=10").toString())))
            }
            assertThat(e.responseCode).isEqualTo(403)
        }
    }
}
