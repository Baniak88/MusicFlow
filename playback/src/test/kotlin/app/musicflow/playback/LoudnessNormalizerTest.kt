package app.musicflow.playback

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.musicflow.core.model.NormalizationLevel
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoudnessNormalizerTest {
    private val n = LoudnessNormalizer

    @Test fun gainBringsTracksToTheTargetLevel() {
        // The user-reported D-Bomb track measures -15.17 LUFS: quieter than target → untouched on Normal.
        assertThat(n.gainFor(-15.17f, NormalizationLevel.NORMAL)!!.gainDb).isWithin(0.01f).of(0f)
        assertThat(n.gainFor(-15.17f, NormalizationLevel.LOUD)!!.gainDb).isWithin(0.01f).of(4.17f)
        assertThat(n.gainFor(-8f, NormalizationLevel.NORMAL)!!.gainDb).isWithin(0.01f).of(-6f)   // loud master → cut
        assertThat(n.gainFor(-14f, NormalizationLevel.LOUD)!!.gainDb).isWithin(0.01f).of(3f)
        assertThat(n.gainFor(-10f, NormalizationLevel.NORMAL)!!.gainDb).isWithin(0.01f).of(-4f)
        assertThat(n.gainFor(-14f, NormalizationLevel.QUIET)!!.gainDb).isWithin(0.01f).of(-5f)
    }

    @Test fun offOrUnknownLoudnessLeavesTrackUntouched() {
        assertThat(n.gainFor(-8f, NormalizationLevel.OFF)).isNull()
        assertThat(n.gainFor(null, NormalizationLevel.NORMAL)).isNull()
    }

    @Test fun gainIsLimitedAndSplitIntoCutAndLimitedBoost() {
        assertThat(n.gainFor(-40f, NormalizationLevel.LOUD)!!.gainDb).isEqualTo(6f)   // max +6 dB boost
        assertThat(n.gainFor(5f, NormalizationLevel.QUIET)!!.gainDb).isEqualTo(-15f)  // max -15 dB cut
        val cut = n.gainFor(-8f, NormalizationLevel.NORMAL)!!
        assertThat(cut.volume).isWithin(0.001f).of(0.501f)   // -6 dB
        assertThat(cut.boostMb).isEqualTo(0)
        val noBoost = n.gainFor(-17f, NormalizationLevel.NORMAL)!!
        assertThat(noBoost.boostMb).isEqualTo(0)            // Normal never boosts (no limiter in the path)
        val boost = n.gainFor(-17f, NormalizationLevel.LOUD)!!
        assertThat(boost.volume).isEqualTo(1f)
        assertThat(boost.boostMb).isEqualTo(600)            // +6 dB via LoudnessEnhancer (limited), Loud only
    }

    @Test fun replayGainFromVorbisAndId3() {
        val vorbis = Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"), VorbisComment("ARTIST", "x"))
        assertThat(n.replayGainTrackGain(vorbis)).isWithin(0.001f).of(-6.2f)
        val id3 = Metadata(TextInformationFrame("TXXX", "replaygain_track_gain", ImmutableList.of("+2,5 dB")))
        assertThat(n.replayGainTrackGain(id3)).isWithin(0.001f).of(2.5f)
        assertThat(n.replayGainTrackGain(Metadata(VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-3 dB")))).isNull()
        assertThat(n.replayGainTrackGain(null)).isNull()
        // RG2 reference is -18 LUFS: a -6.2 dB track gain means the track measures -11.8 LUFS.
        assertThat(n.lufsFromReplayGain(-6.2f)).isWithin(0.001f).of(-11.8f)
        assertThat(n.parseGain("garbage")).isNull()
    }
}
