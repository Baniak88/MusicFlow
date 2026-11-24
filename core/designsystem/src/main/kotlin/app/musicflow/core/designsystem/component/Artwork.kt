package app.musicflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/** Square artwork with a tasteful placeholder while loading or when no artwork exists. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    contentDescription: String? = null,
    iconSize: Dp = 24.dp,
) {
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHighest),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { failed = it is AsyncImagePainter.State.Error },
            )
        }
    }
}
