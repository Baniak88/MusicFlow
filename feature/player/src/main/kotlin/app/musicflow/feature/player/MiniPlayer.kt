package app.musicflow.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.core.designsystem.theme.rememberArtworkColor
import app.musicflow.playback.engine.Progress

/** Persistent mini player shown above the bottom navigation. Swipe sideways to change track. */
@Composable
fun MiniPlayer(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val progress by vm.progress.collectAsStateWithLifecycle(Progress(0, 0, null))
    val useArt by vm.artworkColors.collectAsStateWithLifecycle()
    val tint = rememberArtworkColor(track.artworkUrl, MaterialTheme.colorScheme.surfaceContainerHigh, enabled = useArt)
    var drag by remember { mutableFloatStateOf(0f) }

    Surface(
        color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerHigh, tint, 0.55f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onExpand)
            .testTag("mini_player")
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (drag < -120) vm.next() else if (drag > 120) vm.previous(); drag = 0f },
                    onHorizontalDrag = { _, d -> drag += d },
                )
            },
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUrl, Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                Spacer(Modifier.width(12.dp))
                AnimatedContent(track, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mini", modifier = Modifier.weight(1f)) { t ->
                    Column {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = androidx.compose.ui.graphics.Color.White)
                        Text(t.artistLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f))
                    }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.isBuffering && state.playWhenReady) CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                    IconButton(onClick = vm::togglePlay) {
                        Icon(
                            if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                            tint = androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
                IconButton(onClick = vm::next, enabled = state.hasNext) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), tint = androidx.compose.ui.graphics.Color.White.copy(alpha = if (state.hasNext) 1f else 0.4f))
                }
            }
            val duration = progress.durationMs ?: state.durationMs
            LinearProgressIndicator(
                progress = { if (duration != null && duration > 0) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp),
                color = androidx.compose.ui.graphics.Color.White,
                trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
                drawStopIndicator = {},
            )
        }
    }
}
