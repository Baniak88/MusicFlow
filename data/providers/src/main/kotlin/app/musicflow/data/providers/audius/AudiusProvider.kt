package app.musicflow.data.providers.audius

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.defaultClassify
import app.musicflow.core.model.Album
import app.musicflow.core.model.Artist
import app.musicflow.core.model.ArtistPage
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.CollectionPage
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.MetadataProvider
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.SearchProvider
import app.musicflow.data.providers.api.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * [Audius](https://audius.co): an open music platform where artists publish their own music.
 * Uses its public REST API (`/v1`, no account, no DRM). Every request names the app
 * (`app_name`), as Audius asks third-party apps to do. Streams are the artist-uploaded audio
 * served by Audius' content nodes (`/v1/tracks/<id>/stream` redirects there).
 */
class AudiusProvider(
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val appName: String = "MusicFlow",
    private val base: HttpUrl = "https://api.audius.co/v1/".toHttpUrl(),
) : MusicProvider, SearchProvider, MetadataProvider, StreamResolver {

    override val id = AudiusIds.TRACK
    override val displayName = "Audius"
    override val isExperimental = false
    override val isSearchSource = true
    override val ownedPrefixes = setOf(AudiusIds.TRACK, AudiusIds.USER, AudiusIds.PLAYLIST)

    override val search: SearchProvider get() = this
    override val metadata: MetadataProvider get() = this
    override val streams: StreamResolver get() = this

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("app_name", appName)
        }.build()
        val text = try {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                when {
                    r.code == 429 -> throw ProviderError.RateLimited("Audius rate limit")
                    r.code == 404 -> throw ProviderError.Unavailable("Not found on Audius")
                    !r.isSuccessful -> throw IOException("Audius HTTP ${r.code}")
                }
                r.body?.string().orEmpty()
            }
        } catch (e: ProviderError) {
            throw e
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw defaultClassify(t)
        }
        AudiusParser.data(text)
    }

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        val q = query.trim()
        val params = mapOf("query" to q)
        val items: List<MediaItemSummary> = when (filter) {
            SearchFilter.SONGS, SearchFilter.ALL ->
                AudiusParser.tracks(get("tracks/search", params)).map { MediaItemSummary.TrackItem(it) }
            SearchFilter.ARTISTS -> AudiusParser.users(get("users/search", params)).map { MediaItemSummary.ArtistItem(it) }
            SearchFilter.ALBUMS, SearchFilter.PLAYLISTS ->
                AudiusParser.playlists(get("playlists/search", params)).map { MediaItemSummary.AlbumItem(it) }
        }
        return SearchPage(q, filter, items, null)
    }

    override suspend fun suggestions(query: String): List<String> = emptyList()

    override suspend fun track(id: MediaId): Track =
        AudiusParser.track(get("tracks/${id.key}") as? JsonObject ?: throw ProviderError.Unavailable("Not found on Audius"))
            ?: throw ProviderError.Unavailable("This Audius track cannot be streamed")

    override suspend fun album(id: MediaId, pageToken: String?): CollectionPage {
        val info = (get("playlists/${id.key}") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ProviderError.Unavailable("Playlist not found on Audius")
        val list = AudiusParser.playlist(info) ?: throw ProviderError.Unavailable("Playlist not found on Audius")
        val tracks = AudiusParser.tracks(get("playlists/${id.key}/tracks"))
        return CollectionPage(title = list.title, subtitle = list.artistLine.ifEmpty { null }, artworkUrl = list.artworkUrl, tracks = tracks)
    }

    override suspend fun artist(id: MediaId): ArtistPage {
        val user = AudiusParser.user(get("users/${id.key}") as? JsonObject ?: throw ProviderError.Unavailable("Artist not found on Audius"))
            ?: throw ProviderError.Unavailable("Artist not found on Audius")
        val tracks = AudiusParser.tracks(get("users/${id.key}/tracks", mapOf("sort" to "plays", "limit" to "50")))
        return ArtistPage(artist = user, topTracks = tracks, albums = emptyList(), singles = emptyList())
    }

    override fun canResolve(id: MediaId) = id.providerId == AudiusIds.TRACK

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream {
        // The stream endpoint redirects to a content node; the player follows the redirect.
        val url = base.newBuilder().addPathSegments("tracks/${id.key}/stream").addQueryParameter("app_name", appName).build().toString()
        return ResolvedStream(
            mediaId = id,
            url = url,
            mimeType = "audio/mpeg",
            codec = null, // the API does not state the codec/bitrate of the uploaded file
            bitrateKbps = null,
            sampleRateHz = null,
            expiresAtMillis = null,
            isLossless = false,
            cacheKey = "audius:${id.key}",
        )
    }
}

/** MediaIds: `au:<track id>`, `aua:<user id>`, `aul:<playlist id>`. */
object AudiusIds {
    const val TRACK = "au"
    const val USER = "aua"
    const val PLAYLIST = "aul"
}

/** Pure parsing of Audius API responses (`{"data": …}`), unit-tested. */
object AudiusParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun data(text: String): JsonElement = try {
        json.parseToJsonElement(text).jsonObject["data"] ?: JsonArray(emptyList())
    } catch (e: Exception) {
        throw ProviderError.Parsing("Audius response format changed: ${e.message}", e)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    /** Largest artwork the API offers (1000×1000, else 480×480, else 150×150). */
    private fun art(o: JsonObject?): String? = o?.let { a -> listOf("1000x1000", "480x480", "150x150").firstNotNullOfOrNull { a.str(it) } }
        ?.takeIf { it.startsWith("https://") }

    private fun list(e: JsonElement) = (e as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun tracks(e: JsonElement): List<Track> = list(e).mapNotNull(::track)

    /** A playable track, or null (not streamable / deleted / no title). */
    fun track(o: JsonObject): Track? {
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        if ((o["is_streamable"] as? JsonPrimitive)?.booleanOrNull == false) return null
        if ((o["is_delete"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val user = o.obj("user")
        return Track(
            id = MediaId.of(AudiusIds.TRACK, id),
            title = title,
            artists = listOfNotNull(user?.str("name")?.let { ArtistRef(user.str("id")?.let { u -> MediaId.of(AudiusIds.USER, u).value }, it) }),
            durationMs = (o["duration"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.times(1000L),
            artworkUrl = art(o.obj("artwork")),
            genre = o.str("genre"),
        )
    }

    fun users(e: JsonElement): List<Artist> = list(e).mapNotNull(::user)

    fun user(o: JsonObject): Artist? {
        val id = o.str("id") ?: return null
        return Artist(
            id = MediaId.of(AudiusIds.USER, id),
            name = o.str("name") ?: o.str("handle") ?: return null,
            artworkUrl = art(o.obj("profile_picture")),
            subscriberCount = (o["follower_count"] as? JsonPrimitive)?.intOrNull?.toLong()?.takeIf { it > 0 },
        )
    }

    fun playlists(e: JsonElement): List<Album> = list(e).mapNotNull(::playlist)

    fun playlist(o: JsonObject): Album? {
        val id = o.str("id") ?: return null
        val user = o.obj("user")
        return Album(
            id = MediaId.of(AudiusIds.PLAYLIST, id),
            title = o.str("playlist_name") ?: return null,
            artists = listOfNotNull(user?.str("name")?.let { ArtistRef(user.str("id")?.let { u -> MediaId.of(AudiusIds.USER, u).value }, it) }),
            artworkUrl = art(o.obj("artwork")),
            trackCount = (o["track_count"] as? JsonPrimitive)?.intOrNull,
        )
    }
}
