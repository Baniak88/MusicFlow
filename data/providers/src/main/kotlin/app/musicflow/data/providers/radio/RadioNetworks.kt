package app.musicflow.data.providers.radio

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.TextMatching
import app.musicflow.core.common.defaultClassify
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Polish radio networks whose channels are listed on their own websites. */
enum class RadioNetwork(val key: String, val displayName: String) {
    OPEN_FM("openfm", "Open FM"),
    RMF_ON("rmf", "RMF ON"),
    ESKA("eska", "ESKA");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** A channel of a network, with only what its website lists. */
data class NetworkStation(
    val network: RadioNetwork,
    val id: String,
    val name: String,
    /** Null when the URL is looked up at play time (RMF ON: per-station server list). */
    val streamUrl: String?,
    val logoUrl: String?,
    val isHls: Boolean,
    val category: String? = null,
)

/**
 * Channel lists of Open FM, RMF ON and ESKA, read from their public websites (unofficial: no
 * documented API, may break when a site changes). Lists are cached for 6 hours; a stale list is
 * used when a site is unreachable.
 *
 * MediaIds live in the radio namespace, so playback treats them as live radio:
 * `radio:net/<network>/<id>` or `radio:hls/net/<network>/<id>`.
 */
class RadioNetworks(
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<RadioNetwork, Pair<Long, List<NetworkStation>>>()

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute().use { r ->
                if (r.code == 429) throw ProviderError.RateLimited("$url: rate limited")
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: ProviderError) {
            throw e
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw defaultClassify(t)
        }
    }

    suspend fun stations(network: RadioNetwork): List<NetworkStation> {
        cache[network]?.let { (at, list) -> if (clock() - at < TTL_MS) return list }
        return try {
            val list = when (network) {
                RadioNetwork.OPEN_FM -> NetworkParsers.openFm(fetch("https://open.fm/"))
                RadioNetwork.RMF_ON -> NetworkParsers.rmfOn(fetch("https://www.rmfon.pl/json/stations.txt"))
                RadioNetwork.ESKA -> NetworkParsers.eska(fetch("https://player.eska.pl/"))
            }
            if (list.isEmpty()) throw ProviderError.Parsing("${network.displayName}: no stations found on the website")
            cache[network] = clock() to list
            list
        } catch (e: ProviderError) {
            cache[network]?.second?.let {
                diagnostics.w("radio", "${network.displayName} list refresh failed (${e.message}); using the cached list")
                return it
            }
            throw e
        }
    }

    /** All networks; a network that fails is left out. */
    suspend fun all(): List<NetworkStation> = coroutineScope {
        RadioNetwork.entries.map { n -> async { runCatching { stations(n) }.onFailure { diagnostics.w("radio", "${n.displayName}: ${it.message}") }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
    }

    suspend fun search(query: String): List<NetworkStation> = NetworkParsers.match(query, all())

    suspend fun station(network: RadioNetwork, id: String): NetworkStation =
        stations(network).firstOrNull { it.id == id } ?: throw ProviderError.Unavailable("${network.displayName} no longer lists this station")

    /** Playable URL candidates, best first (RMF ON: MP3 128 kbps servers, then AAC). */
    suspend fun streamUrls(s: NetworkStation): List<String> = when {
        s.streamUrl != null -> listOf(s.streamUrl)
        s.network == RadioNetwork.RMF_ON -> NetworkParsers.rmfStreams(fetch("https://www.rmfon.pl/stacje/flash_aac_${s.id}.xml.txt"))
            .ifEmpty { throw ProviderError.Unavailable("RMF ON lists no stream for ${s.name}") }
        else -> throw ProviderError.Unavailable("No stream for ${s.name}")
    }

    companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val PREFIX = "net/"
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        fun mediaId(s: NetworkStation) = MediaId.of(RadioIds.STATION, (if (s.isHls) "hls/" else "") + "$PREFIX${s.network.key}/${s.id}")

        /** (network, station id) of a network MediaId, or null for Radio Browser stations. */
        fun parse(id: MediaId): Pair<RadioNetwork, String>? {
            val key = RadioIds.uuid(id)
            if (!key.startsWith(PREFIX)) return null
            val parts = key.removePrefix(PREFIX).split('/', limit = 2)
            val network = RadioNetwork.of(parts.getOrNull(0).orEmpty()) ?: return null
            return network to (parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null)
        }

        fun toTrack(s: NetworkStation) = Track(
            id = mediaId(s),
            title = s.name,
            artists = listOf(ArtistRef(null, listOfNotNull(s.network.displayName, s.category).joinToString(" · "))),
            album = null,
            durationMs = null,
            artworkUrl = s.logoUrl?.takeIf { it.startsWith("https://", ignoreCase = true) },
            genre = s.category,
        )
    }
}

/** Pure parsing of the networks' pages (unit-tested with saved copies). */
object NetworkParsers {
    private val json = Json { ignoreUnknownKeys = true }

