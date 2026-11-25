package app.musicflow.playback.stream

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.musicflow.core.model.ProviderError
import app.musicflow.playback.MediaItems
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File

/** Header used internally to route a resolved spec to the chunked YouTube transport. */
internal const val TRANSPORT_HEADER = "X-MusicFlow-Transport"
internal const val TRANSPORT_CHUNKED = "chunked-post"

@UnstableApi
object PlaybackDataSources {

    fun createCache(context: Context, maxBytes: Long): SimpleCache =
        SimpleCache(File(context.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(maxBytes), StandaloneDatabaseProvider(context))

    /**
     * Pipeline: placeholder URI → [ResolvingDataSource] (resolves the real stream lazily) →
     * [RoutingDataSource] → local files directly / remote streams through the disk cache →
     * chunked YouTube transport or plain OkHttp.
     */
    fun factory(
        context: Context,
        client: OkHttpClient,
        cache: SimpleCache?,
        streams: StreamRepository,
    ): DataSource.Factory {
        val plainHttp = OkHttpDataSource.Factory(client)
        val chunked = YouTubeChunkedDataSource.Factory(client)
        val remoteRouting = DataSource.Factory { RemoteRoutingDataSource(plainHttp.createDataSource(), chunked.createDataSource()) }
        val remote: DataSource.Factory = if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(remoteRouting)
                .setCacheKeyFactory(::cacheKey)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else remoteRouting
        val local = DefaultDataSource.Factory(context)
        val routing = DataSource.Factory { LocalOrRemoteDataSource(local.createDataSource(), remote.createDataSource()) }
        return ResolvingDataSource.Factory(routing) { spec -> resolve(spec, streams) }
    }

    /**
     * Cache key: the resolver's stable key, else the URL. SoundCloud HLS segments are re-signed on
     * every resolution, so their key drops the signature query — the same audio stays cached.
     */
    internal fun cacheKey(spec: DataSpec): String {
        spec.key?.let { return it }
        val uri = spec.uri
        val host = uri.host.orEmpty()
        return if (host.endsWith("sndcdn.com") || host.endsWith("media-streaming.soundcloud.cloud")) {
            uri.buildUpon().clearQuery().fragment(null).build().toString()
        } else uri.toString()
    }

    internal fun resolve(spec: DataSpec, streams: StreamRepository): DataSpec {
        val id = MediaItems.mediaIdFromUri(spec.uri) ?: return spec
        val stream = try {
            runBlocking { withTimeout(45_000) { streams.resolve(id) } }
        } catch (e: ProviderError) {
            throw StreamResolutionException(e)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw StreamResolutionException(ProviderError.Network("Timed out resolving stream", e))
        }
        val headers = stream.requestHeaders.toMutableMap()
        if (stream.requiresChunkedPost) headers[TRANSPORT_HEADER] = TRANSPORT_CHUNKED
        return spec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey(stream.cacheKey)
            .setHttpRequestHeaders(spec.httpRequestHeaders + headers)
            .build()
    }
}

/** Sends `content://`/`file://` to the local source, everything else to the remote chain. */
@UnstableApi
private class LocalOrRemoteDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener); remote.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val src = if (scheme == "http" || scheme == "https") remote else local
        active = src
        return src.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { active?.close(); active = null }
}

/** Chooses chunked-POST or plain HTTP based on the transport marker set during resolution. */
@UnstableApi
private class RemoteRoutingDataSource(private val plain: DataSource, private val chunked: DataSource) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        plain.addTransferListener(transferListener); chunked.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val useChunked = dataSpec.httpRequestHeaders[TRANSPORT_HEADER] == TRANSPORT_CHUNKED
        val clean = dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders - TRANSPORT_HEADER).build()
        active = if (useChunked) chunked else plain
        return active!!.open(clean)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { active?.close(); active = null }
}
