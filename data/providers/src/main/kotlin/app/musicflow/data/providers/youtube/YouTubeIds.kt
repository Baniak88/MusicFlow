package app.musicflow.data.providers.youtube

import app.musicflow.core.model.MediaId

/**
 * MediaId scheme for the YouTube Music provider:
 *  - `yt:<videoId>`        track
 *  - `ytpl:<playlistId>`   album (OLAK5uy_…) or playlist (PL…, VL…, RD…)
 *  - `ytch:<channelId>`    artist
 */
object YouTubeIds {
    const val TRACK = "yt"
    const val PLAYLIST = "ytpl"
    const val CHANNEL = "ytch"

    private val videoIdPattern = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val videoInUrl = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""")
    private val listInUrl = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
    private val browseAlbum = Regex("""/browse/(MPREb_[A-Za-z0-9_-]+)""")
    private val channelInUrl = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")

    fun track(videoId: String) = MediaId.of(TRACK, videoId)
    fun playlist(listId: String) = MediaId.of(PLAYLIST, listId)
    fun channel(channelId: String) = MediaId.of(CHANNEL, channelId)

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
    fun musicWatchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"
    fun playlistUrl(listId: String) = "https://www.youtube.com/playlist?list=$listId"
    fun channelUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"

    fun isVideoId(s: String) = videoIdPattern.matches(s)

    fun videoIdFromUrl(url: String): String? =
        videoInUrl.find(url)?.groupValues?.get(1) ?: url.takeIf(::isVideoId)

    fun listIdFromUrl(url: String): String? = listInUrl.find(url)?.groupValues?.get(1)

    fun channelIdFromUrl(url: String): String? = channelInUrl.find(url)?.groupValues?.get(1)

    fun albumBrowseIdFromUrl(url: String): String? = browseAlbum.find(url)?.groupValues?.get(1)

    fun isYouTubeUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** Signed googlevideo URLs carry their expiry as `expire=<epoch seconds>`. */
    fun expiryMillis(streamUrl: String): Long? =
        Regex("""[?&]expire=(\d+)""").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    fun contentLength(streamUrl: String): Long? =
        Regex("""[?&]clen=(\d+)""").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()

    /** Removes the " - Topic" suffix YouTube adds to auto-generated artist channels. */
    fun cleanArtistName(name: String?): String? = name?.removeSuffix(" - Topic")?.trim()?.ifEmpty { null }

    /**
     * Requests the highest-resolution variant of YouTube/Google artwork URLs.
     * `lh3.googleusercontent.com/...=w120-h120` → `=w720-h720`; `i.ytimg.com/vi/x/hqdefault.jpg` is kept.
     */
    fun upscaleArtwork(url: String?, size: Int = 1080): String? {
        if (url == null) return null
        val googleSized = Regex("""=w\d+-h\d+[^/]*$""")
        val googleS = Regex("""=s\d+[^/]*$""")
        return when {
            googleSized.containsMatchIn(url) -> googleSized.replace(url, "=w$size-h$size-l90-rj")
            googleS.containsMatchIn(url) -> googleS.replace(url, "=s$size")
            else -> url
        }
    }
}
