package app.musicflow.playback.engine

import app.musicflow.core.model.RepeatMode
import app.musicflow.core.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class QueueEntry(val index: Int, val uid: String, val track: Track)

data class PlaybackState(
    val connected: Boolean = false,
    val current: Track? = null,
    val currentIndex: Int = -1,
    val queue: List<QueueEntry> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playWhenReady: Boolean = false,
    val durationMs: Long? = null,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val errorMessage: String? = null,
    /** Where the queue came from ("Playing from …"), e.g. an album or playlist name. */
    val queueTitle: String? = null,
)

data class Progress(val positionMs: Long, val bufferedMs: Long, val durationMs: Long?)

/** Playback commands and state for the UI. Implemented on top of a Media3 MediaController. */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>

    /** Position updates (every ~250 ms while playing, on demand otherwise). */
    val progress: Flow<Progress>

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, source: String? = null)

    /**
     * Prepares the streams of tracks the user is likely to play next (top search results, first
     * songs of a list) in the background, so tapping them starts almost instantly.
     */
    fun prefetch(tracks: List<Track>) {}
    fun playNext(track: Track)
    fun addToQueue(tracks: List<Track>)
    fun togglePlayPause()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipToQueueItem(index: Int)
    fun removeFromQueue(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun clearQueue()
    fun setShuffle(enabled: Boolean)
    fun cycleRepeat()
    fun setSpeed(speed: Float)
    fun toggleFavoriteFromNotificationState() {}
}
