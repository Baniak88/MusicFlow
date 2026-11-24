package app.musicflow.data.providers.youtube

import app.musicflow.core.model.AudioQuality

/** Provider-neutral description of one available audio format. */
data class AudioCandidate(
    val formatId: Int,
    val url: String,
    val mimeType: String?,
    val codec: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val channels: Int?,
    val contentLength: Long?,
    /** Original / default audio track (not a dubbed or descriptive track). */
    val isOriginalTrack: Boolean = true,
)

/**
 * Picks the stream that best fits the requested [AudioQuality]:
 * the highest bitrate that does not exceed the target, otherwise the lowest available.
 * At equal bitrate, Opus is preferred over AAC because it is more efficient.
 */
object StreamSelector {
    fun select(candidates: List<AudioCandidate>, quality: AudioQuality): AudioCandidate? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filter { it.isOriginalTrack }.ifEmpty { candidates }
        val ordered = pool.sortedWith(
            compareBy<AudioCandidate> { it.bitrateKbps ?: 0 }.thenBy { if (it.codec?.contains("opus", true) == true) 1 else 0 },
        )
        val max = quality.maxBitrateKbps ?: return ordered.last()
        return ordered.lastOrNull { (it.bitrateKbps ?: 0) <= max } ?: ordered.first()
    }
}
