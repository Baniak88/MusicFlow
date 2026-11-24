package app.musicflow.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.model.Artist
import app.musicflow.core.model.NetworkStatus
import app.musicflow.core.model.Track
import app.musicflow.core.network.ConnectivityMonitor
import app.musicflow.data.repositories.HomeRepository
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.data.repositories.OnlineHome
import app.musicflow.data.repositories.SettingsRepository
import app.musicflow.playback.engine.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class Greeting { MORNING, AFTERNOON, EVENING, NIGHT }

data class LocalHome(
    val recentlyPlayed: List<Track> = emptyList(),
    val continueListening: List<Track> = emptyList(),
    val favoriteArtists: List<Artist> = emptyList(),
    val mostPlayed: List<Track> = emptyList(),
    val favoritesCount: Int = 0,
)

sealed interface OnlineState {
    data object Loading : OnlineState
    data class Loaded(val home: OnlineHome) : OnlineState
    data class Failed(val error: Throwable) : OnlineState
    data object Disabled : OnlineState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    library: LibraryRepository,
    private val home: HomeRepository,
    private val settings: SettingsRepository,
    connectivity: ConnectivityMonitor,
    val playback: PlaybackEngine,
) : ViewModel() {

    val greeting: Greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> Greeting.MORNING
        in 12..17 -> Greeting.AFTERNOON
        in 18..22 -> Greeting.EVENING
        else -> Greeting.NIGHT
    }

    val local: StateFlow<LocalHome> = combine(
        library.recentlyPlayed(20), library.continueListening(10), library.favoriteArtists, library.mostPlayed(10), library.favoriteIds,
    ) { recent, cont, artists, most, favs ->
        LocalHome(recent, cont, artists, most, favs.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalHome())

    val network: StateFlow<NetworkStatus> = connectivity.status

    private val _online = MutableStateFlow<OnlineState>(OnlineState.Loading)
    val online: StateFlow<OnlineState> = _online
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    init {
        refresh()
        // Reload when we come back online or the provider is toggled.
        viewModelScope.launch {
            connectivity.status.map { it.isConnected }.distinctUntilChanged().drop(1).collect { if (it) refresh() }
        }
        viewModelScope.launch {
            settings.settings.map { it.youtubeProviderEnabled to it.discoveryMode }.distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (_online.value is OnlineState.Loaded) _refreshing.value = true else _online.value = OnlineState.Loading
            val s = settings.current()
            _online.value = if (!s.youtubeProviderEnabled) OnlineState.Disabled else runCatching { home.load() }.fold(
                onSuccess = { OnlineState.Loaded(it) },
                onFailure = { OnlineState.Failed(it) },
            )
            _refreshing.value = false
        }
    }

    fun play(tracks: List<Track>, index: Int = 0) = playback.play(tracks, index)
    fun shuffle(tracks: List<Track>) = playback.play(tracks, 0, shuffle = true)
}
