package app.musicflow.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SleepTimerTest {
    @Test fun expiresOnceAtTheRightTime() {
        val t = SleepTimer()
        t.start(minutes = 10, now = 0)
        assertThat(t.consumeIfExpired(now = 599_999)).isFalse()
        assertThat(t.consumeIfExpired(now = 600_000)).isTrue()
        assertThat(t.state.value).isEqualTo(SleepTimerState.Off)
        assertThat(t.consumeIfExpired(now = 700_000)).isFalse()
    }

    @Test fun endOfTrackAndCancel() {
        val t = SleepTimer()
        t.endOfTrack()
        assertThat(t.consumeEndOfTrack()).isTrue()
        assertThat(t.consumeEndOfTrack()).isFalse()
        t.start(5); t.cancel()
        assertThat(t.state.value).isEqualTo(SleepTimerState.Off)
        t.start(0)
        assertThat(t.state.value).isEqualTo(SleepTimerState.Off)
    }
}
