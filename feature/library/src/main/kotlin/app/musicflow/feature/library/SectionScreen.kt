package app.musicflow.feature.library

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import app.musicflow.core.designsystem.component.SkeletonTrackRows
import app.musicflow.core.designsystem.component.TrackRow
import app.musicflow.core.designsystem.navigation.LibrarySection
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import java.text.DateFormat
import java.util.Date

@Composable
fun sectionTitle(s: LibrarySection) = stringResource(
    when (s) {
        LibrarySection.FAVORITES -> R.string.lib_favorites
        LibrarySection.RECENT -> R.string.lib_recent
        LibrarySection.MOST_PLAYED -> R.string.lib_most_played
        LibrarySection.HISTORY -> R.string.lib_history
        LibrarySection.LOCAL -> R.string.lib_local
    },
)

@Composable
fun LibrarySectionScreen(contentPadding: PaddingValues, vm: LibrarySectionViewModel = hiltViewModel()) {
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val content by vm.content.collectAsStateWithLifecycle()
    val title = sectionTitle(vm.section)
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            BackBar(nav::back, sectionTitle(vm.section)) {
                if (vm.section == LibrarySection.HISTORY) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.lib_clear_history)) }
            }
        }
        when (val c = content) {
            SectionContent.Loading -> item { SkeletonTrackRows() }
            SectionContent.NeedsPermission -> item {
                MessageState(
                    stringResource(R.string.lib_local_permission_title), body = stringResource(R.string.lib_local_permission_body),
                    icon = Icons.Rounded.PhoneAndroid, actionLabel = stringResource(R.string.lib_allow),
                    onAction = { permission.launch(vm.localPermission) },
                )
            }
            is SectionContent.Tracks -> {
                if (c.tracks.isEmpty()) item { MessageState(stringResource(R.string.lib_section_empty)) }
                else {
                    item {
                        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            androidx.compose.material3.Button(onClick = { vm.play(c.tracks, 0, source = title) }) { Text(stringResource(R.string.lib_play)) }
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                            androidx.compose.material3.FilledTonalButton(onClick = { vm.play(c.tracks, 0, shuffle = true, source = title) }) { Text(stringResource(R.string.lib_shuffle)) }
                        }
                    }
                    itemsIndexed(c.tracks, key = { _, t -> t.id.value }) { i, t ->
                        TrackRow(t, onClick = { vm.play(c.tracks, i, source = title) }, onMore = { menu.open(t) }, leadingIndex = if (vm.section == LibrarySection.MOST_PLAYED) i + 1 else null)
                    }
                }
            }
            is SectionContent.History -> {
                if (c.entries.isEmpty()) item { MessageState(stringResource(R.string.lib_section_empty)) }
                val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                // Tapping a song plays it and then the following songs of the list (not just one).
                val all = c.entries.map { it.track }
                c.entries.groupBy { fmt.format(Date(it.playedAt)) }.forEach { (day, list) ->
                    item(key = "d$day") { Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                    items(list, key = { "e${it.eventId}" }) { e -> TrackRow(e.track, onClick = { vm.play(all, c.entries.indexOf(e), source = title) }, onMore = { menu.open(e.track) }) }
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog(stringResource(R.string.lib_clear_history), stringResource(R.string.lib_clear_history_body), stringResource(R.string.lib_clear), { confirmClear = false }, vm::clearHistory)
}

@Suppress("unused")
private val isTiramisu = Build.VERSION.SDK_INT >= 33
