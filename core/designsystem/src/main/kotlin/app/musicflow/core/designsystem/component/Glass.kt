package app.musicflow.core.designsystem.component

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.SolidColor
import app.musicflow.core.designsystem.theme.LocalAccent
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.sin

/**
 * MusicFlow surfaces: flat dark cards on a near-black background, with the accent colour the
 * user picked (Settings → Appearance; green by default). The historical names (Violet, Magenta,
 * Orchid, PlayBrush) now all mean "the accent".
 */
object Glass {
    val Fill = Color(0xFF1B1C20)
    val FillStrong = Color(0xFF24262B)
    val Stroke = Color.White.copy(alpha = 0.06f)
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalAccent.current
    val Magenta: Color @Composable @ReadOnlyComposable get() = LocalAccent.current
    val Orchid: Color @Composable @ReadOnlyComposable get() = LocalAccent.current
    val Ember = Color(0xFFF97316)
    val Ocean = Color(0xFF3B5BDB)
    /** Play button / selected chip fill: the accent. */
    val PlayBrush: Brush @Composable @ReadOnlyComposable get() = SolidColor(LocalAccent.current)
    /** Content (icons, text) drawn on the accent. */
    val OnAccent: Color @Composable @ReadOnlyComposable get() = app.musicflow.core.designsystem.theme.onAccent(LocalAccent.current)
    val PanelShape = RoundedCornerShape(20.dp)
}

/** Flat dark card (the former frosted panel): solid fill, a barely visible hairline. */
fun Modifier.glass(shape: Shape = Glass.PanelShape, strong: Boolean = false): Modifier =
    this.clip(shape).background(if (strong) Glass.FillStrong else Glass.Fill, shape).border(BorderStroke(1.dp, Glass.Stroke), shape)

/**
 * Dark backdrop with large soft colour glows (violet, magenta, ember, ocean), like light behind
 * frosted glass. With [artworkUrl], the artwork itself is shown blurred behind it (Android 12+;
 * older versions keep just the glows).
 */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, artworkUrl: String? = null, dim: Float = 0.55f, content: @Composable BoxScope.() -> Unit = {}) {
    val accent = LocalAccent.current
    Box(modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        // A faint accent light from the top, then flat near-black.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to accent.copy(alpha = 0.16f), 0.45f to Color.Transparent)))
        if (artworkUrl != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(
                model = artworkUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(80.dp),
                alpha = 0.28f,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = dim * 0.3f), Color.Black.copy(alpha = dim)))))
        // Always dark glass: text and icons default to white, whatever the app theme.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) { content() }
    }
}

/** Round frosted icon button (top bars, previous/next). */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 22.dp, enabled: Boolean = true) {
    Box(
        modifier.size(size).glass(CircleShape, strong = true).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(iconSize), tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f)) }
}

/** The gradient play/pause button with a soft glass ring. */
@Composable
fun GradientPlayButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 76.dp, ring: Boolean = true) {
    Box(
        modifier.size(size).then(if (ring) Modifier.glass(CircleShape, strong = true) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (ring) size * 0.78f else size).clip(CircleShape).background(Glass.PlayBrush).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription, Modifier.size(size * 0.36f), tint = Glass.OnAccent) }
    }
}

/**
 * Seek bar drawn as audio-style bars. The bar heights are a decorative pattern derived from the
 * track id (MusicFlow does not analyse the audio); played part in the accent gradient.
 * Tap or drag to seek.
 */
@Composable
fun WaveformSeekBar(fraction: Float, seed: String?, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, bars: Int = 64) {
    val accent = LocalAccent.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown by animateFloatAsState(scrub ?: fraction, label = "wave")
    val heights = remember(seed, bars) {
        val s = (seed ?: "musicflow").hashCode()
        List(bars) { i ->
            val a = sin(i * 0.55 + s % 7) * 0.5 + sin(i * 0.21 + s % 13) * 0.35 + sin(i * 1.7 + s % 5) * 0.15
            (0.22 + abs(a) * 0.78).toFloat().coerceIn(0.15f, 1f)
        }
    }
    Canvas(
        modifier.fillMaxWidth().height(44.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { scrub?.let(onSeek); scrub = null },
                    onDragCancel = { scrub = null },
                    onHorizontalDrag = { c, _ -> scrub = (c.position.x / size.width).coerceIn(0f, 1f) },
                )
            },
    ) {
        val gap = size.width / bars
        val w = (gap * 0.45f).coerceAtLeast(2f)
        val played = SolidColor(accent)
        heights.forEachIndexed { i, h ->
            val x = i * gap + (gap - w) / 2
            val barH = size.height * h
            val top = (size.height - barH) / 2
            val isPlayed = (i + 0.5f) / bars <= shown
            if (isPlayed) drawRoundRect(played, Offset(x, top), Size(w, barH), CornerRadius(w / 2))
            else drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(x, top), Size(w, barH), CornerRadius(w / 2))
        }
    }
}
