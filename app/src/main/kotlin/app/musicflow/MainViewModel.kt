package app.musicflow

import android.content.Intent
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.UserSettings
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.repositories.CatalogRepository
import app.musicflow.data.repositories.SearchRepository
import app.musicflow.data.repositories.SettingsRepository
import app.musicflow.playback.engine.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ShellEvent {
    data class OpenCollection(val id: MediaId) : ShellEvent
    data object OpenPlayer : ShellEvent
    data object Unsupported : ShellEvent
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    private val registry: ProviderRegistry,
    private val catalog: CatalogRepository,
    private val search: SearchRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val settings: StateFlow<UserSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _events = MutableSharedFlow<ShellEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ShellEvent> = _events

    /** Handles shared links, music.youtube.com links and "play X" voice searches. */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(::openLink)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("""https://\S+""").find(text)?.value?.let(::openLink) ?: _events.tryEmit(ShellEvent.Unsupported)
            }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
                viewModelScope.launch {
                    val tracks = if (query.isBlank()) emptyList() else runCatching { search.search(query, SearchFilter.SONGS).items.tracks() }.getOrDefault(emptyList())
                    if (tracks.isNotEmpty()) { playback.play(tracks, 0); _events.emit(ShellEvent.OpenPlayer) }
                }
            }
        }
    }

    private fun openLink(url: String) {
        val playlist = registry.enabled().firstNotNullOfOrNull { it.playlists?.parsePlaylistUrl(url) }
        val track = registry.enabled().firstNotNullOfOrNull { it.playlists?.parseTrackUrl(url) }
        viewModelScope.launch {
            when {
                track != null -> runCatching { catalog.track(track) }.onSuccess { playback.play(listOf(it)); _events.emit(ShellEvent.OpenPlayer) }
                    .onFailure { _events.emit(ShellEvent.Unsupported) }
                playlist != null -> _events.emit(ShellEvent.OpenCollection(playlist))
                else -> _events.emit(ShellEvent.Unsupported)
            }
        }
    }
}
