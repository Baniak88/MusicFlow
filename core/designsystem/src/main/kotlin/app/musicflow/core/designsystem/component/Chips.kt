package app.musicflow.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.musicflow.core.designsystem.theme.LocalMfExtras

/** Pill filter chip: filled accent when selected, outlined otherwise. */
@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = CircleShape
    androidx.compose.foundation.layout.Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape),
            )
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * App background. Dark: the "glass" backdrop — near-black with soft ember, magenta, violet and
 * ocean glows. Light: a soft tint at the top fading into the base colour.
 */
@Composable
fun Modifier.appBackground(): Modifier {
    val extras = LocalMfExtras.current
    val base = MaterialTheme.colorScheme.background
    if (!extras.isDark) return this.background(Brush.verticalGradient(0f to extras.gradientTop, 0.45f to base, 1f to base))
    return this.background(base).drawBehind {
        fun glow(color: androidx.compose.ui.graphics.Color, cx: Float, cy: Float, r: Float, a: Float) {
            val c = androidx.compose.ui.geometry.Offset(size.width * cx, size.height * cy)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), androidx.compose.ui.graphics.Color.Transparent), center = c, radius = size.maxDimension * r), radius = size.maxDimension * r, center = c)
        }
        glow(Glass.Ember, 0.0f, 0.12f, 0.38f, 0.32f)
        glow(Glass.Magenta, 1.0f, 0.06f, 0.36f, 0.30f)
        glow(Glass.Violet, 0.75f, 0.55f, 0.50f, 0.26f)
        glow(Glass.Ocean, 0.05f, 0.85f, 0.42f, 0.28f)
    }
}
