package app.musicflow.playback.service

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import app.musicflow.core.common.ApplicationScope
import app.musicflow.core.common.DiagnosticsLog
import app.musicflow.core.model.AudioQuality
import app.musicflow.core.model.MediaId
import app.musicflow.core.model.ProviderError
import app.musicflow.core.model.RepeatMode
import app.musicflow.core.model.ResolvedStream
import app.musicflow.core.model.Track
import app.musicflow.core.model.UserSettings
import app.musicflow.core.network.ConnectivityMonitor
import app.musicflow.data.providers.local.LocalMusicProvider
import app.musicflow.data.providers.registry.ProviderRegistry
import app.musicflow.data.repositories.CatalogRepository
import app.musicflow.data.repositories.LibraryRepository
import app.musicflow.data.repositories.PlaylistRepository
import app.musicflow.data.repositories.SearchRepository
import app.musicflow.data.repositories.SettingsRepository
import app.musicflow.playback.ActiveFormat
import app.musicflow.playback.MediaItems
import app.musicflow.playback.PlaybackRuntime
import app.musicflow.playback.R
import app.musicflow.playback.SleepTimer
import app.musicflow.playback.SleepTimerState
import app.musicflow.playback.effects.AudioEffects
import app.musicflow.playback.stream.PlaybackDataSources
import app.musicflow.playback.stream.StreamErrorClassifier
import app.musicflow.playback.stream.StreamErrorPolicy
import app.musicflow.playback.stream.StreamRepository
import app.musicflow.playback.stream.StreamResolutionException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Foreground media service (type `mediaPlayback`). Runs ExoPlayer inside a
 * [MediaLibraryService] so that playback continues with the screen off or the app in the
 * background, and is controllable from the notification, lock screen, Bluetooth / wired
 * headsets, car head units and Android Auto.
 */
