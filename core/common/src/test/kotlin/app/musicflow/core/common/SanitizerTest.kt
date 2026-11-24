package app.musicflow.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SanitizerTest {
    @Test fun redactsSignedUrlsTokensCookiesAndEmails() {
        val raw = """GET https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&sig=SECRET&n=abc
            Authorization: Bearer abc.def.ghi
            Cookie: SID=topsecret; HSID=x
            https://music.youtube.com/watch?v=x&key=AIzaSyFAKEKEY
            contact me@example.org""".trimIndent()
        val clean = Sanitizer.sanitize(raw)
        assertThat(clean).doesNotContain("SECRET")
        assertThat(clean).doesNotContain("topsecret")
        assertThat(clean).doesNotContain("abc.def.ghi")
        assertThat(clean).doesNotContain("AIzaSyFAKEKEY")
        assertThat(clean).doesNotContain("me@example.org")
        assertThat(clean).contains("<stream-host>")
    }

    @Test fun diagnosticsLogSanitizesOnWriteAndCaps() {
        val log = DiagnosticsLog()
        log.i("t", "token=abcdef")
        assertThat(log.events.value.single().message).isEqualTo("token=<redacted>")
        repeat(400) { log.d("t", "e$it") }
        assertThat(log.events.value).hasSize(300)
        assertThat(log.export("MusicFlow")).contains("e399")
    }
}
