package app.musicflow.feature.home

import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MoreHoriz
import app.musicflow.core.designsystem.component.PillChip
import app.musicflow.core.designsystem.component.EqualizerBars
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
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
    val update by vm.update.collectAsStateWithLifecycle()
    val updateDismissed by vm.updateDismissed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val playing by vm.playback.state.collectAsStateWithLifecycle()
    var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(HomeFilter.ALL) }
    val showSongs = filter == HomeFilter.ALL || filter == HomeFilter.SONGS
    val showAlbums = filter == HomeFilter.ALL || filter == HomeFilter.ALBUMS
    val showArtists = filter == HomeFilter.ALL || filter == HomeFilter.ARTISTS
    val continueList = local.continueListening.ifEmpty { local.recentlyPlayed.take(9) }
    val continueLabel = stringResource(R.string.home_continue_listening)

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "header") { Header(vm.greeting, onSettings = nav::openSettings, onSearch = { nav.openSearch() }) }
            item(key = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(HomeFilter.entries) { f -> PillChip(stringResource(f.label), filter == f, { filter = f }) }
                }
            }
            update?.takeIf { !updateDismissed }?.let { u ->
                item(key = "update") {
                    val install by vm.installState.collectAsStateWithLifecycle()
                    UpdateBanner(
                        u.version.toString(), install,
                        onInstall = vm::installUpdate,
                        onBrowser = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        onDismiss = vm::dismissUpdate,
                    )
                }
            }
            if (!network.isConnected) item(key = "offline") { OfflineBanner() }

            if (showSongs && continueList.isNotEmpty()) {
                item(key = "continue_h") { SectionHeader(continueLabel, onSeeAll = { nav.openLibrarySection(LibrarySection.RECENT) }) }
                item(key = "continue") {
                    TrackRowsPager(continueList, playing.current?.id, playing.isPlaying, onPlay = { i -> vm.play(continueList, i, continueLabel) }, onMore = menu::open)
                }
            }

            when (val o = online) {
                is OnlineState.Loaded -> if (filter != HomeFilter.ALL) {
                    filteredOnline(o.home, filter, vm, playing.current?.id, playing.isPlaying, menu::open)
                } else {
                    val h = o.home
                    val empty = h.quickPicks.isEmpty() && h.mixes.isEmpty() && h.recommendedAlbums.isEmpty() && h.trending.isEmpty() &&
                        h.newReleases.isEmpty() && h.discover.isEmpty()
                    if (empty && h.failures.isNotEmpty()) {
                        // Every online section failed: say so instead of silently showing nothing.
                        item(key = "error") { ErrorState(errorMessage(h.failures.first()), onRetry = vm::refresh) }
                    } else onlineSections(h, vm, playing.current?.id, playing.isPlaying, menu::open)
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

            if (showSongs && local.recentlyPlayed.isNotEmpty()) {
                item(key = "recent_h") { SectionHeader(stringResource(R.string.home_recently_played), onSeeAll = { nav.openLibrarySection(LibrarySection.RECENT) }) }
                item(key = "recent") { val label = stringResource(R.string.home_recently_played); TrackCardRow(local.recentlyPlayed) { i -> vm.play(local.recentlyPlayed, i, label) } }
            }
            if (showArtists && local.favoriteArtists.isNotEmpty()) {
                item(key = "artists_h") { SectionHeader(stringResource(R.string.home_favorite_artists)) }
                item(key = "artists") {
                    LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                        items(local.favoriteArtists, key = { it.id.value }) { ArtistCard(it, onClick = { nav.openArtist(it.id) }) }
                    }
                }
            }
            if (showSongs && local.mostPlayed.isNotEmpty()) {
                item(key = "most_h") { SectionHeader(stringResource(R.string.home_on_repeat), onSeeAll = { nav.openLibrarySection(LibrarySection.MOST_PLAYED) }) }
                item(key = "most") { val label = stringResource(R.string.home_on_repeat); TrackCardRow(local.mostPlayed) { i -> vm.play(local.mostPlayed, i, label) } }
            }
            val isColdStart = local.recentlyPlayed.isEmpty() && local.favoritesCount == 0
            if (isColdStart) item(key = "welcome") { Welcome(onSearch = { nav.openSearch() }) }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Sections shown when a filter chip other than "All" is selected. */
private fun androidx.compose.foundation.lazy.LazyListScope.filteredOnline(
    h: app.musicflow.data.repositories.OnlineHome, filter: HomeFilter, vm: HomeViewModel,
    currentId: app.musicflow.core.model.MediaId?, isPlaying: Boolean, onMore: (Track) -> Unit,
) = onlineSections(
    h.copy(
        quickPicks = if (filter == HomeFilter.SONGS) h.quickPicks else emptyList(),
        mixes = if (filter == HomeFilter.ALBUMS) h.mixes else emptyList(),
        recommendedAlbums = if (filter == HomeFilter.ALBUMS) h.recommendedAlbums else emptyList(),
        trending = if (filter == HomeFilter.SONGS) h.trending else emptyList(),
        newReleases = if (filter == HomeFilter.ALBUMS) h.newReleases else emptyList(),
        discover = if (filter == HomeFilter.SONGS) h.discover else emptyList(),
        suggestedArtists = if (filter == HomeFilter.ARTISTS) h.suggestedArtists else emptyList(),
    ),
    vm, currentId, isPlaying, onMore,
)

private fun androidx.compose.foundation.lazy.LazyListScope.onlineSections(
    h: app.musicflow.data.repositories.OnlineHome, vm: HomeViewModel,
    currentId: app.musicflow.core.model.MediaId?, isPlaying: Boolean, onMore: (Track) -> Unit,
) {
    if (h.mixes.isNotEmpty()) {
        item(key = "mix_h") { SectionHeader(stringResource(R.string.home_for_you)) }
        item(key = "mix") {
            LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                items(h.mixes, key = { "mix" + it.title }) { m ->
                    val label = stringResource(R.string.home_mix_title, m.title)
                    MixCard(m) { vm.play(m.tracks, 0, label) }
                }
            }
        }
    }
    if (h.quickPicks.isNotEmpty()) {
        item(key = "qp_h") { SectionHeader(stringResource(R.string.home_quick_picks)) }
        item(key = "qp") {
            val label = stringResource(R.string.home_quick_picks)
            TrackRowsPager(h.quickPicks, currentId, isPlaying, onPlay = { i -> vm.play(h.quickPicks, i, label) }, onMore = onMore)
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
            val label = stringResource(R.string.home_trending)
            TrackRow(t, onClick = { vm.play(h.trending, i, label) }, leadingIndex = i + 1, isPlaying = t.id == currentId, onMore = { onMore(t) })
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
        item(key = "disc") { val label = stringResource(R.string.home_discover); TrackCardRow(h.discover) { i -> vm.play(h.discover, i, label) } }
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
private fun Header(greeting: Greeting, onSettings: () -> Unit, onSearch: () -> Unit) {
    val text = stringResource(
        when (greeting) {
            Greeting.MORNING -> R.string.home_good_morning
            Greeting.AFTERNOON -> R.string.home_good_afternoon
            Greeting.EVENING -> R.string.home_good_evening
            Greeting.NIGHT -> R.string.home_good_night
        },
    )
    // Glass buttons: menu (settings) on the left, search on the right; greeting below.
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            app.musicflow.core.designsystem.component.GlassIconButton(Icons.Rounded.GridView, stringResource(R.string.home_settings), onSettings)
            Spacer(Modifier.weight(1f))
            app.musicflow.core.designsystem.component.GlassIconButton(Icons.Rounded.Search, stringResource(R.string.home_search), onSearch)
        }
        Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
    }
}

@Composable
private fun UpdateBanner(
    version: String,
    install: app.musicflow.data.repositories.InstallState,
    onInstall: () -> Unit,
    onBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = install is app.musicflow.data.repositories.InstallState.Downloading || install is app.musicflow.data.repositories.InstallState.Installing
    val failed = install is app.musicflow.data.repositories.InstallState.Failed
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("update_banner"),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_update_available, version), style = MaterialTheme.typography.bodyMedium)
                val line = when (install) {
                    is app.musicflow.data.repositories.InstallState.Downloading -> stringResource(R.string.home_update_downloading)
                    is app.musicflow.data.repositories.InstallState.Installing -> stringResource(R.string.home_update_installing)
                    is app.musicflow.data.repositories.InstallState.Failed -> stringResource(R.string.home_update_failed, install.message)
                    else -> null
                }
                line?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
            else if (failed) TextButton(onClick = onBrowser) { Text(stringResource(R.string.home_update_browser)) }
            else TextButton(onClick = onInstall) { Text(stringResource(R.string.home_update_install)) }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.home_update_dismiss)) }
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
    // Made-for-you style cards
    LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
        itemsIndexed(tracks, key = { _, t -> t.id.value }) { i, t ->
            MediaCard(t.title, t.artistLine, t.artworkUrl, onClick = { onPlay(i) })
        }
    }
}

