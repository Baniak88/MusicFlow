package app.musicflow.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** MusicFlow brand palette. Default experience is the AMOLED-friendly dark theme. */
object MfColors {
    // Flat near-black surfaces; the accent colour is the user's choice (AccentColor, green by default).
    val Background = Color(0xFF0E0E10)
    val BackgroundSecondary = Color(0xFF17181B)
    val SurfaceElevated = Color(0xFF1F2024)
    val SurfaceHighest = Color(0xFF2A2B30)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA1A1AA)
    val Accent = Color(0xFF22C55E)
    val AccentSecondary = Color(0xFF8B5CF6)
    val AccentContainer = Color(0xFF4B2766)
    val OnAccentContainer = Color(0xFFF3DDFF)
    val Outline = Color(0xFF2A2B30)
    /** Top of the app background gradient (light theme only; the dark theme is flat). */
    val BackgroundGlow = Color(0xFF0E0E10)
    /** Bottom dock (mini player + navigation). */
    val Dock = Color(0xFF0A0A0B)
    val Error = Color(0xFFFF6B6B)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)

    val PureBlack = Color(0xFF000000)

    // Light theme
    val LightBackground = Color(0xFFF7F7FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFECECF2)
    val LightText = Color(0xFF0F1115)
    val LightTextSecondary = Color(0xFF555B66)
    val LightAccent = Color(0xFF9B2FD6)
}
