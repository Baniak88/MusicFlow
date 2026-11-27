package app.musicflow.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.musicflow.core.model.ThemeMode

/** Text/icon colour on the accent: black on light accents (green, orange), white on dark ones. */
fun onAccent(accent: Color): Color = if (accent.luminance() > 0.35f) Color.Black else Color.White

/** The accent mixed into the dark background ([amount] of accent): tinted cards, mini player, tonal buttons. */
fun accentTint(accent: Color, amount: Float, base: Color = MfColors.Background): Color =
    androidx.compose.ui.graphics.lerp(base, accent, amount)

fun mfDarkScheme(amoled: Boolean, accent: Color = MfColors.Accent): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent(accent),
    primaryContainer = accentTint(accent, 0.28f),
    onPrimaryContainer = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.55f),
    secondary = accent,
    onSecondary = onAccent(accent),
    secondaryContainer = accentTint(accent, 0.22f), // tonal buttons, selected tab
    onSecondaryContainer = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.6f),
    tertiary = Color(0xFF22D3EE),
    background = if (amoled) MfColors.PureBlack else MfColors.Background,
    onBackground = MfColors.TextPrimary,
    surface = if (amoled) MfColors.PureBlack else MfColors.Background,
    onSurface = MfColors.TextPrimary,
    surfaceVariant = MfColors.SurfaceElevated,
    onSurfaceVariant = MfColors.TextSecondary,
    surfaceContainerLowest = if (amoled) MfColors.PureBlack else MfColors.Background,
    surfaceContainerLow = if (amoled) Color(0xFF0B0C0F) else MfColors.BackgroundSecondary,
    surfaceContainer = MfColors.BackgroundSecondary,
    surfaceContainerHigh = MfColors.SurfaceElevated,
    surfaceContainerHighest = MfColors.SurfaceHighest,
    outline = MfColors.Outline,
    outlineVariant = Color(0xFF1F232A),
    error = MfColors.Error,
    onError = Color.Black,
    inverseSurface = Color(0xFFE8E8EE),
    inverseOnSurface = MfColors.Background,
)

fun mfLightScheme(): ColorScheme = lightColorScheme(
    primary = MfColors.LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E0FF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = MfColors.AccentSecondary,
    background = MfColors.LightBackground,
    onBackground = MfColors.LightText,
    surface = MfColors.LightBackground,
    onSurface = MfColors.LightText,
    surfaceVariant = MfColors.LightSurfaceVariant,
    onSurfaceVariant = MfColors.LightTextSecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = MfColors.LightSurface,
    surfaceContainer = Color(0xFFF1F1F6),
    surfaceContainerHigh = MfColors.LightSurfaceVariant,
    surfaceContainerHighest = Color(0xFFE2E2EA),
    outline = Color(0xFFC9CAD3),
    error = Color(0xFFD32F2F),
)

val MfShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The user's accent colour (Settings → Appearance), for components that draw it directly. */
val LocalAccent = staticCompositionLocalOf { MfColors.Accent }

@Immutable
data class MfExtras(val isDark: Boolean, val gradientTop: Color, val success: Color, val warning: Color, val dock: Color)

val LocalMfExtras = staticCompositionLocalOf { MfExtras(true, MfColors.BackgroundGlow, MfColors.Success, MfColors.Warning, MfColors.Dock) }

@Composable
fun MusicFlowTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    amoled: Boolean = false,
    dynamicColor: Boolean = false,
    accent: Color = MfColors.Accent,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val context = LocalContext.current
    val scheme = when {
        // The dark theme uses the accent the user picked; wallpaper colours (Material You) apply to
        // the light theme only.
        dark -> mfDarkScheme(amoled, accent)
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        else -> mfLightScheme().copy(primary = accent, onPrimary = onAccent(accent), secondary = accent)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAccent provides (if (!dark && dynamicColor && Build.VERSION.SDK_INT >= 31) scheme.primary else accent),
        LocalMfExtras provides MfExtras(
            isDark = dark,
            gradientTop = if (dark) (if (amoled) Color.Black else MfColors.BackgroundGlow) else scheme.primaryContainer,
            success = MfColors.Success,
            warning = MfColors.Warning,
            dock = if (dark) (if (amoled) Color(0xFF0C0A0F) else MfColors.Dock) else scheme.surfaceContainerHigh,
        ),
    ) {
        MaterialTheme(colorScheme = scheme, typography = MfTypography, shapes = MfShapes, content = content)
    }
}
