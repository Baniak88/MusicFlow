package app.musicflow.data.repositories

import app.musicflow.core.common.Clock
import app.musicflow.core.common.M3u
import app.musicflow.core.common.M3uEntry
import app.musicflow.core.common.M3uPlaylist
import app.musicflow.core.common.TextMatching
import app.musicflow.core.database.MusicFlowDatabase
import app.musicflow.core.database.entity.PlaylistEntity
import app.musicflow.core.database.entity.PlaylistEntryEntity
import app.musicflow.core.database.entity.TrackEntity
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.Track
import app.musicflow.data.providers.direct.DirectUrlProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class UserPlaylist(val id: Long, val name: String, val trackCount: Int, val artworkUrl: String?, val updatedAt: Long, val sourceUrl: String?)

data class PlaylistEntry(val entryId: Long, val position: Int, val track: Track)

/** Result of adding tracks: which were added and which were skipped as duplicates. */
data class AddResult(val added: Int, val duplicates: List<Track>)

data class ImportResult(val playlistId: Long, val imported: Int, val skipped: Int)

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val createdAt: Long,
    val playlists: List<BackupPlaylist>,
    val favorites: List<Track> = emptyList(),
) {
    companion object { const val FORMAT = "musicflow-backup" }
}

@Serializable
data class BackupPlaylist(val name: String, val description: String? = null, val sourceUrl: String? = null, val tracks: List<Track>)

