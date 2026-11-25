package app.musicflow.data.providers.soundcloud

import app.musicflow.core.model.Album
import app.musicflow.core.model.Artist
import app.musicflow.core.model.ArtistRef
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.PlaylistSummary
import app.musicflow.core.model.Track
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Converts NewPipeExtractor SoundCloud items into MusicFlow models. Never invents values. */
internal object SoundCloudMapper {

    fun bestImage(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val best = images.maxByOrNull { it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0) } ?: images.last()
        val url = if (best.width <= 0 && best.height <= 0) images.last().url else best.url
        return SoundCloudIds.upscaleArtwork(url)
    }

    fun artistRef(name: String?, url: String?): List<ArtistRef> {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(ArtistRef(SoundCloudIds.userFromUrl(url)?.value, clean))
    }

    fun track(item: StreamInfoItem): Track? {
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) return null
        val id = SoundCloudIds.trackFromUrl(item.url) ?: return null
        return Track(
            id = id,
            title = item.name,
            artists = artistRef(item.uploaderName, item.uploaderUrl),
            durationMs = item.duration.takeIf { it > 0 }?.times(1000),
            artworkUrl = bestImage(item.thumbnails),
            releaseYear = runCatching { item.uploadDate?.offsetDateTime()?.year }.getOrNull(),
        )
    }

    fun playlist(item: PlaylistInfoItem): PlaylistSummary? {
        val id = SoundCloudIds.playlistFromUrl(item.url) ?: return null
        return PlaylistSummary(
            id = id,
            title = item.name,
            owner = item.uploaderName?.takeIf { it.isNotBlank() },
            artworkUrl = bestImage(item.thumbnails),
            trackCount = item.streamCount.takeIf { it > 0 }?.toInt(),
        )
    }

    fun album(item: PlaylistInfoItem): Album? {
        val id = SoundCloudIds.playlistFromUrl(item.url) ?: return null
        return Album(
            id = id,
            title = item.name,
            artists = artistRef(item.uploaderName, item.uploaderUrl),
            artworkUrl = bestImage(item.thumbnails),
            trackCount = item.streamCount.takeIf { it > 0 }?.toInt(),
        )
    }

    fun artist(item: ChannelInfoItem): Artist? {
        val id = SoundCloudIds.userFromUrl(item.url) ?: return null
        return Artist(id = id, name = item.name, artworkUrl = bestImage(item.thumbnails), subscriberCount = item.subscriberCount.takeIf { it > 0 })
    }

    fun summary(item: InfoItem): MediaItemSummary? = when (item) {
        is StreamInfoItem -> track(item)?.let(MediaItemSummary::TrackItem)
        is PlaylistInfoItem -> playlist(item)?.let(MediaItemSummary::PlaylistItem)
        is ChannelInfoItem -> artist(item)?.let(MediaItemSummary::ArtistItem)
        else -> null
    }
}
