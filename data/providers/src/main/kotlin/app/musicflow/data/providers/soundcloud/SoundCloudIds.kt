package app.musicflow.data.providers.soundcloud

import app.musicflow.core.model.MediaId

/**
 * MediaId scheme for SoundCloud. Keys are the public permalink without the scheme, which is
 * what NewPipeExtractor accepts and what users paste:
 *  - `sc:soundcloud.com/<user>/<track>`          track
 *  - `scpl:soundcloud.com/<user>/sets/<set>`     playlist or album ("set")
 *  - `scu:soundcloud.com/<user>`                 artist (user profile)
 */
object SoundCloudIds {
    const val TRACK = "sc"
    const val PLAYLIST = "scpl"
    const val USER = "scu"

    private val reserved = setOf(
        "discover", "stream", "search", "upload", "you", "charts", "settings", "messages",
        "notifications", "pages", "terms-of-use", "jobs", "imprint", "mobile", "people", "popular", "tags",
    )

    fun url(id: MediaId) = "https://" + id.key

    /** Normalises a SoundCloud URL to `soundcloud.com/<path>` (no scheme, `m.`/`www.`, query or trailing slash). */
    fun permalink(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val m = Regex("""^(?:https?://)?(?:www\.|m\.)?soundcloud\.com/([^?#]+)""", RegexOption.IGNORE_CASE).find(url.trim()) ?: return null
        val path = m.groupValues[1].trimEnd('/')
        if (path.isEmpty()) return null
        return "soundcloud.com/$path"
    }

    private fun segments(url: String?): List<String>? = permalink(url)?.removePrefix("soundcloud.com/")?.split('/')

    fun trackFromUrl(url: String?): MediaId? {
        val s = segments(url) ?: return null
        if (s.size != 2 || s[0].lowercase() in reserved || s[1] in setOf("sets", "tracks", "albums", "likes", "reposts", "popular-tracks", "followers", "following")) return null
        return MediaId.of(TRACK, permalink(url)!!)
    }

    fun playlistFromUrl(url: String?): MediaId? {
        val s = segments(url) ?: return null
        if (s.size < 3 || s[1] != "sets" || s[0].lowercase() in reserved) return null
        return MediaId.of(PLAYLIST, "soundcloud.com/${s[0]}/sets/${s[2]}")
    }

    fun userFromUrl(url: String?): MediaId? {
        val s = segments(url) ?: return null
        if (s.isEmpty() || s[0].lowercase() in reserved) return null
        return MediaId.of(USER, "soundcloud.com/${s[0]}")
    }

    fun isSoundCloudUrl(text: String) = permalink(text) != null

    /** Expiry of a signed stream URL (`expires=<epoch seconds>`), if the URL reports one. */
    fun expiryMillis(url: String): Long? =
        Regex("""[?&](?:expires|Expires)=(\d{9,11})""").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    /** Artwork: SoundCloud serves 100 px "-large" images by default; the 500 px variant exists for all. */
    fun upscaleArtwork(url: String?): String? = url?.replace(Regex("""-(?:large|t\d+x\d+|small|badge|tiny|mini|crop)\.(jpg|png)"""), "-t500x500.$1")
}
