package app.musicflow.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.core.designsystem.component.messageRes
import app.musicflow.core.designsystem.navigation.LocalSnackbarHost
import app.musicflow.core.model.ProviderError
import kotlinx.coroutines.flow.Flow

@Composable
fun BackBar(onBack: () -> Unit, title: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.lib_back)) }
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

/** Large header with artwork, title, subtitle and play/shuffle buttons. */
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onBack: () -> Unit,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    circleArt: Boolean = false,
    actions: @Composable () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), MaterialTheme.colorScheme.background)))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            BackBar(onBack, actions = actions)
            Artwork(artworkUrl, Modifier.size(220.dp), shape = if (circleArt) CircleShape else MaterialTheme.shapes.large, iconSize = 64.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            extra()
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                onPlay?.let { Button(onClick = it) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lib_play)) } }
                onShuffle?.let { FilledTonalButton(onClick = it) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.lib_shuffle)) } }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun TextInputDialog(title: String, initial: String = "", confirm: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text); onDismiss() }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lib_cancel)) } },
    )
}

/** Shows [LibraryEvent]s as snackbars and handles navigation events. */
@Composable
fun LibraryEventsEffect(events: Flow<LibraryEvent>, onOpenPlaylist: (Long) -> Unit = {}) {
    val host = LocalSnackbarHost.current
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    LaunchedEffect(events) {
        events.collect { e ->
            val msg = when (e) {
                is LibraryEvent.Imported -> resources.getQuantityString(R.plurals.lib_imported, e.count, e.count) +
                    if (e.skipped > 0) " " + resources.getQuantityString(R.plurals.lib_skipped, e.skipped, e.skipped) else ""
                is LibraryEvent.Failed -> resources.getString((e.error as? ProviderError)?.messageRes() ?: app.musicflow.core.designsystem.R.string.mf_error_unknown)
                is LibraryEvent.Added -> if (e.result.added == 0 && e.result.duplicates.isNotEmpty()) resources.getString(R.string.lib_already_in_playlist, e.playlistName)
                    else resources.getString(R.string.lib_added_to, e.playlistName)
                LibraryEvent.Exported -> resources.getString(R.string.lib_exported)
                is LibraryEvent.OpenPlaylist -> { onOpenPlaylist(e.id); null }
            }
            msg?.let { host.showSnackbar(it) }
        }
    }
}

@Composable
fun trackCount(n: Int) = pluralStringResource(R.plurals.lib_tracks, n, n)
