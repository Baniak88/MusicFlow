package app.musicflow.data.providers.youtube

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * EXPERIMENTAL provider for YouTube Music public content, built on NewPipeExtractor (GPL-3.0).
 *
 * This uses undocumented InnerTube endpoints, the same way NewPipe does. It is not an official
 * API: it may break when YouTube changes, and use may conflict with YouTube's Terms of Service in
 * some jurisdictions. It is isolated behind [MusicProvider] and can be disabled in Settings.
 * See PROVIDERS.md.
 */
class YouTubeMusicProvider(
    client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val countryOverride: () -> String? = { null },
) : MusicProvider, SearchProvider, MetadataProvider, StreamResolver, RecommendationProvider, PlaylistProvider {

    override val id = YouTubeIds.TRACK
    override val displayName = "YouTube Music"
    override val isExperimental = true
    override val isOnlineCatalog = true
    override val ownedPrefixes = setOf(YouTubeIds.TRACK, YouTubeIds.PLAYLIST, YouTubeIds.CHANNEL)

    override val search: SearchProvider get() = this
    override val metadata: MetadataProvider get() = this
    override val streams: StreamResolver get() = this
    override val recommendations: RecommendationProvider get() = this
    override val playlists: PlaylistProvider get() = this

    private val downloader = NewPipeDownloader(client)
    @Volatile private var initialized = false

    /** Opaque page tokens → NewPipe [Page] objects (bounded LRU; tokens are session-only). */
    private val pages: MutableMap<String, Page> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Page>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Page>?) = size > 200
        },
    )

    private fun service(): StreamingService {
        if (!initialized) synchronized(this) {
            if (!initialized) {
                val loc = locale()
                val country = countryOverride()?.takeIf { it.length == 2 } ?: loc.country.takeIf { it.length == 2 } ?: "US"
                NewPipe.init(downloader, Localization.fromLocale(loc), ContentCountry(country))
                initialized = true
            }
        }
        return ServiceList.YouTube
    }

    private suspend fun <T> io(op: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw YouTubeErrors.classify(t).also { diagnostics.d("youtube", "$op: ${it::class.simpleName}: ${it.message}") }
        }
    }

    private fun storePage(page: Page?): String? = page?.let { p ->
        UUID.randomUUID().toString().also { pages[it] = p }
    }

    private fun takePage(token: String): Page =
        pages[token] ?: throw ProviderError.Unknown("Results expired, please search again")

    // ---------------------------------------------------------------- search

    private fun npFilter(filter: SearchFilter) = when (filter) {
        SearchFilter.SONGS, SearchFilter.ALL -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        SearchFilter.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
        SearchFilter.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
    }

    private fun mapItems(items: List<InfoItem>, filter: SearchFilter) =
        items.mapNotNull { YouTubeMapper.summary(it, albumsAsAlbums = filter == SearchFilter.ALBUMS) }

    /** Page tokens remember which NewPipe content filter produced them. */
    private val tokenFilters: MutableMap<String, String> = Collections.synchronizedMap(HashMap())

    private fun searchWith(query: String, npFilter: String, filter: SearchFilter, token: String?): SearchPage {
        val s = service()
        val handler = s.searchQHFactory.fromQuery(query, listOf(npFilter), "")
        return if (token == null) {
            val info = SearchInfo.getInfo(s, handler)
            val next = storePage(info.nextPage)?.also { tokenFilters[it] = npFilter }
            SearchPage(
                query = query,
                filter = filter,
                items = mapItems(info.relatedItems, filter),
                nextPageToken = next,
                correctedQuery = info.searchSuggestion?.takeIf { it.isNotBlank() && it != query },
            )
        } else {
            val more = SearchInfo.getMoreItems(s, handler, takePage(token))
            val next = storePage(more.nextPage)?.also { tokenFilters[it] = npFilter }
            SearchPage(query, filter, mapItems(more.items, filter), next)
        }
    }

    private fun searchOnce(query: String, filter: SearchFilter, token: String?): SearchPage {
        if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) return searchSongsMerged(query, filter, token)
        if (token != null) return searchWith(query, tokenFilters[token] ?: npFilter(filter), filter, token)
        return searchWith(query, npFilter(filter), filter, null)
    }

    /**
     * Songs = YouTube Music's official catalogue **merged with** YouTube uploads.
     *
     * The catalogue ("Songs") misses a lot of music that only exists as uploads by fans or
     * artists (e.g. many Polish disco-polo, regional, older or independent tracks). Those are
     * found by the music-videos search, so both are fetched and merged, duplicates removed.
     * Pagination continues both lists with a composite token.
     */
    private fun searchSongsMerged(query: String, filter: SearchFilter, token: String?): SearchPage {
        val (songTok, videoTok) = if (token != null) {
            val parts = token.removePrefix(MERGED).split('|')
            parts.getOrNull(0)?.takeIf { it != NONE } to parts.getOrNull(1)?.takeIf { it != NONE }
        } else null to null
        val first = token == null
        val songs = if (first || songTok != null) runCatching {
            searchWith(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, SearchFilter.SONGS, songTok)
        }.getOrNull() else null
        val videos = if (first || videoTok != null) runCatching {
            searchWith(query, YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS, SearchFilter.SONGS, videoTok)
        }.getOrElse { if (songs == null) throw it else null } else null
        if (songs == null && videos == null && first) throw ProviderError.Network("Search failed")
        if (first && songs?.items.isNullOrEmpty()) diagnostics.i("youtube", "Catalogue songs empty for query; using uploads")
        val merged = SongMerge.merge(query, songs?.items.orEmpty(), videos?.items.orEmpty())
        val next = if (songs?.nextPageToken == null && videos?.nextPageToken == null) null
            else MERGED + (songs?.nextPageToken ?: NONE) + "|" + (videos?.nextPageToken ?: NONE)
        return SearchPage(query, filter, merged, next, correctedQuery = songs?.correctedQuery ?: videos?.correctedQuery)
    }

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        linkResult(query.trim(), filter)?.let { return it }
        if (filter != SearchFilter.ALL) return io("search") { searchOnce(query, filter, pageToken) }
        // "All": top results of every category, fetched concurrently; a failing category is skipped.
        return coroutineScope {
            val songs = async { io("search.songs") { searchOnce(query, SearchFilter.SONGS, null) } }
            val artists = async { runCatching { io("search.artists") { searchOnce(query, SearchFilter.ARTISTS, null) } }.getOrNull() }
            val albums = async { runCatching { io("search.albums") { searchOnce(query, SearchFilter.ALBUMS, null) } }.getOrNull() }
            val playlists = async { runCatching { io("search.playlists") { searchOnce(query, SearchFilter.PLAYLISTS, null) } }.getOrNull() }
            val songPage = songs.await()
            val items = buildList {
                artists.await()?.items?.take(2)?.let(::addAll)
                addAll(songPage.items.take(16))
                albums.await()?.items?.take(6)?.let(::addAll)
                playlists.await()?.items?.take(6)?.let(::addAll)
            }
            SearchPage(query, SearchFilter.ALL, items, nextPageToken = null, correctedQuery = songPage.correctedQuery)
        }
    }

    /** A pasted YouTube / YouTube Music link resolves directly to that track or playlist. */
    private suspend fun linkResult(query: String, filter: SearchFilter): SearchPage? {
        if (!YouTubeIds.isYouTubeUrl(query)) return null
        YouTubeIds.videoIdFromUrl(query)?.let { id ->
            return SearchPage(query, filter, listOf(MediaItemSummary.TrackItem(track(YouTubeIds.track(id)))), null)
        }
        YouTubeIds.listIdFromUrl(query)?.let { list ->
            val page = playlist(YouTubeIds.playlist(list))
            return SearchPage(query, filter, listOf(MediaItemSummary.PlaylistItem(
                app.musicflow.core.model.PlaylistSummary(YouTubeIds.playlist(list), page.title, page.subtitle, page.artworkUrl, page.tracks.size),
            )), null)
        }
        return null
    }

    override suspend fun suggestions(query: String): List<String> = io("suggestions") {
        service().suggestionExtractor.suggestionList(query).take(10)
    }

    // ---------------------------------------------------------------- metadata

    override suspend fun track(id: MediaId): Track = io("track") {
        val info = StreamInfo.getInfo(service(), YouTubeIds.watchUrl(id.key))
        trackFrom(info)
    }

    private fun trackFrom(info: StreamInfo): Track = Track(
        id = YouTubeIds.track(info.id),
        title = info.name,
        artists = YouTubeMapper.artistRef(info.uploaderName, info.uploaderUrl),
        durationMs = info.duration.takeIf { it > 0 }?.times(1000),
        artworkUrl = YouTubeMapper.bestImage(info.thumbnails),
        releaseYear = runCatching { info.uploadDate?.offsetDateTime()?.year }.getOrNull(),
        genre = info.category?.takeIf { it.isNotBlank() && it != "Music" },
    )

    override suspend fun album(id: MediaId, pageToken: String?): CollectionPage = playlist(id, pageToken)

    override suspend fun artist(id: MediaId): ArtistPage = coroutineScope {
        val s = service()
        val info = io("artist") { ChannelInfo.getInfo(s, YouTubeIds.channelUrl(id.key)) }
        val name = YouTubeIds.cleanArtistName(info.name) ?: info.name
        val artist = Artist(id, name, YouTubeMapper.bestImage(info.avatars), info.subscriberCount.takeIf { it > 0 })

        val releases = async {
            val fromTab = runCatching {
                io("artist.releases") {
                    val tab = info.tabs.firstOrNull { ChannelTabs.ALBUMS in it.contentFilters } ?: return@io emptyList()
                    ChannelTabInfo.getInfo(s, tab).relatedItems.filterIsInstance<PlaylistInfoItem>().mapNotNull { item ->
                        YouTubeMapper.album(item)?.let { a -> if (a.artists.isEmpty()) a.copy(artists = listOf(app.musicflow.core.model.ArtistRef(id.value, name))) else a }
                    }
                }
            }.getOrDefault(emptyList())
            // Artist channels often expose no "Releases" tab through the extractor: fall back to an
            // album search, keeping only albums credited to this artist.
            fromTab.ifEmpty {
                runCatching {
                    io("artist.albumSearch") {
                        searchWith(name, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS, SearchFilter.ALBUMS, null).items
                            .filterIsInstance<MediaItemSummary.AlbumItem>().map { it.album }
                            .filter { a -> ArtistMatch.creditedTo(a.artistLine, a.title, name, id.value, a.artists.mapNotNull { it.id }) }
                    }
                }.getOrDefault(emptyList())
            }
        }
        val top = async {
            runCatching {
                searchOnce(name, SearchFilter.SONGS, null).items
                    .filterIsInstance<MediaItemSummary.TrackItem>().map { it.track }
                    .filter { t -> ArtistMatch.creditedTo(t.artistLine, t.title, name, id.value, t.artists.mapNotNull { it.id }) }
                    .take(20)
            }.getOrDefault(emptyList())
        }
        val all = releases.await()
        ArtistPage(
            artist = artist,
            topTracks = top.await(),
            albums = all.filter { (it.trackCount ?: 99) > 3 },
            singles = all.filter { (it.trackCount ?: 99) <= 3 },
            description = info.description?.takeIf { it.isNotBlank() },
        )
    }

    // ---------------------------------------------------------------- playlists

    override suspend fun playlist(id: MediaId, pageToken: String?): CollectionPage = io("playlist") {
        val s = service()
        val url = YouTubeIds.playlistUrl(id.key)
        if (pageToken == null) {
            val info = PlaylistInfo.getInfo(s, url)
            val art = YouTubeMapper.bestImage(info.thumbnails)
            val isAlbum = id.key.startsWith("OLAK5uy_")
            val albumRef = if (isAlbum) AlbumRef(id.value, info.name) else null
            CollectionPage(
                title = info.name,
                subtitle = YouTubeIds.cleanArtistName(info.uploaderName),
                artworkUrl = art,
                tracks = info.relatedItems.mapNotNull { YouTubeMapper.track(it, albumRef, if (isAlbum) art else null) },
                nextPageToken = storePage(info.nextPage),
            )
        } else {
            val more = PlaylistInfo.getMoreItems(s, url, takePage(pageToken))
            CollectionPage("", null, null, more.items.mapNotNull { YouTubeMapper.track(it) }, storePage(more.nextPage))
        }
    }

    override fun parsePlaylistUrl(url: String): MediaId? {
        if (!YouTubeIds.isYouTubeUrl(url)) return null
        return YouTubeIds.listIdFromUrl(url)?.let(YouTubeIds::playlist)
    }

    override fun parseTrackUrl(url: String): MediaId? {
        if (!YouTubeIds.isYouTubeUrl(url)) return null
        return YouTubeIds.videoIdFromUrl(url)?.let(YouTubeIds::track)
    }

    // ---------------------------------------------------------------- streams

    override fun canResolve(id: MediaId) = id.providerId == YouTubeIds.TRACK

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream = io("resolve") {
        val info = StreamInfo.getInfo(service(), YouTubeIds.watchUrl(id.key))
        val candidates = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && !it.content.isNullOrBlank() }
            .map { a ->
                AudioCandidate(
                    formatId = a.itag,
                    url = a.content,
                    mimeType = a.format?.mimeType,
                    codec = a.codec?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: a.format?.suffix,
                    // averageBitrate is reported in kbit/s, the itag bitrate in bit/s.
                    bitrateKbps = a.averageBitrate.takeIf { it > 0 } ?: a.bitrate.takeIf { it > 0 }?.div(1000),
                    sampleRateHz = a.itagItem?.sampleRate?.takeIf { it > 0 },
                    channels = a.itagItem?.audioChannels?.takeIf { it > 0 },
                    contentLength = a.itagItem?.contentLength?.takeIf { it > 0 } ?: YouTubeIds.contentLength(a.content),
                    isOriginalTrack = a.audioTrackType == null || a.audioTrackType == AudioTrackType.ORIGINAL,
                )
            }
        val chosen = StreamSelector.select(candidates, quality)
        if (chosen != null) {
            val chunked = YoutubeParsingHelper.isVisionOsStreamingUrl(chosen.url) || YoutubeParsingHelper.isWebStreamingUrl(chosen.url)
            val headers = if (YoutubeParsingHelper.isVisionOsStreamingUrl(chosen.url)) {
                mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
            } else mapOf("User-Agent" to NewPipeDownloader.USER_AGENT)
            ResolvedStream(
                mediaId = id,
                url = chosen.url,
                mimeType = chosen.mimeType,
                codec = chosen.codec,
                bitrateKbps = chosen.bitrateKbps,
                sampleRateHz = chosen.sampleRateHz,
                channels = chosen.channels,
                expiresAtMillis = YouTubeIds.expiryMillis(chosen.url),
                contentLength = chosen.contentLength,
                isLossless = false, // YouTube only serves lossy Opus/AAC.
                requestHeaders = headers,
                cacheKey = "yt:${id.key}:${chosen.formatId}",
                requiresChunkedPost = chunked,
            )
        } else if (!info.hlsUrl.isNullOrBlank()) {
            ResolvedStream(
                mediaId = id, url = info.hlsUrl, mimeType = "application/x-mpegURL", codec = null, bitrateKbps = null,
                sampleRateHz = null, expiresAtMillis = YouTubeIds.expiryMillis(info.hlsUrl), cacheKey = "yt:${id.key}:hls", isHls = true,
            )
        } else {
            throw ProviderError.Unavailable("No playable audio stream was returned for this track")
        }
    }

    // ---------------------------------------------------------------- recommendations

    override suspend fun related(seed: MediaId, limit: Int): List<Track> {
        if (seed.providerId != YouTubeIds.TRACK) return emptyList()
        val s = service()
        // 1) YouTube Music radio ("mix") for the seed track. 2) Fallback: related items of the video.
        val mix = runCatching {
            io("related.mix") {
                PlaylistInfo.getInfo(s, "https://www.youtube.com/watch?v=${seed.key}&list=RDAMVM${seed.key}")
                    .relatedItems.mapNotNull { YouTubeMapper.track(it) }
            }
        }.getOrElse { emptyList() }
        val tracks = mix.ifEmpty {
            io("related.stream") {
                StreamInfo.getInfo(s, YouTubeIds.watchUrl(seed.key)).relatedItems
                    .filterIsInstance<StreamInfoItem>().mapNotNull { YouTubeMapper.track(it) }
            }
        }
        return tracks
            .filterNot { isDerivative(it.title) }
            .filter { it.id != seed && (it.durationMs ?: 180_000L) in 60_000L..900_000L }
            .distinctBy { TextMatching.trackKey(it.title, it.primaryArtist) }
            .take(limit)
    }

    override suspend fun trending(): List<Track> = io("trending") {
        val s = service()
        val extractor = s.kioskList.getExtractorById("trending_music", null)
        extractor.fetchPage()
        KioskInfo.getInfo(extractor).relatedItems.mapNotNull { YouTubeMapper.track(it) }
    }

    companion object {
        private const val MERGED = "merged:"
        private const val NONE = "-"

        /** True for sped-up/slowed/8D/etc. re-uploads, including letter-spaced spellings. */
        fun isDerivative(title: String): Boolean =
            DERIVATIVE.containsMatchIn(title) || DERIVATIVE_COMPACT.containsMatchIn(title.replace(" ", "").lowercase())

        private val DERIVATIVE_COMPACT = Regex("""(slowed|spedup|nightcore|reverb(?!erat)|bassboosted|8daudio|\d{3}hz|mashup)""")

        /** Re-uploads that are not the original recording; excluded from radio/autoplay. */
        val DERIVATIVE = Regex(
            """\b(\d{3}\s?hz|sped\s?up|slowed|nightcore|8d audio|reverb|bass\s?boosted|1 hour|10 hours|karaoke|instrumental cover|tiktok|mashup)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** Albums matching a query; used by Explore for genre/decade shelves. */
    suspend fun albums(query: String): List<Album> =
        search(query, SearchFilter.ALBUMS).items.filterIsInstance<MediaItemSummary.AlbumItem>().map { it.album }
}
