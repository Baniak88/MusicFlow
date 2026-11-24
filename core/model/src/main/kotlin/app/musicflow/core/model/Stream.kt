package app.musicflow.core.model

/** User-selectable quality preference. Mapped to a concrete stream by the resolver. */
enum class AudioQuality {
    DATA_SAVER, STANDARD, HIGH, BEST;

    /** Target maximum bitrate in kbit/s; `null` means "highest available". */
    val maxBitrateKbps: Int?
        get() = when (this) {
            DATA_SAVER -> 64
            STANDARD -> 128
            HIGH -> 192
            BEST -> null
        }
}

/**
 * A concrete, playable audio stream as reported by the source. Nothing here is guessed:
 * if the source does not report a field, it is `null`.
 */
data class ResolvedStream(
    val mediaId: MediaId,
    val url: String,
    val mimeType: String?,
    val codec: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val channels: Int? = null,
    /** Epoch millis after which the URL is known to stop working, or `null` if it does not expire. */
    val expiresAtMillis: Long?,
    val contentLength: Long? = null,
    val isLossless: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
    /** A stable cache key (same audio → same key even if the URL is re-signed). */
    val cacheKey: String,
    val loudnessDb: Float? = null,
    /**
     * YouTube "UMP-less" progressive URLs must be fetched as POST requests in bounded byte
     * ranges (`&range=a-b&rn=n`). The playback layer uses a chunked data source when set.
     */
    val requiresChunkedPost: Boolean = false,
    /** True for HLS manifests (e.g. live streams); false for progressive files. */
    val isHls: Boolean = false,
) {
    fun isExpired(nowMillis: Long, safetyMarginMillis: Long = 60_000): Boolean =
        expiresAtMillis != null && nowMillis + safetyMarginMillis >= expiresAtMillis

    /** Human readable format line, e.g. "OPUS · 160 kbps · 48 kHz". Only reported values. */
    fun describe(): String = buildList {
        codec?.let { add(it.uppercase()) }
        bitrateKbps?.let { add("$it kbps") }
        sampleRateHz?.let { add("${it / 1000} kHz") }
        if (isLossless) add("Lossless")
    }.joinToString(" · ")
}
