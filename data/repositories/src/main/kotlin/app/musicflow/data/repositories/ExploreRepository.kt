package app.musicflow.data.repositories

import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.PlaylistSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.tracks
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

enum class CategoryGroup { GENRE, MOOD, DECADE, CHARTS, RADIO }

/**
 * Explore categories. Content comes from the provider's own search for a descriptive query,
 * so everything shown is real catalogue data (no curated fake lists).
 */
enum class ExploreCategory(val group: CategoryGroup, val query: String, val color: Long) {
    ELECTRONIC(CategoryGroup.GENRE, "electronic music", 0xFF3B82F6),
    DANCE(CategoryGroup.GENRE, "dance hits", 0xFFEC4899),
    EDM(CategoryGroup.GENRE, "edm", 0xFF8B5CF6),
    HOUSE(CategoryGroup.GENRE, "house music", 0xFFF59E0B),
    TRANCE(CategoryGroup.GENRE, "trance", 0xFF06B6D4),
    TECHNO(CategoryGroup.GENRE, "techno", 0xFF64748B),
    POP(CategoryGroup.GENRE, "pop hits", 0xFFF43F5E),
    ROCK(CategoryGroup.GENRE, "rock classics", 0xFFDC2626),
    HIP_HOP(CategoryGroup.GENRE, "hip hop", 0xFFEAB308),
    RNB(CategoryGroup.GENRE, "r&b", 0xFFA855F7),
    CHILL(CategoryGroup.MOOD, "chill music", 0xFF14B8A6),
    WORKOUT(CategoryGroup.MOOD, "workout music", 0xFFEF4444),
    HITS_80S(CategoryGroup.DECADE, "80s hits", 0xFFD946EF),
    HITS_90S(CategoryGroup.DECADE, "90s hits", 0xFF6366F1),
    HITS_2000S(CategoryGroup.DECADE, "2000s hits", 0xFF0EA5E9),
    NEW_RELEASES(CategoryGroup.CHARTS, "new music {year}", 0xFF22C55E),
    UK_CHARTS(CategoryGroup.CHARTS, "uk top 40 official chart", 0xFF1D4ED8),
    GLOBAL_CHARTS(CategoryGroup.CHARTS, "global top 50 {year}", 0xFF7C3AED),
    /** Internet radio (Radio Browser): stations, not songs. Queries: "@local", "@world" or a genre tag. */
    RADIO_RMF_ON(CategoryGroup.RADIO, "@net:rmf", 0xFFE11D48),
    RADIO_ESKA(CategoryGroup.RADIO, "@net:eska", 0xFF2563EB),
    RADIO_LOCAL(CategoryGroup.RADIO, "@local", 0xFFC45BF6),
    RADIO_WORLD(CategoryGroup.RADIO, "@world", 0xFF8B5CF6),
    RADIO_POP(CategoryGroup.RADIO, "pop", 0xFFF43F5E),
    RADIO_ROCK(CategoryGroup.RADIO, "rock", 0xFFDC2626),
    RADIO_DANCE(CategoryGroup.RADIO, "dance", 0xFFEC4899),
    RADIO_JAZZ(CategoryGroup.RADIO, "jazz", 0xFFF59E0B),
    RADIO_CLASSICAL(CategoryGroup.RADIO, "classical", 0xFF14B8A6),
    RADIO_NEWS(CategoryGroup.RADIO, "news", 0xFF64748B);

    fun resolvedQuery(year: Int = Calendar.getInstance().get(Calendar.YEAR)) = query.replace("{year}", year.toString())
}

data class CategoryContent(val playlists: List<PlaylistSummary>, val tracks: List<Track>)

@Singleton
class ExploreRepository @Inject constructor(private val catalog: CatalogRepository) {
    suspend fun load(category: ExploreCategory): CategoryContent = coroutineScope {
        if (category.group == CategoryGroup.RADIO) {
            val stations = when (category.query) {
                "@local" -> catalog.radioStations()
                "@world" -> catalog.radioStations(worldwide = true)
                else -> catalog.radioStations(tag = category.query)
            }
            return@coroutineScope CategoryContent(playlists = emptyList(), tracks = stations)
        }
        val q = category.resolvedQuery()
        val playlists = async { runCatching { catalog.searchItems(q, SearchFilter.PLAYLISTS) } }
        val songs = async { runCatching { catalog.searchItems(q, SearchFilter.SONGS) } }
        val p = playlists.await()
        val s = songs.await()
        if (p.isFailure && s.isFailure) throw p.exceptionOrNull()!!
        CategoryContent(
            playlists = p.getOrDefault(emptyList()).filterIsInstance<MediaItemSummary.PlaylistItem>().map { it.playlist },
            tracks = s.getOrDefault(emptyList()).tracks(),
        )
    }
}
