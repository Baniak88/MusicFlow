package app.musicflow.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import kotlinx.serialization.json.Json

/**
 * Converts between [Track] and Media3 [MediaItem]. The item's URI is a `musicflow://` placeholder;
 * the real (expiring) stream URL is resolved lazily by the data source right before loading,
 * so queued items never hold stale URLs.
 */
object MediaItems {
    const val SCHEME = "musicflow"
    private const val EXTRA_TRACK = "app.musicflow.track"
    private val json = Json { ignoreUnknownKeys = true }

    /** [revision] forces a reload of the same track (e.g. after a quality change). */
    fun placeholderUri(id: MediaId, revision: Long? = null): Uri = Uri.Builder().scheme(SCHEME).authority("media")
        .appendQueryParameter("id", id.value)
        .apply { revision?.let { appendQueryParameter("rev", it.toString()) } }
        .build()

    fun mediaIdFromUri(uri: Uri): MediaId? =
        if (uri.scheme == SCHEME) MediaId.parseOrNull(uri.getQueryParameter("id")) else null

    fun toMediaItem(track: Track): MediaItem {
        val extras = Bundle().apply { putString(EXTRA_TRACK, json.encodeToString(Track.serializer(), track)) }
        val isHls = track.id.providerId == "url" && track.id.key.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        return MediaItem.Builder()
            .setMediaId(track.id.value)
            .setUri(placeholderUri(track.id))
            .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(metadata(track, extras))
            .build()
    }

    fun metadata(track: Track, extras: Bundle? = null): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setDisplayTitle(track.title)
        .setArtist(track.artistLine.ifEmpty { null })
        .setSubtitle(track.artistLine.ifEmpty { null })
        .setAlbumTitle(track.album?.title)
        .setArtworkUri(track.artworkUrl?.let(Uri::parse))
        .setReleaseYear(track.releaseYear)
        .setGenre(track.genre)
        .setDurationMs(track.durationMs)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras ?: Bundle().apply { putString(EXTRA_TRACK, json.encodeToString(Track.serializer(), track)) })
        .build()

    /** Rebuilds the [Track] from an item (possibly received over IPC without its URI). */
    fun toTrack(item: MediaItem): Track? {
        item.mediaMetadata.extras?.getString(EXTRA_TRACK)?.let { raw ->
            runCatching { return json.decodeFromString(Track.serializer(), raw) }
        }
        val id = MediaId.parseOrNull(item.mediaId) ?: return null
        val md = item.mediaMetadata
        return Track(
            id = id,
            title = md.title?.toString() ?: md.displayTitle?.toString() ?: return null,
            artists = listOfNotNull(md.artist?.toString()?.let { app.musicflow.core.model.ArtistRef(null, it) }),
            durationMs = md.durationMs,
            artworkUrl = md.artworkUri?.toString(),
        )
    }

    /** Ensures an item has its playable placeholder URI (controllers strip URIs over IPC). */
    fun ensurePlayable(item: MediaItem): MediaItem? {
        if (item.localConfiguration != null) return item
        val track = toTrack(item) ?: return null
        return toMediaItem(track)
    }
}
