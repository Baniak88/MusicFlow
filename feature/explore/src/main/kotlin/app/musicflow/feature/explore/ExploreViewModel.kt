package app.musicflow.feature.explore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.CategoryContent
import app.musicflow.data.repositories.CategoryGroup
import app.musicflow.data.repositories.ExploreCategory
import app.musicflow.data.repositories.ExploreRepository
import app.musicflow.playback.engine.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CategoryUi {
    data object Loading : CategoryUi
    data class Loaded(val content: CategoryContent) : CategoryUi
    data class Failed(val error: Throwable) : CategoryUi
}

@HiltViewModel
class ExploreViewModel @Inject constructor() : ViewModel() {
    val group = MutableStateFlow<CategoryGroup?>(null)
    fun categories(g: CategoryGroup?) = ExploreCategory.entries.filter { g == null || it.group == g }
}

@HiltViewModel
class CategoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ExploreRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val category: ExploreCategory = ExploreCategory.valueOf(savedState.get<String>("name") ?: ExploreCategory.POP.name)
    private val _ui = MutableStateFlow<CategoryUi>(CategoryUi.Loading)
    val ui: StateFlow<CategoryUi> = _ui
    val newestFirst = MutableStateFlow(false)

    init { load() }

    fun load() {
        _ui.value = CategoryUi.Loading
        viewModelScope.launch {
            _ui.value = runCatching { repo.load(category) }.fold({ CategoryUi.Loaded(it) }, { CategoryUi.Failed(it) })
        }
    }

    /** Release-date sorting is only offered when most tracks report a year. */
    fun canSortByDate(tracks: List<Track>) = tracks.isNotEmpty() && tracks.count { it.releaseYear != null } >= tracks.size * 0.7

    fun sorted(tracks: List<Track>, newest: Boolean) =
        if (newest && canSortByDate(tracks)) tracks.sortedByDescending { it.releaseYear ?: Int.MIN_VALUE } else tracks

    fun play(tracks: List<Track>, index: Int) = playback.play(tracks, index)
    fun shuffle(tracks: List<Track>) = playback.play(tracks, 0, shuffle = true)
}
