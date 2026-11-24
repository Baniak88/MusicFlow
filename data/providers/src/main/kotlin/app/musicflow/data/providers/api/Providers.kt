package app.musicflow.data.providers.api

import app.musicflow.core.model.ArtistPage
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.CollectionPage
import app.musicflow.core.model.Lyrics
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.core.model.Track

/*
 * Provider contracts. Every method may throw [app.musicflow.core.model.ProviderError];
 * implementations must classify their own failures so callers can decide on retries.
 */

interface SearchProvider {
    suspend fun search(query: String, filter: SearchFilter, pageToken: String? = null): SearchPage
    suspend fun suggestions(query: String): List<String>
}

interface MetadataProvider {
    suspend fun track(id: MediaId): Track
    suspend fun album(id: MediaId, pageToken: String? = null): CollectionPage
    suspend fun artist(id: MediaId): ArtistPage
}

interface StreamResolver {
    fun canResolve(id: MediaId): Boolean
    suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream
}

interface RecommendationProvider {
    /** Tracks related to [seed], suitable for automatic queue continuation ("radio"). */
    suspend fun related(seed: MediaId, limit: Int = 25): List<Track>

    /** Currently trending music as reported by the provider, if it has such a feed. */
    suspend fun trending(): List<Track>
}

interface PlaylistProvider {
    suspend fun playlist(id: MediaId, pageToken: String? = null): CollectionPage

    /** Converts a public playlist URL into a [MediaId] this provider can load, or `null`. */
    fun parsePlaylistUrl(url: String): MediaId?

    /** Converts a public track URL into a [MediaId], or `null`. */
    fun parseTrackUrl(url: String): MediaId? = null
}

interface LyricsProvider {
    val name: String
    suspend fun lyrics(track: Track): Lyrics?
}

enum class Capability { SEARCH, METADATA, STREAMS, RECOMMENDATIONS, PLAYLISTS, TRENDING }

/**
 * A music source. Capabilities are optional: a provider exposes only what it really supports.
 */
interface MusicProvider {
    /** Stable id, also the prefix of [MediaId]s it owns. */
    val id: String
    val displayName: String

    /** Experimental providers depend on undocumented third-party endpoints and may break. */
    val isExperimental: Boolean

    /** Additional [MediaId.providerId] prefixes this provider owns (e.g. playlists, channels). */
    val ownedPrefixes: Set<String> get() = setOf(id)

    val search: SearchProvider? get() = null
    val metadata: MetadataProvider? get() = null
    val streams: StreamResolver? get() = null
    val recommendations: RecommendationProvider? get() = null
    val playlists: PlaylistProvider? get() = null

    val capabilities: Set<Capability>
        get() = buildSet {
            if (search != null) add(Capability.SEARCH)
            if (metadata != null) add(Capability.METADATA)
            if (streams != null) add(Capability.STREAMS)
            if (recommendations != null) { add(Capability.RECOMMENDATIONS); add(Capability.TRENDING) }
            if (playlists != null) add(Capability.PLAYLISTS)
        }

    fun owns(id: MediaId): Boolean = id.providerId in ownedPrefixes
}

/** Convenience helpers on mixed results. */
fun List<MediaItemSummary>.tracks(): List<Track> = filterIsInstance<MediaItemSummary.TrackItem>().map { it.track }
