package app.musicflow.playback

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.musicflow.core.model.NormalizationLevel
import kotlin.math.pow

/** Where a track's loudness value came from (shown in the player / diagnostics). */
enum class LoudnessSource { PROVIDER, REPLAYGAIN }

data class NormalizationGain(val gainDb: Float, val source: LoudnessSource) {
    /** Attenuation part, applied through the player volume (0..1). */
    val volume: Float get() = if (gainDb < 0) 10f.pow(gainDb / 20f) else 1f

    /** Boost part in millibels, applied through LoudnessEnhancer (has a built-in limiter). */
    val boostMb: Int get() = if (gainDb > 0) (gainDb * 100).toInt() else 0
}

/**
 * Per-track loudness normalisation (like Spotify/YouTube): a constant gain per track so that
 * every track plays at the same integrated loudness. Dynamics inside the track are untouched.
 */
@UnstableApi
object LoudnessNormalizer {
    const val MAX_BOOST_DB = 6f
    const val MAX_CUT_DB = -15f

    /** ReplayGain 2 reference loudness. */
    private const val REPLAYGAIN_REFERENCE_LUFS = -18f

    /**
     * Quiet/Normal only *attenuate* loud tracks (clean, no extra processing — like YouTube).
     * Loud may also boost quiet tracks through LoudnessEnhancer's limiter (like Spotify "Loud").
     */
    fun gainFor(loudnessLufs: Float?, level: NormalizationLevel, source: LoudnessSource = LoudnessSource.PROVIDER): NormalizationGain? {
        val target = level.targetLufs ?: return null
        val lufs = loudnessLufs ?: return null
        val maxBoost = if (level == NormalizationLevel.LOUD) MAX_BOOST_DB else 0f
        return NormalizationGain((target - lufs).coerceIn(MAX_CUT_DB, maxBoost), source)
    }

    /** ReplayGain track gain (dB, to reach -18 LUFS) → measured loudness in LUFS. */
    fun lufsFromReplayGain(trackGainDb: Float): Float = REPLAYGAIN_REFERENCE_LUFS - trackGainDb

    /** Parses "-6.20 dB" style values. */
    fun parseGain(text: String): Float? =
        Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)\s*(?:dB)?\s*$""", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in -60f..60f }

    /** Finds REPLAYGAIN_TRACK_GAIN in Vorbis comments (FLAC/Ogg/Opus) or ID3 TXXX frames (MP3). */
    fun replayGainTrackGain(metadata: Metadata?): Float? {
        metadata ?: return null
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is VorbisComment -> if (e.key.equals("REPLAYGAIN_TRACK_GAIN", true)) parseGain(e.value)?.let { return it }
                is TextInformationFrame -> if (e.id == "TXXX" && e.description.equals("REPLAYGAIN_TRACK_GAIN", true)) {
                    e.values.firstNotNullOfOrNull(::parseGain)?.let { return it }
                }
            }
        }
        return null
    }
}
