package app.musicflow.core.designsystem

import app.musicflow.core.designsystem.component.ArtworkUrls
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkUrlsTest {
    @Test fun youTubeFramesGetTheHdVersion() {
        assertThat(ArtworkUrls.hd("https://i.ytimg.com/vi/oCZugu1ea18/hqdefault.jpg?sqp=abc")).isEqualTo("https://i.ytimg.com/vi/oCZugu1ea18/hq720.jpg")
        assertThat(ArtworkUrls.hd("https://i3.ytimg.com/vi_webp/oCZugu1ea18/mqdefault.webp")).isEqualTo("https://i.ytimg.com/vi/oCZugu1ea18/hq720.jpg")
    }

    @Test fun otherUrlsHaveNoAlternative() {
        assertThat(ArtworkUrls.hd("https://lh3.googleusercontent.com/abc=w1080-h1080-l90-rj")).isNull()
        assertThat(ArtworkUrls.hd("https://i.ytimg.com/vi/oCZugu1ea18/hq720.jpg")).isNull()
        assertThat(ArtworkUrls.hd(null)).isNull()
    }
}
