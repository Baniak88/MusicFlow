package app.musicflow.core.designsystem.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a dominant, readable colour from the current artwork for the player background.
 * Colours are darkened so white text always keeps AA contrast.
 */
@Composable
fun rememberArtworkColor(url: String?, fallback: Color, enabled: Boolean = true): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(fallback) }
    LaunchedEffect(url, enabled) {
        if (!enabled || url == null) { color = fallback; return@LaunchedEffect }
        val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
        val bitmap: Bitmap = result.image.toBitmap()
        color = withContext(Dispatchers.Default) {
            val p = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = p.darkVibrantSwatch ?: p.vibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
            swatch?.rgb?.let { Color(darkenForContrast(it)) } ?: fallback
        }
    }
    return color
}

/** Lowers lightness until white text has a contrast ratio of at least 4.5:1. */
fun darkenForContrast(argb: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    var c = argb
    while (ColorUtils.calculateContrast(Color.White.toArgb(), c or 0xFF000000.toInt()) < 4.5 && hsl[2] > 0.05f) {
        hsl[2] -= 0.04f
        c = ColorUtils.HSLToColor(hsl)
    }
    return c
}
