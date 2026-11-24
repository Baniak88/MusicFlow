package app.musicflow.data.repositories

import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecommendationEngineTest {
    private val engine = RecommendationEngine(maxPerArtist = 2)
    private val now = 1_000_000_000_000L
    private fun t(id: String, artist: String, year: Int? = null) = Track(MediaId("yt:$id"), "Song $id", listOf(ArtistRef(null, artist)), releaseYear = year)
    private val empty = RecommendationEngine.Profile(emptyMap(), emptyMap(), emptySet(), emptySet())

    @Test fun capsTracksPerArtist() {
        val candidates = (1..10).map { t("a$it", "Same Artist") } + (1..3).map { t("b$it", "Other $it") }
        val out = engine.recommend(candidates, empty, now, 2026, discovery = false, limit = 10)
        assertThat(out.count { it.primaryArtist == "Same Artist" }).isEqualTo(2)
    }

    @Test fun avoidsBackToBackSameArtist() {
        val c = listOf(t("1", "A"), t("2", "A"), t("3", "B"), t("4", "B"), t("5", "C"))
        val out = engine.recommend(c, empty, now, 2026, false, limit = 5)
        out.zipWithNext().forEach { (x, y) -> assertThat(x.primaryArtist).isNotEqualTo(y.primaryArtist) }
    }

    @Test fun favouriteArtistsAndCompletionsRankHigherSkipsLower() {
        val profile = RecommendationEngine.Profile(
            tracks = mapOf(
                "yt:loved" to RecommendationEngine.TrackSignal(plays = 10, completions = 10, skips = 0, lastPlayedAt = now - 30L * 24 * 3600_000),
                "yt:skipped" to RecommendationEngine.TrackSignal(plays = 5, completions = 0, skips = 5, lastPlayedAt = now - 30L * 24 * 3600_000),
            ),
            artists = mapOf("fav" to RecommendationEngine.ArtistSignal(20, 0)),
            favoriteTrackIds = emptySet(),
            favoriteArtistKeys = setOf("fav"),
        )
        val loved = engine.score(t("loved", "X"), profile, now, 2026, false).score
        val skipped = engine.score(t("skipped", "Y"), profile, now, 2026, false).score
        val favArtist = engine.score(t("new", "Fav"), profile, now, 2026, false).score
        val unknown = engine.score(t("new2", "Nobody"), profile, now, 2026, false).score
        assertThat(loved).isGreaterThan(skipped)
        assertThat(favArtist).isGreaterThan(unknown)
    }

    @Test fun recentlyPlayedIsPushedBack() {
        val recent = RecommendationEngine.TrackSignal(5, 5, 0, lastPlayedAt = now - 60_000)
        val old = RecommendationEngine.TrackSignal(5, 5, 0, lastPlayedAt = now - 20L * 24 * 3600_000)
        val p1 = empty.copy(tracks = mapOf("yt:x" to recent))
        val p2 = empty.copy(tracks = mapOf("yt:x" to old))
        assertThat(engine.score(t("x", "A"), p1, now, 2026, false).score).isLessThan(engine.score(t("x", "A"), p2, now, 2026, false).score)
    }

    @Test fun discoveryModeFavoursNewReleasesAndUnheardTracks() {
        val newRelease = t("n", "A", year = 2026)
        val oldie = t("o", "B", year = 1999)
        assertThat(engine.score(newRelease, empty, now, 2026, true).score).isGreaterThan(engine.score(oldie, empty, now, 2026, true).score)
        assertThat(engine.score(newRelease, empty, now, 2026, false).score).isEqualTo(engine.score(oldie, empty, now, 2026, false).score)
    }

    @Test fun balancesFamiliarAndNew() {
        val familiar = (1..10).map { t("f$it", "F$it") }
        val fresh = (1..10).map { t("n$it", "N$it") }
        val profile = empty.copy(tracks = familiar.associate { it.id.value to RecommendationEngine.TrackSignal(20, 20, 0, now - 40L * 24 * 3600_000) })
        val out = engine.recommend(familiar + fresh, profile, now, 2026, discovery = false, limit = 10, familiarShare = 0.5)
        assertThat(out.count { it.id.value.startsWith("yt:f") }).isEqualTo(5)
        assertThat(out.count { it.id.value.startsWith("yt:n") }).isEqualTo(5)
    }

    @Test fun excludesAndDeduplicates() {
        val c = listOf(t("1", "A"), Track(MediaId("yt:dup"), "Song 1 (Official Video)", listOf(ArtistRef(null, "A"))), t("2", "B"))
        val out = engine.recommend(c, empty, now, 2026, false, limit = 10, excludeIds = setOf("yt:2"))
        assertThat(out.map { it.id.value }).containsExactly("yt:1")
    }
}
