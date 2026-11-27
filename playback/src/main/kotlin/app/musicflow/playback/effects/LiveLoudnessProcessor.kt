package app.musicflow.playback.effects

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Loudness normalisation for live radio. Stations carry no loudness data (unlike YouTube
 * tracks), and broadcasters master very differently, so this measures the stream itself and
 * slowly steers it to the same target as songs.
 *
 * * Measurement: ITU-R BS.1770 / EBU R128 — K-weighting, 400 ms blocks, absolute gate at
 *   −70 LUFS and a relative-style gate ignoring blocks 10 dB below the running level
 *   (pauses, talk breaks).
 * * Running level: exponential average of the gated blocks (~6 s time constant); faster
 *   during the first seconds of a station.
 * * Gain: target − level, limited to [−15, +9] dB, changed at most [SLEW_DB_PER_S] (no
 *   pumping). Boosts go through a soft limiter so peaks never clip.
 *
 * Only active while [live] is true (the service sets it for radio items); otherwise it passes
 * audio through untouched. 16-bit PCM only (the app disables float output).
 */
@UnstableApi
class LiveLoudnessProcessor : BaseAudioProcessor() {
    /** Set by the service: the current item is a live stream. */
    @Volatile var live: Boolean = false

    /** Target loudness (LUFS), or null when normalisation is off. */
    @Volatile var targetLufs: Float? = -14f

    /** Current applied gain in dB (for diagnostics / the player's info line). */
    @Volatile var currentGainDb: Float = 0f
        private set

    /** Measured running loudness (LUFS), null until enough audio was heard. */
    @Volatile var measuredLufs: Float? = null
        private set

    private var channels = 2
    private var sampleRate = 48_000
    private var k = KWeighting(48_000, 2)
    private var blockSamples = 19_200 // frames per 400 ms block
    private var blockFrames = 0
    private var blockSum = 0.0
    private var level: Double? = null // running loudness, LUFS
    private var heardMs = 0L
    private var gainDb = 0.0
    private var desiredDb = 0.0
    private var wasLive = false
    private var samples = DoubleArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        k = KWeighting(sampleRate, channels)
        blockSamples = sampleRate * 4 / 10
        resetMeasurement()
    }

    override fun onReset() {
        resetMeasurement()
        gainDb = 0.0
        currentGainDb = 0f
    }

    private fun resetMeasurement() {
        blockFrames = 0
        blockSum = 0.0
        level = null
        measuredLufs = null
        heardMs = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val target = targetLufs
        val active = live && target != null
        if (active != wasLive) {
            wasLive = active
            resetMeasurement() // a new station: measure from scratch
        }
        val frameBytes = 2 * channels
        val frames = remaining / frameBytes
        val maxStep = SLEW_DB_PER_S / sampleRate // dB per frame
        for (f in 0 until frames) {
            var squares = 0.0
            if (samples.size != channels) samples = DoubleArray(channels)
            for (c in 0 until channels) {
                val s = input.short.toDouble() / 32768.0
                samples[c] = s
                if (active) {
                    val w = k.process(c, s)
                    squares += w * w // channel weights are 1.0 for L/R (BS.1770)
                }
            }
            if (active) {
                blockSum += squares
                if (++blockFrames >= blockSamples) {
                    onBlock(blockSum / blockFrames, target!!)
                    blockFrames = 0
                    blockSum = 0.0
                }
            }
            val wanted = if (active) desiredDb else 0.0
            if (gainDb != wanted) {
                val fast = if (active && heardMs < WARMUP_MS) 4.0 else 1.0
                val step = maxStep * fast
                gainDb = if (abs(wanted - gainDb) <= step) wanted else gainDb + if (wanted > gainDb) step else -step
            }
            val g = if (gainDb == 0.0) 1.0 else 10.0.pow(gainDb / 20.0)
            for (c in 0 until channels) {
                var y = samples[c] * g
                if (gainDb > 0) y = softLimit(y)
                out.putShort((y.coerceIn(-1.0, 32767.0 / 32768.0) * 32768.0).toInt().toShort())
            }
        }
        // Keep any partial frame for the next call (never happens with whole-frame buffers).
        while (input.hasRemaining()) out.put(input.get())
        currentGainDb = gainDb.toFloat()
        out.flip()
    }

    private fun onBlock(meanSquare: Double, target: Float) {
        heardMs += 400
        val blockLufs = if (meanSquare <= 0) -120.0 else -0.691 + 10 * log10(meanSquare)
        if (blockLufs < ABSOLUTE_GATE) return
        val current = level
        if (current != null && blockLufs < current - RELATIVE_GATE_DB) return // pauses / speech dips
        val alpha = if (heardMs < WARMUP_MS) 0.3 else BLOCK_ALPHA
        val next = if (current == null) blockLufs else current + alpha * (blockLufs - current)
        level = next
        measuredLufs = next.toFloat()
        desiredDb = (target - next).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
    }

    /** Transparent below 0.8, then a smooth curve that approaches (never reaches) full scale. */
    private fun softLimit(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = a - KNEE
        val room = 1.0 - KNEE
        val y = KNEE + room * (over / (over + room))
        return if (x < 0) -y else y
    }

    /** BS.1770 K-weighting: high-shelf (+4 dB above ~1.7 kHz) then a 38 Hz high-pass, per channel. */
    private class KWeighting(fs: Int, channels: Int) {
        private val s1: DoubleArray = shelf(fs)
        private val s2: DoubleArray = highPass(fs)
        private val z = Array(channels) { DoubleArray(4) }

        private companion object {
            fun shelf(fs: Int): DoubleArray {
                val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
                val kk = tan(PI * f0 / fs)
                val vh = 10.0.pow(g / 20.0)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + kk / q + kk * kk
                return doubleArrayOf(
                    (vh + vb * kk / q + kk * kk) / a0, 2 * (kk * kk - vh) / a0, (vh - vb * kk / q + kk * kk) / a0,
                    2 * (kk * kk - 1) / a0, (1 - kk / q + kk * kk) / a0,
                )
            }

            fun highPass(fs: Int): DoubleArray {
                val f0 = 38.13547087602444; val q = 0.5003270373238773
                val kk = tan(PI * f0 / fs)
                val a0 = 1 + kk / q + kk * kk
                return doubleArrayOf(1.0, -2.0, 1.0, 2 * (kk * kk - 1) / a0, (1 - kk / q + kk * kk) / a0)
            }
        }

        fun process(c: Int, x: Double): Double {
            val st = z[c]
            // Direct form II transposed, two stages.
            val y1 = s1[0] * x + st[0]
            st[0] = s1[1] * x - s1[3] * y1 + st[1]
            st[1] = s1[2] * x - s1[4] * y1
            val y2 = s2[0] * y1 + st[2]
            st[2] = s2[1] * y1 - s2[3] * y2 + st[3]
            st[3] = s2[2] * y1 - s2[4] * y2
            return y2
        }
    }

    companion object {
        const val MAX_BOOST_DB = 9.0
        const val MAX_CUT_DB = -15.0
        /** Gain change limit after warm-up (dB/s); 4× faster during the first seconds. */
        const val SLEW_DB_PER_S = 1.5
        private const val WARMUP_MS = 4_000L
        private const val ABSOLUTE_GATE = -70.0
        private const val RELATIVE_GATE_DB = 10.0
        /** ~6 s time constant with 400 ms blocks. */
        private const val BLOCK_ALPHA = 0.065
        private const val KNEE = 0.8
    }
}
