package app.musicflow.data.providers.youtube

import app.musicflow.core.model.Album
import app.musicflow.core.model.AlbumRef
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

/** Converts NewPipeExtractor items into MusicFlow models. Never invents values. */
internal object YouTubeMapper {

    fun bestImage(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val best = images.maxByOrNull { (it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0)) } ?: images.last()
        val url = if (best.width <= 0 && best.height <= 0) images.last().url else best.url
        return YouTubeIds.upscaleArtwork(url)
    }

    fun artistRef(name: String?, url: String?): List<ArtistRef> {
        val clean = YouTubeIds.cleanArtistName(name) ?: return emptyList()
        val id = url?.let(YouTubeIds::channelIdFromUrl)?.let { YouTubeIds.channel(it).value }
        // YouTube Music sometimes joins several artists with " & " or ", " in one name.
        return listOf(ArtistRef(id, clean))
    }

    fun track(item: StreamInfoItem, album: AlbumRef? = null, artworkOverride: String? = null): Track? {
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) return null
        val videoId = YouTubeIds.videoIdFromUrl(item.url) ?: return null
        return Track(
            id = YouTubeIds.track(videoId),
            title = item.name,
            artists = artistRef(item.uploaderName, item.uploaderUrl),
            album = album,
            durationMs = item.duration.takeIf { it > 0 }?.times(1000),
            artworkUrl = artworkOverride ?: bestImage(item.thumbnails),
            releaseYear = runCatching { item.uploadDate?.offsetDateTime()?.year }.getOrNull(),
        )
    }

    fun album(item: PlaylistInfoItem): Album? {
        val listId = YouTubeIds.listIdFromUrl(item.url) ?: return null
        return Album(
            id = YouTubeIds.playlist(listId),
            title = item.name,
            artists = artistRef(item.uploaderName, item.uploaderUrl),
            artworkUrl = bestImage(item.thumbnails),
            trackCount = item.streamCount.takeIf { it > 0 }?.toInt(),
        )
    }

    fun playlist(item: PlaylistInfoItem): PlaylistSummary? {
        val listId = YouTubeIds.listIdFromUrl(item.url) ?: return null
        return PlaylistSummary(
            id = YouTubeIds.playlist(listId),
            title = item.name,
            owner = YouTubeIds.cleanArtistName(item.uploaderName),
            artworkUrl = bestImage(item.thumbnails),
            trackCount = item.streamCount.takeIf { it > 0 }?.toInt(),
        )
    }

    fun artist(item: ChannelInfoItem): Artist? {
        val channelId = YouTubeIds.channelIdFromUrl(item.url) ?: return null
        return Artist(
            id = YouTubeIds.channel(channelId),
            name = YouTubeIds.cleanArtistName(item.name) ?: item.name,
            artworkUrl = bestImage(item.thumbnails),
            subscriberCount = item.subscriberCount.takeIf { it > 0 },
        )
    }

    /**
     * @param albumsAsAlbums when true, playlist items are albums (album search, artist releases);
     * otherwise they are regular playlists.
     */
    fun summary(item: InfoItem, albumsAsAlbums: Boolean): MediaItemSummary? = when (item) {
        is StreamInfoItem -> track(item)?.let(MediaItemSummary::TrackItem)
        is PlaylistInfoItem -> if (albumsAsAlbums) album(item)?.let(MediaItemSummary::AlbumItem) else playlist(item)?.let(MediaItemSummary::PlaylistItem)
        is ChannelInfoItem -> artist(item)?.let(MediaItemSummary::ArtistItem)
        else -> null
    }
}
