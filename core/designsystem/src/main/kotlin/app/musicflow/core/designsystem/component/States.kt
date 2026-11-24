package app.musicflow.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.musicflow.core.designsystem.R

/** Animated shimmer used by skeleton placeholders. */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(0f, 1400f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    background(Brush.linearGradient(listOf(base, hi, base), start = Offset(x - 400f, 0f), end = Offset(x, 200f)))
}

@Composable
fun SkeletonCardRow(modifier: Modifier = Modifier, count: Int = 4) {
    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(count) {
            Column {
                Box(Modifier.size(152.dp).clip(MaterialTheme.shapes.medium).shimmer())
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(110.dp).height(12.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(70.dp).height(10.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
            }
        }
    }
}

@Composable
fun SkeletonTrackRows(modifier: Modifier = Modifier, count: Int = 6) {
    Column(modifier) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.small).shimmer())
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.35f).height(10.dp).clip(MaterialTheme.shapes.extraSmall).shimmer())
                }
            }
        }
    }
}

@Composable
fun MessageState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Rounded.LibraryMusic,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        body?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) = MessageState(
    title = stringResource(R.string.mf_error_title),
    body = message,
    icon = Icons.Rounded.CloudOff,
    actionLabel = stringResource(R.string.mf_retry),
    onAction = onRetry,
    modifier = modifier,
)
