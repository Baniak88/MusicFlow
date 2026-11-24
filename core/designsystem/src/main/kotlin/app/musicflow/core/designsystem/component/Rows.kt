package app.musicflow.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicflow.core.common.Formatting
import app.musicflow.core.designsystem.R
import app.musicflow.core.model.Album
import app.musicflow.core.model.Artist
import app.musicflow.core.model.PlaylistSummary
import app.musicflow.core.model.Track

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    showArtwork: Boolean = true,
    leadingIndex: Int? = null,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: onMore, role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIndex != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                if (isPlaying) Icon(Icons.Rounded.Equalizer, null, tint = MaterialTheme.colorScheme.primary)
                else Text("$leadingIndex", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (showArtwork) {
            Artwork(track.artworkUrl, Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(track.artistLine.ifEmpty { null }, track.album?.title).joinToString(" • ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        track.durationMs?.let {
            Spacer(Modifier.width(8.dp))
            Text(Formatting.duration(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.mf_more_options)) }
        }
    }
}

@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 152.dp,
    circular: Boolean = false,
) {
    Column(
        modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickableNoLong(onClick)
            .padding(bottom = 4.dp),
        horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(
            artworkUrl,
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = if (circular) CircleShape else MaterialTheme.shapes.medium,
            contentDescription = title,
            iconSize = 40.dp,
        )
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoLong(onClick: () -> Unit) = this.combinedClickable(onClick = onClick, role = Role.Button)


@Composable fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) =
    MediaCard(album.title, listOfNotNull(album.artistLine.ifEmpty { null }, album.year?.toString()).joinToString(" • "), album.artworkUrl, onClick, modifier)

@Composable fun PlaylistCard(p: PlaylistSummary, onClick: () -> Unit, modifier: Modifier = Modifier) =
    MediaCard(p.title, p.owner, p.artworkUrl, onClick, modifier)

@Composable fun ArtistCard(a: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) =
    MediaCard(a.name, null, a.artworkUrl, onClick, modifier, width = 128.dp, circular = true)

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.mf_see_all))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}

val HorizontalListPadding = PaddingValues(horizontal = 16.dp)
val HorizontalListSpacing = Arrangement.spacedBy(14.dp)

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
