package app.musicflow.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.musicflow.core.common.Formatting
import app.musicflow.core.designsystem.component.Artwork
import app.musicflow.core.designsystem.component.messageRes
import app.musicflow.core.designsystem.navigation.LocalNavigator
import app.musicflow.core.designsystem.navigation.LocalTrackMenu
import app.musicflow.core.designsystem.theme.rememberArtworkColor
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.RepeatMode
import app.musicflow.playback.EngineStatus
import app.musicflow.playback.SleepTimerState
import app.musicflow.playback.engine.Progress
import kotlin.math.roundToInt

private enum class PlayerSheet { NONE, QUEUE, LYRICS, SLEEP, SPEED, QUALITY }

@Composable
fun FullPlayer(vm: PlayerViewModel, onCollapse: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle(Progress(0, 0, null))
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val status by vm.engineStatus.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val useArt by vm.artworkColors.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.NONE) }
    var more by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    BackHandler(onBack = onCollapse)

    val track = state.current
    val base = MaterialTheme.colorScheme.background
    val artColor = rememberArtworkColor(track?.artworkUrl, app.musicflow.core.designsystem.theme.LocalMfExtras.current.gradientTop, enabled = useArt)
    val top by animateColorAsState(artColor, tween(600), label = "bg")
    val muted = Color.White.copy(alpha = 0.65f)

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.coerceAtLeast(0f).roundToInt()) }
            .background(Brush.verticalGradient(0f to top, 0.55f to androidx.compose.ui.graphics.lerp(top, base, 0.75f), 1f to base))
            .testTag("full_player")
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dragY > 220) onCollapse(); dragY = 0f },
                    onVerticalDrag = { _, d -> dragY += d },
                )
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            // Drag handle (tap also closes)
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp).clickable(onClickLabel = stringResource(R.string.player_collapse), onClick = onCollapse), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
            }
            // "Playing on <device>" + lyrics
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(outputIconOrPhone(output.kind), null, Modifier.size(26.dp), tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_playing_on), style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(outputLabel(output), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { sheet = PlayerSheet.LYRICS }) { Icon(Icons.Rounded.Mic, stringResource(R.string.player_lyrics), tint = Color.White) }
            }
            Spacer(Modifier.height(20.dp))

            // Artwork: swipe horizontally to change track.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                var dragX by remember { mutableFloatStateOf(0f) }
                AnimatedContent(track, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "art") { t ->
                    Artwork(
                        t?.artworkUrl,
                        Modifier
                            .widthIn(max = 440.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .offset { IntOffset(dragX.roundToInt() / 3, 0) }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragEnd = { if (dragX < -150) vm.next() else if (dragX > 150) vm.previous(); dragX = 0f },
                                    onHorizontalDrag = { _, d -> dragX += d },
                                )
                            },
                        shape = RoundedCornerShape(22.dp),
                        contentDescription = t?.title,
                        iconSize = 96.dp,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))

            // Title / artist + like + share
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        track?.artistLine.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = track?.artists?.firstOrNull()?.id != null) {
                            MediaId.parseOrNull(track?.artists?.firstOrNull()?.id)?.let { onCollapse(); nav.openArtist(it) }
                        },
                    )
                }
                IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.player_unlike else R.string.player_like),
                        Modifier.size(28.dp), tint = if (fav) MaterialTheme.colorScheme.primary else Color.White)
                }
                IconButton(onClick = { track?.let { shareTrack(context, it) } }) {
                    Icon(Icons.Rounded.IosShare, stringResource(R.string.player_share), Modifier.size(26.dp), tint = Color.White)
                }
            }

            ProgressSection(progress, state.durationMs, vm::seek)
            QualityLine(status, onClick = { sheet = PlayerSheet.QUALITY })

            // Transport controls
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = if (state.shuffle) MaterialTheme.colorScheme.primary else muted)
                }
                IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(40.dp), tint = Color.White) }
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = vm::togglePlay, modifier = Modifier.size(80.dp).testTag("play_pause")) {
                        Icon(if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play), Modifier.size(64.dp), tint = Color.White)
                    }
                    if (state.isBuffering && state.playWhenReady) CircularProgressIndicator(Modifier.size(78.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                }
                IconButton(onClick = vm::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), Modifier.size(40.dp), tint = Color.White.copy(alpha = if (state.hasNext) 1f else 0.4f))
                }
                IconButton(onClick = vm::cycleRepeat) {
                    Icon(
                        if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        stringResource(when (state.repeat) { RepeatMode.OFF -> R.string.player_repeat_off; RepeatMode.ALL -> R.string.player_repeat_all; RepeatMode.ONE -> R.string.player_repeat_one }),
                        tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else muted,
                    )
                }
            }

            status.lastError?.let { err ->
                Text(stringResource(err.messageRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 4.dp).clickable { vm.dismissError() })
            }

            // "Playing from <source>" + queue + more
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp).clip(RoundedCornerShape(12.dp)).clickable { sheet = PlayerSheet.QUEUE }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue), Modifier.padding(8.dp).size(24.dp), tint = Color.White)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.player_playing_from), style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(state.queueTitle ?: stringResource(R.string.player_queue_default), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (sleep != SleepTimerState.Off) Icon(Icons.Rounded.Bedtime, stringResource(R.string.player_sleep_timer), Modifier.padding(end = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.player_more), tint = Color.White) }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_quality)) }, leadingIcon = { Icon(Icons.Rounded.HighQuality, null) }, onClick = { more = false; sheet = PlayerSheet.QUALITY })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_sleep_timer)) }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) }, onClick = { more = false; sheet = PlayerSheet.SLEEP })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_speed)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { more = false; sheet = PlayerSheet.SPEED })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_equalizer)) }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) }, onClick = { more = false; onCollapse(); nav.openEqualizer() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_lyrics)) }, leadingIcon = { Icon(Icons.Rounded.Lyrics, null) }, onClick = { more = false; sheet = PlayerSheet.LYRICS })
                        track?.let { t -> DropdownMenuItem(text = { Text(stringResource(R.string.player_track_options)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { more = false; menu.open(t) }) }
                        track?.album?.id?.let { MediaId.parseOrNull(it) }?.let { albumId ->
                            DropdownMenuItem(text = { Text(track.album?.title.orEmpty()) }, leadingIcon = { Icon(Icons.Rounded.Album, null) }, onClick = { more = false; onCollapse(); nav.openCollection(albumId) })
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        PlayerSheet.QUEUE -> QueueSheet(vm) { sheet = PlayerSheet.NONE }
        PlayerSheet.LYRICS -> LyricsSheet(vm) { sheet = PlayerSheet.NONE }
        PlayerSheet.SLEEP -> SleepTimerDialog(vm, sleep) { sheet = PlayerSheet.NONE }
        PlayerSheet.SPEED -> SpeedDialog(state.speed, vm::setSpeed) { sheet = PlayerSheet.NONE }
        PlayerSheet.QUALITY -> QualityDialog(vm) { sheet = PlayerSheet.NONE }
        PlayerSheet.NONE -> Unit
    }
}

/** Shares a link to the track (YouTube Music for YouTube tracks, the URL for direct links). */
private fun shareTrack(context: android.content.Context, t: app.musicflow.core.model.Track) {
    val link = when (t.id.providerId) {
        "yt" -> "https://music.youtube.com/watch?v=${t.id.key}"
        "url" -> t.id.key
        else -> null
    }
    val text = listOfNotNull("${t.artistLine} – ${t.title}".trim(' ', '–'), link).joinToString("\n")
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProgressSection(progress: Progress, fallbackDuration: Long?, onSeek: (Long) -> Unit) {
    val duration = progress.durationMs ?: fallbackDuration ?: 0L
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val fraction = scrubbing ?: if (duration > 0) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(top = 14.dp)) {
        Slider(
            value = fraction,
            onValueChange = { scrubbing = it },
            onValueChangeFinished = { scrubbing?.let { onSeek((it * duration).toLong()) }; scrubbing = null },
            enabled = duration > 0,
            thumb = { Box(Modifier.size(if (scrubbing != null) 14.dp else 10.dp).clip(CircleShape).background(Color.White)) },
            track = { st ->
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(st.value).height(3.dp).background(Color.White))
                }
            },
            modifier = Modifier.height(24.dp).testTag("seek_bar"),
        )
        val shown = (fraction * duration).toLong()
        Row {
            Text(Formatting.duration(shown), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) Formatting.duration(duration) else "--:--", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun QualityLine(status: EngineStatus, onClick: () -> Unit) {
    val stream = status.stream
    val format = status.format
    val text = when {
        stream != null && stream.describe().isNotEmpty() -> stream.describe()
        format != null -> listOfNotNull(
            format.sampleMimeType?.substringAfter('/')?.uppercase(),
            format.bitrateKbps?.let { "$it kbps" },
            format.sampleRateHz?.let { "${it / 1000} kHz" },
        ).joinToString(" · ")
        else -> ""
    }
    Text(
        text.ifEmpty { stringResource(R.string.player_quality) } + "  ›",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp).testTag("quality_line"),
    )
}

@Composable
private fun QualityDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val active by vm.activeQuality.collectAsStateWithLifecycle()
    val status by vm.engineStatus.collectAsStateWithLifecycle()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_quality)) },
        text = {
            Column {
                Text(
                    stringResource(if (vm.isMetered) R.string.player_quality_for_mobile else R.string.player_quality_for_wifi),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                app.musicflow.core.model.AudioQuality.entries.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setQuality(q); onDismiss() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = q == active, onClick = { vm.setQuality(q); onDismiss() })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(stringResource(qualityName(q)), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(qualityHint(q)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                status.stream?.describe()?.takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.player_quality_now, it), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
                Text(
                    status.normalizationDb?.let { db ->
                        stringResource(R.string.player_norm_applied, "%+.1f".format(db), "%.1f".format(status.loudnessLufs ?: 0f))
                    } ?: stringResource(R.string.player_norm_none),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp),
                )
                Text(stringResource(R.string.player_quality_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_close)) } },
    )
}

private fun qualityName(q: app.musicflow.core.model.AudioQuality) = when (q) {
    app.musicflow.core.model.AudioQuality.DATA_SAVER -> R.string.player_q_saver
    app.musicflow.core.model.AudioQuality.STANDARD -> R.string.player_q_standard
    app.musicflow.core.model.AudioQuality.HIGH -> R.string.player_q_high
    app.musicflow.core.model.AudioQuality.BEST -> R.string.player_q_best
}

private fun qualityHint(q: app.musicflow.core.model.AudioQuality) = when (q) {
    app.musicflow.core.model.AudioQuality.DATA_SAVER -> R.string.player_q_saver_hint
    app.musicflow.core.model.AudioQuality.STANDARD -> R.string.player_q_standard_hint
    app.musicflow.core.model.AudioQuality.HIGH -> R.string.player_q_high_hint
    app.musicflow.core.model.AudioQuality.BEST -> R.string.player_q_best_hint
}
