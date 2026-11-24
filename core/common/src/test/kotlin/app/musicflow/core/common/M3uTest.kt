package app.musicflow.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uTest {
    @Test fun parsesExtendedM3uPreservingOrder() {
        val text = """
            #EXTM3U
            #PLAYLIST:Road trip
            #EXTINF:215,Daft Punk - One More Time
            https://music.youtube.com/watch?v=FGBhQbmPwH8
            #EXTINF:-1,Untitled
            /sdcard/Music/a.mp3
            plain.flac
        """.trimIndent()
        val p = M3u.parse(text)
        assertThat(p.name).isEqualTo("Road trip")
        assertThat(p.entries.map { it.location }).containsExactly(
            "https://music.youtube.com/watch?v=FGBhQbmPwH8", "/sdcard/Music/a.mp3", "plain.flac").inOrder()
        assertThat(p.entries[0].artist).isEqualTo("Daft Punk")
        assertThat(p.entries[0].title).isEqualTo("One More Time")
        assertThat(p.entries[0].durationSeconds).isEqualTo(215)
        assertThat(p.entries[1].durationSeconds).isNull()
        assertThat(p.entries[2].title).isNull()
    }

    @Test fun writeThenParseRoundTrips() {
        val original = M3uPlaylist("Mix", listOf(M3uEntry("a", "T1", "A1", 10), M3uEntry("b", "T2", null, null)))
        val parsed = M3u.parse(M3u.write(original))
        assertThat(parsed).isEqualTo(original)
    }

    @Test fun formatsAndParsesDurations() {
        assertThat(Formatting.duration(225_000)).isEqualTo("3:45")
        assertThat(Formatting.duration(3_723_000)).isEqualTo("1:02:03")
        assertThat(Formatting.parseDuration("1:02:03")).isEqualTo(3_723_000)
        assertThat(Formatting.parseDuration("abc")).isNull()
    }
}
