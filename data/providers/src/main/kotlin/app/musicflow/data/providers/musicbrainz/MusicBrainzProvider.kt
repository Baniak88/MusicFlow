package app.musicflow.data.providers.musicbrainz

import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.common.TextMatching
import app.musicflow.core.common.defaultClassify
import app.musicflow.core.model.Album
import app.musicflow.core.model.AlbumRef
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Collections

/**
 * [MusicBrainz](https://musicbrainz.org) — the open music encyclopedia — as a catalogue:
 * search recordings, albums (release groups) and artists, album tracklists, artist
 * discographies and facts, with covers from the Cover Art Archive.
 *
 * MusicBrainz has **no audio**. A MusicBrainz track is played through [audio] (YouTube Music):
 * MusicFlow searches it there and plays it only when title, artist and duration agree
 * ([TextMatching.isReliableMatch]); otherwise the track is reported as unavailable instead of
 * playing a guess.
 *
 * API rules followed: an identifying User-Agent and at most one request per second.
 */
class MusicBrainzProvider(
    private val client: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    /** Finds and plays a matching recording (YouTube Music); null disables playback. */
    private val audio: AudioMatcher?,
    private val userAgent: String = "MusicFlow/1.0 ( https://github.com/Baniak88/MusicFlow )",
    private val base: HttpUrl = "https://musicbrainz.org/ws/2/".toHttpUrl(),
    private val minIntervalMs: Long = 1_100,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : MusicProvider, SearchProvider, MetadataProvider, StreamResolver {

    /** Searches another provider for a recording and resolves its stream. */
    interface AudioMatcher {
        val name: String
        suspend fun candidates(query: String): List<Track>
        suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream
    }

    override val id = MbIds.RECORDING
    override val displayName = "MusicBrainz"
    override val isExperimental = false
    override val isSearchSource = true
    override val ownedPrefixes = setOf(MbIds.RECORDING, MbIds.RELEASE_GROUP, MbIds.ARTIST)

    override val search: SearchProvider get() = this
    override val metadata: MetadataProvider get() = this
    override val streams: StreamResolver? get() = if (audio != null) this else null

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val gate = Mutex()
    private var lastRequest = 0L

    /** Matches already found this session (MusicBrainz id → provider id), so replays are instant. */
    private val matches: MutableMap<String, MediaId> = Collections.synchronizedMap(LinkedHashMap())

    private suspend fun <T> get(path: String, query: Map<String, String>, serializer: KSerializer<T>): T {
        val url = base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("fmt", "json")
        }.build()
        val text = gate.withLock {
            var attempt = 0
            while (true) {
                val wait = lastRequest + minIntervalMs - clock()
                if (wait > 0) delay(wait)
                var retryAfterMs: Long? = null
                try {
                    return@withLock withContext(Dispatchers.IO) {
                        client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()).execute().use { r ->
                            when {
                                r.code == 503 || r.code == 429 -> {
                                    retryAfterMs = r.header("Retry-After")?.toLongOrNull()?.times(1000)
                                    throw ProviderError.RateLimited("MusicBrainz is busy, try again shortly")
                                }
                                r.code == 404 -> throw ProviderError.Unavailable("Not found in MusicBrainz")
                                !r.isSuccessful -> throw IOException("MusicBrainz HTTP ${r.code}")
                            }
                            r.body?.string().orEmpty()
                        }
                    }
                } catch (e: ProviderError.RateLimited) {
                    // MusicBrainz asks clients to slow down and retry (503 + Retry-After).
                    if (++attempt > MAX_RETRIES) throw e
                    lastRequest = clock()
                    delay((retryAfterMs ?: (attempt * 2_000L)).coerceAtMost(8_000))
                } catch (e: ProviderError) {
                    throw e
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    throw defaultClassify(t)
                } finally {
                    lastRequest = clock()
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw ProviderError.Parsing("MusicBrainz response format changed: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        val q = query.trim()
        val offset = pageToken?.toIntOrNull() ?: 0
        val page = mapOf("query" to q, "limit" to PAGE.toString(), "offset" to offset.toString())
        fun next(count: Int, size: Int) = (offset + size).takeIf { size > 0 && it < count }?.toString()
        return when (filter) {
            SearchFilter.SONGS -> get("recording", page, MbRecordingSearch.serializer()).let { r ->
                SearchPage(q, filter, MbMapper.rankRecordings(q, r.recordings).map { MediaItemSummary.TrackItem(MbMapper.track(it)) }, next(r.count, r.recordings.size))
            }
            SearchFilter.ALBUMS -> get("release-group", page, MbReleaseGroupSearch.serializer()).let { r ->
                SearchPage(q, filter, MbMapper.rankGroups(q, r.releaseGroups).map { MediaItemSummary.AlbumItem(MbMapper.album(it)) }, next(r.count, r.releaseGroups.size))
            }
            SearchFilter.ARTISTS -> get("artist", page, MbArtistSearch.serializer()).let { r ->
                SearchPage(q, filter, r.artists.map { MediaItemSummary.ArtistItem(MbMapper.artist(it)) }, next(r.count, r.artists.size))
            }
            SearchFilter.PLAYLISTS -> SearchPage(q, filter, emptyList(), null) // MusicBrainz has no playlists
            SearchFilter.ALL -> coroutineScope {
                // Sequential under the rate limit anyway; artists and albums first, best matches only.
                val artists = runCatching { get("artist", mapOf("query" to q, "limit" to "3"), MbArtistSearch.serializer()).artists.filter { (it.score ?: 0) >= 90 } }.getOrDefault(emptyList())
                val songs = async { MbMapper.rankRecordings(q, get("recording", mapOf("query" to q, "limit" to "40"), MbRecordingSearch.serializer()).recordings).take(20) }
                val albums = runCatching { MbMapper.rankGroups(q, get("release-group", mapOf("query" to q, "limit" to "15"), MbReleaseGroupSearch.serializer()).releaseGroups).take(8) }.getOrDefault(emptyList())
                val items = artists.take(2).map { MediaItemSummary.ArtistItem(MbMapper.artist(it)) } +
                    songs.await().map { MediaItemSummary.TrackItem(MbMapper.track(it)) } +
                    albums.map { MediaItemSummary.AlbumItem(MbMapper.album(it)) }
                SearchPage(q, filter, items, null)
            }
        }
    }

    override suspend fun suggestions(query: String): List<String> = emptyList()

    // ---------------------------------------------------------------- metadata

    override suspend fun track(id: MediaId): Track =
        MbMapper.track(get("recording/${id.key}", mapOf("inc" to "artist-credits+releases+release-groups"), MbRecording.serializer()))

    /** Album = release group; its tracklist comes from the most representative release. */
    override suspend fun album(id: MediaId, pageToken: String?): CollectionPage {
        val rg = get("release-group/${id.key}", mapOf("inc" to "releases+artist-credits"), MbReleaseGroupRef.serializer())
        val release = MbMapper.representative(rg.releases) ?: throw ProviderError.Unavailable("No release of this album is listed in MusicBrainz")
        val full = get("release/${release.id}", mapOf("inc" to "recordings+artist-credits"), MbRelease.serializer())
        val ref = AlbumRef(id.value, rg.title)
        val art = MbIds.cover(rg.id, 500)
        val year = MbMapper.year(rg.firstReleaseDate)
        return CollectionPage(
            title = rg.title,
            subtitle = MbMapper.credit(rg.artistCredit).ifEmpty { null },
            artworkUrl = art,
            tracks = full.media.flatMap { it.tracks }.map { t -> MbMapper.trackOf(t, full, ref, art, year) },
            year = year,
        )
    }

    override suspend fun artist(id: MediaId): ArtistPage {
        val a = get("artist/${id.key}", mapOf("inc" to "genres+tags"), MbArtist.serializer())
        val groups = get(
            "release-group", mapOf("artist" to id.key, "type" to "album|ep|single", "limit" to "100"), MbReleaseGroupSearch.serializer(),
        ).releaseGroups.filter { it.secondaryTypes.none { t -> t in SKIP_SECONDARY } }
            .sortedByDescending { it.firstReleaseDate.orEmpty() }
        val facts = MbMapper.facts(a)
        return ArtistPage(
            artist = MbMapper.artist(a),
            topTracks = emptyList(), // MusicBrainz has no popularity data
            albums = groups.filter { it.primaryType == "Album" }.map(MbMapper::album),
            singles = groups.filter { it.primaryType == "Single" || it.primaryType == "EP" }.map(MbMapper::album),
            description = MbMapper.describe(facts),
        )
    }

    /**
     * Facts for an artist known only by name (YouTube/SoundCloud artist pages): the best
     * MusicBrainz match, only if the name matches exactly (normalised) with a high score.
     */
    suspend fun factsFor(name: String): ArtistFacts? {
        val found = get("artist", mapOf("query" to "artist:\"${name.replace("\"", "")}\"", "limit" to "5"), MbArtistSearch.serializer()).artists
        val best = found.firstOrNull { (it.score ?: 0) >= 95 && TextMatching.normalize(it.name) == TextMatching.normalize(name) } ?: return null
        return MbMapper.facts(get("artist/${best.id}", mapOf("inc" to "genres+tags"), MbArtist.serializer()))
    }

    // ---------------------------------------------------------------- playback via a reliable match

    override fun canResolve(id: MediaId) = id.providerId == MbIds.RECORDING && audio != null

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream {
        val a = audio ?: throw ProviderError.NotSupported("MusicBrainz has no audio")
        val target = matches[id.key] ?: run {
            val t = track(id)
            val found = a.candidates("${t.primaryArtist.orEmpty()} ${t.title}".trim())
            val match = bestMatch(t, found)
                ?: throw ProviderError.Unavailable("No reliable match for “${t.title}” on ${a.name}; MusicBrainz itself has no audio")
            diagnostics.i("musicbrainz", "Matched ${t.title} / ${t.primaryArtist} → ${match.id} (${match.title} / ${match.artistLine})")
            match.id.also { matches[id.key] = it }
        }
        // Keep MusicFlow's own id (history, likes) but play the matched provider's stream.
        return a.resolve(target, quality).copy(mediaId = id, cacheKey = a.name + ":" + target.key)
    }

    companion object {
        const val PAGE = 25
        private const val MAX_RETRIES = 3
        private val SKIP_SECONDARY = setOf("Compilation", "Live", "Remix", "DJ-mix", "Mixtape/Street", "Interview", "Audiobook", "Spokenword", "Demo")

        /** First candidate that is the same recording (title, artist and duration agree). */
        fun bestMatch(t: Track, candidates: List<Track>): Track? = candidates.firstOrNull { c ->
            val titles = listOf(c.title, c.title.substringAfter(" - ", c.title))
            titles.any { title ->
                TextMatching.isReliableMatch(t.title, t.primaryArtist, t.durationMs, title, c.artistLine.ifEmpty { null }, c.durationMs, durationToleranceMs = 7_000)
            }
        }
    }
}

/** MediaIds: `mbr:<recording mbid>`, `mbrg:<release group mbid>`, `mba:<artist mbid>`. */
object MbIds {
    const val RECORDING = "mbr"
    const val RELEASE_GROUP = "mbrg"
    const val ARTIST = "mba"

    /** Cover Art Archive front image of a release group (sizes 250/500/1200). May not exist. */
    fun cover(releaseGroupId: String, size: Int = 250) = "https://coverartarchive.org/release-group/$releaseGroupId/front-$size"
}

internal object MbMapper {
    fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /** Whether the user typed this artist's name (e.g. "karma police radiohead"). */
    private fun mentions(query: String, credit: String): Boolean {
        val c = TextMatching.normalize(credit)
        return c.isNotEmpty() && (" " + TextMatching.normalize(query) + " ").contains(" $c ")
    }

    /**
     * MusicBrainz ranks by text score only, so covers and namesakes come first. Re-rank: the
     * artist the user named first, then (within the same score band) the versions that appear on
     * the most releases — a good proxy for "the well-known recording". Music videos are dropped.
     */
    fun rankRecordings(query: String, items: List<MbRecording>): List<MbRecording> = items.filter { !it.video }
        .sortedWith(
            compareByDescending<MbRecording> { mentions(query, credit(it.artistCredit)) }
                .thenByDescending { (it.score ?: 0) / 10 }
                .thenByDescending { it.releases.size }
                .thenByDescending { it.releases.any { r -> r.status == "Official" } },
        )

    fun rankGroups(query: String, items: List<MbReleaseGroupRef>): List<MbReleaseGroupRef> = items.sortedWith(
        compareByDescending<MbReleaseGroupRef> { mentions(query, credit(it.artistCredit)) }
            .thenByDescending { (it.score ?: 0) / 10 }
            .thenByDescending { it.releases.size },
    )

    fun credit(credits: List<MbCredit>) = credits.joinToString("") { it.name + it.joinPhrase }.trim()

    fun artists(credits: List<MbCredit>) = credits.map { ArtistRef(it.artist.id.takeIf { id -> id.isNotEmpty() }?.let { id -> MediaId.of(MbIds.ARTIST, id).value }, it.name) }

    fun track(r: MbRecording): Track {
        val release = representative(r.releases)
        val rg = release?.releaseGroup
        return Track(
            id = MediaId.of(MbIds.RECORDING, r.id),
            title = r.title,
            artists = artists(r.artistCredit),
            album = rg?.let { AlbumRef(MediaId.of(MbIds.RELEASE_GROUP, it.id).value, it.title) } ?: release?.let { AlbumRef(null, it.title) },
            durationMs = r.length?.takeIf { it > 0 },
            artworkUrl = rg?.let { MbIds.cover(it.id) },
            releaseYear = year(r.firstReleaseDate),
        )
    }

    fun trackOf(t: MbTrack, release: MbRelease, album: AlbumRef, art: String, year: Int?): Track = Track(
        id = MediaId.of(MbIds.RECORDING, t.recording.id),
        title = t.title.ifEmpty { t.recording.title },
        artists = artists(t.artistCredit.ifEmpty { t.recording.artistCredit.ifEmpty { release.artistCredit } }),
        album = album,
        durationMs = (t.length ?: t.recording.length)?.takeIf { it > 0 },
        artworkUrl = art,
        releaseYear = year,
    )

    fun album(g: MbReleaseGroupRef) = Album(
        id = MediaId.of(MbIds.RELEASE_GROUP, g.id),
        title = g.title,
        artists = artists(g.artistCredit),
        year = year(g.firstReleaseDate),
        artworkUrl = MbIds.cover(g.id),
    )

    fun artist(a: MbArtist) = Artist(id = MediaId.of(MbIds.ARTIST, a.id), name = a.name)

    /** Prefer an official release, then the earliest dated one. */
    fun representative(releases: List<MbReleaseRef>): MbReleaseRef? =
        releases.sortedWith(compareBy({ if (it.status == "Official") 0 else 1 }, { it.date?.takeIf { d -> d.isNotEmpty() } ?: "9999" })).firstOrNull()

    fun facts(a: MbArtist) = ArtistFacts(
        mbid = a.id,
        name = a.name,
        type = a.type,
        area = a.area?.name?.takeIf { it.isNotBlank() } ?: a.country,
        begin = a.lifeSpan?.begin,
        end = a.lifeSpan?.end,
        ended = a.lifeSpan?.ended == true,
        disambiguation = a.disambiguation?.takeIf { it.isNotBlank() },
        genres = (a.genres.ifEmpty { a.tags }).sortedByDescending { it.count }.map { it.name }.take(6),
    )

    /** One factual line, e.g. "Group · United Kingdom · 1985 – · rock, alternative rock". */
    fun describe(f: ArtistFacts): String? = listOfNotNull(
        f.type,
        f.area,
        f.begin?.let { b -> year(b)?.toString()?.let { y -> if (f.ended) "$y – ${year(f.end) ?: "?"}" else "$y –" } },
        f.genres.take(4).joinToString(", ").ifEmpty { null },
        f.disambiguation,
    ).joinToString(" · ").ifEmpty { null }
}
