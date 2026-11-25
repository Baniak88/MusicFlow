package app.musicflow.data.providers.soundcloud

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.TextMatching
import app.musicflow.core.model.Album
import app.musicflow.core.model.AlbumRef
import app.musicflow.core.model.Artist
import app.musicflow.core.model.ArtistPage
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.CollectionPage
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.PlaylistSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.MetadataProvider
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.PlaylistProvider
import app.musicflow.data.providers.api.RecommendationProvider
import app.musicflow.data.providers.api.SearchProvider
import app.musicflow.data.providers.api.StreamResolver
import app.musicflow.data.providers.newpipe.NewPipeRuntime
import app.musicflow.data.providers.youtube.NewPipeDownloader
import app.musicflow.data.providers.youtube.YouTubeErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * EXPERIMENTAL SoundCloud provider, built on NewPipeExtractor (GPL-3.0), like the YouTube one.
 *
 * Uses SoundCloud's public web API the same way NewPipe does (no account, no official API key).
 * SoundCloud serves audio as HLS (AAC 160 kbps, MP3 128 kbps or Opus 64 kbps); SoundCloud Go+
 * tracks only offer a 30-second preview and are reported as restricted instead of being played
 * as if they were complete. See PROVIDERS.md.
 */
class SoundCloudProvider(
    client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val countryOverride: () -> String? = { null },
) : MusicProvider, SearchProvider, MetadataProvider, StreamResolver, RecommendationProvider, PlaylistProvider {

    override val id = SoundCloudIds.TRACK
    override val displayName = "SoundCloud"
    override val isExperimental = true
    /** Searchable, but Home/Explore stay on the main catalogue (YouTube Music). */
    override val isSearchSource = true
    override val ownedPrefixes = setOf(SoundCloudIds.TRACK, SoundCloudIds.PLAYLIST, SoundCloudIds.USER)

    override val search: SearchProvider get() = this
    override val metadata: MetadataProvider get() = this
    override val streams: StreamResolver get() = this
    override val recommendations: RecommendationProvider get() = this
    override val playlists: PlaylistProvider get() = this

    private val downloader = NewPipeDownloader(client)

    private val pages: MutableMap<String, Page> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Page>?) = size > 200
        },
    )
    private val tokenFilters: MutableMap<String, String> = Collections.synchronizedMap(HashMap())

    private fun service(): StreamingService {
        NewPipeRuntime.ensure(downloader, locale(), countryOverride())
        return ServiceList.SoundCloud
    }

    private suspend fun <T> io(op: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw YouTubeErrors.classify(t).also { diagnostics.d("soundcloud", "$op: ${it::class.simpleName}: ${it.message}") }
        }
    }

    private fun storePage(page: Page?, filter: String): String? = page?.let { p ->
        UUID.randomUUID().toString().also { pages[it] = p; tokenFilters[it] = filter }
    }

    // ---------------------------------------------------------------- search

    private fun npFilter(filter: SearchFilter) = when (filter) {
        SearchFilter.ALL, SearchFilter.SONGS -> SoundcloudSearchQueryHandlerFactory.TRACKS
        SearchFilter.ARTISTS -> SoundcloudSearchQueryHandlerFactory.USERS
        SearchFilter.ALBUMS, SearchFilter.PLAYLISTS -> SoundcloudSearchQueryHandlerFactory.PLAYLISTS
    }

    private fun map(items: List<org.schabi.newpipe.extractor.InfoItem>, filter: SearchFilter): List<MediaItemSummary> = items.mapNotNull { item ->
        if (filter == SearchFilter.ALBUMS && item is PlaylistInfoItem) SoundCloudMapper.album(item)?.let(MediaItemSummary::AlbumItem)
        else SoundCloudMapper.summary(item)
    }

    private fun searchWith(query: String, filter: SearchFilter, token: String?): SearchPage = try {
        searchOrThrow(query, filter, token)
    } catch (e: SearchExtractor.NothingFoundException) {
        SearchPage(query, filter, emptyList(), null) // no results is not an error
    }

    private fun searchOrThrow(query: String, filter: SearchFilter, token: String?): SearchPage {
        val s = service()
        val np = token?.let { tokenFilters[it] } ?: npFilter(filter)
        val handler = s.searchQHFactory.fromQuery(query, listOf(np), "")
        return if (token == null) {
            val info = SearchInfo.getInfo(s, handler)
            SearchPage(query, filter, map(info.relatedItems, filter), storePage(info.nextPage, np), info.searchSuggestion?.takeIf { info.isCorrectedSearch })
        } else {
            val page = pages[token] ?: throw ProviderError.Unknown("Results expired, please search again")
            val more = SearchInfo.getMoreItems(s, handler, page)
            SearchPage(query, filter, map(more.items, filter), storePage(more.nextPage, np))
        }
    }

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        linkResult(query.trim(), filter)?.let { return it }
        if (filter != SearchFilter.ALL) return io("search") { searchWith(query, filter, pageToken) }
        return coroutineScope {
            val tracks = async { runCatching { io("search.tracks") { searchWith(query, SearchFilter.SONGS, null) } } }
            val users = async { runCatching { io("search.users") { searchWith(query, SearchFilter.ARTISTS, null) } }.getOrNull() }
            val sets = async { runCatching { io("search.sets") { searchWith(query, SearchFilter.PLAYLISTS, null) } }.getOrNull() }
            val trackResult = tracks.await()
            val userPage = users.await()
            val setPage = sets.await()
            // Only fail when every category failed; otherwise show what was found.
            if (trackResult.isFailure && userPage == null && setPage == null) throw trackResult.exceptionOrNull()!!
            val trackPage = trackResult.getOrNull()
            val items = buildList {
                userPage?.items?.take(2)?.let(::addAll)
                trackPage?.items?.take(16)?.let(::addAll)
                setPage?.items?.take(6)?.let(::addAll)
            }
            SearchPage(query, SearchFilter.ALL, items, nextPageToken = null, correctedQuery = trackPage?.correctedQuery)
        }
    }

    /** A pasted SoundCloud link resolves directly to that track, set or profile. */
    private suspend fun linkResult(query: String, filter: SearchFilter): SearchPage? {
        if (!SoundCloudIds.isSoundCloudUrl(query)) return null
        SoundCloudIds.playlistFromUrl(query)?.let { id ->
            val page = playlist(id)
            return SearchPage(query, filter, listOf(MediaItemSummary.PlaylistItem(PlaylistSummary(id, page.title, page.subtitle, page.artworkUrl, page.tracks.size))), null)
        }
        SoundCloudIds.trackFromUrl(query)?.let { id -> return SearchPage(query, filter, listOf(MediaItemSummary.TrackItem(track(id))), null) }
        SoundCloudIds.userFromUrl(query)?.let { id ->
            val a = artist(id).artist
            return SearchPage(query, filter, listOf(MediaItemSummary.ArtistItem(a)), null)
        }
        return null
    }

    override suspend fun suggestions(query: String): List<String> = io("suggestions") {
        service().suggestionExtractor.suggestionList(query).take(10)
    }

    // ---------------------------------------------------------------- metadata

    /**
     * The stream extractor reads the track page directly. Unlike `StreamInfo`, it does not fail
     * when no audio stream is available, and it throws the precise reason (Go+, geo-blocked) on
     * fetch, so restrictions are reported as such.
     */
    private fun extractor(id: MediaId): StreamExtractor = service().getStreamExtractor(SoundCloudIds.url(id)).apply { fetchPage() }

    override suspend fun track(id: MediaId): Track = io("track") { trackFrom(extractor(id), id) }

    private fun trackFrom(e: StreamExtractor, id: MediaId): Track = Track(
        id = SoundCloudIds.trackFromUrl(runCatching { e.url }.getOrNull()) ?: id,
        title = e.name,
        artists = SoundCloudMapper.artistRef(runCatching { e.uploaderName }.getOrNull(), runCatching { e.uploaderUrl }.getOrNull()),
        durationMs = runCatching { e.length }.getOrNull()?.takeIf { it > 0 }?.times(1000),
        artworkUrl = runCatching { SoundCloudMapper.bestImage(e.thumbnails) }.getOrNull(),
        releaseYear = runCatching { e.uploadDate?.offsetDateTime()?.year }.getOrNull(),
        genre = runCatching { e.category }.getOrNull()?.takeIf { it.isNotBlank() },
    )

    override suspend fun album(id: MediaId, pageToken: String?): CollectionPage = playlist(id, pageToken)

    override suspend fun artist(id: MediaId): ArtistPage = coroutineScope {
        val s = service()
        val info = io("artist") { ChannelInfo.getInfo(s, SoundCloudIds.url(id)) }
        val artist = Artist(id, info.name, SoundCloudMapper.bestImage(info.avatars), info.subscriberCount.takeIf { it > 0 })
        fun tab(filter: String) = info.tabs.firstOrNull { filter in it.contentFilters }
        val popular = async {
            runCatching {
                io("artist.tracks") {
                    val t = tab("popular_tracks") ?: tab(ChannelTabs.TRACKS) ?: return@io emptyList()
                    ChannelTabInfo.getInfo(s, t).relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(SoundCloudMapper::track).take(20)
                }
            }.getOrDefault(emptyList())
        }
        val albums = async {
            runCatching {
                io("artist.albums") {
                    val t = tab(ChannelTabs.ALBUMS) ?: return@io emptyList()
                    ChannelTabInfo.getInfo(s, t).relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull(SoundCloudMapper::album)
                }
            }.getOrDefault(emptyList())
        }
        val all: List<Album> = albums.await()
        ArtistPage(
            artist = artist,
            topTracks = popular.await(),
            albums = all.filter { (it.trackCount ?: 99) > 3 },
            singles = all.filter { (it.trackCount ?: 99) <= 3 },
            description = info.description?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- playlists

    override suspend fun playlist(id: MediaId, pageToken: String?): CollectionPage = io("playlist") {
        val s = service()
        val url = SoundCloudIds.url(id)
        if (pageToken == null) {
            val info = PlaylistInfo.getInfo(s, url)
            val ref = AlbumRef(id.value, info.name)
            CollectionPage(
                title = info.name,
                subtitle = info.uploaderName?.takeIf { it.isNotBlank() },
                artworkUrl = SoundCloudMapper.bestImage(info.thumbnails),
                tracks = info.relatedItems.mapNotNull { SoundCloudMapper.track(it)?.copy(album = ref) },
                nextPageToken = storePage(info.nextPage, "playlist"),
            )
        } else {
            val page = pages[pageToken] ?: throw ProviderError.Unknown("Results expired, please reload")
            val more = PlaylistInfo.getMoreItems(s, url, page)
            CollectionPage(title = "", subtitle = null, artworkUrl = null, tracks = more.items.mapNotNull(SoundCloudMapper::track), nextPageToken = storePage(more.nextPage, "playlist"))
        }
    }

    override fun parsePlaylistUrl(url: String): MediaId? = SoundCloudIds.playlistFromUrl(url)
    override fun parseTrackUrl(url: String): MediaId? = SoundCloudIds.trackFromUrl(url)

    // ---------------------------------------------------------------- streams

    override fun canResolve(id: MediaId) = id.providerId == SoundCloudIds.TRACK

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream = io("resolve") {
        val chosen = select(extractor(id).audioStreams, quality)
            ?: throw ProviderError.Unavailable("SoundCloud returned no playable audio stream for this track")
        val url = chosen.content
        ResolvedStream(
            mediaId = id,
            url = url,
            mimeType = "application/x-mpegURL",
            codec = codecOf(chosen),
            bitrateKbps = chosen.averageBitrate.takeIf { it > 0 },
            sampleRateHz = null, // not reported by SoundCloud
            expiresAtMillis = SoundCloudIds.expiryMillis(url),
            isLossless = false, // SoundCloud streams are lossy (AAC/MP3/Opus)
            requestHeaders = mapOf("User-Agent" to NewPipeDownloader.USER_AGENT),
            // Unique per resolution: the playlist holds signed segment URLs that expire.
            cacheKey = "sc:${id.key}:${codecOf(chosen)}:${url.hashCode()}",
            isHls = true,
        )
    }

    override suspend fun related(seed: MediaId, limit: Int): List<Track> {
        if (seed.providerId != SoundCloudIds.TRACK) return emptyList()
        return io("related") {
            extractor(seed).relatedItems?.items.orEmpty()
                .filterIsInstance<StreamInfoItem>().mapNotNull(SoundCloudMapper::track)
                .filter { it.id != seed && (it.durationMs ?: 180_000L) in 60_000L..900_000L }
                .distinctBy { TextMatching.trackKey(it.title, it.primaryArtist) }
                .take(limit)
        }
    }

    /** SoundCloud has charts, but MusicFlow's trending shelf stays on the main catalogue. */
    override suspend fun trending(): List<Track> = emptyList()

    companion object {
        internal fun codecOf(s: AudioStream): String? = when (s.format) {
            MediaFormat.M4A -> "aac"
            MediaFormat.MP3 -> "mp3"
            MediaFormat.OPUS -> "opus"
            else -> s.format?.suffix
        }

        /**
         * Highest bitrate within the quality limit (lowest available if all exceed it).
         * Only HLS streams with a URL are considered — that is all SoundCloud serves today.
         */
        internal fun select(streams: List<AudioStream>, quality: AudioQuality): AudioStream? {
            val usable = streams.filter { it.deliveryMethod == DeliveryMethod.HLS && !it.content.isNullOrBlank() }
            if (usable.isEmpty()) return null
            val max = quality.maxBitrateKbps
            val within = if (max == null) usable else usable.filter { it.averageBitrate in 1..max }
            return within.maxByOrNull { it.averageBitrate } ?: usable.minByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: Int.MAX_VALUE }
        }
    }
}
