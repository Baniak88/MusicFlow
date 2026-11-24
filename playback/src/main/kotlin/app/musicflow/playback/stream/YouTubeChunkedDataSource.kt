package app.musicflow.playback.stream

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * Reads YouTube progressive audio the way current YouTube clients do: POST requests for bounded
 * byte ranges (`&range=start-end&rn=n`), stitched together transparently. Large unbounded
 * requests to these URLs are throttled or rejected, so chunking keeps playback stable.
 */
@UnstableApi
class YouTubeChunkedDataSource(
    private val client: OkHttpClient,
    private val chunkSize: Long = DEFAULT_CHUNK,
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var call: Call? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var position = 0L
    private var end = Long.MAX_VALUE
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var requestNumber = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val total = Regex("""[?&]clen=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        position = dataSpec.position
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
            total != null -> total
            else -> Long.MAX_VALUE
        }
        if (total != null && position >= total) {
            throw HttpDataSource.InvalidResponseCodeException(416, "Range not satisfiable", null, emptyMap(), dataSpec, ByteArray(0))
        }
        bytesRemaining = if (end == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else end - position
        openChunk(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openChunk(spec: DataSpec) {
        closeChunk()
        val chunkEnd = if (end == Long.MAX_VALUE) position + chunkSize - 1 else min(position + chunkSize, end) - 1
        val url = spec.uri.buildUpon()
            .appendQueryParameter("range", "$position-$chunkEnd")
            .appendQueryParameter("rn", (requestNumber++).toString())
            .build().toString()
        val builder = Request.Builder().url(url).post(BODY.toRequestBody())
        spec.httpRequestHeaders.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newCall(builder.build())
        call = c
        val resp = try {
            c.execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, spec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        if (!resp.isSuccessful) {
            val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
            val headers = resp.headers.toMultimap()
            val code = resp.code
            val msg = resp.message
            resp.close()
            throw HttpDataSource.InvalidResponseCodeException(code, msg, null, headers, spec, body)
        }
        response = resp
        stream = resp.body?.byteStream()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val spec = dataSpec ?: throw IllegalStateException("not opened")
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else min(length.toLong(), bytesRemaining).toInt()
        val n = try {
            stream?.read(buffer, offset, toRead) ?: -1
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, spec, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (n == -1) {
            // End of this chunk: continue with the next one if the file has more bytes.
            if (end != Long.MAX_VALUE && position < end) {
                openChunk(spec)
                return read(buffer, offset, length)
            }
            return C.RESULT_END_OF_INPUT
        }
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap() ?: emptyMap()

    private fun closeChunk() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null; response = null; call = null
    }

    override fun close() {
        closeChunk()
        if (opened) { opened = false; transferEnded() }
        dataSpec = null
    }

    class Factory(private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = YouTubeChunkedDataSource(client)
    }

    companion object {
        const val DEFAULT_CHUNK = 1024L * 1024
        private val BODY = byteArrayOf('x'.code.toByte(), 0)
    }
}