@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject lateinit var client: OkHttpClient
    @Inject lateinit var registry: ProviderRegistry
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var search: SearchRepository
    @Inject lateinit var playlists: PlaylistRepository
    @Inject lateinit var catalog: CatalogRepository
    @Inject lateinit var explore: app.musicflow.data.repositories.ExploreRepository
    @Inject lateinit var connectivity: ConnectivityMonitor
    @Inject lateinit var diagnostics: DiagnosticsLog
    @Inject lateinit var runtime: PlaybackRuntime
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var effects: AudioEffects
    @Inject lateinit var cacheHolder: AudioCacheHolder
    @Inject lateinit var localProvider: LocalMusicProvider
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var crossfader: Crossfader
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private var crossfadeJob: Job? = null
    private lateinit var session: MediaLibrarySession
    private lateinit var streams: StreamRepository
    private lateinit var prefs: SharedPreferences

    @Volatile private var settings = UserSettings()

    /** Measures and levels live radio (stations carry no loudness data). */
    private val liveLoudness = app.musicflow.playback.effects.LiveLoudnessProcessor()
    private var listening: ListeningSession? = null
    private var consecutiveAutoSkips = 0
    private var continuationJob: Job? = null
    private var persistJob: Job? = null
    private var sleepJob: Job? = null
    private var networkRetryJob: Job? = null
    private val continuedFrom = HashSet<String>()

    private data class ListeningSession(val track: Track, val startedAt: Long, var accumulatedMs: Long = 0, var resumedAt: Long? = null) {
        fun pause(now: Long) { resumedAt?.let { accumulatedMs += now - it }; resumedAt = null }
        fun total(now: Long) = accumulatedMs + (resumedAt?.let { now - it } ?: 0)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("playback_state", MODE_PRIVATE)
        settings = kotlinx.coroutines.runBlocking { settingsRepo.current() }

        streams = StreamRepository(
            resolveRemote = { id, q -> registry.callFor(id, "resolve") { p -> p.streams?.resolve(id, q) ?: throw ProviderError.NotSupported() } },
            quality = ::currentQuality,
            diagnostics = diagnostics,
            alternative = ::localAlternative,
        )
        runtime.prefetcher = { t -> streams.resolve(t.id, t) }

        player = buildPlayer(settings)
        player.addListener(playerListener)
        crossfader = Crossfader(ExoCrossfadePlayer(player), { ExoCrossfadePlayer(buildFader()) }, setMainGain = { g -> crossfadeGain = g; applyVolume() }, log = { diagnostics.i("crossfade", it) }).apply {
            fadeMs = settings.crossfadeSeconds * 1000L
            blocked = { sleepTimer.state.value == SleepTimerState.EndOfTrack }
        }
        player.addAnalyticsListener(analyticsListener)

        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val callback = LibraryCallback(this, scope, library, playlists, search, catalog, explore, localProvider, ::onFavoriteCommand, ::restoreForResumption)
        session = MediaLibrarySession.Builder(this, player, callback)
            .apply { sessionActivity?.let(::setSessionActivity) }
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_musicflow) },
        )
        runtime.update { it.copy(serviceRunning = true) }
        diagnostics.i("service", "Playback service started")

        observeSettings()
        observeConnectivity()
        observeSleepTimer()
        runCrossfadeLoop()
    }

    /** Checks ~10×/s while playing whether a crossfade should start or progress. */
    private fun runCrossfadeLoop() {
        crossfadeJob = scope.launch {
            while (isActive) {
                if (crossfader.fadeMs > 0 && (player.isPlaying || crossfader.isActive)) {
                    crossfader.tick()
                    delay(50)
                } else delay(500)
            }
        }
    }

    /** Session-less player that plays the outgoing track during a crossfade. */
    private fun buildFader(): ExoPlayer = ExoPlayer.Builder(this)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build()

    /**
     * Re-resolves the current remote track at the new quality without losing the position
     * (used when the user changes audio quality while listening).
     */
    private fun reloadCurrentForQuality() {
        val item = player.currentMediaItem ?: return
        val id = MediaId.parseOrNull(item.mediaId) ?: return
        if (id.providerId == "local") return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        crossfader.finish()
        reloadingIndex = index
        val reloaded = item.buildUpon().setUri(MediaItems.placeholderUri(id, revision = System.currentTimeMillis())).build()
        player.replaceMediaItem(index, reloaded)
        player.seekTo(index, position)
        diagnostics.i("playback", "Quality changed: reloading current track at ${position / 1000}s")
    }
    private var reloadingIndex: Int? = null

    // ---------------------------------------------------------------- volume & normalisation

    /** Player volume = normalisation × crossfade × sleep-timer fade, so features never fight. */
    private var normalizationGain = 1f
    private var crossfadeGain = 1f
    private var sleepGain = 1f

    private fun applyVolume() { player.volume = (normalizationGain * crossfadeGain * sleepGain).coerceIn(0f, 1f) }

    /** Applies per-track loudness normalisation for the current track (no data → unity gain). */
    private fun applyNormalization(lufs: Float?, source: app.musicflow.playback.LoudnessSource) {
        val gain = app.musicflow.playback.LoudnessNormalizer.gainFor(lufs, settings.normalization, source)
        normalizationGain = gain?.volume ?: 1f
        effects.setNormalizationBoost(gain?.boostMb ?: 0)
        applyVolume()
        runtime.update { it.copy(normalizationDb = gain?.gainDb, loudnessLufs = lufs, loudnessSource = gain?.source) }
    }

    private fun refreshNormalizationFromStream() {
        val id = player.currentMediaItem?.mediaId?.let(MediaId::parseOrNull) ?: return
        scope.launch {
            val lufs = streams.cached(id)?.loudnessLufs
            if (lufs != null || runtime.status.value.loudnessSource != app.musicflow.playback.LoudnessSource.REPLAYGAIN) {
                applyNormalization(lufs, app.musicflow.playback.LoudnessSource.PROVIDER)
            }
        }
    }

    // ---------------------------------------------------------------- player

    private fun buildPlayer(s: UserSettings): ExoPlayer {
        val dataSources = PlaybackDataSources.factory(this, client, cacheHolder.cache, streams, allowCleartextMedia = { settings.radioAllowHttp })
        val errorPolicy = StreamErrorPolicy(streams, diagnostics) { connectivity.current().isConnected }
        mediaSourceFactory = DefaultMediaSourceFactory(dataSources).setLoadErrorHandlingPolicy(errorPolicy)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(s.bufferProfile.minBufferMs, s.bufferProfile.maxBufferMs, 1_000, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        liveLoudness.targetLufs = s.normalization.targetLufs
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(liveLoudness))
                    .build()
        }.setEnableAudioFloatOutput(false)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true, // ducks for notifications, pauses for calls/other apps
            )
            .setHandleAudioBecomingNoisy(s.pauseOnDisconnect) // headphones unplugged / BT disconnected
            .setWakeMode(C.WAKE_MODE_NETWORK) // keeps CPU + Wi-Fi alive while playing with screen off
            .setSkipSilenceEnabled(s.skipSilence)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    private fun currentQuality(): AudioQuality {
        val net = connectivity.current()
        return if (net.isMetered) settings.qualityMobile else settings.qualityWifi
    }

    /** Uses an on-device copy only when title, artist and duration match reliably. */
    private suspend fun localAlternative(track: Track): ResolvedStream? {
        if (track.id.providerId == "local" || !localProvider.hasPermission()) return null
        val match = localProvider.scan().firstOrNull { StreamRepository.isReliableAlternative(track, it) } ?: return null
        return localProvider.resolve(match.id, AudioQuality.BEST)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val now = System.currentTimeMillis()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && reloadingIndex != null) {
                reloadingIndex = null // same track re-resolved at a new quality: not a new listen
                return
            }
            val viaCrossfade = crossfader.consumeSelfTransition()
            if (!viaCrossfade && crossfader.isActive) crossfader.finish() // user skipped during a fade
            finishListening(userSkipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !viaCrossfade, now = now)
            mediaItem?.let(MediaItems::toTrack)?.let { t ->
                listening = ListeningSession(t, now, resumedAt = if (player.isPlaying) now else null)
                scope.launch { runCatching { library.remember(t) } }
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) consecutiveAutoSkips = 0
            runtime.update { it.copy(stream = null, format = null, loudnessSource = null) }
            liveLoudness.live = mediaItem?.mediaId?.startsWith("radio:") == true
            refreshNormalizationFromStream()
            effects.ensureActive()
            runtime.clearError()
            maybeContinueQueue()
            prefetchNext()
            schedulePersist()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            listening?.let { if (isPlaying) it.resumedAt = now else it.pause(now) }
            crossfader.onMainPlayingChanged(isPlaying)
            if (isPlaying) effects.ensureActive() // compression/EQ may have been taken over meanwhile
            if (!isPlaying) persistPosition()
        }

        override fun onPlaybackStateChanged(state: Int) {
            runtime.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
            if (state == Player.STATE_READY) {
                consecutiveAutoSkips = 0
                scope.launch {
                    val id = player.currentMediaItem?.mediaId?.let(MediaId::parseOrNull) ?: return@launch
                    val cached = streams.cached(id)
                    runtime.update { it.copy(stream = cached) }
                    if (cached?.loudnessLufs != null) applyNormalization(cached.loudnessLufs, app.musicflow.playback.LoudnessSource.PROVIDER)
                }
            }
            if (state == Player.STATE_ENDED) finishListening(userSkipped = false, now = System.currentTimeMillis())
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) { schedulePersist(); maybeContinueQueue() }
        }

        override fun onRepeatModeChanged(repeatMode: Int) = schedulePersist()

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            effects.attach(audioSessionId)
            runtime.update { it.copy(audioSessionId = audioSessionId) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        /** Internet radio: show the song the station announces (ICY "StreamTitle") as now playing. */
        override fun onMetadata(metadata: androidx.media3.common.Metadata) {
            val item = player.currentMediaItem ?: return
            if (MediaId.parseOrNull(item.mediaId)?.providerId != app.musicflow.core.model.ProviderIds.RADIO) return
            val title = (0 until metadata.length()).asSequence().map { metadata.get(it) }
                .filterIsInstance<androidx.media3.extractor.metadata.icy.IcyInfo>()
                .mapNotNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() && t != "-" } }.firstOrNull() ?: return
            val station = MediaItems.toTrack(item)?.title ?: item.mediaMetadata.title?.toString()
            if (item.mediaMetadata.title?.toString() == title) return
            val updated = item.buildUpon().setMediaMetadata(
                item.mediaMetadata.buildUpon().setTitle(title).setDisplayTitle(title).setArtist(station).setSubtitle(station).build(),
            ).build()
            reloadingIndex = player.currentMediaItemIndex // same stream, new song title: not a new listen
            player.replaceMediaItem(player.currentMediaItemIndex, updated)
            diagnostics.d("radio", "Now playing on $station: $title")
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // Local/direct files: use ReplayGain tags when the provider gave no loudness.
            if (runtime.status.value.loudnessLufs != null && runtime.status.value.loudnessSource == app.musicflow.playback.LoudnessSource.PROVIDER) return
            val rg = tracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { g -> (0 until g.length).asSequence().map { g.getTrackFormat(it).metadata } }
                .firstNotNullOfOrNull { app.musicflow.playback.LoudnessNormalizer.replayGainTrackGain(it) } ?: return
            applyNormalization(app.musicflow.playback.LoudnessNormalizer.lufsFromReplayGain(rg), app.musicflow.playback.LoudnessSource.REPLAYGAIN)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            runtime.update {
                it.copy(format = ActiveFormat(
                    sampleMimeType = format.sampleMimeType,
                    codecs = format.codecs,
                    sampleRateHz = format.sampleRate.takeIf { r -> r != Format.NO_VALUE },
                    channels = format.channelCount.takeIf { c -> c != Format.NO_VALUE },
                    bitrateKbps = (format.averageBitrate.takeIf { b -> b != Format.NO_VALUE } ?: format.bitrate.takeIf { b -> b != Format.NO_VALUE })?.div(1000),
                ))
            }
        }
    }

    // ---------------------------------------------------------------- errors & recovery

    private fun handleError(error: PlaybackException) {
        // A stream of another type than queued (HLS vs file): fix the item and retry, no error shown.
        player.currentMediaItem?.let { item ->
            val id = MediaId.parseOrNull(item.mediaId)
            val cached = id?.let { kotlinx.coroutines.runBlocking { streams.cached(it) } }
            if (cached != null && MediaItems.isQueuedAsHls(item) != cached.isHls &&
                fixStreamType(player.currentMediaItemIndex, item.mediaId, cached.isHls, current = true)
            ) return
        }
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val provider = chain.firstNotNullOfOrNull { (it as? StreamResolutionException)?.error }
        val http = chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
        val classified: ProviderError = provider ?: when {
            http != null && http.responseCode in listOf(403, 410) -> ProviderError.StreamExpired("Stream rejected (HTTP ${http.responseCode})")
            http != null && http.responseCode == 404 -> ProviderError.Unavailable("Stream not found")
            error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ProviderError.Network(error.message ?: "Network error", error)
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> ProviderError.Network(error.message ?: "I/O error", error)
            error.errorCode in 4000..4999 -> ProviderError.Unavailable("Unsupported audio format", error)
            else -> ProviderError.Unknown(error.message ?: "Playback error", error)
        }
        runtime.reportError(classified, classified.message)
        diagnostics.e("playback", "Player error ${error.errorCodeName}: ${classified::class.simpleName}", error)

        if (classified.isTransient) {
            // Network drop or expired URL: retry the same item when (or while) online.
            networkRetryJob?.cancel()
            networkRetryJob = scope.launch {
                repeat(6) { attempt ->
                    if (classified is ProviderError.StreamExpired) player.currentMediaItem?.mediaId?.let(MediaId::parseOrNull)?.let { streams.invalidate(it) }
                    connectivity.status.first { it.isConnected }
                    delay(StreamErrorClassifier.decide(classified, attempt + 1).let { (it as? app.musicflow.playback.stream.LoadDecision.Retry)?.delayMs ?: 2_000 })
                    if (player.playerError == null) return@launch
                    diagnostics.i("playback", "Recovering playback (attempt ${attempt + 1})")
                    player.prepare()
                    delay(4_000)
                    if (player.playerError == null) return@launch
                }
            }
        } else if (player.hasNextMediaItem() && consecutiveAutoSkips < 5) {
            // Permanent problem with this track only: skip it rather than stopping the queue.
            consecutiveAutoSkips++
            runtime.update { it.copy(autoSkipped = it.autoSkipped + 1) }
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    private fun observeConnectivity() {
        scope.launch {
            connectivity.status.collect { status ->
                diagnostics.i("network", "Network: ${status.type} metered=${status.isMetered}")
                if (status.isConnected && player.playerError != null) {
                    val err = runtime.status.value.lastError
                    if (err == null || err.isTransient) { diagnostics.i("playback", "Network back; resuming"); player.prepare() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings / timer

    private fun observeSettings() {
        scope.launch {
            settingsRepo.settings.collect { s ->
                val old = settings
                settings = s
                diagnostics.enabled = s.diagnosticsEnabled
                registry.setEnabled("yt", s.youtubeProviderEnabled)
                registry.setEnabled("sc", s.soundcloudProviderEnabled)
                registry.setEnabled("radio", s.radioProviderEnabled)
                registry.setEnabled("mbr", s.musicbrainzEnabled)
                if (old.skipSilence != s.skipSilence) player.skipSilenceEnabled = s.skipSilence
                if (old.pauseOnDisconnect != s.pauseOnDisconnect) player.setHandleAudioBecomingNoisy(s.pauseOnDisconnect)
                crossfader.fadeMs = s.crossfadeSeconds * 1000L
                if (old.qualityWifi != s.qualityWifi || old.qualityMobile != s.qualityMobile) {
                    val metered = connectivity.current().isMetered
                    val changedNow = if (metered) old.qualityMobile != s.qualityMobile else old.qualityWifi != s.qualityWifi
                    streams.clear()
                    if (changedNow) reloadCurrentForQuality()
                }
                effects.setLeveling(s.volumeLeveling)
                liveLoudness.targetLufs = s.normalization.targetLufs
                if (old.normalization != s.normalization) {
                    val st = runtime.status.value
                    applyNormalization(st.loudnessLufs, st.loudnessSource ?: app.musicflow.playback.LoudnessSource.PROVIDER)
                }
            }
        }
    }

    private fun observeSleepTimer() {
        scope.launch {
            sleepTimer.state.collect { state ->
                sleepJob?.cancel()
                player.pauseAtEndOfMediaItems = state == SleepTimerState.EndOfTrack
                sleepGain = 1f; applyVolume()
                if (state is SleepTimerState.At) sleepJob = scope.launch {
                    while (isActive) {
                        val remaining = state.endsAtMillis - System.currentTimeMillis()
                        // Gentle 15 s fade-out before pausing.
                        if (remaining in 1..15_000) { sleepGain = (remaining / 15_000f).coerceIn(0f, 1f); applyVolume() }
                        if (sleepTimer.consumeIfExpired()) {
                            player.pause(); sleepGain = 1f; applyVolume()
                            diagnostics.i("sleep", "Sleep timer paused playback")
                            break
                        }
                        delay(500)
                    }
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepTimer.consumeEndOfTrack()) {
                    player.pauseAtEndOfMediaItems = false
                }
            }
        })
    }

    // ---------------------------------------------------------------- queue

    /** Appends related tracks when the queue is about to run out ("autoplay"). */
    private fun maybeContinueQueue() {
        if (!settings.autoContinueQueue || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (player.mediaItemCount == 0 || remaining > 1 || continuationJob?.isActive == true) return
        val seed = player.currentMediaItem?.let(MediaItems::toTrack) ?: return
        if (!continuedFrom.add(seed.id.value)) return
        continuationJob = scope.launch {
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val related = runCatching { catalog.related(seed.id, 25) }
                .onFailure { diagnostics.w("queue", "Autoplay unavailable: ${it.message}") }
                .getOrDefault(emptyList())
                .filter { it.id.value !in existing }
                .take(15)
            if (related.isNotEmpty()) {
                player.addMediaItems(related.map(MediaItems::toMediaItem))
                diagnostics.i("queue", "Autoplay added ${related.size} related tracks")
            }
        }
    }

    /** Resolves the next track's stream URL in advance so the transition is seamless. */
    private fun prefetchNext() {
        if (!player.hasNextMediaItem()) return
        val index = player.nextMediaItemIndex
        val next = player.getMediaItemAt(index)
        val id = MediaId.parseOrNull(next.mediaId) ?: return
        appScope.launch(Dispatchers.IO) {
            val stream = runCatching { streams.resolve(id, MediaItems.toTrack(next)) }.getOrNull() ?: return@launch
            scope.launch { fixStreamType(index, id.value, stream.isHls, current = false) }
        }
    }

    /**
     * Re-queues an item whose resolved stream type differs from how it was queued (HLS vs file),
     * e.g. a DRM-only SoundCloud track that plays from YouTube Music. Returns true if changed.
     */
    private fun fixStreamType(index: Int, mediaId: String, hls: Boolean, current: Boolean): Boolean {
        if (index !in 0 until player.mediaItemCount) return false
        val item = player.getMediaItemAt(index)
        if (item.mediaId != mediaId || MediaItems.isQueuedAsHls(item) == hls) return false
        val position = if (current) player.currentPosition else 0
        if (current) reloadingIndex = index
        player.replaceMediaItem(index, MediaItems.withStreamType(item, hls))
        if (current) { player.seekTo(index, position); player.prepare(); player.play() }
        diagnostics.i("playback", "Re-queued ${item.mediaId} as ${if (hls) "HLS" else "file"} stream")
        return true
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1_500)
            val tracks = (0 until player.mediaItemCount).mapNotNull { MediaItems.toTrack(player.getMediaItemAt(it)) }
            appScope.launch { runCatching { library.saveQueue(tracks) } }
            persistPosition()
        }
    }

    private fun persistPosition() {
        prefs.edit {
            putInt(KEY_INDEX, player.currentMediaItemIndex)
            putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
            putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            putInt(KEY_REPEAT, player.repeatMode)
        }
    }

    /** Restores the persisted queue (Bluetooth "play" button / Android Auto resumption). */
    private suspend fun restoreForResumption(): MediaSession.MediaItemsWithStartPosition? {
        val tracks = library.restoreQueue()
        if (tracks.isEmpty()) return null
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, tracks.lastIndex)
        return MediaSession.MediaItemsWithStartPosition(tracks.map(MediaItems::toMediaItem), index, prefs.getLong(KEY_POSITION, 0))
    }

    private fun finishListening(userSkipped: Boolean, now: Long) {
        val s = listening ?: return
        listening = null
        val listened = s.total(now)
        appScope.launch { runCatching { library.recordPlay(s.track, s.startedAt, listened, userSkipped) } }
    }

    private suspend fun onFavoriteCommand(): Boolean {
        val track = player.currentMediaItem?.let(MediaItems::toTrack) ?: return false
        return library.toggleFavorite(track)
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing if music is playing (user swiped the app away); otherwise stop the service.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            persistPosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        runtime.prefetcher = null
        finishListening(userSkipped = false, now = System.currentTimeMillis())
        persistPosition()
        crossfadeJob?.cancel()
        crossfader.release()
        effects.release()
        session.release()
        player.release()
        scope.cancel()
        runtime.update { it.copy(serviceRunning = false, buffering = false) }
        diagnostics.i("service", "Playback service stopped")
        super.onDestroy()
    }

    companion object {
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        val COMMAND_FAVORITE = SessionCommand("app.musicflow.FAVORITE", Bundle.EMPTY)

        fun repeatOf(mode: Int) = when (mode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }

        fun favoriteButton(isFavorite: Boolean, label: String) = CommandButton.Builder(
            if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        ).setDisplayName(label).setSessionCommand(COMMAND_FAVORITE).build()
    }
}
