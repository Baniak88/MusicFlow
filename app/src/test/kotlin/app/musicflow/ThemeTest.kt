package app.musicflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import app.musicflow.core.designsystem.theme.MfColors
import app.musicflow.core.designsystem.theme.MusicFlowTheme
import app.musicflow.core.model.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    private fun backgroundFor(mode: ThemeMode, amoled: Boolean = false): Color {
        var bg = Color.Unspecified
        compose.setContent { MusicFlowTheme(themeMode = mode, amoled = amoled) { bg = MaterialTheme.colorScheme.background } }
        compose.waitForIdle()
        return bg
    }

    @Test fun darkThemeUsesBrandColours() {
        assertThat(backgroundFor(ThemeMode.DARK)).isEqualTo(MfColors.Background)
    }

    @Test fun amoledUsesPureBlack() {
        assertThat(backgroundFor(ThemeMode.DARK, amoled = true)).isEqualTo(Color.Black)
    }

    @Test fun lightThemeSwitchesPalette() {
        assertThat(backgroundFor(ThemeMode.LIGHT)).isEqualTo(MfColors.LightBackground)
    }

    @Config(qualifiers = "night")
    @Test fun systemModeFollowsNightConfiguration() {
        assertThat(backgroundFor(ThemeMode.SYSTEM)).isEqualTo(MfColors.Background)
    }

    @Config(qualifiers = "notnight")
    @Test fun systemModeFollowsDayConfiguration() {
        assertThat(backgroundFor(ThemeMode.SYSTEM)).isEqualTo(MfColors.LightBackground)
    }

    @Test fun primaryTextHasAccessibleContrastOnBackground() {
        // WCAG AA: 4.5:1 for body text.
        fun ch(v: Float): Double = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        fun lum(c: Color): Double = 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        fun contrast(a: Color, b: Color): Double {
            val l1 = lum(a); val l2 = lum(b)
            return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
        }
        assertThat(contrast(MfColors.TextPrimary, MfColors.Background)).isAtLeast(4.5)
        assertThat(contrast(MfColors.TextSecondary, MfColors.Background)).isAtLeast(4.5)
        assertThat(contrast(MfColors.TextSecondary, MfColors.SurfaceElevated)).isAtLeast(4.5)
        assertThat(contrast(MfColors.LightText, MfColors.LightBackground)).isAtLeast(4.5)
    }
}
