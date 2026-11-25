package app.musicflow.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** Animated "now playing" bars; static when [playing] is false. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "eq")
    val speeds = listOf(520, 380, 610, 450, 560)
    val heights = speeds.mapIndexed { i, ms ->
        val v by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "b$i")
        if (playing) v else listOf(0.4f, 0.7f, 0.5f, 0.8f, 0.45f)[i]
    }
    Canvas(modifier.size(28.dp, 24.dp)) {
        val bar = size.width / (heights.size * 2 - 1)
        heights.forEachIndexed { i, h ->
            val hh = size.height * h
            drawRoundRect(color, Offset(i * bar * 2, (size.height - hh) / 2), Size(bar, hh), CornerRadius(bar / 2))
        }
    }
}
