package app.musicflow.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.SearchRepository
import app.musicflow.playback.engine.PlaybackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

data class ResultsState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val items: List<MediaItemSummary> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Throwable? = null,
    val nextPageToken: String? = null,
    val correctedQuery: String? = null,
    /** Provider id searched (e.g. `yt`, `sc`); null = main catalogue. */
    val source: String? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: SearchRepository,
    private val playback: PlaybackEngine,
) : ViewModel() {
    val input = MutableStateFlow("")

    /** Selectable online sources (shown as chips when there is more than one). */
    val sources: List<app.musicflow.data.repositories.SearchSource> get() = repo.sources()
    val submitted = MutableStateFlow(false)

    val history: StateFlow<List<String>> = repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Suggestions are debounced (300 ms) so typing does not fire a request per keystroke. */
    val suggestions: StateFlow<List<String>> = input
        .debounce(300)
        .distinctUntilChanged()
        .mapLatest { q -> if (q.length < 2) emptyList() else app.musicflow.core.common.runCatchingCancellable { repo.suggestions(q, _results.value.source) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _results = MutableStateFlow(ResultsState())
    val results: StateFlow<ResultsState> = _results
    private var searchJob: Job? = null

    fun onInput(text: String) {
        input.value = text
        if (submitted.value && text != _results.value.query) submitted.value = false
    }

    fun submit(query: String = input.value, filter: SearchFilter = _results.value.filter, source: String? = _results.value.source) {
        val q = query.trim()
        if (q.isEmpty()) return
        input.value = q
        submitted.value = true
        searchJob?.cancel()
        _results.value = ResultsState(query = q, filter = filter, loading = true, source = source)
        searchJob = viewModelScope.launch {
            repo.recordQuery(q)
            val result = app.musicflow.core.common.runCatchingCancellable { repo.search(q, filter, sourceId = source) }
            coroutineContext.ensureActive() // a newer search replaced this one: never show stale results
            result.getOrNull()?.items?.filterIsInstance<MediaItemSummary.TrackItem>()?.map { it.track }?.let(playback::prefetch)
            _results.value = result.fold(
                onSuccess = { ResultsState(q, filter, SearchRepository.dedupe(emptyList(), it.items), nextPageToken = it.nextPageToken, correctedQuery = it.correctedQuery, source = source) },
                onFailure = { ResultsState(q, filter, error = it, source = source) },
            )
        }
    }

    fun setFilter(filter: SearchFilter) { if (filter != _results.value.filter) submit(_results.value.query, filter) }

    fun setSource(source: String) {
        val current = _results.value.source ?: sources.firstOrNull()?.id
        if (source != current) submit(_results.value.query, _results.value.filter, source)
    }

    /** Loads the next page; duplicate items across pages are dropped. Guarded against double calls. */
    fun loadMore() {
        val s = _results.value
        val token = s.nextPageToken ?: return
        if (s.loading || s.loadingMore) return
        _results.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            app.musicflow.core.common.runCatchingCancellable { repo.search(s.query, s.filter, token, s.source) }
                .onSuccess { page ->
                    val cur = _results.value
                    if (cur.query == s.query && cur.filter == s.filter && cur.source == s.source) {
                        _results.value = cur.copy(items = cur.items + SearchRepository.dedupe(cur.items, page.items), nextPageToken = page.nextPageToken, loadingMore = false)
                    }
                }
                .onFailure { _results.value = _results.value.copy(loadingMore = false, nextPageToken = null) }
        }
    }

    fun clear() { input.value = ""; submitted.value = false }
    fun deleteHistory(q: String) = viewModelScope.launch { repo.deleteQuery(q) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }

    fun playTrack(track: Track) {
        // Play the tapped song, followed by the other songs in the results.
        val tracks = _results.value.items.filterIsInstance<MediaItemSummary.TrackItem>().map { it.track }
        val idx = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playback.play(if (tracks.isEmpty()) listOf(track) else tracks, idx, source = "“${_results.value.query}”")
    }
}
