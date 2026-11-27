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
    // Sharper artwork where the host has it (YouTube video frames: 1280×720 without the black
    // bars of the 4:3 "hqdefault"); falls back to the original URL if that size does not exist.
    var src by remember(url) { mutableStateOf(ArtworkUrls.hd(url) ?: url) }
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
        if (src != null) {
            AsyncImage(
                model = src,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { st ->
                    if (st is AsyncImagePainter.State.Error && src != url) src = url
                    else failed = st is AsyncImagePainter.State.Error
                },
            )
        }
    }
}

/** Higher-resolution variants of artwork URLs (pure; unit-tested). */
object ArtworkUrls {
    private val ytFrame = Regex("""^https://i\d?\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{6,})/(?:hq|mq|sd)?default\.(?:jpg|webp)(?:\?.*)?$""")

    /** A sharper URL for the same picture, or null when there is none to try. */
    fun hd(url: String?): String? {
        url ?: return null
        ytFrame.matchEntire(url)?.let { return "https://i.ytimg.com/vi/${it.groupValues[1]}/hq720.jpg" }
        return null
    }
}
