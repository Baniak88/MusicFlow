package app.musicflow.data.providers.direct

import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.ProviderIds
import app.musicflow.core.model.ResolvedStream
import app.musicflow.data.providers.api.MusicProvider
import app.musicflow.data.providers.api.StreamResolver

/**
 * Plays authorised direct audio URLs (e.g. a self-hosted file, an internet radio stream or an
 * M3U entry). The user supplies the URL; MusicFlow never scrapes or guesses one.
 * Only HTTPS is accepted for remote URLs (cleartext is blocked by the network security config).
 */
class DirectUrlProvider : MusicProvider, StreamResolver {
    override val id = ProviderIds.DIRECT_URL
    override val displayName = "Direct audio URL"
    override val isExperimental = false
    override val streams: StreamResolver get() = this

    override fun canResolve(id: MediaId) = id.providerId == ProviderIds.DIRECT_URL

    override suspend fun resolve(id: MediaId, quality: AudioQuality): ResolvedStream {
        val url = id.key
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw ProviderError.NotSupported("Only HTTPS audio URLs are supported")
        }
        val lower = url.substringBefore('?').lowercase()
        val isHls = lower.endsWith(".m3u8")
        return ResolvedStream(
            mediaId = id,
            url = url,
            mimeType = when {
                isHls -> "application/x-mpegURL"
                lower.endsWith(".mp3") -> "audio/mpeg"
                lower.endsWith(".flac") -> "audio/flac"
                lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
                lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
                lower.endsWith(".wav") -> "audio/wav"
                else -> null
            },
            codec = null, bitrateKbps = null, sampleRateHz = null,
            expiresAtMillis = null,
            // Lossless is only claimed for formats that are lossless by definition.
            isLossless = lower.endsWith(".flac") || lower.endsWith(".wav"),
            cacheKey = "url:$url",
            isHls = isHls,
        )
    }

    companion object {
        fun idFor(url: String) = MediaId.of(ProviderIds.DIRECT_URL, url)
    }
}
