package app.musicflow.feature.explore

import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.designsystem.component.ErrorState
import app.musicflow.core.designsystem.component.PillChip
import app.musicflow.core.designsystem.component.HorizontalListPadding
import app.musicflow.core.designsystem.component.HorizontalListSpacing
import app.musicflow.core.designsystem.component.MessageState
import app.musicflow.core.designsystem.component.PlaylistCard
import app.musicflow.core.designsystem.component.SectionHeader
import app.musicflow.core.designsystem.component.SkeletonCardRow
import app.musicflow.core.designsystem.component.SkeletonTrackRows
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.component.errorMessage
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import app.musicflow.data.repositories.CategoryGroup
import app.musicflow.data.repositories.ExploreCategory

@Composable
fun categoryLabel(c: ExploreCategory): String = stringResource(
    when (c) {
        ExploreCategory.RADIO_LOCAL -> R.string.cat_radio_local
        ExploreCategory.RADIO_WORLD -> R.string.cat_radio_world
        ExploreCategory.RADIO_POP -> R.string.cat_radio_pop
        ExploreCategory.RADIO_ROCK -> R.string.cat_radio_rock
        ExploreCategory.RADIO_DANCE -> R.string.cat_radio_dance
        ExploreCategory.RADIO_JAZZ -> R.string.cat_radio_jazz
        ExploreCategory.RADIO_CLASSICAL -> R.string.cat_radio_classical
        ExploreCategory.RADIO_NEWS -> R.string.cat_radio_news
        ExploreCategory.ELECTRONIC -> R.string.cat_electronic
        ExploreCategory.DANCE -> R.string.cat_dance
        ExploreCategory.EDM -> R.string.cat_edm
        ExploreCategory.HOUSE -> R.string.cat_house
        ExploreCategory.TRANCE -> R.string.cat_trance
        ExploreCategory.TECHNO -> R.string.cat_techno
        ExploreCategory.POP -> R.string.cat_pop
        ExploreCategory.ROCK -> R.string.cat_rock
        ExploreCategory.HIP_HOP -> R.string.cat_hiphop
        ExploreCategory.RNB -> R.string.cat_rnb
        ExploreCategory.CHILL -> R.string.cat_chill
        ExploreCategory.WORKOUT -> R.string.cat_workout
        ExploreCategory.HITS_80S -> R.string.cat_80s
        ExploreCategory.HITS_90S -> R.string.cat_90s
        ExploreCategory.HITS_2000S -> R.string.cat_2000s
        ExploreCategory.NEW_RELEASES -> R.string.cat_new_releases
        ExploreCategory.UK_CHARTS -> R.string.cat_uk_charts
        ExploreCategory.GLOBAL_CHARTS -> R.string.cat_global_charts
    },
)

@Composable
private fun groupLabel(g: CategoryGroup) = stringResource(
    when (g) {
        CategoryGroup.RADIO -> R.string.group_radio
        CategoryGroup.GENRE -> R.string.group_genre
        CategoryGroup.MOOD -> R.string.group_mood
        CategoryGroup.DECADE -> R.string.group_decade
        CategoryGroup.CHARTS -> R.string.group_charts
    },
)

@Composable
fun ExploreScreen(contentPadding: PaddingValues, vm: ExploreViewModel = hiltViewModel()) {
    val group by vm.group.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 20.dp, bottom = 4.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(R.string.explore_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.IconButton(onClick = { nav.openSettings() }) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Settings, stringResource(R.string.explore_settings))
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { PillChip(stringResource(R.string.group_all), group == null, { vm.group.value = null }) }
                    items(CategoryGroup.entries) { g ->
                        PillChip(groupLabel(g), group == g, { vm.group.value = g })
                    }
                }
            }
        }
        items(vm.categories(group), key = { it.name }) { c ->
            CategoryTile(categoryLabel(c), Color(c.color)) { nav.openCategory(c.name) }
        }
    }
}

@Composable
private fun CategoryTile(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f), MaterialTheme.colorScheme.surfaceContainerHigh)))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.align(Alignment.BottomStart))
    }
}

@Composable
fun CategoryScreen(contentPadding: PaddingValues, vm: CategoryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val newest by vm.newestFirst.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val color = Color(vm.category.color)
    val label = categoryLabel(vm.category)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), MaterialTheme.colorScheme.background)))) {
                Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)) {
                    IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.explore_back)) }
                    Text(categoryLabel(vm.category), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 16.dp))
                }
            }
        }
        when (val s = ui) {
            CategoryUi.Loading -> item { Column { SkeletonCardRow(); Spacer(Modifier.height(16.dp)); SkeletonTrackRows() } }
            is CategoryUi.Failed -> item { ErrorState(errorMessage(s.error), onRetry = vm::load) }
            is CategoryUi.Loaded -> {
                val tracks = vm.sorted(s.content.tracks, newest)
                if (s.content.playlists.isEmpty() && tracks.isEmpty()) item { MessageState(stringResource(R.string.explore_empty)) }
                if (s.content.playlists.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.explore_playlists)) }
                    item {
                        LazyRow(contentPadding = HorizontalListPadding, horizontalArrangement = HorizontalListSpacing) {
                            items(s.content.playlists, key = { it.id.value }) { PlaylistCard(it, onClick = { nav.openCollection(it.id) }) }
                        }
                    }
                }
                if (tracks.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.explore_songs)) }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { vm.play(tracks, 0, label) }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.explore_play)) }
                            FilledTonalButton(onClick = { vm.shuffle(tracks, label) }) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.explore_shuffle)) }
                            if (vm.canSortByDate(s.content.tracks)) {
                                PillChip(stringResource(R.string.explore_newest), newest, { vm.newestFirst.value = !newest })
                            }
                        }
                    }
                    itemsIndexed(tracks, key = { _, t -> t.id.value }) { i, t ->
                        TrackRow(t, onClick = { vm.play(tracks, i, label) }, onMore = { menu.open(t) })
                    }
                }
            }
        }
    }
}
