package app.musicflow.data.providers.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.musicflow.core.common.TextMatching
import app.musicflow.core.model.AlbumRef
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ProviderIds
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.SearchFilter
import app.musicflow.core.model.SearchPage
import app.musicflow.core.model.Track
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.SearchProvider
import app.musicflow.data.providers.api.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Audio files on the device, read through MediaStore. Works fully offline.
 * Needs READ_MEDIA_AUDIO (Android 13+) or READ_EXTERNAL_STORAGE (Android 9–12), requested only
 * when the user opens "Local music".
 */
class LocalMusicProvider(private val context: Context) : MusicProvider, SearchProvider, StreamResolver {
    override val id = ProviderIds.LOCAL
    override val displayName = "On this device"
    override val isExperimental = false
    override val search: SearchProvider get() = this
    override val streams: StreamResolver get() = this

    val requiredPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
        )
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection, projection,
            "COALESCE(${MediaStore.Audio.Media.IS_MUSIC}, 1) != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            while (c.moveToNext()) {
                val mediaId = c.getLong(idCol)
                val artist = c.getString(artistCol)?.takeIf { it != MediaStore.UNKNOWN_STRING && it.isNotBlank() }
                val albumId = c.getLong(albumIdCol)
                tracks += Track(
                    id = MediaId.of(ProviderIds.LOCAL, mediaId.toString()),
                    title = c.getString(titleCol) ?: "Unknown",
                    artists = listOfNotNull(artist?.let { ArtistRef(null, it) }),
                    album = c.getString(albumCol)?.let { AlbumRef(null, it) },
                    durationMs = c.getLong(durCol).takeIf { it > 0 },
                    artworkUrl = ContentUris.withAppendedId(ALBUM_ART, albumId).toString(),
                    releaseYear = c.getInt(yearCol).takeIf { it > 0 },
                )
            }
        }
        tracks
    }

    override suspend fun search(query: String, filter: SearchFilter, pageToken: String?): SearchPage {
        if (filter != SearchFilter.ALL && filter != SearchFilter.SONGS) return SearchPage(query, filter, emptyList(), null)
        val q = TextMatching.normalize(query)
        val hits = scan().filter { t ->
            TextMatching.normalize(t.title).contains(q) || TextMatching.normalize(t.artistLine).contains(q) ||
                TextMatching.normalize(t.album?.title.orEmpty()).contains(q)
        }
        return SearchPage(query, filter, hits.map(MediaItemSummary::TrackItem), null)
    }

    override suspend fun suggestions(query: String): List<String> = emptyList()

    override fun canResolve(id: MediaId) = id.providerId == ProviderIds.LOCAL

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream {
        val mediaId = id.key.toLongOrNull() ?: throw ProviderError.Unavailable("Invalid local id")
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: throw ProviderError.Unavailable("The file is no longer on this device")
        return ResolvedStream(
            mediaId = id, url = uri.toString(), mimeType = mime,
            codec = mime.substringAfter('/').substringBefore(';').removePrefix("x-"),
            bitrateKbps = null, sampleRateHz = null, expiresAtMillis = null,
            isLossless = mime.contains("flac") || mime.contains("wav") || mime.contains("alac"),
            cacheKey = "local:$mediaId",
        )
    }

    companion object {
        private val ALBUM_ART = android.net.Uri.parse("content://media/external/audio/albumart")
    }
}
