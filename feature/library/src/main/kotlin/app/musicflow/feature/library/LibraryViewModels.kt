package app.musicflow.feature.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.designsystem.navigation.LibrarySection
import app.musicflow.core.model.Album
import app.musicflow.core.model.Artist
import app.musicflow.core.model.ArtistPage
import app.musicflow.core.model.CollectionPage
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.repositories.AddResult
import app.musicflow.data.repositories.CatalogRepository
import app.musicflow.data.repositories.HistoryEntry
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.data.repositories.PlaylistEntry
import app.musicflow.data.repositories.PlaylistRepository
import app.musicflow.data.repositories.UserPlaylist
import app.musicflow.playback.engine.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One-shot user-facing messages (snackbars). */
sealed interface LibraryEvent {
    data class Imported(val count: Int, val skipped: Int) : LibraryEvent
    data class Failed(val error: Throwable) : LibraryEvent
    data class Added(val result: AddResult, val playlistName: String) : LibraryEvent
    data object Exported : LibraryEvent
    data class OpenPlaylist(val id: Long) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    library: LibraryRepository,
    private val playlistRepo: PlaylistRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val playlists: StateFlow<List<UserPlaylist>> = playlistRepo.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites: StateFlow<List<Track>> = library.favoriteTracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val albums: StateFlow<List<Album>> = library.savedAlbums.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val artists: StateFlow<List<Artist>> = library.favoriteArtists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events
    val busy = MutableStateFlow(false)

    fun create(name: String) = viewModelScope.launch { _events.emit(LibraryEvent.OpenPlaylist(playlistRepo.create(name))) }

    fun importUrl(url: String) = launchBusy {
        val r = playlistRepo.importPublic(url.trim())
        _events.emit(LibraryEvent.Imported(r.imported, r.skipped))
        _events.emit(LibraryEvent.OpenPlaylist(r.playlistId))
    }

    fun importM3u(uri: Uri) = launchBusy {
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
            ?: throw IllegalStateException("Cannot read file")
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported"
        val r = playlistRepo.importM3u(text, name)
        _events.emit(LibraryEvent.Imported(r.imported, r.skipped))
        _events.emit(LibraryEvent.OpenPlaylist(r.playlistId))
    }

    /** Plays liked songs starting with [track] (tapping a song plays it, it doesn't navigate). */
    fun playFavoritesFrom(track: Track) {
        val list = favorites.value
        playback.play(list, list.indexOfFirst { it.id == track.id }.coerceAtLeast(0), source = likedSongsLabel)
    }

    fun playFavorites(shuffle: Boolean) = favorites.value.takeIf { it.isNotEmpty() }?.let { playback.play(it, 0, shuffle, likedSongsLabel) }
    private val likedSongsLabel get() = context.getString(R.string.lib_favorites)

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        app.musicflow.core.common.runCatchingCancellable { block() }.onFailure { _events.emit(LibraryEvent.Failed(it)) }
        busy.value = false
    }
}

