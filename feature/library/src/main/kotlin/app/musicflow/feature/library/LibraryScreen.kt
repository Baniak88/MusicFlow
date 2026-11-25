package app.musicflow.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.navigation.LibrarySection
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu

private enum class LibTab { PLAYLISTS, SONGS, ALBUMS, ARTISTS }

@Composable
fun LibraryScreen(contentPadding: PaddingValues, vm: LibraryViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibTab.PLAYLISTS) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showImportUrl by rememberSaveable { mutableStateOf(false) }
    var addMenu by rememberSaveable { mutableStateOf(false) }
    val pickM3u = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importM3u) }

    LibraryEventsEffect(vm.events, onOpenPlaylist = nav::openUserPlaylist)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lib_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.lib_add)) }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.lib_new_playlist)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { addMenu = false; showCreate = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lib_import_url)) }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, onClick = { addMenu = false; showImportUrl = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lib_import_m3u)) }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { addMenu = false; pickM3u.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/plain", "*/*")) })
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Shortcut(Icons.Rounded.Favorite, stringResource(R.string.lib_favorites), trackCount(favorites.size)) { nav.openLibrarySection(LibrarySection.FAVORITES) }
                Shortcut(Icons.Rounded.Schedule, stringResource(R.string.lib_recent), null) { nav.openLibrarySection(LibrarySection.RECENT) }
                Shortcut(Icons.Rounded.TrendingUp, stringResource(R.string.lib_most_played), null) { nav.openLibrarySection(LibrarySection.MOST_PLAYED) }
                Shortcut(Icons.Rounded.History, stringResource(R.string.lib_history), null) { nav.openLibrarySection(LibrarySection.HISTORY) }
                Shortcut(Icons.Rounded.PhoneAndroid, stringResource(R.string.lib_local), null) { nav.openLibrarySection(LibrarySection.LOCAL) }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibTab.entries) { t ->
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = {
                        Text(stringResource(when (t) { LibTab.PLAYLISTS -> R.string.lib_playlists; LibTab.SONGS -> R.string.lib_songs; LibTab.ALBUMS -> R.string.lib_albums; LibTab.ARTISTS -> R.string.lib_artists }))
                    })
                }
            }
        }
        when (tab) {
            LibTab.PLAYLISTS -> if (playlists.isEmpty()) item { MessageState(stringResource(R.string.lib_no_playlists), body = stringResource(R.string.lib_no_playlists_body), actionLabel = stringResource(R.string.lib_new_playlist), onAction = { showCreate = true }) }
                else items(playlists, key = { "p${it.id}" }) { p -> EntityRow(p.name, trackCount(p.trackCount), p.artworkUrl) { nav.openUserPlaylist(p.id) } }
            LibTab.SONGS -> if (favorites.isEmpty()) item { MessageState(stringResource(R.string.lib_no_favorites)) }
                else items(favorites, key = { "f${it.id.value}" }) { t -> TrackRow(t, onClick = { vm.playFavoritesFrom(t) }, onMore = { menu.open(t) }) }
            LibTab.ALBUMS -> if (albums.isEmpty()) item { MessageState(stringResource(R.string.lib_no_albums)) }
                else items(albums, key = { "a${it.id.value}" }) { a -> EntityRow(a.title, a.artistLine, a.artworkUrl) { nav.openCollection(a.id) } }
            LibTab.ARTISTS -> if (artists.isEmpty()) item { MessageState(stringResource(R.string.lib_no_artists)) }
                else items(artists, key = { "r${it.id.value}" }) { a -> EntityRow(a.name, null, a.artworkUrl, circle = true) { nav.openArtist(a.id) } }
        }
    }

    if (showCreate) TextInputDialog(stringResource(R.string.lib_new_playlist), confirm = stringResource(R.string.lib_create), label = stringResource(R.string.lib_name), onDismiss = { showCreate = false }, onConfirm = vm::create)
    if (showImportUrl) TextInputDialog(stringResource(R.string.lib_import_url), confirm = stringResource(R.string.lib_import), label = stringResource(R.string.lib_playlist_link), onDismiss = { showImportUrl = false }, onConfirm = vm::importUrl)
}

@Composable
private fun Shortcut(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun EntityRow(title: String, subtitle: String?, artwork: String?, circle: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, Modifier.size(56.dp), shape = if (circle) androidx.compose.foundation.shape.CircleShape else MaterialTheme.shapes.small)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
