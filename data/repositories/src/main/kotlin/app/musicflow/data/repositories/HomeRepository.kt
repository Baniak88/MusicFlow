package app.musicflow.data.repositories

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.model.Album
import app.musicflow.core.model.Artist
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.PlaylistSummary
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.Track
import app.musicflow.core.common.TextMatching
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** A personalised mix built from one seed artist. */
data class Mix(val title: String, val seedArtist: String, val artworkUrl: String?, val tracks: List<Track>)

data class OnlineHome(
    val quickPicks: List<Track> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val recommendedAlbums: List<Album> = emptyList(),
    val trending: List<Track> = emptyList(),
    val newReleases: List<PlaylistSummary> = emptyList(),
    val discover: List<Track> = emptyList(),
    val suggestedArtists: List<Artist> = emptyList(),
    val failures: List<Throwable> = emptyList(),
)

/**
 * Builds the online parts of Home from real provider data, personalised with the local
 * listening profile. Every section is fetched independently; a failing section is omitted
 * rather than failing the whole screen.
 */
@Singleton
class HomeRepository @Inject constructor(
    private val db: MusicFlowDatabase,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
) {
    private val engine = RecommendationEngine()
    private val monthMs = 30L * 24 * 3600 * 1000

    suspend fun profile(now: Long = System.currentTimeMillis()): RecommendationEngine.Profile {
        val tracks = db.history().trackStats(now - 6 * monthMs)
        val artists = db.history().artistStats(now - 6 * monthMs)
        val favArtists = db.library().allFavoriteArtists()
        val trackEntities = db.tracks().getAll(tracks.map { it.trackId })
        val genres = trackEntities.mapNotNull { it.genre?.lowercase() }.groupingBy { it }.eachCount()
        return RecommendationEngine.Profile(
            tracks = tracks.associate { it.trackId to RecommendationEngine.TrackSignal(it.plays, it.completions, it.skips, it.lastPlayedAt) },
            artists = artists.associate { TextMatching.normalize(it.artistName) to RecommendationEngine.ArtistSignal(it.plays, it.skips) },
            favoriteTrackIds = db.library().allFavoriteIds().toSet(),
            favoriteArtistKeys = favArtists.map { TextMatching.normalize(it.name) }.toSet(),
            genres = genres,
        )
    }

    /** Seed tracks: most-played and favourite tracks, one per artist. */
    private suspend fun seeds(now: Long, max: Int): List<Track> {
        val stats = db.history().trackStats(now - 3 * monthMs).filter { it.skips * 2 < it.plays }.sortedByDescending { it.plays }
        val ids = (stats.map { it.trackId } + db.library().allFavoriteIds()).distinct()
        return db.tracks().getAll(ids.take(40)).map { it.toModel() }
            .sortedBy { ids.indexOf(it.id.value) }
            .distinctBy { TextMatching.normalize(it.primaryArtist.orEmpty()) }
            .take(max)
    }

    suspend fun load(): OnlineHome = coroutineScope {
        if (!catalog.hasOnlineCatalog()) return@coroutineScope OnlineHome()
        val now = System.currentTimeMillis()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val discovery = settings.current().discoveryMode
        val profile = profile(now)
        val seeds = seeds(now, 4)
        val failures = mutableListOf<Throwable>()
        fun <T> safe(name: String, r: Result<T>, default: T): T = r.getOrElse {
            diagnostics.w("home", "$name failed: ${it::class.simpleName}: ${it.message}")
            failures += it; default
        }

        val relatedDeferred = seeds.map { seed -> async { seed to runCatching { catalog.related(seed.id, 25) } } }
        val trendingD = async { runCatching { catalog.trending() } }
        val newReleasesD = async { runCatching { catalog.searchItems("new music $year", SearchFilter.PLAYLISTS) } }
        val albumsD = async {
            runCatching {
                val artists = seeds.mapNotNull { it.primaryArtist }.take(3)
                artists.map { a -> async { runCatching { catalog.searchItems(a, SearchFilter.ALBUMS) }.getOrDefault(emptyList()) } }
                    .awaitAll().flatten().filterIsInstance<MediaItemSummary.AlbumItem>().map { it.album }
            }
        }
        val related = relatedDeferred.awaitAll().map { (seed, r) -> seed to safe("related", r, emptyList()) }
        val trending = safe("trending", trendingD.await(), emptyList())

        val allCandidates = related.flatMap { it.second } + trending
        val seedIds = seeds.map { it.id.value }.toSet()
        val quickPicks = engine.recommend(allCandidates, profile, now, year, discovery, limit = 20, excludeIds = seedIds)
        val discover = engine.recommend(
            allCandidates.filter { profile.tracks[it.id.value] == null && engine.artistKey(it) !in profile.artists.keys },
            profile, now, year, discovery = true, limit = 15, familiarShare = 0.0,
            excludeIds = quickPicks.map { it.id.value }.toSet(),
        )
        val mixes = related.filter { it.second.size >= 5 }.map { (seed, tracks) ->
            Mix(
                title = seed.primaryArtist ?: seed.title,
                seedArtist = seed.primaryArtist.orEmpty(),
                artworkUrl = seed.artworkUrl,
                tracks = listOf(seed) + engine.recommend(tracks, profile, now, year, discovery, limit = 30, excludeIds = setOf(seed.id.value)),
            )
        }
        val suggestedArtists = allCandidates
            .mapNotNull { t -> t.artists.firstOrNull()?.let { a -> a.id?.let { Artist(MediaId(it), a.name, null) } } }
            .filter { TextMatching.normalize(it.name) !in profile.favoriteArtistKeys }
            .distinctBy { it.id }.take(10)

        OnlineHome(
            quickPicks = quickPicks,
            mixes = mixes,
            recommendedAlbums = safe("albums", albumsD.await(), emptyList()).distinctBy { it.id }.take(12),
            trending = trending.take(20),
            newReleases = safe("newReleases", newReleasesD.await(), emptyList())
                .filterIsInstance<MediaItemSummary.PlaylistItem>().map { it.playlist }.take(10),
            discover = discover,
            suggestedArtists = suggestedArtists,
            failures = failures,
        )
    }
}
