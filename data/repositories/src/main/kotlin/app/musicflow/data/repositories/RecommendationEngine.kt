package app.musicflow.data.repositories

import app.musicflow.core.common.TextMatching
import app.musicflow.core.model.Track
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Local, explainable recommendation scoring — no network, no paid AI API.
 *
 * Signals: listening frequency, completion vs. skip rates, favourites, favourite artists,
 * recency (to avoid replaying what you just heard), release year (discovery mode).
 * The final list is diversified: at most [maxPerArtist] tracks per artist, and a configurable
 * ratio between familiar tracks and discoveries.
 */
class RecommendationEngine(
    private val maxPerArtist: Int = 2,
) {
    data class TrackSignal(val plays: Int, val completions: Int, val skips: Int, val lastPlayedAt: Long)
    data class ArtistSignal(val plays: Int, val skips: Int)

    data class Profile(
        val tracks: Map<String, TrackSignal>,
        val artists: Map<String, ArtistSignal>,
        val favoriteTrackIds: Set<String>,
        val favoriteArtistKeys: Set<String>,
        val genres: Map<String, Int> = emptyMap(),
    ) {
        val isEmpty get() = tracks.isEmpty() && favoriteTrackIds.isEmpty() && favoriteArtistKeys.isEmpty()
    }

    data class Scored(val track: Track, val score: Double, val familiar: Boolean)

    fun artistKey(t: Track) = TextMatching.normalize(t.primaryArtist.orEmpty())

    fun score(t: Track, p: Profile, now: Long, currentYear: Int, discovery: Boolean): Scored {
        val ts = p.tracks[t.id.value]
        val aKey = artistKey(t)
        val asig = p.artists[aKey]
        var s = 0.0

        // Artist affinity: log-scaled plays, penalised by skip ratio.
        if (asig != null && asig.plays > 0) {
            val skipRatio = asig.skips.toDouble() / asig.plays
            s += ln(1.0 + asig.plays) * (1.0 - 1.5 * skipRatio)
        }
        if (aKey.isNotEmpty() && aKey in p.favoriteArtistKeys) s += 2.0
        if (t.id.value in p.favoriteTrackIds) s += 1.5
        t.genre?.let { g -> p.genres[g.lowercase()]?.let { s += min(1.5, ln(1.0 + it) / 2) } }

        val familiar = ts != null || t.id.value in p.favoriteTrackIds
        if (ts != null && ts.plays > 0) {
            val completion = ts.completions.toDouble() / ts.plays
            val skip = ts.skips.toDouble() / ts.plays
            s += completion * 1.5 - skip * 3.0
            // Recently played → push back so it's not repeated (half-life ≈ 1.5 days).
            val hours = max(0.0, (now - ts.lastPlayedAt) / 3_600_000.0)
            s -= 2.5 * exp(-hours / 36.0)
        } else {
            // Novelty bonus; stronger in discovery mode.
            s += if (discovery) 1.6 else 0.6
        }
        if (discovery) {
            t.releaseYear?.let { y -> if (y >= currentYear - 1) s += 1.2 else if (y >= currentYear - 3) s += 0.5 }
        }
        return Scored(t, s, familiar)
    }

    /**
     * Ranks [candidates] and returns up to [limit] diversified tracks.
     * @param familiarShare fraction of familiar tracks (the rest are discoveries).
     */
    fun recommend(
        candidates: List<Track>,
        profile: Profile,
        now: Long,
        currentYear: Int,
        discovery: Boolean,
        limit: Int = 20,
        familiarShare: Double = if (discovery) 0.25 else 0.5,
        excludeIds: Set<String> = emptySet(),
    ): List<Track> {
        val unique = candidates
            .filter { it.id.value !in excludeIds }
            .distinctBy { TextMatching.trackKey(it.title, it.primaryArtist) }
        val scored = unique.map { score(it, profile, now, currentYear, discovery) }.sortedByDescending { it.score }
        val familiarQuota = (limit * familiarShare).toInt()
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Scored>(limit)
        var familiarCount = 0

        fun tryAdd(c: Scored, enforceQuota: Boolean): Boolean {
            val key = artistKey(c.track)
            if ((perArtist[key] ?: 0) >= maxPerArtist && key.isNotEmpty()) return false
            if (enforceQuota && c.familiar && familiarCount >= familiarQuota) return false
            out += c; perArtist[key] = (perArtist[key] ?: 0) + 1
            if (c.familiar) familiarCount++
            return true
        }
        for (c in scored) { if (out.size >= limit) break; tryAdd(c, enforceQuota = true) }
        // Fill remaining slots without the familiar quota (still respecting artist cap).
        if (out.size < limit) for (c in scored) { if (out.size >= limit) break; if (c !in out) tryAdd(c, enforceQuota = false) }
        return interleave(out).map { it.track }
    }

    /** Avoids two tracks of the same artist back-to-back where possible. */
    private fun interleave(list: List<Scored>): List<Scored> {
        val pool = list.toMutableList()
        val out = ArrayList<Scored>(list.size)
        while (pool.isNotEmpty()) {
            val lastKey = out.lastOrNull()?.let { artistKey(it.track) }
            val idx = pool.indexOfFirst { artistKey(it.track) != lastKey }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(idx)
        }
        return out
    }
}
