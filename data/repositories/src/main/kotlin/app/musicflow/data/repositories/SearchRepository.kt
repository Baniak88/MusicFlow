package app.musicflow.data.repositories

import app.musicflow.core.common.Clock
import app.musicflow.core.common.TextMatching
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.database.entity.SearchHistoryEntity
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val registry: ProviderRegistry,
    private val db: MusicFlowDatabase,
) {
    var clock: Clock = Clock.System

    val history: Flow<List<String>> = db.history().searches(20).map { l -> l.map { it.query } }

    suspend fun recordQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val existing = db.history().search(q)
        db.history().upsertSearch(SearchHistoryEntity(q, clock.now(), (existing?.useCount ?: 0) + 1))
    }

    suspend fun deleteQuery(query: String) = db.history().deleteSearch(query)
    suspend fun clearHistory() = db.history().clearSearches()

    /**
     * Searches the primary online catalogue. If no online provider is available, falls back to
     * on-device music so search still works offline.
     */
    suspend fun search(query: String, filter: SearchFilter, pageToken: String? = null): SearchPage {
        val provider = registry.primaryCatalog()?.takeIf { it !is LocalMusicProvider }
            ?: registry.enabled().filterIsInstance<LocalMusicProvider>().firstOrNull()
            ?: throw ProviderError.NotSupported("No search provider enabled")
        return registry.call(provider, "search") { it.search!!.search(query, filter, pageToken) }
    }

    /** Local history matches first, then provider suggestions; de-duplicated case-insensitively. */
    suspend fun suggestions(prefix: String): List<String> {
        val p = prefix.trim()
        if (p.isEmpty()) return emptyList()
        val local = db.history().searchesStartingWith(p, 4).map { it.query }
        val remote = registry.primaryCatalog()?.let { provider ->
            runCatching {
                registry.call(provider, "suggestions", policy = app.musicflow.core.common.RetryPolicy.None) { it.search!!.suggestions(p) }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return (local + remote).distinctBy { it.lowercase().trim() }.take(10)
    }

    companion object {
        /**
         * Removes duplicates across pages / categories: the same id, or the same song (normalised
         * title + primary artist) appearing twice, e.g. a music video and its audio upload.
         */
        fun dedupe(existing: List<MediaItemSummary>, incoming: List<MediaItemSummary>): List<MediaItemSummary> {
            val seenIds = existing.mapTo(HashSet()) { it.id }
            val seenKeys = existing.mapNotNullTo(HashSet()) { key(it) }
            return incoming.filter { item ->
                val k = key(item)
                val fresh = item.id !in seenIds && (k == null || k !in seenKeys)
                if (fresh) { seenIds += item.id; k?.let(seenKeys::add) }
                fresh
            }
        }

        private fun key(item: MediaItemSummary): String? =
            (item as? MediaItemSummary.TrackItem)?.track?.let { TextMatching.trackKey(it.title, it.primaryArtist) }
    }
}
