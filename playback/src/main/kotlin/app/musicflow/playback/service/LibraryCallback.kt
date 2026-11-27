package app.musicflow.playback.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.repositories.CatalogRepository
import app.musicflow.data.repositories.CategoryGroup
import app.musicflow.data.repositories.ExploreCategory
import app.musicflow.data.repositories.ExploreRepository
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.data.repositories.PlaylistRepository
import app.musicflow.data.repositories.SearchRepository
import app.musicflow.playback.MediaItems
import app.musicflow.playback.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * Media library tree for Android Auto, Wear and other browsers. The root has four tabs with
 * everything the app offers:
 *   Home → Continue listening · Recently played · Most played · Trending
 *   Library → Liked songs · Playlists · Saved albums · Favourite artists · On this device
 *   Radio → Open FM · RMF ON · ESKA · popular here · worldwide · genres → stations
 *   Explore → genres / moods / decades / charts → songs (+ playlists of the category)
 * Choosing an item plays its whole list from that item (like in the app). Also handles
 * voice/text search (songs and radio stations) and playback resumption.
 */
@UnstableApi
internal class LibraryCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val search: SearchRepository,
    private val catalog: CatalogRepository,
    private val explore: ExploreRepository,
    private val local: LocalMusicProvider,
    private val toggleFavorite: suspend () -> Boolean,
    private val resumption: suspend () -> MediaSession.MediaItemsWithStartPosition?,
) : MediaLibrarySession.Callback {

    private val lastSearch = HashMap<String, List<Track>>()

    /** Lists shown recently (parent id → tracks, time), so a chosen item plays with its list and lists load once. */
    private val shown = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Track>>>()

    private fun folder(id: String, title: String, subtitle: String? = null, artwork: String? = null, grid: Boolean = false) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false)
                .setArtworkUri(artwork?.let(android.net.Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle().apply {
                    // Android Auto: show the children as a grid (stations, albums) or a list.
                    putInt(CONTENT_STYLE_PLAYABLE, if (grid) STYLE_GRID else STYLE_LIST)
                    putInt(CONTENT_STYLE_BROWSABLE, STYLE_LIST)
                })
                .build(),
        ).build()

    private fun s(id: Int) = context.getString(id)

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(MusicService.COMMAND_FAVORITE).build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setMediaButtonPreferences(ImmutableList.of(MusicService.favoriteButton(false, context.getString(R.string.playback_favorite))))
            .build()
    }

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        if (customCommand.customAction != MusicService.COMMAND_FAVORITE.customAction) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        return scope.future {
            val fav = toggleFavorite()
            session.setMediaButtonPreferences(ImmutableList.of(MusicService.favoriteButton(fav, context.getString(R.string.playback_favorite))))
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        val rootExtras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putBoolean(SEARCH_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE, STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
        }
        return Futures.immediateFuture(
            LibraryResult.ofItem(folder(ROOT, context.getString(R.string.app_name_short)), LibraryParams.Builder().setExtras(rootExtras).build()),
        )
    }

    /** Tracks of a playable list (null for folders of folders). Loaded at most once per 10 minutes. */
    private suspend fun tracksOf(parentId: String): List<Track>? {
        val cached = shown[parentId]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_MS && parentId !in LIVE_LISTS) return cached.second
        val tracks: List<Track> = when {
            parentId == CONTINUE -> library.continueListening(50).first()
            parentId == RECENT -> library.recentlyPlayed(50).first()
            parentId == FAVORITES -> library.favoriteTracks.first()
            parentId == MOST_PLAYED -> library.mostPlayed(50).first()
            parentId == TRENDING -> catalog.trending()
            parentId == LOCAL -> if (local.hasPermission()) local.scan() else emptyList()
            parentId.startsWith(PLAYLIST_PREFIX) -> parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                ?.let { id -> playlists.entries(id).first().map { it.track } }.orEmpty()
            parentId.startsWith(COLLECTION_PREFIX) -> catalog.collection(MediaId(parentId.removePrefix(COLLECTION_PREFIX))).tracks
            parentId.startsWith(ARTIST_PREFIX) -> catalog.artist(MediaId(parentId.removePrefix(ARTIST_PREFIX))).topTracks
            parentId.startsWith(CATEGORY_PREFIX) -> ExploreCategory.entries.firstOrNull { it.name == parentId.removePrefix(CATEGORY_PREFIX) }
                ?.let { explore.load(it).tracks }.orEmpty()
            else -> return null
        }
        shown[parentId] = System.currentTimeMillis() to tracks
        return tracks
    }

    private fun groupFolder(g: CategoryGroup, title: Int) = folder("$GROUP_PREFIX${g.name}", s(title))

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val items: List<MediaItem> = runCatching<List<MediaItem>> {
            when {
                parentId == ROOT -> listOf(
                    folder(HOME, s(R.string.auto_home)),
                    folder(LIBRARY, s(R.string.auto_library)),
                    folder(RADIO, s(R.string.auto_radio)),
                    folder(EXPLORE, s(R.string.auto_explore)),
                )
                parentId == HOME -> listOf(
                    folder(CONTINUE, s(R.string.auto_continue)),
                    folder(RECENT, s(R.string.auto_recent)),
                    folder(MOST_PLAYED, s(R.string.auto_most_played)),
                    folder(TRENDING, s(R.string.auto_trending)),
                )
                parentId == LIBRARY -> listOf(
                    folder(FAVORITES, s(R.string.auto_favorites)),
                    folder(PLAYLISTS, s(R.string.auto_playlists)),
                    folder(ALBUMS, s(R.string.auto_albums)),
                    folder(ARTISTS, s(R.string.auto_artists)),
                    folder(LOCAL, s(R.string.auto_local)),
                )
                parentId == PLAYLISTS -> playlists.playlists.first().map { folder("$PLAYLIST_PREFIX${it.id}", it.name) }
                parentId == ALBUMS -> library.savedAlbums.first().map {
                    folder("$COLLECTION_PREFIX${it.id.value}", it.title, it.artistLine.ifEmpty { null }, it.artworkUrl)
                }
                parentId == ARTISTS -> library.favoriteArtists.first().map {
                    folder("$ARTIST_PREFIX${it.id.value}", it.name, null, it.artworkUrl)
                }
                parentId == RADIO -> ExploreCategory.entries.filter { it.group == CategoryGroup.RADIO }
                    .map { folder("$CATEGORY_PREFIX${it.name}", s(autoCategoryLabel(it)), grid = true) }
                parentId == EXPLORE -> listOf(
                    groupFolder(CategoryGroup.GENRE, R.string.auto_genres),
                    groupFolder(CategoryGroup.MOOD, R.string.auto_moods),
                    groupFolder(CategoryGroup.DECADE, R.string.auto_decades),
                    groupFolder(CategoryGroup.CHARTS, R.string.auto_charts),
                )
                parentId.startsWith(GROUP_PREFIX) -> ExploreCategory.entries.filter { it.group.name == parentId.removePrefix(GROUP_PREFIX) }
                    .map { folder("$CATEGORY_PREFIX${it.name}", s(autoCategoryLabel(it))) }
                else -> tracksOf(parentId)?.map(MediaItems::toMediaItem).orEmpty()
            }
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            return@future LibraryResult.ofError(SessionError.ERROR_IO)
        }
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = if (pageSize == Int.MAX_VALUE) items.size else (from + pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val track = MediaId.parseOrNull(mediaId)?.let { library.cachedTrack(it) }
        if (track != null) LibraryResult.ofItem(MediaItems.toMediaItem(track), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = scope.future {
        // Songs from the main catalogue, plus radio stations ("RMF FM", "Open FM Po Polsku").
        val songs = runCatching { search.search(query, SearchFilter.SONGS).items.tracks() }.getOrDefault(emptyList())
        val stations = if (search.sources().any { it.id == "radio" }) {
            runCatching { search.search(query, SearchFilter.ALL, sourceId = "radio").items.tracks() }.getOrDefault(emptyList())
        } else emptyList()
        val q = app.musicflow.core.common.TextMatching.normalize(query)
        // A query naming a station ("rmf fm") puts stations first; otherwise songs first.
        val stationFirst = stations.firstOrNull()?.let { app.musicflow.core.common.TextMatching.normalize(it.title) == q } == true
        val results = if (stationFirst) stations.take(10) + songs else songs + stations.take(10)
        lastSearch[query] = results
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
        page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val items = lastSearch[query].orEmpty().map(MediaItems::toMediaItem)
        val from = (page * pageSize).coerceAtMost(items.size)
        val to = (from + pageSize).coerceAtMost(items.size)
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
    }

    /**
     * Android Auto sends just the chosen item: play it with the rest of its list, as in the app
     * (an album continues with the next song, a station list with the next station).
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        val single = mediaItems.singleOrNull()
        val list = single?.let { item -> (shown.values.map { it.second } + lastSearch.values).firstOrNull { l -> l.any { it.id.value == item.mediaId } } }
        if (single != null && list != null && list.size > 1) {
            val index = list.indexOfFirst { it.id.value == single.mediaId }
            MediaSession.MediaItemsWithStartPosition(list.map(MediaItems::toMediaItem), index, startPositionMs)
        } else {
            val resolved = rebuild(mediaItems)
            MediaSession.MediaItemsWithStartPosition(resolved, if (startIndex == androidx.media3.common.C.INDEX_UNSET) 0 else startIndex.coerceAtMost((resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }
    }

    /** Controllers strip URIs over IPC: rebuild playable items from metadata or the local cache. */
    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        scope.future { rebuild(mediaItems) }

    private suspend fun rebuild(mediaItems: List<MediaItem>): MutableList<MediaItem> =
        mediaItems.mapNotNull { item ->
            MediaItems.ensurePlayable(item)
                ?: MediaId.parseOrNull(item.mediaId)?.let { library.cachedTrack(it) }?.let(MediaItems::toMediaItem)
                ?: (lastSearch.values.flatten() + shown.values.flatMap { it.second }).firstOrNull { it.id.value == item.mediaId }?.let(MediaItems::toMediaItem)
        }.toMutableList()

    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
        resumption() ?: throw UnsupportedOperationException("Nothing to resume")
    }

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val FAVORITES = "favorites"
        const val MOST_PLAYED = "most_played"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST_PREFIX = "playlist:"
        const val HOME = "home"
        const val LIBRARY = "library"
        const val RADIO = "radio_tab"
        const val EXPLORE = "explore"
        const val CONTINUE = "continue"
        const val TRENDING = "trending"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val LOCAL = "local_music"
        const val COLLECTION_PREFIX = "collection|"
        const val ARTIST_PREFIX = "artist|"
        const val CATEGORY_PREFIX = "category|"
        const val GROUP_PREFIX = "group|"
        /** History lists change while driving: always reloaded. */
        private val LIVE_LISTS = setOf(CONTINUE, RECENT, FAVORITES, MOST_PLAYED)
        private const val CACHE_MS = 10 * 60 * 1000L

        // Android Auto content-style extras (androidx.media.utils.MediaConstants values).
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2
    }
}
