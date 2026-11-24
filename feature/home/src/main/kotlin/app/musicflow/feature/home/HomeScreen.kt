package app.musicflow.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.AlbumCard
import app.musicflow.core.designsystem.component.ArtistCard
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.core.designsystem.component.ErrorState
import app.musicflow.core.designsystem.component.HorizontalListPadding
import app.musicflow.core.designsystem.component.HorizontalListSpacing
import app.musicflow.core.designsystem.component.MediaCard
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.PlaylistCard
import app.musicflow.core.designsystem.component.SectionHeader
import app.musicflow.core.designsystem.component.SkeletonCardRow
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.component.errorMessage
import app.musicflow.core.designsystem.navigation.LibrarySection
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.Mix

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(contentPadding: PaddingValues, vm: HomeViewModel = hiltViewModel()) {
    val local by vm.local.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "header") { Header(vm.greeting, onSearch = { nav.openSearch() }, onSettings = nav::openSettings) }
            if (!network.isConnected) item(key = "offline") { OfflineBanner() }

            if (local.continueListening.isNotEmpty()) {
                item(key = "continue_h") { SectionHeader(stringResource(R.string.home_continue_listening)) }
                item(key = "continue") { TrackCardRow(local.continueListening) { i -> vm.play(local.continueListening, i) } }
            }

            when (val o = online) {
                is OnlineState.Loaded -> {
                    val h = o.home
                    val empty = h.quickPicks.isEmpty() && h.mixes.isEmpty() && h.recommendedAlbums.isEmpty() && h.trending.isEmpty() &&
                        h.newReleases.isEmpty() && h.discover.isEmpty()
                    if (empty && h.failures.isNotEmpty()) {
                        // Every online section failed: say so instead of silently showing nothing.
                        item(key = "error") { ErrorState(errorMessage(h.failures.first()), onRetry = vm::refresh) }
                    } else onlineSections(h, vm, menu::open)
                }
                OnlineState.Loading -> item(key = "skeleton") {
                    Column { SectionHeader(stringResource(R.string.home_quick_picks)); SkeletonCardRow(); SectionHeader(stringResource(R.string.home_recommended_albums)); SkeletonCardRow() }
                }
                is OnlineState.Failed -> item(key = "error") { ErrorState(errorMessage(o.error), onRetry = vm::refresh) }
                OnlineState.Disabled -> item(key = "disabled") {
                    MessageState(stringResource(R.string.home_online_disabled_title), body = stringResource(R.string.home_online_disabled_body),
                        actionLabel = stringResource(R.string.home_open_settings), onAction = nav::openSettings)
                }
            }

            if (local.recentlyPlayed.isNotEmpty()) {
                item(key = "recent_h") { SectionHeader(stringResource(R.string.home_recently_played), onSeeAll = { nav.openLibrarySection(LibrarySection.RECENT) }) }
                item(key = "recent") { TrackCardRow(local.recentlyPlayed) { i -> vm.play(local.recentlyPlayed, i) } }
            }
            if (local.favoriteArtists.isNotEmpty()) {
                item(key = "artists_h") { SectionHeader(stringResource(R.string.home_favorite_artists)) }
                item(key = "artists") {
                    LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                        items(local.favoriteArtists, key = { it.id.value }) { ArtistCard(it, onClick = { nav.openArtist(it.id) }) }
                    }
                }
            }
            if (local.mostPlayed.isNotEmpty()) {
                item(key = "most_h") { SectionHeader(stringResource(R.string.home_on_repeat), onSeeAll = { nav.openLibrarySection(LibrarySection.MOST_PLAYED) }) }
                item(key = "most") { TrackCardRow(local.mostPlayed) { i -> vm.play(local.mostPlayed, i) } }
            }
            val isColdStart = local.recentlyPlayed.isEmpty() && local.favoritesCount == 0
            if (isColdStart) item(key = "welcome") { Welcome(onSearch = { nav.openSearch() }) }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.onlineSections(h: app.musicflow.data.repositories.OnlineHome, vm: HomeViewModel, onMore: (Track) -> Unit) {
    if (h.quickPicks.isNotEmpty()) {
        item(key = "qp_h") { SectionHeader(stringResource(R.string.home_quick_picks), subtitle = stringResource(R.string.home_for_you)) }
        item(key = "qp") { QuickPicks(h.quickPicks, onPlay = { i -> vm.play(h.quickPicks, i) }, onMore = onMore) }
    }
    if (h.mixes.isNotEmpty()) {
        item(key = "mix_h") { SectionHeader(stringResource(R.string.home_your_mixes)) }
        item(key = "mix") {
            LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                items(h.mixes, key = { "mix" + it.title }) { m -> MixCard(m) { vm.play(m.tracks) } }
            }
        }
    }
    if (h.recommendedAlbums.isNotEmpty()) {
        item(key = "alb_h") { SectionHeader(stringResource(R.string.home_recommended_albums)) }
        item(key = "alb") {
            val nav = LocalNavigator.current
            LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                items(h.recommendedAlbums, key = { it.id.value }) { AlbumCard(it, onClick = { nav.openCollection(it.id) }) }
            }
        }
    }
    if (h.trending.isNotEmpty()) {
        item(key = "tr_h") { SectionHeader(stringResource(R.string.home_trending)) }
        itemsIndexed(h.trending.take(10), key = { _, t -> "tr" + t.id.value }) { i, t ->
            TrackRow(t, onClick = { vm.play(h.trending, i) }, leadingIndex = i + 1, onMore = { onMore(t) })
        }
    }
    if (h.newReleases.isNotEmpty()) {
        item(key = "nr_h") { SectionHeader(stringResource(R.string.home_new_releases)) }
        item(key = "nr") {
            val nav = LocalNavigator.current
            LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                items(h.newReleases, key = { it.id.value }) { PlaylistCard(it, onClick = { nav.openCollection(it.id) }) }
            }
        }
    }
    if (h.discover.isNotEmpty()) {
        item(key = "disc_h") { SectionHeader(stringResource(R.string.home_discover), subtitle = stringResource(R.string.home_discover_sub)) }
        item(key = "disc") { TrackCardRow(h.discover) { i -> vm.play(h.discover, i) } }
    }
    if (h.suggestedArtists.isNotEmpty()) {
        item(key = "sa_h") { SectionHeader(stringResource(R.string.home_artists_you_may_like)) }
        item(key = "sa") {
            val nav = LocalNavigator.current
            LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                items(h.suggestedArtists, key = { "sa" + it.id.value }) { ArtistCard(it, onClick = { nav.openArtist(it.id) }) }
            }
        }
    }
}

