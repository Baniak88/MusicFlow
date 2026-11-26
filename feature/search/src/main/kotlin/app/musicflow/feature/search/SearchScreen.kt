package app.musicflow.feature.search

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.core.designsystem.component.ErrorState
import app.musicflow.core.designsystem.component.PillChip
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.SkeletonTrackRows
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.component.errorMessage
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import app.musicflow.core.model.MediaItemSummary
import app.musicflow.core.model.SearchFilter

@Composable
fun SearchScreen(contentPadding: PaddingValues, initialQuery: String? = null, vm: SearchViewModel = hiltViewModel()) {
    val input by vm.input.collectAsStateWithLifecycle()
    val submitted by vm.submitted.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(initialQuery) {
        // No automatic keyboard: it popped up every time the Search tab was revisited.
        if (!initialQuery.isNullOrBlank()) vm.submit(initialQuery)
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        OutlinedTextField(
            value = input,
            onValueChange = vm::onInput,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) } },
            singleLine = true,
            shape = CircleShape,
            // Frosted glass field: translucent fill, hairline border, accent border when focused.
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = app.musicflow.core.designsystem.component.Glass.FillStrong,
                focusedContainerColor = app.musicflow.core.designsystem.component.Glass.FillStrong,
                unfocusedBorderColor = app.musicflow.core.designsystem.component.Glass.Stroke,
                focusedBorderColor = app.musicflow.core.designsystem.component.Glass.Orchid,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).focusRequester(focus).testTag("search_field"),
        )
        Box(Modifier.weight(1f)) {
            when {
                submitted -> Results(results, contentPadding, vm)
                input.isNotBlank() && suggestions.isNotEmpty() -> SuggestionList(suggestions, contentPadding) { keyboard?.hide(); vm.submit(it) }
                else -> HistoryList(history, contentPadding, onClick = { keyboard?.hide(); vm.submit(it) }, onDelete = { vm.deleteHistory(it) }, onClear = { vm.clearHistory() })
            }
        }
    }
}

@Composable
private fun SuggestionList(items: List<String>, padding: PaddingValues, onClick: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
        items(items, key = { "s$it" }) { s -> QueryRow(s, Icons.AutoMirrored.Rounded.TrendingUp, onClick = { onClick(s) }) }
    }
}

@Composable
private fun HistoryList(items: List<String>, padding: PaddingValues, onClick: (String) -> Unit, onDelete: (String) -> Unit, onClear: () -> Unit) {
    if (items.isEmpty()) {
        MessageState(stringResource(R.string.search_empty_title), body = stringResource(R.string.search_empty_body), icon = Icons.Rounded.Search)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding())) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_clear_history)) }
            }
        }
        items(items, key = { "h$it" }) { q ->
            QueryRow(q, Icons.Rounded.History, onClick = { onClick(q) }, trailing = {
                IconButton(onClick = { onDelete(q) }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_remove)) }
            })
        }
    }
}

@Composable
private fun QueryRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun filterLabel(f: SearchFilter) = stringResource(
    when (f) {
        SearchFilter.ALL -> R.string.search_filter_all
        SearchFilter.SONGS -> R.string.search_filter_songs
        SearchFilter.ALBUMS -> R.string.search_filter_albums
        SearchFilter.ARTISTS -> R.string.search_filter_artists
        SearchFilter.PLAYLISTS -> R.string.search_filter_playlists
    },
)

@Composable
private fun Results(state: ResultsState, padding: PaddingValues, vm: SearchViewModel) {
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd, state.nextPageToken) { if (nearEnd) vm.loadMore() }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)) {
        val sources = vm.sources
        if (sources.size > 1) item(key = "sources") {
            val selected = state.source ?: sources.first().id
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(sources, key = { it.id }) { src -> PillChip(src.name, selected == src.id, { vm.setSource(src.id) }) }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchFilter.entries) { f -> PillChip(filterLabel(f), state.filter == f, { vm.setFilter(f) }) }
            }
        }
        state.correctedQuery?.let { c ->
            item {
                TextButton(onClick = { vm.submit(c) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.search_did_you_mean, c)) }
            }
        }
        when {
            state.loading -> item { SkeletonTrackRows() }
            state.error != null -> item { ErrorState(errorMessage(state.error), onRetry = { vm.submit(state.query, state.filter) }) }
            state.items.isEmpty() -> item { MessageState(stringResource(R.string.search_no_results, state.query), icon = Icons.Rounded.Search) }
            else -> items(state.items, key = { it::class.simpleName + it.id.value }) { item ->
                when (item) {
                    is MediaItemSummary.TrackItem -> TrackRow(item.track, onClick = { vm.playTrack(item.track) }, onMore = { menu.open(item.track) })
                    is MediaItemSummary.ArtistItem -> EntityRow(item.artist.name, stringResource(R.string.search_type_artist), item.artist.artworkUrl, circle = true) { nav.openArtist(item.artist.id) }
                    is MediaItemSummary.AlbumItem -> EntityRow(item.album.title, listOfNotNull(stringResource(R.string.search_type_album), item.album.artistLine.ifEmpty { null }).joinToString(" • "), item.album.artworkUrl) { nav.openCollection(item.album.id) }
                    is MediaItemSummary.PlaylistItem -> EntityRow(item.playlist.title, listOfNotNull(stringResource(R.string.search_type_playlist), item.playlist.owner).joinToString(" • "), item.playlist.artworkUrl) { nav.openCollection(item.playlist.id) }
                }
            }
        }
        if (state.loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
    }
}

@Composable
private fun EntityRow(title: String, subtitle: String, artwork: String?, circle: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, Modifier.size(52.dp), shape = if (circle) CircleShape else MaterialTheme.shapes.small)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
