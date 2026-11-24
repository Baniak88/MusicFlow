package app.musicflow

import app.musicflow.core.model.RepeatMode
import app.musicflow.core.model.Track
import app.musicflow.playback.engine.PlaybackEngine
import app.musicflow.playback.engine.PlaybackState
import app.musicflow.playback.engine.Progress
import app.musicflow.playback.engine.QueueEntry
import app.musicflow.playback.service.PlaybackModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import javax.inject.Singleton

/** In-memory engine for UI tests (Robolectric cannot bind a real MediaSessionService). */
class FakePlaybackEngine : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackState(connected = true))
    override val state: StateFlow<PlaybackState> = _state
    override val progress: Flow<Progress> = flowOf(Progress(0, 0, null))
    private fun set(queue: List<Track>, index: Int, playing: Boolean = true) = _state.update {
        it.copy(queue = queue.mapIndexed { i, t -> QueueEntry(i, "${t.id}#$i", t) }, currentIndex = index, current = queue.getOrNull(index),
            isPlaying = playing, playWhenReady = playing, hasNext = index < queue.lastIndex, hasPrevious = index > 0, durationMs = queue.getOrNull(index)?.durationMs)
    }
    private val q get() = _state.value.queue.map { it.track }
    override fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) = set(tracks, startIndex)
    override fun playNext(track: Track) = set(q.toMutableList().apply { add((_state.value.currentIndex + 1).coerceAtMost(size), track) }, _state.value.currentIndex.coerceAtLeast(0))
    override fun addToQueue(tracks: List<Track>) = set(q + tracks, _state.value.currentIndex.coerceAtLeast(0))
    override fun togglePlayPause() = _state.update { it.copy(isPlaying = !it.isPlaying, playWhenReady = !it.playWhenReady) }
    override fun pause() = _state.update { it.copy(isPlaying = false, playWhenReady = false) }
    override fun next() { if (_state.value.hasNext) set(q, _state.value.currentIndex + 1) }
    override fun previous() { if (_state.value.currentIndex > 0) set(q, _state.value.currentIndex - 1) }
    override fun seekTo(positionMs: Long) = Unit
    override fun skipToQueueItem(index: Int) = set(q, index)
    override fun removeFromQueue(index: Int) = set(q.filterIndexed { i, _ -> i != index }, _state.value.currentIndex.coerceAtMost(q.size - 2))
    override fun moveQueueItem(from: Int, to: Int) = set(q.toMutableList().apply { add(to, removeAt(from)) }, _state.value.currentIndex)
    override fun clearQueue() = set(emptyList(), -1, playing = false)
    override fun setShuffle(enabled: Boolean) = _state.update { it.copy(shuffle = enabled) }
    override fun cycleRepeat() = _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % 3]) }
    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PlaybackModule::class])
object FakePlaybackModule {
    @Provides @Singleton fun engine(): PlaybackEngine = FakePlaybackEngine()
}
