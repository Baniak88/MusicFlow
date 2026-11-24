package app.musicflow.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextMatchingTest {
    @Test fun normalizeStripsDecorationsCaseAndDiacritics() {
        assertThat(TextMatching.normalize("Café Déjà Vu (Official Video)")).isEqualTo("cafe deja vu")
        assertThat(TextMatching.normalize("Song Title [Lyrics] feat. Someone")).isEqualTo("song title")
    }

    @Test fun trackKeyIgnoresFeaturedArtists() {
        assertThat(TextMatching.trackKey("Levels (Radio Edit)", "Avicii, Etta"))
            .isNotEqualTo(TextMatching.trackKey("Levels", "Avicii"))
        assertThat(TextMatching.trackKey("Levels (Official Audio)", "Avicii & Someone"))
            .isEqualTo(TextMatching.trackKey("levels", "AVICII"))
    }

    @Test fun levenshteinDistance() {
        assertThat(TextMatching.levenshtein("kitten", "sitting")).isEqualTo(3)
        assertThat(TextMatching.levenshtein("", "abc")).isEqualTo(3)
    }

    @Test fun reliableMatchRequiresTitleArtistAndDuration() {
        assertThat(TextMatching.isReliableMatch("Strobe", "deadmau5", 600_000, "Strobe (Official Audio)", "deadmau5", 602_000)).isTrue()
        assertThat(TextMatching.isReliableMatch("Strobe", "deadmau5", 600_000, "Strobe", "deadmau5", 200_000)).isFalse()
        assertThat(TextMatching.isReliableMatch("Strobe", "deadmau5", null, "Ghosts n Stuff", "deadmau5", null)).isFalse()
        assertThat(TextMatching.isReliableMatch("Strobe", "deadmau5", null, "Strobe", "Tiesto", null)).isFalse()
    }
}
