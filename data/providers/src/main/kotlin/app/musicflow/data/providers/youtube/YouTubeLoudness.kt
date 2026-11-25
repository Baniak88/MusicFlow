package app.musicflow.data.providers.youtube

import org.schabi.newpipe.extractor.stream.StreamExtractor

/**
 * Reads YouTube's measured track loudness from the player response NewPipe already fetched
 * (no extra request). YouTube reports `playerConfig.audioConfig.trackAbsoluteLoudnessLkfs`
 * (integrated loudness, LUFS) and per-format `loudnessDb` relative to a -14 LUFS target.
 *
 * NewPipe does not expose these fields, so the private `playerResponse` is read reflectively.
 * Any failure (field renamed in a future extractor version, missing data) returns `null`:
 * normalisation is then simply not applied to that track.
 */
object YouTubeLoudness {
    private const val YOUTUBE_TARGET_LUFS = -14f

    fun read(extractor: StreamExtractor): Float? = runCatching {
        val field = generateSequence<Class<*>>(extractor.javaClass) { it.superclass }
            .firstNotNullOfOrNull { c -> runCatching { c.getDeclaredField("playerResponse") }.getOrNull() }
            ?: return null
        field.isAccessible = true
        fromPlayerResponse(field.get(extractor) as? Map<*, *> ?: return null)
    }.getOrNull()

    fun fromPlayerResponse(response: Map<*, *>): Float? {
        val audio = (response["playerConfig"] as? Map<*, *>)?.get("audioConfig") as? Map<*, *>
        number(audio?.get("trackAbsoluteLoudnessLkfs"))?.let { return it }
        number(audio?.get("perceptualLoudnessDb"))?.let { return it }
        val formats = ((response["streamingData"] as? Map<*, *>)?.get("adaptiveFormats") as? List<*>).orEmpty()
        val relative = formats.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("loudnessDb")?.let(::number) } ?: return null
        return YOUTUBE_TARGET_LUFS + relative
    }

    private fun number(v: Any?): Float? = (v as? Number)?.toFloat()?.takeIf { it.isFinite() && it in -70f..10f }
}
