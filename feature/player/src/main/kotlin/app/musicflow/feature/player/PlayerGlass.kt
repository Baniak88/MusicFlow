package app.musicflow.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.common.Formatting
import app.musicflow.core.designsystem.component.Glass
import app.musicflow.core.designsystem.component.GlassBackdrop
import app.musicflow.core.designsystem.component.GlassIconButton
import app.musicflow.core.designsystem.component.GradientPlayButton
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.WaveformSeekBar
import app.musicflow.core.designsystem.component.errorMessage
import app.musicflow.core.model.RepeatMode
import app.musicflow.playback.engine.Progress

/** A tab with a soft curved edge: [up] = bulges upwards (bottom of the player), else downwards. */
private fun bumpShape(up: Boolean) = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    if (up) {
        moveTo(0f, h); cubicTo(w * 0.16f, h, w * 0.2f, 0f, w * 0.38f, 0f); lineTo(w * 0.62f, 0f); cubicTo(w * 0.8f, 0f, w * 0.84f, h, w, h); close()
    } else {
        moveTo(0f, 0f); cubicTo(w * 0.16f, 0f, w * 0.2f, h, w * 0.38f, h); lineTo(w * 0.62f, h); cubicTo(w * 0.8f, h, w * 0.84f, 0f, w, 0f); close()
    }
}

/** "Lyrics" tab: opens (up) or closes (down) the lyrics panel. */
@Composable
internal fun LyricsTab(label: String, up: Boolean, onClick: () -> Unit) {
    val shape = bumpShape(up)
    Box(
        Modifier.width(190.dp).height(50.dp).clip(shape).background(Glass.FillStrong).clickable(role = Role.Button, onClick = onClick).testTag("lyrics_tab"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(top = if (up) 10.dp else 0.dp, bottom = if (up) 0.dp else 10.dp))
    }
}

/** Full-screen lyrics over the blurred artwork, with the current line highlighted and mini controls. */
@Composable
internal fun LyricsPanel(vm: PlayerViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle(Progress(0, 0, null))
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    LaunchedEffect(state.current?.id) { state.current?.let(vm::loadLyrics) }
    val track = state.current
    val muted = Color.White.copy(alpha = 0.7f)
    val duration = progress.durationMs ?: state.durationMs ?: 0L
    val fraction = if (duration > 0) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    GlassBackdrop(Modifier.testTag("lyrics_panel"), artworkUrl = track?.artworkUrl, dim = 0.62f) {
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            LyricsTab(stringResource(R.string.player_lyrics), up = false, onClick = onClose)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                when (val l = lyrics) {
                    LyricsState.Idle, LyricsState.Loading -> CircularProgressIndicator(color = Color.White)
                    LyricsState.NotFound -> MessageState(stringResource(R.string.player_no_lyrics))
                    is LyricsState.Failed -> MessageState(stringResource(R.string.player_lyrics_failed), body = errorMessage(l.error))
                    is LyricsState.Loaded -> {
                        val synced = l.lyrics.synced
                        if (synced != null) {
                            val current = synced.indexOfLast { it.timeMs <= progress.positionMs }
                            val list = rememberLazyListState()
                            LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - 2).coerceAtLeast(0)) }
                            LazyColumn(state = list, contentPadding = PaddingValues(vertical = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                itemsIndexed(synced) { i, line ->
                                    Text(
                                        line.text.ifEmpty { "♪" },
                                        style = if (i == current) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium,
                                        color = if (i == current) Color.White else Color.White.copy(alpha = 0.5f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().clickable { vm.seek(line.timeMs) }.padding(vertical = 9.dp),
                                    )
                                }
                            }
                        } else {
                            Text(
                                l.lyrics.plain.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                            )
                        }
                    }
                }
            }
            (lyrics as? LyricsState.Loaded)?.let { l ->
                Text(stringResource(R.string.player_lyrics_source, l.lyrics.source), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            // Mini transport
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(track?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(48.dp))
                    Text(track?.artistLine.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.player_unlike else R.string.player_like), tint = if (fav) Glass.Orchid else Color.White)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Formatting.duration(progress.positionMs), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(40.dp))
                    WaveformSeekBar(fraction, track?.id?.value, { f -> vm.seek((f * duration).toLong()) }, Modifier.weight(1f), enabled = duration > 0)
                    Text(if (duration > 0) Formatting.duration(duration) else "--:--", style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::toggleShuffle) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = if (state.shuffle) Glass.Orchid else muted) }
                    GlassIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), vm::previous, size = 50.dp)
                    GradientPlayButton(
                        if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play), vm::togglePlay, size = 72.dp,
                    )
                    GlassIconButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next), vm::next, size = 50.dp, enabled = state.hasNext)
                    IconButton(onClick = vm::cycleRepeat) {
                        Icon(if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, stringResource(R.string.player_repeat_all),
                            tint = if (state.repeat != RepeatMode.OFF) Glass.Orchid else muted)
                    }
                }
            }
        }
    }
}
