package app.musicflow.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.musicflow.playback.effects.LiveLoudnessProcessor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveLoudnessTest {
    private val rate = 48_000

    private fun processor(live: Boolean, target: Float? = -14f) = LiveLoudnessProcessor().apply {
        this.live = live
        targetLufs = target
        configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    /** Feeds [seconds] of a 1 kHz stereo sine at [amplitude]; returns the RMS (dBFS) of the last second of output. */
    private fun run(p: LiveLoudnessProcessor, amplitude: Double, seconds: Int): Double {
        var t = 0L
        var lastSecondSquares = 0.0
        var lastSecondCount = 0
        val chunk = 4_800 // 100 ms
        repeat(seconds * 10) { n ->
            val buf = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(chunk) {
                val v = (amplitude * sin(2 * PI * 1000 * t++ / rate) * 32767).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
            }
            buf.flip()
            p.queueInput(buf)
            val out = p.output.order(ByteOrder.LITTLE_ENDIAN)
            while (out.remaining() >= 2) {
                val s = out.short / 32768.0
                if (n >= seconds * 10 - 10) { lastSecondSquares += s * s; lastSecondCount++ }
            }
        }
        return 10 * log10(lastSecondSquares / lastSecondCount)
    }

    @Test fun loudAndQuietStationsEndAtTheSameLevel() {
        val loud = run(processor(live = true), amplitude = 0.9, seconds = 40)
        val quiet = run(processor(live = true), amplitude = 0.08, seconds = 40)
        // Input differs by 21 dB; after levelling the difference is small.
        assertThat(kotlin.math.abs(loud - quiet)).isLessThan(2.5)
        // A 1 kHz sine at the target reads about target − 0.7 dB RMS per channel (K-weighting ≈ +0.7 dB, two channels).
        assertThat(loud).isWithin(2.0).of(-14.0 - 3.0 - 0.7)
    }

    @Test fun gainIsLimitedAndPeaksNeverClip() {
        val p = processor(live = true)
        val veryQuiet = run(p, amplitude = 0.005, seconds = 40)
        assertThat(p.currentGainDb).isWithin(0.01f).of(LiveLoudnessProcessor.MAX_BOOST_DB.toFloat())
        assertThat(veryQuiet).isLessThan(-30.0)
    }

    @Test fun songsAndDisabledNormalisationPassThrough() {
        val input = 20 * log10(0.5 / sqrt(2.0))
        assertThat(run(processor(live = false), amplitude = 0.5, seconds = 3)).isWithin(0.1).of(input)
        assertThat(run(processor(live = true, target = null), amplitude = 0.5, seconds = 3)).isWithin(0.1).of(input)
    }
}