enum class HomeFilter(val label: Int) { ALL(R.string.home_filter_all), SONGS(R.string.home_filter_songs), ALBUMS(R.string.home_filter_albums), ARTISTS(R.string.home_filter_artists) }

/** Pages of three rows (artwork, title, artist, now-playing bars or ⋯), swiped horizontally. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRowsPager(tracks: List<Track>, currentId: app.musicflow.core.model.MediaId?, isPlaying: Boolean, onPlay: (Int) -> Unit, onMore: (Track) -> Unit) {
    val pages = tracks.chunked(3)
    val pager = rememberPagerState { pages.size }
    // A page fills the width (a sliver of the next page shows there is more), so titles have room.
    HorizontalPager(pager, pageSize = PageSize.Fill, contentPadding = PaddingValues(end = if (pages.size > 1) 28.dp else 0.dp)) { p ->
        Column {
            pages[p].forEachIndexed { j, t ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPlay(p * 3 + j) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.artworkUrl, Modifier.size(56.dp), shape = androidx.compose.foundation.shape.CircleShape)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (t.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(t.artistLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Tapping the row plays it; only the playing track shows bars.
                    if (t.id == currentId) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { EqualizerBars(isPlaying) }
                    }
                    IconButton(onClick = { onMore(t) }) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.home_more), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun MixCard(mix: Mix, onPlay: () -> Unit) {
    Column(Modifier.width(168.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onPlay)) {
        Box {
            Artwork(mix.artworkUrl, Modifier.size(168.dp), shape = MaterialTheme.shapes.medium)
            // Dark scrim at the bottom only: the cover stays visible and the title readable.
            Box(
                Modifier.matchParentSize().clip(MaterialTheme.shapes.medium).background(
                    Brush.verticalGradient(
                        0.45f to androidx.compose.ui.graphics.Color.Transparent,
                        1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f),
                    ),
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
