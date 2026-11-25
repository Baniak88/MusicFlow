package app.musicflow.data.providers.radio

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.defaultClassify
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A station as returned by the Radio Browser API (only the fields MusicFlow uses). */
@Serializable
data class RadioStation(
    @SerialName("stationuuid") val uuid: String,
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    @SerialName("lastcheckok") val lastCheckOk: Int = 1,
    @SerialName("clickcount") val clickCount: Int = 0,
)

/**
 * Internet radio from the community directory [Radio Browser](https://www.radio-browser.info)
 * (open data, public API, no key). Stations are live streams: they have no duration, are never
 * cached to disk and never crossfaded.
 *
 * Only HTTPS streams are offered (MusicFlow refuses cleartext traffic, see PRIVACY.md); the
 * directory is queried with `is_https=true` and `hidebroken=true`.
 * Following the API's guidelines, playing a station is reported to `/json/url/<uuid>` (station
 * id only), which keeps the directory's popularity ranking meaningful.
 */
class RadioBrowserProvider(
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val userAgent: String = "MusicFlow/1.0",
    /** Directory mirrors (base URLs), tried in order: the API's `*.api.radio-browser.info` servers. */
    servers: List<String> = DEFAULT_SERVERS,
    private val countryCode: () -> String? = { java.util.Locale.getDefault().country.takeIf { it.length == 2 } },
) : MusicProvider, SearchProvider, MetadataProvider, StreamResolver {

    override val id = RadioIds.STATION
    override val displayName = "Radio Browser"
    override val isExperimental = false
    override val isSearchSource = true

    override val search: SearchProvider get() = this
    override val metadata: MetadataProvider get() = this
    override val streams: StreamResolver get() = this

    private val bases: List<HttpUrl> = servers.map { it.toHttpUrl() }
    @Volatile private var preferred = 0
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (attempt in bases.indices) {
            val i = (preferred + attempt) % bases.size
            val url = bases[i].newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            try {
                val text = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { r ->
                    if (r.code == 429) throw ProviderError.RateLimited("Radio Browser rate limit")
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
                preferred = i
                return@withContext text
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                diagnostics.d("radio", "${bases[i].host} failed (${t::class.simpleName}); trying next mirror")
            }
        }
        throw (last as? ProviderError) ?: defaultClassify(last ?: IOException("No Radio Browser server reachable"))
    }

    private fun parse(text: String): List<RadioStation> = try {
        json.decodeFromString(ListSerializer(RadioStation.serializer()), text)
    } catch (e: Exception) {
        throw ProviderError.Parsing("Radio Browser response format changed: ${e.message}", e)
    }

    private suspend fun stations(path: String, query: Map<String, String>): List<RadioStation> =
        parse(get(path, query)).filter(::playable)

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        // Stations are neither artists nor albums: other filters honestly return nothing.
        if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALBUMS) return SearchPage(query, filter, emptyList(), null)
        val offset = pageToken?.toIntOrNull() ?: 0
        val base = mapOf("limit" to PAGE.toString(), "offset" to offset.toString(), "hidebroken" to "true", "is_https" to "true", "order" to "clickcount", "reverse" to "true")
        val byName = stations("json/stations/search", base + ("name" to query.trim()))
        // Genres ("rock", "jazz") are tags, not names: add tag matches on the first page.
        val byTag = if (offset == 0 && byName.size < PAGE) runCatching { stations("json/stations/search", base + ("tag" to query.trim().lowercase())) }.getOrDefault(emptyList()) else emptyList()
        val all = (byName + byTag).distinctBy { it.uuid }
        val next = if (byName.size >= PAGE) (offset + PAGE).toString() else null
        return SearchPage(query, filter, all.map { MediaItemSummary.TrackItem(RadioIds.toTrack(it)) }, next)
    }

    override suspend fun suggestions(query: String): List<String> = emptyList()

    /** Most popular stations in [country] (ISO code), or worldwide when null. */
    suspend fun top(country: String? = countryCode(), limit: Int = 40): List<Track> {
        val q = mutableMapOf("limit" to limit.toString(), "hidebroken" to "true", "is_https" to "true", "order" to "clickcount", "reverse" to "true")
        country?.let { q["countrycode"] = it }
        return stations("json/stations/search", q).map(RadioIds::toTrack)
    }

    /** Most popular stations for a genre tag (e.g. "rock", "jazz"). */
    suspend fun byTag(tag: String, limit: Int = 40): List<Track> =
        stations("json/stations/search", mapOf("tag" to tag, "limit" to limit.toString(), "hidebroken" to "true", "is_https" to "true", "order" to "clickcount", "reverse" to "true"))
            .map(RadioIds::toTrack)

    // ---------------------------------------------------------------- metadata

    private suspend fun station(id: MediaId): RadioStation =
        parse(get("json/stations/byuuid/${RadioIds.uuid(id)}")).firstOrNull()
            ?: throw ProviderError.Unavailable("This station is no longer listed in Radio Browser")

    override suspend fun track(id: MediaId): Track = RadioIds.toTrack(station(id))
    override suspend fun album(id: MediaId, pageToken: String?): CollectionPage = throw ProviderError.NotSupported("Stations have no albums")
    override suspend fun artist(id: MediaId): ArtistPage = throw ProviderError.NotSupported("Stations have no artists")

    // ---------------------------------------------------------------- streams

    override fun canResolve(id: MediaId) = id.providerId == RadioIds.STATION

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream {
        val s = station(id)
        if (s.lastCheckOk == 0) diagnostics.w("radio", "Station ${s.name} failed Radio Browser's last check; trying anyway")
        // Counts the click (station id only) and returns the current stream URL.
        val counted = runCatching {
            json.decodeFromString(ClickResult.serializer(), get("json/url/${s.uuid}")).url.takeIf { it.isNotBlank() }
        }.getOrNull()
        val url = listOf(counted, s.urlResolved, s.url).firstOrNull { it != null && it.startsWith("https://", ignoreCase = true) }
            ?: throw ProviderError.NotSupported("This station only streams over unencrypted HTTP, which MusicFlow does not allow")
        return ResolvedStream(
            mediaId = id,
            url = url,
            mimeType = if (s.hls == 1) "application/x-mpegURL" else null,
            codec = s.codec.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.lowercase(),
            bitrateKbps = s.bitrate.takeIf { it > 0 },
            sampleRateHz = null,
            expiresAtMillis = null,
            isLossless = false, // shown only if the source said so; radio streams are lossy
            requestHeaders = mapOf("User-Agent" to userAgent), // ExoPlayer requests ICY metadata itself
            cacheKey = "radio:${s.uuid}",
            isHls = s.hls == 1,
            isLive = true,
        )
    }

    @Serializable private data class ClickResult(val ok: Boolean = false, val url: String = "")

    companion object {
        const val PAGE = 40
        val DEFAULT_SERVERS = listOf("de1", "fi1", "de2", "nl1").map { "https://$it.api.radio-browser.info/" }

        /** HTTPS stream with a name; Radio Browser also lists HTTP-only stations which MusicFlow can't play. */
        fun playable(s: RadioStation) = s.name.isNotBlank() &&
            listOf(s.urlResolved, s.url).any { it.startsWith("https://", ignoreCase = true) }
    }
}

/**
 * MediaIds for stations: `radio:<uuid>`, or `radio:hls/<uuid>` for HLS stations — the stream
 * type must be known when queueing (it selects the media source), before the URL is resolved.
 */
object RadioIds {
    const val STATION = "radio"

    fun of(s: RadioStation) = MediaId.of(STATION, if (s.hls == 1) "hls/${s.uuid}" else s.uuid)
    fun uuid(id: MediaId) = id.key.removePrefix("hls/")
    fun isHls(id: MediaId) = id.providerId == STATION && id.key.startsWith("hls/")

    /** Real directory data only: name, country, genre tags, codec/bitrate as listed. */
    fun toTrack(s: RadioStation): Track {
        val tags = s.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        val line = listOfNotNull(
            s.country.takeIf { it.isNotBlank() },
            tags.joinToString(", ").takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { "Radio" }
        return Track(
            id = of(s),
            title = s.name.trim(),
            artists = listOf(ArtistRef(null, line)),
            album = null,
            durationMs = null, // live
            artworkUrl = s.favicon.takeIf { it.startsWith("https://", ignoreCase = true) },
            genre = tags.firstOrNull(),
        )
    }
}
