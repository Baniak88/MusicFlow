package app.musicflow.playback.engine

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.musicflow.core.common.ApplicationScope
import app.musicflow.core.model.RepeatMode
import app.musicflow.core.model.Track
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.playback.MediaItems
import app.musicflow.playback.service.MusicService
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to [MusicService] through a [MediaController] (the same API Android Auto and the
 * system UI use) and exposes its state as flows. Commands issued before the connection is
 * established are queued and replayed once connected.
 */
@UnstableApi
@Singleton
class MediaControllerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val library: LibraryRepository,
) : PlaybackEngine {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var connecting = false

    init { connect() }

    private fun connect() {
        if (controller != null || connecting) return
        connecting = true
        scope.launch {
            runCatching {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                MediaController.Builder(context, token).buildAsync().await()
            }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                restoreIfEmpty(c)
                refresh(c)
                while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
            }.onFailure {
                _state.value = _state.value.copy(errorMessage = it.message)
            }
            connecting = false
        }
    }

    /** Shows the last session's queue (paused) after a cold start, like most music apps. */
    private suspend fun restoreIfEmpty(c: MediaController) {
        if (c.mediaItemCount > 0) return
        val tracks = runCatching { library.restoreQueue() }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return
        val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
        val index = prefs.getInt(MusicService.KEY_INDEX, 0).coerceIn(0, tracks.lastIndex)
        c.setMediaItems(tracks.map(MediaItems::toMediaItem), index, prefs.getLong(MusicService.KEY_POSITION, 0))
        c.shuffleModeEnabled = prefs.getBoolean(MusicService.KEY_SHUFFLE, false)
        c.repeatMode = prefs.getInt(MusicService.KEY_REPEAT, Player.REPEAT_MODE_OFF)
        c.prepare()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending.addLast(block); connect() }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    private fun refresh(p: Player) {
        val queue = (0 until p.mediaItemCount).mapNotNull { i ->
            val item = p.getMediaItemAt(i)
            MediaItems.toTrack(item)?.let { QueueEntry(i, "${item.mediaId}#$i", it) }
        }
        val current = p.currentMediaItem?.let(MediaItems::toTrack)
        _state.value = PlaybackState(
            connected = true,
            current = current,
            currentIndex = p.currentMediaItemIndex,
            queue = queue,
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            playWhenReady = p.playWhenReady,
            durationMs = p.duration.takeIf { it > 0 } ?: current?.durationMs,
            shuffle = p.shuffleModeEnabled,
            repeat = MusicService.repeatOf(p.repeatMode),
            speed = p.playbackParameters.speed,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem() || p.currentPosition > 3_000,
            errorMessage = p.playerError?.message,
        )
    }

    override val progress: Flow<Progress> = flow {
        while (true) {
            val c = controller
            if (c != null) emit(Progress(c.currentPosition.coerceAtLeast(0), c.bufferedPosition, c.duration.takeIf { it > 0 }))
            delay(if (c?.isPlaying == true) 250 else 1_000)
        }
    }

    private fun items(tracks: List<Track>): List<MediaItem> = tracks.map(MediaItems::toMediaItem)

    override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        scope.launch { runCatching { library.rememberAll(tracks) } }
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle && startIndex == 0) tracks.indices.random() else startIndex.coerceIn(0, tracks.lastIndex)
            c.setMediaItems(items(tracks), start, 0)
            c.prepare()
            c.play()
        }
    }

    override fun playNext(track: Track) = withController { c ->
        if (c.mediaItemCount == 0) { play(listOf(track)); return@withController }
        c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.toMediaItem(track))
    }

    override fun addToQueue(tracks: List<Track>) = withController { c ->
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItems(items(tracks))
        if (wasEmpty) { c.prepare(); c.play() }
    }

    override fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    override fun pause() = withController { it.pause() }
    override fun next() = withController { it.seekToNext() }
    override fun previous() = withController { it.seekToPrevious() }
    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    override fun skipToQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) { c.seekToDefaultPosition(index); c.prepare(); c.play() }
    }
    override fun removeFromQueue(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }
    override fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }
    override fun clearQueue() = withController { it.clearMediaItems() }
    override fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }
    override fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    override fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 2f)) }

    companion object {
        fun repeatLabel(r: RepeatMode) = r.name
    }
}
