package app.musicflow.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.musicflow.core.designsystem.component.Glass
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import app.musicflow.core.designsystem.navigation.TrackMenu
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.UserPlaylist

/** Provides [LocalTrackMenu] to [content] and renders the track actions sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuHost(content: @Composable () -> Unit) {
    var track by remember { mutableStateOf<Track?>(null) }
    var addTo by remember { mutableStateOf<Track?>(null) }
    val menu = remember { TrackMenu { track = it } }
    val vm: TrackMenuViewModel = hiltViewModel()
    LibraryEventsEffect(vm.events)

    CompositionLocalProvider(LocalTrackMenu provides menu) {
        content()
        track?.let { t ->
            vm.bind(t)
            val fav by vm.isFavorite.collectAsStateWithLifecycle()
            val nav = LocalNavigator.current
            ModalBottomSheet(onDismissRequest = { track = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(t.artworkUrl, Modifier.size(52.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Action(Icons.Rounded.SkipNext, stringResource(R.string.lib_play_next)) { vm.playNext(t); track = null }
                    Action(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.lib_add_to_queue)) { vm.addToQueue(t); track = null }
                    Action(Icons.Rounded.Radio, stringResource(R.string.lib_start_radio)) { vm.startRadio(t); track = null }
                    Action(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.lib_remove_favorite else R.string.lib_add_favorite)) { vm.toggleFavorite(t) }
                    Action(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.lib_add_to_playlist)) { addTo = t; track = null }
                    t.artists.firstOrNull { it.id != null }?.let { a ->
                        Action(Icons.Rounded.Person, stringResource(R.string.lib_go_to_artist, a.name)) { track = null; MediaId.parseOrNull(a.id)?.let(nav::openArtist) }
                    }
                    t.album?.id?.let { MediaId.parseOrNull(it) }?.let { albumId ->
                        Action(Icons.Rounded.Album, stringResource(R.string.lib_go_to_album)) { track = null; nav.openCollection(albumId) }
                    }
                }
            }
        }
        addTo?.let { t -> AddToPlaylistDialog(t, vm, onDismiss = { addTo = null }) }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Lets the user pick a playlist; warns before adding a song that is already in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistDialog(track: Track, vm: TrackMenuViewModel, onDismiss: () -> Unit) {
    val playlists by vm.userPlaylists.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var duplicateOf by remember { mutableStateOf<UserPlaylist?>(null) }
    val repoCheck: (UserPlaylist) -> Unit = { p -> vm.addToPlaylist(track, p); onDismiss() }

    if (create) {
        TextInputDialog(stringResource(R.string.lib_new_playlist), confirm = stringResource(R.string.lib_create), label = stringResource(R.string.lib_name), onDismiss = { create = false; onDismiss() }) {
            vm.createAndAdd(track, it)
        }
        return
    }
    duplicateOf?.let { p ->
        ConfirmDialog(stringResource(R.string.lib_duplicate_title), stringResource(R.string.lib_duplicate_body, p.name), stringResource(R.string.lib_add_anyway), onDismiss = { duplicateOf = null; onDismiss() }) {
            vm.addToPlaylist(track, p, force = true)
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            // What is being added.
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.artworkUrl, Modifier.size(52.dp), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lib_add_to_playlist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    PlaylistChoice(
                        leading = {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(Glass.PlayBrush), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Add, null, tint = Color.White)
                            }
                        },
                        title = stringResource(R.string.lib_new_playlist),
                        subtitle = null,
                        onClick = { create = true },
                    )
                }
                items(playlists, key = { it.id }) { p ->
                    PlaylistChoice(
                        leading = { Artwork(p.artworkUrl, Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), iconSize = 22.dp) },
                        title = p.name,
                        subtitle = trackCount(p.trackCount),
                        onClick = { repoCheck(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistChoice(leading: @Composable () -> Unit, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
