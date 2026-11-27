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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** Region/city as entered in the directory (e.g. "Kielce", "Świętokrzyskie"). */
    val state: String = "",
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
    /** Open FM, RMF ON and ESKA channels from their own websites (see [RadioNetworks]). */
    private val networks: RadioNetworks? = null,
) : MusicProvider, SearchProvider, MetadataProvider, StreamResolver {

    override val id = RadioIds.STATION
    override val displayName = "Radio Browser"
    override val isExperimental = false
    override val isSearchSource = true

    override val search: SearchProvider get() = this
    override val metadata: MetadataProvider get() = this
    override val streams: StreamResolver get() = this

    private val bases: List<HttpUrl> = servers.map { it.toHttpUrl() }

    /** User setting: also offer stations that stream over plain HTTP (most small broadcasters). */
    @Volatile var allowHttp: Boolean = true
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
        parse(get(path, query + (if (allowHttp) emptyMap() else mapOf("is_https" to "true")))).filter { playable(it, allowHttp) }

    private fun common(limit: Int, offset: Int = 0) = mapOf(
        "limit" to limit.toString(), "offset" to offset.toString(), "hidebroken" to "true", "order" to "clickcount", "reverse" to "true",
    )

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        // Stations are neither artists nor albums: other filters honestly return nothing.
        if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALBUMS) return SearchPage(query, filter, emptyList(), null)
        val offset = pageToken?.toIntOrNull() ?: 0
        val q = query.trim()
        val spaced = RadioSearch.unglue(q) // "danceradio" → "dance radio", "openfm" → "open fm"
        val first = try {
            stations("json/stations/search", common(PAGE, offset) + ("name" to q))
        } catch (e: ProviderError) {
            // Directory unreachable: the networks' own channels are still worth showing.
            val own = if (offset == 0) networks?.let { n -> runCatching { n.search(spaced) }.getOrDefault(emptyList()) }.orEmpty() else emptyList()
            if (own.isEmpty()) throw e
            return SearchPage(query, filter, own.take(40).map { MediaItemSummary.TrackItem(RadioNetworks.toTrack(it)) }, null)
        }
        val byName = first +
            (if (spaced != q && offset == 0) runCatching { stations("json/stations/search", common(PAGE) + ("name" to spaced)) }.getOrDefault(emptyList()) else emptyList())
        if (offset > 0) return SearchPage(query, filter, byName.map { MediaItemSummary.TrackItem(RadioIds.toTrack(it)) }, if (byName.size >= PAGE) (offset + PAGE).toString() else null)
        // Channels of Open FM / RMF ON / ESKA first: they come from the broadcasters themselves.
        val fromNetworks = networks?.let { n -> runCatching { n.search(spaced) }.getOrDefault(emptyList()) }.orEmpty().take(40)
        // "radio fama kielce": the station is "Radio FAMA" with region "Kielce". Search each
        // distinctive word in names and regions, plus the whole query as a genre tag, then rank
        // stations matching the most words first.
        val words = RadioSearch.words(spaced)
        val extra = coroutineScope {
            val calls = buildList {
                add(async { runCatching { stations("json/stations/search", common(PAGE) + ("tag" to q.lowercase())) }.getOrDefault(emptyList()) })
                if (words.size > 1 || byName.size < 5) words.take(3).forEach { w ->
                    add(async { runCatching { stations("json/stations/search", common(PAGE) + ("name" to w)) }.getOrDefault(emptyList()) })
                    add(async { runCatching { stations("json/stations/search", common(PAGE) + ("state" to w)) }.getOrDefault(emptyList()) })
                }
            }
            calls.flatMap { it.await() }
        }
        val ranked = RadioSearch.rank(spaced, RadioSearch.bestVersions((byName + extra).distinctBy { it.uuid }))
        val next = if (byName.size >= PAGE) PAGE.toString() else null
        val networkNames = fromNetworks.map { app.musicflow.core.common.TextMatching.normalize(it.name) }.toSet()
        val items = fromNetworks.map { MediaItemSummary.TrackItem(RadioNetworks.toTrack(it)) } +
            ranked.filter { app.musicflow.core.common.TextMatching.normalize(it.name) !in networkNames }.take(60).map { MediaItemSummary.TrackItem(RadioIds.toTrack(it)) }
        return SearchPage(query, filter, items, next)
    }

    override suspend fun suggestions(query: String): List<String> = emptyList()

    /** Most popular stations in [country] (ISO code), or worldwide when null. */
    suspend fun top(country: String? = countryCode(), limit: Int = 40): List<Track> =
        RadioSearch.bestVersions(stations("json/stations/search", common(limit) + (country?.let { mapOf("countrycode" to it) } ?: emptyMap()))).map(RadioIds::toTrack)

    /** Now-playing of a network station (Open FM, RMF ON, ESKA); null for directory stations (they use ICY). */
    suspend fun nowPlaying(id: MediaId): NowPlaying? {
        val (n, key) = RadioNetworks.parse(id) ?: return null
        val networks = networks ?: return null
        return networks.nowPlaying(networks.station(n, key))
    }

    /** All channels of one network (Open FM, RMF ON, ESKA), in the site's order. */
    suspend fun network(network: RadioNetwork): List<Track> =
        (networks ?: throw ProviderError.NotSupported("Radio networks are not available")).stations(network).map(RadioNetworks::toTrack)

    /** Most popular stations for a genre tag (e.g. "rock", "jazz"). */
    suspend fun byTag(tag: String, limit: Int = 40): List<Track> =
        RadioSearch.bestVersions(stations("json/stations/search", common(limit) + ("tag" to tag))).map(RadioIds::toTrack)

    // ---------------------------------------------------------------- metadata

    private suspend fun station(id: MediaId): RadioStation =
        parse(get("json/stations/byuuid/${RadioIds.uuid(id)}")).firstOrNull()
            ?: throw ProviderError.Unavailable("This station is no longer listed in Radio Browser")

    override suspend fun track(id: MediaId): Track {
        RadioNetworks.parse(id)?.let { (n, key) -> return RadioNetworks.toTrack(requireNetworks().station(n, key)) }
        return RadioIds.toTrack(station(id))
    }

    private fun requireNetworks() = networks ?: throw ProviderError.NotSupported("Radio networks are not available")
    override suspend fun album(id: MediaId, pageToken: String?): CollectionPage = throw ProviderError.NotSupported("Stations have no albums")
    override suspend fun artist(id: MediaId): ArtistPage = throw ProviderError.NotSupported("Stations have no artists")

    // ---------------------------------------------------------------- streams

    override fun canResolve(id: MediaId) = id.providerId == RadioIds.STATION

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream {
        RadioNetworks.parse(id)?.let { (n, key) -> return resolveNetwork(id, requireNetworks().station(n, key)) }
        val s = station(id)
        if (s.lastCheckOk == 0) diagnostics.w("radio", "Station ${s.name} failed Radio Browser's last check; trying anyway")
        // Counts the click (station id only) and returns the current stream URL.
        val counted = runCatching {
            json.decodeFromString(ClickResult.serializer(), get("json/url/${s.uuid}")).url.takeIf { it.isNotBlank() }
        }.getOrNull()
        val candidates = listOf(counted, s.urlResolved, s.url).filterNotNull().filter { it.isNotBlank() }
        val url = candidates.firstOrNull { it.startsWith("https://", ignoreCase = true) }
            ?: candidates.firstOrNull { allowHttp && it.startsWith("http://", ignoreCase = true) }
            ?: throw ProviderError.NotSupported("This station only streams over unencrypted HTTP; allow it in Settings → Providers → Radio")
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
            allowCleartext = url.startsWith("http://", ignoreCase = true),
        )
    }

    private suspend fun resolveNetwork(id: MediaId, s: NetworkStation): ResolvedStream {
        val url = requireNetworks().streamUrls(s).first()
        return ResolvedStream(
            mediaId = id,
            url = url,
            mimeType = if (s.isHls) "application/x-mpegURL" else null,
            codec = null, // not stated by the site; the player shows what the stream reports
            bitrateKbps = null,
            sampleRateHz = null,
            expiresAtMillis = null,
            isLossless = false,
            // Open FM's CDN serves only its web player: same browser identity as the site.
            requestHeaders = if (s.network == RadioNetwork.OPEN_FM) mapOf("User-Agent" to RadioNetworks.BROWSER_UA) else mapOf("User-Agent" to userAgent),
            cacheKey = "radio:${s.network.key}:${s.id}",
            isHls = s.isHls,
            isLive = true,
            allowCleartext = false,
        )
    }

    @Serializable private data class ClickResult(val ok: Boolean = false, val url: String = "")

    companion object {
        const val PAGE = 40
        val DEFAULT_SERVERS = listOf("de1", "fi1", "de2", "nl1").map { "https://$it.api.radio-browser.info/" }

        /** A named station with an HTTPS stream (or HTTP, when the user allows unencrypted radio). */
        fun playable(s: RadioStation, allowHttp: Boolean = false) = s.name.isNotBlank() &&
            listOf(s.urlResolved, s.url).any { it.startsWith("https://", ignoreCase = true) || (allowHttp && it.startsWith("http://", ignoreCase = true)) }
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
            listOfNotNull(s.state.trim().takeIf { it.isNotBlank() }, s.country.takeIf { it.isNotBlank() }).joinToString(", ").ifEmpty { null },
            tags.joinToString(", ").takeIf { it.isNotBlank() },
            RadioSearch.quality(s),
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

/** Word-based matching for station search (names, regions and tags), pure and unit-tested. */
object RadioSearch {
    /** Words too generic to search on their own. */
    private val generic = setOf("radio", "fm", "am", "stacja", "station", "the", "online", "live", "polskie", "polska", "music", "muzyka")

    fun words(query: String): List<String> = app.musicflow.core.common.TextMatching.normalize(query).split(' ')
        .filter { it.length >= 3 && it !in generic }.distinct().sortedByDescending { it.length }

    private val glue = Regex("""(?i)^(.{3,}?)(radio|fm|music)$|^(radio|open)(.{3,})$""")

    /** Splits words typed together: "danceradio" → "dance radio", "openfm" → "open fm", "radiozet" → "radio zet". */
    fun unglue(query: String): String = query.trim().split(Regex("""\s+""")).joinToString(" ") { w ->
        glue.matchEntire(w)?.groupValues?.let { g -> if (g[1].isNotEmpty()) "${g[1]} ${g[2]}" else "${g[3]} ${g[4]}" } ?: w
    }

    /** "MP3 320 kbps" — only what the directory reports. */
    fun quality(s: RadioStation): String? {
        val codec = s.codec.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) }
        val kbps = s.bitrate.takeIf { it > 0 }?.let { "$it kbps" }
        return listOfNotNull(codec, kbps).joinToString(" ").ifEmpty { null }
    }

    /** Sound quality score from listed data: bitrate, with HE-AAC (AAC+) counted as roughly double. */
    private fun qualityScore(s: RadioStation): Int {
        val b = s.bitrate.takeIf { it > 0 } ?: Regex("""(?i)(?:mp3|aac)[-_]?(\d{2,3})|(\d{3})\.mp3""").find(s.urlResolved.ifBlank { s.url })
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return if (s.codec.contains("+")) b * 2 else b
    }

    /**
     * The directory often lists one station several times (different bitrates, mirrors, HTTP and
     * HTTPS). Keep one entry per name: the best sound quality, HTTPS if equal, then the most popular.
     */
    fun bestVersions(stations: List<RadioStation>): List<RadioStation> {
        val best = stations.groupBy { app.musicflow.core.common.TextMatching.normalize(it.name) }.mapValues { (_, list) ->
            list.maxWithOrNull(compareBy<RadioStation> { qualityScore(it) }.thenBy { if (it.urlResolved.startsWith("https", true)) 1 else 0 }.thenBy { it.clickCount })!!
        }
        val popularity = stations.groupBy { app.musicflow.core.common.TextMatching.normalize(it.name) }.mapValues { (_, l) -> l.sumOf { it.clickCount } }
        // Keep the original order of first appearance; the group's popularity is the sum of its entries.
        return stations.map { app.musicflow.core.common.TextMatching.normalize(it.name) }.distinct()
            .map { key -> best.getValue(key).copy(clickCount = popularity.getValue(key)) }
    }

    /** Stations matching more query words (in name, region, tags or country) first, then popularity. */
    fun rank(query: String, stations: List<RadioStation>): List<RadioStation> {
        val ws = words(query)
        val norm = app.musicflow.core.common.TextMatching.normalize(query)
        // A two-letter word that is a country code ("danceradio cz") points to that country.
        val countries = norm.split(' ').filter { it.length == 2 }.map { it.uppercase() }.toSet()
        val nameQuery = norm.split(' ').filter { it.length != 2 || it.uppercase() !in countries }.joinToString(" ")
        fun exact(s: RadioStation) = app.musicflow.core.common.TextMatching.normalize(s.name) == nameQuery
        if (ws.isEmpty()) return stations.sortedWith(compareByDescending<RadioStation> { exact(it) }.thenByDescending { it.countryCode.uppercase() in countries })
        fun hits(s: RadioStation): Int {
            val hay = " " + app.musicflow.core.common.TextMatching.normalize(listOf(s.name, s.state, s.tags, s.country).joinToString(" ")) + " "
            return ws.count { w -> hay.contains(w) }
        }
        return stations.sortedWith(
            compareByDescending<RadioStation> { hits(it) + (if (it.countryCode.uppercase() in countries) 1 else 0) }
                .thenByDescending { exact(it) }
                .thenByDescending { it.clickCount },
        )
    }
}
