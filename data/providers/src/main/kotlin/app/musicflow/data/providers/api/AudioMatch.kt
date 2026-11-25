package app.musicflow.data.providers.api

import app.musicflow.core.common.TextMatching
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.Track

/**
 * Plays a track through another provider when its own source has no playable audio
 * (MusicBrainz has none; some SoundCloud tracks are DRM-encrypted). Implemented with YouTube Music.
 */
interface AudioMatcher {
    val name: String
    suspend fun candidates(query: String): List<Track>
    suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream
}

object AudioMatch {
    /** First candidate that is the same recording: title, artist and duration (±7 s) agree. */
    fun best(t: Track, candidates: List<Track>): Track? = candidates.firstOrNull { c ->
        val titles = listOf(c.title, c.title.substringAfter(" - ", c.title))
        titles.any { title ->
            TextMatching.isReliableMatch(t.title, t.primaryArtist, t.durationMs, title, c.artistLine.ifEmpty { null }, c.durationMs, durationToleranceMs = 7_000)
        }
    }
}
