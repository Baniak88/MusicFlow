package app.musicflow.core.model

import kotlinx.serialization.Serializable

/**
 * Stable identifier of a playable or browsable item across providers.
 *
 * Encoded as `"<providerId>:<key>"`, e.g. `yt:dQw4w9WgXcQ`, `local:1234`, `url:https://…`.
 * The provider prefix is what lets the playback layer route a [MediaId] to the right
 * [StreamResolver][app.musicflow.core.model.SourceType] without the UI knowing provider details.
 */
@Serializable
@JvmInline
value class MediaId(val value: String) {
    init {
        require(value.indexOf(':') > 0) { "MediaId must be '<provider>:<key>', was '$value'" }
    }

    val providerId: String get() = value.substringBefore(':')
    val key: String get() = value.substringAfter(':')

    override fun toString(): String = value

    companion object {
        fun of(providerId: String, key: String): MediaId = MediaId("$providerId:$key")
        fun parseOrNull(raw: String?): MediaId? =
            raw?.takeIf { it.indexOf(':') > 0 }?.let(::MediaId)
    }
}

object ProviderIds {
    const val YOUTUBE_MUSIC = "yt"
    const val SOUNDCLOUD = "sc"
    const val AUDIUS = "au"
    const val RADIO = "radio"
    const val MUSICBRAINZ = "mbr"
    const val LOCAL = "local"
    const val DIRECT_URL = "url"
}