@Singleton
class PlaylistRepository @Inject constructor(
    private val db: MusicFlowDatabase,
    private val registry: ProviderRegistry,
) {
    var clock: Clock = Clock.System
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val dao get() = db.playlists()

    val playlists: Flow<List<UserPlaylist>> = dao.playlists().map { l ->
        l.map { UserPlaylist(it.id, it.name, it.trackCount, it.artworkUrl, it.updatedAt, it.sourceUrl) }
    }

    fun playlist(id: Long): Flow<UserPlaylist?> = dao.playlist(id).map { p ->
        p?.let { UserPlaylist(it.id, it.name, 0, null, it.updatedAt, it.sourceUrl) }
    }

    fun entries(id: Long): Flow<List<PlaylistEntry>> = dao.entries(id).map { l -> l.map { PlaylistEntry(it.entryId, it.position, it.track()) } }

    suspend fun create(name: String, sourceUrl: String? = null): Long {
        val clean = name.trim().ifEmpty { "Playlist" }
        val now = clock.now()
        return dao.insert(PlaylistEntity(name = clean, createdAt = now, updatedAt = now, sourceUrl = sourceUrl))
    }

    suspend fun rename(id: Long, name: String) {
        val p = dao.getPlaylist(id) ?: return
        dao.update(p.copy(name = name.trim().ifEmpty { p.name }, updatedAt = clock.now()))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Tracks already in the playlist, matched by id or by normalised title + artist. */
    suspend fun findDuplicates(id: Long, tracks: List<Track>): List<Track> {
        val existing = dao.rawEntries(id).map { it.trackId }.toSet()
        val existingKeys = db.tracks().getAll(existing.toList()).map { TextMatching.trackKey(it.title, it.primaryArtistName) }.toSet()
        return tracks.filter { it.id.value in existing || TextMatching.trackKey(it.title, it.primaryArtist) in existingKeys }
    }

    suspend fun add(id: Long, tracks: List<Track>, allowDuplicates: Boolean = false): AddResult = db.transaction {
        val dups = if (allowDuplicates) emptyList() else findDuplicates(id, tracks)
        val dupIds = dups.map { it.id }.toSet()
        // Also de-duplicate within the batch itself.
        val toAdd = tracks.filter { it.id !in dupIds }.let { if (allowDuplicates) it else it.distinctBy { t -> t.id } }
        db.tracks().upsertAll(toAdd.map { TrackEntity.from(it, clock.now()) })
        var pos = dao.nextPosition(id)
        dao.insertEntries(toAdd.map { PlaylistEntryEntity(playlistId = id, trackId = it.id.value, position = pos++, addedAt = clock.now()) })
        dao.touch(id, clock.now())
        AddResult(toAdd.size, dups)
    }

    suspend fun remove(id: Long, entryId: Long) = db.transaction {
        dao.deleteEntry(entryId)
        dao.rewriteOrder(id, dao.rawEntries(id).map { it.entryId }, clock.now())
    }

    suspend fun move(id: Long, from: Int, to: Int) = db.transaction {
        val order = dao.rawEntries(id).map { it.entryId }.toMutableList()
        if (from !in order.indices || to !in order.indices) return@transaction
        order.add(to, order.removeAt(from))
        dao.rewriteOrder(id, order, clock.now())
    }

    suspend fun reorder(id: Long, orderedEntryIds: List<Long>) = dao.rewriteOrder(id, orderedEntryIds, clock.now())

    // ------------------------------------------------------------- import / export

    /**
     * Imports a public playlist from a supported provider URL, preserving the original order.
     * Follows continuation pages up to [maxTracks].
     */
    suspend fun importPublic(url: String, maxTracks: Int = 1000): ImportResult {
        val provider = registry.enabled().firstOrNull { it.playlists?.parsePlaylistUrl(url) != null }
            ?: throw ProviderError.NotSupported("This playlist link is not supported")
        val id = provider.playlists!!.parsePlaylistUrl(url)!!
        val first = registry.call(provider, "importPlaylist") { it.playlists!!.playlist(id) }
        val tracks = first.tracks.toMutableList()
        var token = first.nextPageToken
        while (token != null && tracks.size < maxTracks) {
            val page = registry.call(provider, "importPlaylist.more") { it.playlists!!.playlist(id, token) }
            tracks += page.tracks
            token = page.nextPageToken
        }
        val pid = create(first.title, sourceUrl = url)
        val result = add(pid, tracks.take(maxTracks), allowDuplicates = true)
        return ImportResult(pid, result.added, 0)
    }

    /**
     * Imports an M3U playlist. Entries are mapped in order to: YouTube links, HTTPS direct audio
     * URLs, or (by title/artist) previously known tracks. Unresolvable entries are skipped.
     */
    suspend fun importM3u(text: String, fallbackName: String): ImportResult {
        val parsed = M3u.parse(text)
        var skipped = 0
        val tracks = parsed.entries.mapNotNull { e ->
            val loc = e.location.trim()
            val providerTrack = registry.enabled().firstNotNullOfOrNull { it.playlists?.parseTrackUrl(loc) }
            val id = when {
                providerTrack != null -> providerTrack
                loc.startsWith("https://", true) -> DirectUrlProvider.idFor(loc)
                else -> MediaId.parseOrNull(loc)?.takeIf { registry.forMedia(it) != null }
            }
            if (id == null) { skipped++; null } else {
                db.tracks().get(id.value)?.toModel() ?: Track(
                    id = id,
                    title = e.title ?: loc.substringAfterLast('/').substringBefore('?').ifEmpty { loc },
                    artists = listOfNotNull(e.artist?.let { ArtistRef(null, it) }),
                    durationMs = e.durationSeconds?.times(1000),
                )
            }
        }
        val pid = create(parsed.name ?: fallbackName)
        val r = add(pid, tracks, allowDuplicates = true)
        return ImportResult(pid, r.added, skipped)
    }

    suspend fun exportM3u(id: Long): String {
        val p = dao.getPlaylist(id) ?: throw ProviderError.Unavailable("Playlist not found")
        val entries = dao.rawEntries(id)
        val byId = db.tracks().getAll(entries.map { it.trackId }).associateBy { it.id }
        val out = entries.mapNotNull { e ->
            val t = byId[e.trackId] ?: return@mapNotNull null
            M3uEntry(location = shareableLocation(MediaId(t.id)), title = t.title, artist = t.primaryArtistName.ifEmpty { null }, durationSeconds = t.durationMs?.div(1000))
        }
        return M3u.write(M3uPlaylist(p.name, out))
    }

    private fun shareableLocation(id: MediaId): String = when (id.providerId) {
        "yt" -> "https://music.youtube.com/watch?v=${id.key}"
        "url" -> id.key
        else -> id.value
    }

    suspend fun backup(favorites: List<Track>): String {
        val playlists = dao.allPlaylists().map { p ->
            val entries = dao.rawEntries(p.id)
            val byId = db.tracks().getAll(entries.map { it.trackId }).associateBy { it.id }
            BackupPlaylist(p.name, p.description, p.sourceUrl, entries.mapNotNull { byId[it.trackId]?.toModel() })
        }
        return json.encodeToString(BackupFile.serializer(), BackupFile(createdAt = clock.now(), playlists = playlists, favorites = favorites))
    }

    /** Restores playlists (appended, never overwriting existing ones) and favourites. */
    suspend fun restore(text: String, setFavorite: suspend (Track) -> Unit): Int {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw ProviderError.Parsing("Not a MusicFlow backup file", e)
        }
        if (file.format != BackupFile.FORMAT) throw ProviderError.Parsing("Not a MusicFlow backup file")
        file.playlists.forEach { bp ->
            val pid = create(bp.name, bp.sourceUrl)
            add(pid, bp.tracks, allowDuplicates = true)
        }
        file.favorites.forEach { setFavorite(it) }
        return file.playlists.size
    }
}
