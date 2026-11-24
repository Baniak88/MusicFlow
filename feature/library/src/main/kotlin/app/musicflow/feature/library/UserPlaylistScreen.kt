package app.musicflow.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun UserPlaylistScreen(contentPadding: PaddingValues, vm: UserPlaylistViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var overflow by rememberSaveable { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri -> uri?.let(vm::export) }
    LibraryEventsEffect(vm.events)

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = entries.indexOfFirst { it.entryId == from.key }
        val toIdx = entries.indexOfFirst { it.entryId == to.key }
        if (fromIdx >= 0 && toIdx >= 0) vm.move(fromIdx, toIdx)
    }
    val name = playlist?.name.orEmpty()

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item(key = "header") {
            CollectionHeader(
                title = name,
                subtitle = trackCount(entries.size),
                artworkUrl = entries.firstOrNull()?.track?.artworkUrl,
                onBack = nav::back,
                onPlay = if (entries.isNotEmpty()) ({ vm.play() }) else null,
                onShuffle = if (entries.isNotEmpty()) ({ vm.play(shuffle = true) }) else null,
                actions = {
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.lib_more)) }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.lib_rename)) }, onClick = { overflow = false; rename = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.lib_export_m3u)) }, onClick = { overflow = false; exporter.launch("$name.m3u") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.lib_delete)) }, onClick = { overflow = false; delete = true })
                        }
                    }
                },
            )
        }
        if (entries.isEmpty()) item(key = "empty") { MessageState(stringResource(R.string.lib_empty_playlist), body = stringResource(R.string.lib_empty_playlist_body)) }
        items(entries, key = { it.entryId }) { entry ->
            ReorderableItem(reorderState, key = entry.entryId) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "drag")
                Surface(shadowElevation = elevation, color = MaterialTheme.colorScheme.background) {
                    TrackRow(
                        entry.track,
                        onClick = { vm.play(entries.indexOf(entry)) },
                        onMore = { menu.open(entry.track) },
                        trailing = {
                            IconButton(onClick = { vm.remove(entry) }) { Icon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.lib_remove)) }
                            IconButton(onClick = {}, modifier = Modifier.draggableHandle(onDragStopped = { vm.commitOrder() })) {
                                Icon(Icons.Rounded.DragHandle, stringResource(R.string.lib_reorder))
                            }
                        },
                    )
                }
            }
        }
    }

    if (rename) TextInputDialog(stringResource(R.string.lib_rename), initial = name, confirm = stringResource(R.string.lib_save), label = stringResource(R.string.lib_name), onDismiss = { rename = false }, onConfirm = vm::rename)
    if (delete) ConfirmDialog(stringResource(R.string.lib_delete_q), stringResource(R.string.lib_delete_body, name), stringResource(R.string.lib_delete), onDismiss = { delete = false }) {
        vm.delete(); nav.back()
    }
}
