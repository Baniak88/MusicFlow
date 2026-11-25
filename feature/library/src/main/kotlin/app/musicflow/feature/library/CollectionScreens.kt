package app.musicflow.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.AlbumCard
import app.musicflow.core.designsystem.component.ErrorState
import app.musicflow.core.designsystem.component.HorizontalListPadding
import app.musicflow.core.designsystem.component.HorizontalListSpacing
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.SectionHeader
import app.musicflow.core.designsystem.component.SkeletonTrackRows
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.component.errorMessage
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu

@Composable
private fun LazyListState.isNearEnd(): Boolean {
    val v by remember { derivedStateOf { layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= layoutInfo.totalItemsCount - 4 } == true } }
    return v
}

@Composable
fun CollectionScreen(contentPadding: PaddingValues, vm: CollectionViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val state by vm.state.collectAsStateWithLifecycle()
    val saved by vm.isSaved.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nearEnd = listState.isNearEnd()
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }
    LibraryEventsEffect(vm.events, onOpenPlaylist = nav::openUserPlaylist)

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        when (val s = state) {
            LoadState.Loading -> { item { BackBar(nav::back) }; item { SkeletonTrackRows(count = 10) } }
            is LoadState.Failed -> { item { BackBar(nav::back) }; item { ErrorState(errorMessage(s.error), onRetry = { vm.load() }) } }
            is LoadState.Loaded -> {
                val page = s.value
                item {
                    CollectionHeader(
                        title = page.title, subtitle = listOfNotNull(page.subtitle, trackCount(page.tracks.size).takeIf { page.nextPageToken == null }).joinToString(" • "),
                        artworkUrl = page.artworkUrl, onBack = nav::back,
                        onPlay = { vm.play() }, onShuffle = { vm.play(shuffle = true) },
                        actions = {
                            IconButton(onClick = vm::toggleSaved) {
                                Icon(if (saved) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, stringResource(if (saved) R.string.lib_remove_from_library else R.string.lib_save_to_library))
                            }
                            IconButton(onClick = vm::saveAsPlaylist) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.lib_save_as_playlist)) }
                        },
                    )
                }
                if (page.tracks.isEmpty()) item { MessageState(stringResource(R.string.lib_section_empty)) }
                itemsIndexed(page.tracks, key = { i, t -> "${t.id.value}#$i" }) { i, t ->
                    TrackRow(t, onClick = { vm.play(i) }, onMore = { menu.open(t) })
                }
                if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
            }
        }
    }
}

@Composable
fun ArtistScreen(contentPadding: PaddingValues, vm: ArtistViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val state by vm.state.collectAsStateWithLifecycle()
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        when (val s = state) {
            LoadState.Loading -> { item { BackBar(nav::back) }; item { SkeletonTrackRows(count = 8) } }
            is LoadState.Failed -> { item { BackBar(nav::back) }; item { ErrorState(errorMessage(s.error), onRetry = { vm.load() }) } }
            is LoadState.Loaded -> {
                val p = s.value
                item {
                    CollectionHeader(
                        title = p.artist.name,
                        subtitle = p.artist.subscriberCount?.let { stringResource(R.string.lib_subscribers, compactNumber(it)) },
                        artworkUrl = p.artist.artworkUrl, circleArt = true, onBack = nav::back,
                        onPlay = if (p.topTracks.isNotEmpty()) ({ vm.play(p.topTracks, 0) }) else null,
                        onShuffle = if (p.topTracks.isNotEmpty()) ({ vm.play(p.topTracks, 0, shuffle = true) }) else null,
                        actions = {
                            IconButton(onClick = { vm.radio() }) { Icon(Icons.Rounded.Radio, stringResource(R.string.lib_artist_radio)) }
                            IconButton(onClick = vm::toggleFavorite) {
                                Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(R.string.lib_follow), tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        },
                    )
                }
                if (p.topTracks.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.lib_top_songs)) }
                    itemsIndexed(p.topTracks.take(10), key = { _, t -> "t" + t.id.value }) { i, t -> TrackRow(t, onClick = { vm.play(p.topTracks, i) }, onMore = { menu.open(t) }) }
                }
                if (p.albums.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.lib_albums)) }
                    item { LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) { items(p.albums, key = { it.id.value }) { AlbumCard(it, onClick = { nav.openCollection(it.id) }) } } }
                }
                if (p.singles.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.lib_singles)) }
                    item { LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) { items(p.singles, key = { it.id.value }) { AlbumCard(it, onClick = { nav.openCollection(it.id) }) } } }
                }
                facts?.let { f ->
                    item(key = "mb_facts") {
                        SectionHeader(stringResource(R.string.lib_about_musicbrainz))
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOfNotNull(
                                f.type?.let { stringResource(R.string.lib_mb_type) to it },
                                f.area?.let { stringResource(R.string.lib_mb_area) to it },
                                f.begin?.let { b -> stringResource(R.string.lib_mb_active) to (b + if (f.ended) " – " + (f.end ?: "?") else " –") },
                                f.genres.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lib_mb_genres) to it.joinToString(", ") },
                                f.disambiguation?.let { stringResource(R.string.lib_mb_note) to it },
                            ).forEach { (k, v) ->
                                Text("$k: $v", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.TextButton(onClick = { nav.openArtist(app.musicflow.core.model.MediaId.of("mba", f.mbid)) }, contentPadding = PaddingValues(0.dp)) {
                                Text(stringResource(R.string.lib_mb_discography))
                            }
                        }
                    }
                }
                p.description?.let { d ->
                    item { SectionHeader(stringResource(R.string.lib_about)) }
                    item { Text(d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                if (p.topTracks.isEmpty() && p.albums.isEmpty() && p.singles.isEmpty()) item { MessageState(stringResource(R.string.lib_section_empty)) }
            }
        }
    }
}

/** Compact number formatting ("1.2M") available on all supported API levels. */
private fun compactNumber(n: Long): String = when {
    n >= 1_000_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fK", n / 1e3)
    else -> n.toString()
}
