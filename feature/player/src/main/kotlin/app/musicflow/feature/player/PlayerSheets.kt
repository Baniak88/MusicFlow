package app.musicflow.feature.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.component.errorMessage
import app.musicflow.playback.SleepTimerState
import app.musicflow.playback.engine.Progress
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Local list for smooth drag-and-drop; the move is sent to the player on drop.
    var local by remember(state.queue) { mutableStateOf(state.queue) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val f = local.indexOfFirst { it.uid == from.key }; val t = local.indexOfFirst { it.uid == to.key }
        if (f >= 0 && t >= 0) { if (dragFrom == null) dragFrom = local[f].index; local = local.toMutableList().apply { add(t, removeAt(f)) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_queue), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.clearQueue(); onDismiss() }) { Text(stringResource(R.string.player_clear_queue)) }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(local, key = { it.uid }) { entry ->
                    ReorderableItem(reorder, key = entry.uid) { dragging ->
                        val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "q")
                        Surface(shadowElevation = elevation, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            TrackRow(
                                entry.track,
                                onClick = { vm.skipTo(entry.index) },
                                isPlaying = entry.index == state.currentIndex,
                                trailing = {
                                    IconButton(onClick = { vm.remove(entry.index) }) { Icon(Icons.Rounded.Close, stringResource(R.string.player_remove_from_queue)) }
                                    IconButton(onClick = {}, modifier = Modifier.draggableHandle(onDragStopped = {
                                        val from = dragFrom; val to = local.indexOfFirst { it.uid == entry.uid }
                                        if (from != null && to >= 0 && from != to) vm.move(from, to)
                                        dragFrom = null
                                    })) { Icon(Icons.Rounded.DragHandle, stringResource(R.string.player_reorder)) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle(Progress(0, 0, null))
    LaunchedEffect(state.current?.id) { state.current?.let(vm::loadLyrics) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.player_lyrics), style = MaterialTheme.typography.titleLarge)
            when (val l = lyrics) {
                LyricsState.Idle, LyricsState.Loading -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                LyricsState.NotFound -> MessageState(stringResource(R.string.player_no_lyrics))
                is LyricsState.Failed -> MessageState(stringResource(R.string.player_lyrics_failed), body = errorMessage(l.error))
                is LyricsState.Loaded -> {
                    val synced = l.lyrics.synced
                    if (synced != null) {
                        val current = synced.indexOfLast { it.timeMs <= progress.positionMs }
                        val list = rememberLazyListState()
                        LaunchedEffect(current) { if (current > 2) list.animateScrollToItem(current - 2) }
                        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
                            itemsIndexed(synced) { i, line ->
                                Text(
                                    line.text.ifEmpty { "♪" },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (i == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth().clickable { vm.seek(line.timeMs) }.padding(vertical = 8.dp),
                                )
                            }
                        }
                    } else {
                        Text(l.lyrics.plain.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp))
                    }
                    Text(stringResource(R.string.player_lyrics_source, l.lyrics.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun SleepTimerDialog(vm: PlayerViewModel, current: SleepTimerState, onDismiss: () -> Unit) {
    val options = listOf(5, 15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column {
                if (current is SleepTimerState.At) {
                    val mins = ((current.endsAtMillis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1
                    Text(stringResource(R.string.player_sleep_active, mins), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (current == SleepTimerState.EndOfTrack) Text(stringResource(R.string.player_sleep_eot_active), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                options.forEach { m ->
                    Text(stringResource(R.string.player_minutes, m), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { vm.startSleep(m); onDismiss() }.padding(vertical = 12.dp))
                }
                Text(stringResource(R.string.player_end_of_track), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { vm.sleepEndOfTrack(); onDismiss() }.padding(vertical = 12.dp))
            }
        },
        confirmButton = { if (current != SleepTimerState.Off) TextButton(onClick = { vm.cancelSleep(); onDismiss() }) { Text(stringResource(R.string.player_turn_off)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_close)) } },
    )
}

@Composable
fun SpeedDialog(current: Float, onSet: (Float) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_speed)) },
        text = {
            Column {
                Text("%.2f×".format(v), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Slider(v, { v = (it * 20).toInt() / 20f }, valueRange = 0.5f..2f, steps = 29)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f).forEach { s -> FilterChip(selected = v == s, onClick = { v = s }, label = { Text("$s×") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(v); onDismiss() }) { Text(stringResource(R.string.player_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_close)) } },
    )
}
