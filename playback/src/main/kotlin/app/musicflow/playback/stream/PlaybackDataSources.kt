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
/** Live streams (radio) bypass the disk cache: they never end and would only evict music. */
internal const val TRANSPORT_LIVE = "live"

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
        /** Radio setting: plain-HTTP follow-up requests (HLS playlists/segments) of chosen streams. */
        allowCleartextMedia: () -> Boolean = { false },
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
        val routing = DataSource.Factory { LocalOrRemoteDataSource(local.createDataSource(), remote.createDataSource(), plainHttp.createDataSource(), allowCleartextMedia) }
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

    /** Key-less media requests that may be cached: SoundCloud's signed but immutable HLS segments. */
    internal fun cacheableWithoutKey(uri: Uri): Boolean {
        val host = uri.host.orEmpty()
        return host.endsWith("sndcdn.com") || host.endsWith("media-streaming.soundcloud.cloud")
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
        if (stream.isLive) headers[TRANSPORT_HEADER] = TRANSPORT_LIVE
        if (stream.allowCleartext) headers[app.musicflow.core.network.CleartextGuard.ALLOW_HEADER] = "radio"
        return spec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey(stream.cacheKey)
            .setHttpRequestHeaders(spec.httpRequestHeaders + headers)
            .build()
    }
}

/** Sends `content://`/`file://` to the local source, everything else to the remote chain. */
@UnstableApi
private class LocalOrRemoteDataSource(
    private val local: DataSource,
    private val remote: DataSource,
    private val live: DataSource,
    private val allowCleartextMedia: () -> Boolean,
) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener); remote.addTransferListener(transferListener); live.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        if (dataSpec.httpRequestHeaders[TRANSPORT_HEADER] == TRANSPORT_LIVE) {
            active = live
            return live.open(dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders - TRANSPORT_HEADER).build())
        }
        // Follow-up requests made by a media source itself (HLS playlist refreshes, segments) carry
        // no resolver key. Only SoundCloud's immutable segments are worth caching; live playlists
        // must never be served from the cache (they would go stale and playback would stop).
        if ((scheme == "http" || scheme == "https") && dataSpec.key == null && !PlaybackDataSources.cacheableWithoutKey(dataSpec.uri)) {
            active = live
            val spec = if (scheme == "http" && allowCleartextMedia()) {
                dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders + (app.musicflow.core.network.CleartextGuard.ALLOW_HEADER to "radio")).build()
            } else dataSpec
            return live.open(spec)
        }
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