@Composable
private fun Header(greeting: Greeting, onSearch: () -> Unit, onSettings: () -> Unit) {
    val text = stringResource(
        when (greeting) {
            Greeting.MORNING -> R.string.home_good_morning
            Greeting.AFTERNOON -> R.string.home_good_afternoon
            Greeting.EVENING -> R.string.home_good_evening
            Greeting.NIGHT -> R.string.home_good_night
        },
    )
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.background)),
        ),
    ) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, stringResource(R.string.home_search)) }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.home_settings)) }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_offline), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TrackCardRow(tracks: List<Track>, onPlay: (Int) -> Unit) {
    LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
        itemsIndexed(tracks, key = { _, t -> t.id.value }) { i, t ->
            MediaCard(t.title, t.artistLine, t.artworkUrl, onClick = { onPlay(i) })
        }
    }
}

/** Pages of four compact rows, like a quick-pick shelf. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPicks(tracks: List<Track>, onPlay: (Int) -> Unit, onMore: (Track) -> Unit) {
    val pages = tracks.chunked(4)
    val pager = rememberPagerState { pages.size }
    HorizontalPager(pager, pageSize = PageSize.Fixed(320.dp), contentPadding = PaddingValues(start = 0.dp, end = 32.dp)) { p ->
        Column {
            pages[p].forEachIndexed { j, t ->
                TrackRow(t, onClick = { onPlay(p * 4 + j) }, onMore = { onMore(t) })
            }
        }
    }
}

@Composable
private fun MixCard(mix: Mix, onPlay: () -> Unit) {
    Column(Modifier.width(168.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onPlay)) {
        Box {
            Artwork(mix.artworkUrl, Modifier.size(168.dp), shape = MaterialTheme.shapes.medium)
            Box(
                Modifier.matchParentSize().clip(MaterialTheme.shapes.medium).background(
                    Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))),
                ),
            )
            Text(
                stringResource(R.string.home_mix_title, mix.title),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(pluralStringResource(R.plurals.home_tracks, mix.tracks.size, mix.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Welcome(onSearch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_welcome_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onSearch) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.home_find_music))
        }
    }
}
