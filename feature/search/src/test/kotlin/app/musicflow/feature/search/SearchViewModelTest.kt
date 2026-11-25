package app.musicflow.feature.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.RetryPolicy
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.SearchProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.repositories.SearchRepository
import app.musicflow.playback.engine.PlaybackEngine
import app.musicflow.playback.engine.PlaybackState
import app.musicflow.playback.engine.Progress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class PagedProvider : MusicProvider, SearchProvider {
    override val id = "yt"; override val displayName = "fake"; override val isExperimental = false
    override val isOnlineCatalog = true
    override val search get() = this
    val suggestionCalls = mutableListOf<String>()
    var searchCalls = 0
    fun t(i: Int, title: String = "Song $i") = MediaItemSummary.TrackItem(Track(MediaId("yt:$i"), title, listOf(ArtistRef(null, "A"))))
    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        searchCalls++
        // Like a blocking network call: it finishes even after its coroutine was cancelled.
        if (query == "slow") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(1_000) }
        return when (pageToken) {
            null -> SearchPage(query, filter, listOf(t(1), t(2)), "p2", correctedQuery = if (query == "avici") "avicii" else null)
            // Page 2 repeats id 2 and the same song as 1 under another id: both must be dropped.
            "p2" -> SearchPage(query, filter, listOf(t(2), MediaItemSummary.TrackItem(Track(MediaId("yt:99"), "Song 1 (Official Video)", listOf(ArtistRef(null, "A")))), t(3)), null)
            else -> error("bad token")
        }
    }
    override suspend fun suggestions(query: String): List<String> { suggestionCalls += query; return listOf("$query remix") }
}

private class NoopEngine : PlaybackEngine {
    val played = mutableListOf<Pair<List<Track>, Int>>()
    override val state = MutableStateFlow(PlaybackState())
    override val progress = emptyFlow<Progress>()
    override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean, source: String?) { played += tracks to startIndex }
    override fun playNext(track: Track) {}; override fun addToQueue(tracks: List<Track>) {}; override fun togglePlayPause() {}
    override fun pause() {}; override fun next() {}; override fun previous() {}; override fun seekTo(positionMs: Long) {}
    override fun skipToQueueItem(index: Int) {}; override fun removeFromQueue(index: Int) {}; override fun moveQueueItem(from: Int, to: Int) {}
    override fun clearQueue() {}; override fun setShuffle(enabled: Boolean) {}; override fun cycleRepeat() {}; override fun setSpeed(speed: Float) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MusicFlowDatabase
    private lateinit var provider: PagedProvider
    private lateinit var engine: NoopEngine
    private lateinit var vm: SearchViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MusicFlowDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() } // deterministic under the test dispatcher
            .build()
        provider = PagedProvider()
        val registry = ProviderRegistry(setOf(provider), DiagnosticsLog()).apply { retryPolicy = RetryPolicy.None }
        engine = NoopEngine()
        vm = SearchViewModel(SearchRepository(registry, db), engine)
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    /** Reported: typing a new query showed "unexpected error (… was cancelled)". */
    @Test fun replacedSearchNeverShowsACancellationError() = runTest(dispatcher) {
        vm.submit("slow"); advanceTimeBy(100)
        vm.submit("fast"); advanceUntilIdle()
        assertThat(vm.results.value.error).isNull()
        assertThat(vm.results.value.query).isEqualTo("fast")
        assertThat(vm.results.value.items).isNotEmpty()
    }

    @Test fun suggestionsAreDebounced() = runTest(dispatcher) {
        val job = launch { vm.suggestions.collect {} }
        "daft punk".forEachIndexed { i, _ -> vm.onInput("daft punk".substring(0, i + 1)); advanceTimeBy(50) }
        advanceTimeBy(400); advanceUntilIdle()
        // One request for the final text, not one per keystroke.
        assertThat(provider.suggestionCalls).containsExactly("daft punk")
        assertThat(vm.suggestions.value).contains("daft punk remix")
        job.cancel()
    }

    @Test fun searchPaginatesWithoutDuplicatesAndRecordsHistory() = runTest(dispatcher) {
        val job = launch { vm.history.collect {} }
        vm.submit("songs"); advanceUntilIdle()
        assertThat(vm.results.value.items.map { it.id.value }).containsExactly("yt:1", "yt:2").inOrder()
        vm.loadMore(); vm.loadMore() // second call is ignored while loading
        advanceUntilIdle()
        assertThat(vm.results.value.items.map { it.id.value }).containsExactly("yt:1", "yt:2", "yt:3").inOrder()
        assertThat(vm.results.value.nextPageToken).isNull()
        assertThat(provider.searchCalls).isEqualTo(2)
        assertThat(vm.history.value).containsExactly("songs")
        job.cancel()
    }

    @Test fun didYouMeanAndPlayingFromResults() = runTest(dispatcher) {
        vm.submit("avici"); advanceUntilIdle()
        assertThat(vm.results.value.correctedQuery).isEqualTo("avicii")
        val second = (vm.results.value.items[1] as MediaItemSummary.TrackItem).track
        vm.playTrack(second)
        assertThat(engine.played.single().second).isEqualTo(1)
        assertThat(engine.played.single().first).hasSize(2)
    }

    @Test fun changingFilterRunsANewSearch() = runTest(dispatcher) {
        vm.submit("x"); advanceUntilIdle()
        vm.setFilter(SearchFilter.ALBUMS); advanceUntilIdle()
        assertThat(vm.results.value.filter).isEqualTo(SearchFilter.ALBUMS)
        assertThat(provider.searchCalls).isEqualTo(2)
    }
}
