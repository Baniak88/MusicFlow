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
 * Media library tree for Android Auto, Wear and other browsers:
 *   root → Recently played · Favourites · Most played · Playlists → playlist → tracks.
 * Also handles voice/text search from Android Auto and playback resumption.
 */
@UnstableApi
internal class LibraryCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val search: SearchRepository,
    private val toggleFavorite: suspend () -> Boolean,
    private val resumption: suspend () -> MediaSession.MediaItemsWithStartPosition?,
) : MediaLibrarySession.Callback {

    private val lastSearch = HashMap<String, List<Track>>()

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

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

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, context.getString(R.string.app_name_short)), params))

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val items: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                folder(RECENT, context.getString(R.string.auto_recent)),
                folder(FAVORITES, context.getString(R.string.auto_favorites)),
                folder(MOST_PLAYED, context.getString(R.string.auto_most_played)),
                folder(PLAYLISTS, context.getString(R.string.auto_playlists)),
            )
            parentId == RECENT -> library.recentlyPlayed(50).first().map(MediaItems::toMediaItem)
            parentId == FAVORITES -> library.favoriteTracks.first().map(MediaItems::toMediaItem)
            parentId == MOST_PLAYED -> library.mostPlayed(50).first().map(MediaItems::toMediaItem)
            parentId == PLAYLISTS -> playlists.playlists.first().map { folder("$PLAYLIST_PREFIX${it.id}", it.name) }
            parentId.startsWith(PLAYLIST_PREFIX) -> parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                ?.let { id -> playlists.entries(id).first().map { MediaItems.toMediaItem(it.track) } }.orEmpty()
            else -> emptyList()
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
        val results = runCatching { search.search(query, SearchFilter.SONGS).items.tracks() }.getOrDefault(emptyList())
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

    /** Controllers strip URIs over IPC: rebuild playable items from metadata or the local cache. */
    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = scope.future {
        mediaItems.mapNotNull { item ->
            MediaItems.ensurePlayable(item)
                ?: MediaId.parseOrNull(item.mediaId)?.let { library.cachedTrack(it) }?.let(MediaItems::toMediaItem)
                ?: lastSearch.values.flatten().firstOrNull { it.id.value == item.mediaId }?.let(MediaItems::toMediaItem)
        }.toMutableList()
    }

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
    }
}