@HiltViewModel
class UserPlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repo: PlaylistRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: savedState.get<String>("id")?.toLong() ?: 0L
    val playlist = repo.playlist(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val dbEntries = repo.entries(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local copy so drag-and-drop is smooth; persisted when the drag ends. */
    val entries = MutableStateFlow<List<PlaylistEntry>>(emptyList())
    private var dragging = false
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events

    init { viewModelScope.launch { dbEntries.collect { if (!dragging) entries.value = it } } }

    fun move(from: Int, to: Int) {
        dragging = true
        entries.value = entries.value.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun commitOrder() = viewModelScope.launch {
        repo.reorder(id, entries.value.map { it.entryId })
        dragging = false
    }

    fun remove(entry: PlaylistEntry) = viewModelScope.launch { repo.remove(id, entry.entryId) }
    fun rename(name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete() = viewModelScope.launch { repo.delete(id) }
    fun play(index: Int = 0, shuffle: Boolean = false) = playback.play(entries.value.map { it.track }, index, shuffle, playlist.value?.name)

    fun export(uri: Uri) = viewModelScope.launch {
        app.musicflow.core.common.runCatchingCancellable {
            val text = repo.exportM3u(id)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } }
        }.onSuccess { _events.emit(LibraryEvent.Exported) }.onFailure { _events.emit(LibraryEvent.Failed(it)) }
    }
}

sealed interface SectionContent {
    data class Tracks(val tracks: List<Track>) : SectionContent
    data class History(val entries: List<HistoryEntry>) : SectionContent
    data object NeedsPermission : SectionContent
    data object Loading : SectionContent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibrarySectionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val local: LocalMusicProvider,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val section = LibrarySection.valueOf(savedState.get<String>("section") ?: LibrarySection.FAVORITES.name)
    val localPermission: String = local.requiredPermission
    private val reload = MutableStateFlow(0)

    val content: StateFlow<SectionContent> = reload.flatMapLatest {
        when (section) {
            LibrarySection.FAVORITES -> library.favoriteTracks.map { SectionContent.Tracks(it) }
            LibrarySection.RECENT -> library.recentlyPlayed(100).map { SectionContent.Tracks(it) }
            LibrarySection.MOST_PLAYED -> library.mostPlayed(100).map { SectionContent.Tracks(it) }
            LibrarySection.HISTORY -> library.history(500).map { SectionContent.History(it) }
            LibrarySection.LOCAL -> if (!local.hasPermission()) flowOf(SectionContent.NeedsPermission)
                else kotlinx.coroutines.flow.flow { emit(SectionContent.Loading); emit(SectionContent.Tracks(local.scan())) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionContent.Loading)

    fun refresh() { reload.value++ }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }
    fun play(tracks: List<Track>, index: Int, shuffle: Boolean = false, source: String? = null) = playback.play(tracks, index, shuffle, source)
}

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data class Failed(val error: Throwable) : LoadState<Nothing>
}

@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val id = MediaId(savedState.get<String>("id")!!)
    private val _state = MutableStateFlow<LoadState<CollectionPage>>(LoadState.Loading)
    val state: StateFlow<LoadState<CollectionPage>> = _state
    val loadingMore = MutableStateFlow(false)
    val isSaved = library.isAlbumSaved(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        _state.value = app.musicflow.core.common.runCatchingCancellable { catalog.collection(id) }.fold({ LoadState.Loaded(it) }, { LoadState.Failed(it) })
    }

    fun loadMore() {
        val cur = (_state.value as? LoadState.Loaded)?.value ?: return
        val token = cur.nextPageToken ?: return
        if (loadingMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            app.musicflow.core.common.runCatchingCancellable { catalog.collection(id, token) }.onSuccess { more ->
                val existing = cur.tracks.map { it.id }.toSet()
                _state.value = LoadState.Loaded(cur.copy(tracks = cur.tracks + more.tracks.filter { it.id !in existing }, nextPageToken = more.nextPageToken))
            }
            loadingMore.value = false
        }
    }

    fun toggleSaved() = viewModelScope.launch {
        val page = (_state.value as? LoadState.Loaded)?.value ?: return@launch
        val album = Album(id, page.title, listOfNotNull(page.subtitle?.let { app.musicflow.core.model.ArtistRef(null, it) }), page.year, page.artworkUrl, page.tracks.size)
        library.setAlbumSaved(album, !isSaved.value)
    }

    fun saveAsPlaylist() = viewModelScope.launch {
        val page = (_state.value as? LoadState.Loaded)?.value ?: return@launch
        val pid = playlists.create(page.title)
        playlists.add(pid, page.tracks, allowDuplicates = true)
        _events.emit(LibraryEvent.OpenPlaylist(pid))
    }

    fun play(index: Int = 0, shuffle: Boolean = false) {
        val page = (_state.value as? LoadState.Loaded)?.value ?: return
        playback.play(page.tracks, index, shuffle, page.title)
    }
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val id = MediaId(savedState.get<String>("id")!!)
    private val _state = MutableStateFlow<LoadState<ArtistPage>>(LoadState.Loading)
    val state: StateFlow<LoadState<ArtistPage>> = _state
    val isFavorite = library.isArtistFavorite(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Facts from MusicBrainz (country, type, years, genres) when it knows this artist for sure. */
    private val _facts = MutableStateFlow<app.musicflow.data.providers.musicbrainz.ArtistFacts?>(null)
    val facts: StateFlow<app.musicflow.data.providers.musicbrainz.ArtistFacts?> = _facts

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = LoadState.Loading
        _state.value = app.musicflow.core.common.runCatchingCancellable { catalog.artist(id) }.fold({ LoadState.Loaded(it) }, { LoadState.Failed(it) })
        (_state.value as? LoadState.Loaded)?.value?.artist?.let { a -> _facts.value = catalog.artistFacts(a) }
    }

    fun toggleFavorite() = viewModelScope.launch {
        val a = (_state.value as? LoadState.Loaded)?.value?.artist ?: return@launch
        library.setArtistFavorite(a, !isFavorite.value)
    }

    fun play(tracks: List<Track>, index: Int, shuffle: Boolean = false) =
        playback.play(tracks, index, shuffle, (_state.value as? LoadState.Loaded)?.value?.artist?.name)

    /** Artist radio: the artist's top track followed by related tracks. */
    fun radio() = viewModelScope.launch {
        val top = (_state.value as? LoadState.Loaded)?.value?.topTracks?.firstOrNull() ?: return@launch
        val related = app.musicflow.core.common.runCatchingCancellable { catalog.related(top.id, 30) }.getOrDefault(emptyList())
        playback.play(listOf(top) + related, 0, source = (_state.value as? LoadState.Loaded)?.value?.artist?.name)
    }
}

@HiltViewModel
class TrackMenuViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val userPlaylists = playlists.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events
    private val current = MutableStateFlow<Track?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFavorite: StateFlow<Boolean> = current.flatMapLatest { t -> t?.let { library.isFavorite(it.id) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun bind(track: Track) { current.value = track }
    fun playNext(t: Track) = playback.playNext(t)
    fun addToQueue(t: Track) = playback.addToQueue(listOf(t))
    fun toggleFavorite(t: Track) = viewModelScope.launch { library.toggleFavorite(t) }
    fun startRadio(t: Track) = viewModelScope.launch {
        playback.play(listOf(t), 0)
        // Queue continuation in the service appends related tracks automatically.
    }

    /** Adds to a playlist; returns duplicates so the UI can ask whether to add them anyway. */
    fun addToPlaylist(t: Track, playlist: UserPlaylist, force: Boolean = false) = viewModelScope.launch {
        val r = playlists.add(playlist.id, listOf(t), allowDuplicates = force)
        _events.emit(LibraryEvent.Added(r, playlist.name))
    }

    fun createAndAdd(t: Track, name: String) = viewModelScope.launch {
        val id = playlists.create(name)
        val r = playlists.add(id, listOf(t))
        _events.emit(LibraryEvent.Added(r, name))
    }
}