    /** Open FM: the station list embedded in the home page (`__NEXT_DATA__`). Premium channels are skipped. */
    fun openFm(html: String): List<NetworkStation> {
        val data = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw ProviderError.Parsing("Open FM page format changed (no station data)")
        val fallback = try {
            json.parseToJsonElement(data).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["fallback"]!!.jsonObject
        } catch (e: Exception) {
            throw ProviderError.Parsing("Open FM page format changed: ${e.message}", e)
        }
        val stations = (fallback["stations"] as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }
        val categories = (fallback["categories"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val categoryOf = HashMap<Int, String>()
        val order = LinkedHashSet<Int>()
        for (c in categories) {
            val name = c["name"]?.jsonPrimitive?.contentOrNull?.let(::cleanCategory)
            for (id in (c["stations"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }) {
                order += id
                if (name != null) categoryOf.putIfAbsent(id, name)
            }
        }
        val byId = stations.mapNotNull { s ->
            val id = s["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (s["premium"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val name = s["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = s["streamUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isEmpty() || !url.startsWith("https://", true)) return@mapNotNull null
            id to NetworkStation(
                RadioNetwork.OPEN_FM, id.toString(), name, url,
                s["logoUrl"]?.jsonPrimitive?.contentOrNull, isHls = url.contains(".m3u8"), category = categoryOf[id],
            )
        }.toMap()
        // Site order: the categories (Top 10 first), then everything else by id.
        return (order.filter { it in byId } + byId.keys.sorted().filter { it !in order }).map { byId.getValue(it) }
    }

    private fun cleanCategory(name: String) = name.dropWhile { !it.isLetterOrDigit() }.trim().ifEmpty { null }

    @kotlinx.serialization.Serializable
    private data class RmfStation(val id: String = "", val name: String = "", val slug: String = "")

    /** RMF ON: `json/stations.txt`; stream servers are looked up per station at play time. */
    fun rmfOn(text: String): List<NetworkStation> = try {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RmfStation.serializer()), text)
    } catch (e: Exception) {
        throw ProviderError.Parsing("RMF ON list format changed: ${e.message}", e)
    }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.map {
        NetworkStation(RadioNetwork.RMF_ON, it.id, it.name.trim(), streamUrl = null, logoUrl = "https://www.rmfon.pl/img/z/s${it.id}.jpg", isHls = false)
    }

    /** RMF ON server list (`stacje/flash_aac_<id>.xml.txt`): MP3 (128 kbps) servers first, then AAC; distinct, HTTPS only. */
    fun rmfStreams(xml: String): List<String> {
        val mp3 = Regex("""<item_mp3[^>]*>\s*([^<\s]+)\s*</item_mp3>""").findAll(xml).map { it.groupValues[1] }
        val aac = Regex("""<item(?:\s[^>]*)?>\s*([^<\s]+)\s*</item>""").findAll(xml).map { it.groupValues[1] }
        return (mp3 + aac).map(::unescape).filter { it.startsWith("https://", true) }.distinct().toList()
    }

    /** ESKA: channel tiles of player.eska.pl (`data-stream-url`, logo, title). */
    fun eska(html: String): List<NetworkStation> =
        Regex("""<a\s[^>]*data-stream-url="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { m ->
            val url = unescape(m.groupValues[1])
            val body = m.groupValues[2]
            val name = Regex("""<span>([^<]+)</span>""").find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()
                ?: Regex("""alt="([^"]+)"""").find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()
            val logo = Regex("""<img[^>]*\ssrc="([^"]+)"""").find(body)?.groupValues?.get(1)?.let(::unescape)
            val id = Regex("""/(\d+-\d+)\.aac""").find(url)?.groupValues?.get(1)
                ?: Regex("""data-stream-uid="([^"]+)"""").find(m.value)?.groupValues?.get(1)
            if (name.isNullOrEmpty() || id == null || !url.startsWith("https://", true)) null
            else NetworkStation(RadioNetwork.ESKA, id, name, url, logo, isHls = url.contains(".m3u8"))
        }.distinctBy { it.id }.toList()

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&rsquo;", "’").replace("&lt;", "<").replace("&gt;", ">")

    /**
     * Stations whose name (or network name + name) matches the query words. "rmf" lists all of
     * RMF ON, "open fm 357" finds Radio 357 on Open FM, "eska rap" finds ESKA Rap20.
     */
    fun match(query: String, stations: List<NetworkStation>): List<NetworkStation> {
        // "radio" and "on" are too generic: "radio eska" should still find "ESKA LOVE".
        val words = TextMatching.normalize(query).split(' ').filter { it.isNotEmpty() && it != "radio" && it != "on" }
        val q = TextMatching.normalize(query)
        if (q.isEmpty()) return emptyList()
        fun hay(s: NetworkStation) = " " + TextMatching.normalize("${s.network.displayName} ${s.network.key} ${s.name} ${s.category.orEmpty()}") + " "
        val scored = stations.mapNotNull { s ->
            val h = hay(s)
            val name = TextMatching.normalize(s.name)
            val all = words.isNotEmpty() && words.all { w -> h.contains(" $w") }
            when {
                name == q -> s to 3
                all && name.contains(q) -> s to 2
                all -> s to 1
                else -> null
            }
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }
}
