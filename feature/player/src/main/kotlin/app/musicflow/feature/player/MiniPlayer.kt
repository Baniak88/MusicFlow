package app.musicflow.feature.player

import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.playback.engine.Progress

/**
 * Mini player row for the bottom dock: artwork, title, artist, output device, play/pause, next
 * and a thin progress line. Tap to open the player; swipe sideways to change track.
 */
@Composable
fun MiniPlayer(vm: PlayerViewModel, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val progress by vm.progress.collectAsStateWithLifecycle(Progress(0, 0, null))
    val output by vm.output.collectAsStateWithLifecycle()
    var drag by remember { mutableFloatStateOf(0f) }

    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .testTag("mini_player")
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (drag < -120) vm.next() else if (drag > 120) vm.previous(); drag = 0f },
                    onHorizontalDrag = { _, d -> drag += d },
                )
            }
            .padding(start = 16.dp, end = 8.dp, top = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artworkUrl, Modifier.size(48.dp), shape = androidx.compose.foundation.shape.CircleShape)
            Spacer(Modifier.width(12.dp))
            AnimatedContent(track, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mini", modifier = Modifier.weight(1f)) { t ->
                Column {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // The output device (e.g. Bluetooth headphones) as a small icon in the subtitle
                    // line, so the title keeps the room.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        outputIcon(output.kind)?.let { icon ->
                            Icon(icon, outputLabel(output), Modifier.padding(end = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val fav by vm.isFavorite.collectAsStateWithLifecycle()
            IconButton(onClick = { vm.toggleFavorite() }) {
                Icon(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(if (fav) R.string.player_unlike else R.string.player_like),
                    Modifier.size(22.dp), tint = if (fav) app.musicflow.core.designsystem.component.Glass.Orchid else MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                app.musicflow.core.designsystem.component.GradientPlayButton(
                    if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                    vm::togglePlay, size = 44.dp, ring = false,
                )
                if (state.isBuffering && state.playWhenReady) CircularProgressIndicator(Modifier.size(46.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
            }
            IconButton(onClick = vm::next, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), Modifier.size(26.dp))
            }
        }
        val duration = progress.durationMs ?: state.durationMs
        val fraction = if (duration != null && duration > 0) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(Modifier.padding(top = 6.dp, end = 8.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
