package app.musicflow.feature.player

import app.musicflow.core.designsystem.component.glass
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.style.TextAlign
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
    val output by vm.output.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val menu = LocalTrackMenu.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.NONE) }
    var more by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    BackHandler(onBack = { if (showLyrics) showLyrics = false else onCollapse() })

    val track = state.current
    val muted = Color.White.copy(alpha = 0.7f)
    val duration = progress.durationMs ?: state.durationMs ?: 0L
    val fraction = if (duration > 0) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    app.musicflow.core.designsystem.component.GlassBackdrop(
        Modifier
            .offset { IntOffset(0, dragY.coerceAtLeast(0f).roundToInt()) }
            .testTag("full_player")
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dragY > 220) onCollapse(); dragY = 0f },
                    onVerticalDrag = { _, d -> dragY += d },
                )
            },
        artworkUrl = track?.artworkUrl,
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top bar: collapse · "Now playing" (tap: queue) · more
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                app.musicflow.core.designsystem.component.GlassIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_collapse), onCollapse)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { sheet = PlayerSheet.QUEUE }.padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(outputIconOrPhone(output.kind), null, Modifier.size(14.dp), tint = muted)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            listOfNotNull(outputLabel(output), state.queueTitle).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    app.musicflow.core.designsystem.component.GlassIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.player_more), { more = true })
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_queue)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) }, onClick = { more = false; sheet = PlayerSheet.QUEUE })
                        track?.let { t -> DropdownMenuItem(text = { Text(stringResource(R.string.player_track_options)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { more = false; menu.open(t) }) }
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_quality)) }, leadingIcon = { Icon(Icons.Rounded.HighQuality, null) }, onClick = { more = false; sheet = PlayerSheet.QUALITY })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_sleep_timer)) }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null) }, onClick = { more = false; sheet = PlayerSheet.SLEEP })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_speed)) }, leadingIcon = { Icon(Icons.Rounded.Speed, null) }, onClick = { more = false; sheet = PlayerSheet.SPEED })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_equalizer)) }, leadingIcon = { Icon(Icons.Rounded.Equalizer, null) }, onClick = { more = false; onCollapse(); nav.openEqualizer() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.player_share)) }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) }, onClick = { more = false; track?.let { shareTrack(context, it) } })
                        track?.album?.id?.let { MediaId.parseOrNull(it) }?.let { albumId ->
                            DropdownMenuItem(text = { Text(track.album?.title.orEmpty()) }, leadingIcon = { Icon(Icons.Rounded.Album, null) }, onClick = { more = false; onCollapse(); nav.openCollection(albumId) })
                        }
                    }
                }
            }

            // Round artwork in a glass ring; swipe sideways to change track.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                var dragX by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier
                        .widthIn(max = 360.dp).fillMaxWidth(0.86f).aspectRatio(1f)
                        .offset { IntOffset(dragX.roundToInt() / 3, 0) }
                        .then(Modifier.glass(CircleShape, strong = true))
                        .padding(10.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (dragX < -150) vm.next() else if (dragX > 150) vm.previous(); dragX = 0f },
                                onHorizontalDrag = { _, d -> dragX += d },
                            )
                        },
                ) {
                    AnimatedContent(track, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "art") { t ->
                        Artwork(t?.artworkUrl, Modifier.fillMaxSize(), shape = CircleShape, contentDescription = t?.title, iconSize = 96.dp)
                    }
                }
            }

            // Title · artist with add-to-playlist and like
            Text(
                track?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { track?.let(menu::open) }) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.player_add_to_playlist), tint = Color.White)
                }
                Text(
                    track?.artistLine.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clickable(enabled = track?.artists?.firstOrNull()?.id != null) {
                        MediaId.parseOrNull(track?.artists?.firstOrNull()?.id)?.let { onCollapse(); nav.openArtist(it) }
                    },
                )
                IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.player_unlike else R.string.player_like),
                        Modifier.size(26.dp), tint = if (fav) app.musicflow.core.designsystem.component.Glass.Orchid else Color.White)
                }
            }

            // Waveform seek bar with times
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Formatting.duration(progress.positionMs), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(40.dp))
                app.musicflow.core.designsystem.component.WaveformSeekBar(
                    fraction = fraction, seed = track?.id?.value, enabled = duration > 0,
                    onSeek = { f -> vm.seek((f * duration).toLong()) },
                    modifier = Modifier.weight(1f).testTag("seek_bar"),
                )
                Text(if (duration > 0) Formatting.duration(duration) else "--:--", style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
            }
            QualityLine(status, onClick = { sheet = PlayerSheet.QUALITY })

            // Transport: previous · play/pause · next
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                app.musicflow.core.designsystem.component.GlassIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous), vm::previous, size = 56.dp, iconSize = 28.dp)
                Box(contentAlignment = Alignment.Center) {
                    app.musicflow.core.designsystem.component.GradientPlayButton(
                        if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                        vm::togglePlay, Modifier.testTag("play_pause"), size = 84.dp,
                    )
                    if (state.isBuffering && state.playWhenReady) CircularProgressIndicator(Modifier.size(84.dp), color = Color.White, strokeWidth = 2.dp)
                }
                app.musicflow.core.designsystem.component.GlassIconButton(Icons.Rounded.SkipNext, stringResource(R.string.player_next), vm::next, size = 56.dp, iconSize = 28.dp, enabled = state.hasNext)
            }

            status.lastError?.let { err ->
                Text(stringResource(err.messageRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp).clickable { vm.dismissError() })
            }

            // Bottom: shuffle · "Lyrics" tab · repeat
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = if (state.shuffle) app.musicflow.core.designsystem.component.Glass.Orchid else muted)
                }
                Spacer(Modifier.weight(1f))
                LyricsTab(stringResource(R.string.player_lyrics), up = true) { showLyrics = true }
                Spacer(Modifier.weight(1f))
                if (sleep != SleepTimerState.Off) Icon(Icons.Rounded.Bedtime, stringResource(R.string.player_sleep_timer), Modifier.padding(bottom = 14.dp).size(18.dp), tint = app.musicflow.core.designsystem.component.Glass.Orchid)
                IconButton(onClick = vm::cycleRepeat) {
                    Icon(
                        if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        stringResource(when (state.repeat) { RepeatMode.OFF -> R.string.player_repeat_off; RepeatMode.ALL -> R.string.player_repeat_all; RepeatMode.ONE -> R.string.player_repeat_one }),
                        tint = if (state.repeat != RepeatMode.OFF) app.musicflow.core.designsystem.component.Glass.Orchid else muted,
                    )
                }
            }
        }

        AnimatedVisibility(showLyrics, enter = slideInVertically(tween(300)) { it } + fadeIn(), exit = slideOutVertically(tween(250)) { it } + fadeOut()) {
            LyricsPanel(vm, onClose = { showLyrics = false })
        }
    }

    when (sheet) {
        PlayerSheet.QUEUE -> QueueSheet(vm) { sheet = PlayerSheet.NONE }
        PlayerSheet.LYRICS -> Unit // shown as the full-screen lyrics panel
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
