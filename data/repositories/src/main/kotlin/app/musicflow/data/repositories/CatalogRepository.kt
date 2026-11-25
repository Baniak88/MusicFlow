package app.musicflow.data.repositories

import app.musicflow.core.model.ArtistPage
import app.musicflow.core.model.CollectionPage
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.tracks
import app.musicflow.data.providers.registry.ProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** Browsable remote content: albums, artists, public playlists, related tracks. */
@Singleton
class CatalogRepository @Inject constructor(
    private val registry: ProviderRegistry,
    private val library: LibraryRepository,
) {
    suspend fun collection(id: MediaId, pageToken: String? = null): CollectionPage = registry.callFor(id, "collection") { p ->
        (p.playlists?.playlist(id, pageToken) ?: p.metadata?.album(id, pageToken) ?: throw ProviderError.NotSupported())
    }

    suspend fun artist(id: MediaId): ArtistPage = registry.callFor(id, "artist") { p ->
        p.metadata?.artist(id) ?: throw ProviderError.NotSupported()
    }

    suspend fun related(seed: MediaId, limit: Int = 25): List<Track> = registry.callFor(seed, "related") { p ->
        p.recommendations?.related(seed, limit) ?: emptyList()
    }

    suspend fun trending(): List<Track> {
        val p = registry.primaryCatalog() ?: return emptyList()
        return registry.call(p, "trending", policy = app.musicflow.core.common.RetryPolicy.None) { it.recommendations?.trending() ?: emptyList() }
    }

    suspend fun searchItems(query: String, filter: SearchFilter): List<MediaItemSummary> {
        val p = registry.primaryCatalog()
            ?: registry.enabled().firstOrNull { it.search != null } // offline: on-device music
            ?: throw ProviderError.NotSupported("No search provider enabled")
        return registry.call(p, "searchItems") { it.search!!.search(query, filter).items }
    }

    suspend fun searchTracks(query: String): List<Track> = searchItems(query, SearchFilter.SONGS).tracks()

    /** Full metadata for a track, preferring the local cache. */
    suspend fun track(id: MediaId): Track = library.cachedTrack(id)
        ?: registry.callFor(id, "track") { p -> p.metadata?.track(id) ?: throw ProviderError.NotSupported() }

    fun hasOnlineCatalog(): Boolean = registry.primaryCatalog() != null

    private inline fun <reified T : app.musicflow.data.providers.api.MusicProvider> enabled(): T? =
        registry.enabled().filterIsInstance<T>().firstOrNull()

    /** Internet radio stations (Radio Browser): popular in [country] (ISO code) or worldwide, or by genre tag. */
    suspend fun radioStations(country: String? = null, tag: String? = null, worldwide: Boolean = false): List<Track> {
        val radio = enabled<app.musicflow.data.providers.radio.RadioBrowserProvider>() ?: throw ProviderError.NotSupported("Radio Browser is turned off")
        return registry.call(radio, "stations") { p ->
            val r = p as app.musicflow.data.providers.radio.RadioBrowserProvider
            when {
                tag != null -> r.byTag(tag)
                worldwide -> r.top(country = null)
                else -> r.top(country = country ?: java.util.Locale.getDefault().country.takeIf { it.length == 2 })
            }
        }
    }

    /**
     * MusicBrainz facts for an artist page from another source (matched by exact name only).
     * Null when MusicBrainz is off, has no confident match, or the page is MusicBrainz's own.
     */
    suspend fun artistFacts(artist: app.musicflow.core.model.Artist): app.musicflow.data.providers.musicbrainz.ArtistFacts? {
        if (artist.id.providerId == app.musicflow.data.providers.musicbrainz.MbIds.ARTIST) return null
        val mb = enabled<app.musicflow.data.providers.musicbrainz.MusicBrainzProvider>() ?: return null
        return runCatching {
            registry.call(mb, "facts", policy = app.musicflow.core.common.RetryPolicy.None) { (it as app.musicflow.data.providers.musicbrainz.MusicBrainzProvider).factsFor(artist.name) }
        }.getOrNull()
    }
}
