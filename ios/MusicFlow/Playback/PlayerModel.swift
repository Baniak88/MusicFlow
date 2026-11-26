import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// Playback: AVPlayer with background audio, lock screen / Control Center / headphone and car
/// controls (MPRemoteCommandCenter), interruptions and live radio titles (ICY metadata).
/// All state lives on the main actor.
@MainActor
final class PlayerModel: ObservableObject {
    @Published private(set) var queue = PlayQueue()
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var position: TimeInterval = 0
    @Published private(set) var duration: TimeInterval?
    /// "Now on air" title reported by a radio stream.
    @Published private(set) var liveTitle: String?
    /// Codec/bitrate as reported by the source.
    @Published private(set) var streamInfo: String?
    @Published var message: String?

    var current: Track? { queue.current }

    private let player = AVPlayer()
    private let resolver: StreamResolver
    private let library: LibraryStore
    private var loadTask: Task<Void, Never>?
    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var failObserver: NSObjectProtocol?
    private var metadataOutput: AVPlayerItemMetadataOutput?
    private let metadataDelegate = MetadataDelegate()
    private var artworkTask: Task<Void, Never>?
    /// Keeps the resource loader of the current YouTube item alive (AVFoundation holds it weakly).
    private var audioLoader: YouTubeAudioLoader?
    private var artwork: (id: String, art: MPMediaItemArtwork)?
    private var consecutiveFailures = 0
    private var wantsPlay = false
    private var isSeeking = false

    init(resolver: StreamResolver, library: LibraryStore) {
        self.resolver = resolver
        self.library = library
        player.automaticallyWaitsToMinimizeStalling = true
        configureSession()
        observePlayer()
        configureRemoteCommands()
        metadataDelegate.onTitle = { [weak self] title in
            Task { @MainActor in
                self?.liveTitle = title
                self?.updateNowPlaying()
            }
        }
    }

    // MARK: - Public controls

    func play(_ tracks: [Track], startAt index: Int = 0, shuffle: Bool = false) {
        guard !tracks.isEmpty else { return }
        queue.set(tracks, start: index, shuffle: shuffle)
        consecutiveFailures = 0
        startCurrent()
    }

    func togglePlayPause() {
        if isPlaying || wantsPlay && isBuffering { pause() } else { resume() }
    }

    func resume() {
        guard current != nil else { return }
        if player.currentItem == nil {
            startCurrent()
            return
        }
        // A live stream resumes at the live edge instead of replaying a stale buffer.
        if current?.isLive == true, !isPlaying { startCurrent(); return }
        wantsPlay = true
        activateSession()
        player.play()
        updateNowPlaying()
    }

    func pause() {
        wantsPlay = false
        player.pause()
        updateNowPlaying()
    }

    func next() {
        guard queue.next(auto: false) else { return }
        consecutiveFailures = 0
        startCurrent()
    }

    func previous() {
        if position > 3, current?.isLive != true {
            seek(to: 0)
            return
        }
        guard queue.previous() else { seek(to: 0); return }
        consecutiveFailures = 0
        startCurrent()
    }

    func seek(to seconds: TimeInterval) {
        guard current?.isLive != true else { return }
        position = seconds
        isSeeking = true
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600)) { [weak self] _ in
            Task { @MainActor in
                self?.isSeeking = false
                self?.updateNowPlaying()
            }
        }
    }

    func jump(to index: Int) {
        queue.jump(to: index)
        consecutiveFailures = 0
        startCurrent()
    }

    func playNext(_ t: Track) {
        let wasEmpty = queue.isEmpty
        queue.playNext(t)
        if wasEmpty { startCurrent() } else { resolver.prefetch(queue.upcoming) }
    }

    func addToQueue(_ t: Track) {
        let wasEmpty = queue.isEmpty
        queue.append(t)
        if wasEmpty { startCurrent() }
    }

    func removeFromQueue(at index: Int) {
        let wasCurrent = queue.remove(at: index)
        if queue.isEmpty { stop(); return }
        if wasCurrent { startCurrent(autoplay: wantsPlay) }
    }

    func moveInQueue(from source: IndexSet, to destination: Int) {
        queue.move(from: source, to: destination)
    }

    func toggleShuffle() {
        queue.setShuffle(!queue.isShuffled)
        resolver.prefetch(queue.upcoming)
    }

    func cycleRepeat() {
        switch queue.repeatMode {
        case .off: queue.repeatMode = .all
        case .all: queue.repeatMode = .one
        case .one: queue.repeatMode = .off
        }
    }

    func stop() {
        loadTask?.cancel()
        wantsPlay = false
        player.pause()
        detachItem()
        player.replaceCurrentItem(with: nil)
        queue.clear()
        isPlaying = false
        isBuffering = false
        position = 0
        duration = nil
        liveTitle = nil
        streamInfo = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - Loading

    private func startCurrent(autoplay: Bool = true) {
        loadTask?.cancel()
        guard let track = current else { stop(); return }
        player.pause()
        detachItem()
        player.replaceCurrentItem(with: nil)
        position = 0
        duration = track.duration
        liveTitle = nil
        streamInfo = nil
        isBuffering = true
        wantsPlay = autoplay
        updateNowPlaying()
        loadArtwork(for: track)

        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let stream = try await self.resolver.resolve(track)
                try Task.checkCancellation()
                guard self.current?.id == track.id else { return }
                self.attach(stream, for: track)
                self.library.recordPlay(track)
                self.resolver.prefetch(self.queue.upcoming)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.current?.id == track.id else { return }
                self.failed(track, error)
            }
        }
    }

    private func failed(_ track: Track, _ error: Error) {
        isBuffering = false
        consecutiveFailures += 1
        let reason = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        // Skip an unplayable track like other players do, but stop after several failures in a row.
        if consecutiveFailures < 4, wantsPlay, queue.next(auto: false) {
            message = L.f("player.skipped", track.title, reason)
            startCurrent()
        } else {
            message = L.f("player.failed", track.title, reason)
            wantsPlay = false
            isPlaying = false
            updateNowPlaying()
        }
    }

    private func attach(_ stream: ResolvedStream, for track: Track) {
        let asset: AVURLAsset
        if stream.chunked {
            let loader = YouTubeAudioLoader(url: stream.url, userAgent: stream.userAgent, contentLength: stream.contentLength)
            audioLoader = loader
            asset = loader.makeAsset()
        } else {
            var options: [String: Any] = [:]
            if let ua = stream.userAgent { options[AVURLAssetHTTPUserAgentKey] = ua }
            asset = AVURLAsset(url: stream.url, options: options)
        }
        let item = AVPlayerItem(asset: asset)
        if stream.isLive {
            let output = AVPlayerItemMetadataOutput(identifiers: nil)
            output.setDelegate(metadataDelegate, queue: .main)
            item.add(output)
            metadataOutput = output
        }
        if duration == nil { duration = stream.duration }
        streamInfo = stream.qualityLabel

        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            let status = item.status
            let error = item.error
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                if status == .failed {
                    self.failed(track, error.map { ProviderError.network($0.localizedDescription) } ?? ProviderError.unavailable(""))
                } else if status == .readyToPlay {
                    self.consecutiveFailures = 0
                    let d = item.duration.seconds
                    if !stream.isLive, d.isFinite, d > 0 { self.duration = d }
                    self.updateNowPlaying()
                }
            }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.itemEnded() }
        }
        failObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] n in
            let err = n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            Task { @MainActor in
                guard let self, let t = self.current else { return }
                self.failed(t, ProviderError.network(err?.localizedDescription ?? "stream"))
            }
        }
        player.replaceCurrentItem(with: item)
        if wantsPlay {
            activateSession()
            player.play()
        } else {
            isBuffering = false
        }
        updateNowPlaying()
    }

    private func detachItem() {
        itemObservation = nil
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        if let o = failObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = nil
        failObserver = nil
        if let out = metadataOutput { player.currentItem?.remove(out) }
        metadataOutput = nil
        audioLoader?.cancelAll()
        audioLoader = nil
    }

    private func itemEnded() {
        if queue.repeatMode == .one {
            seek(to: 0)
            player.play()
            return
        }
        if queue.next(auto: true) {
            startCurrent()
        } else {
            // End of the queue: stay on the last track, ready to replay.
            wantsPlay = false
            player.pause()
            seek(to: 0)
        }
    }

    // MARK: - Observation

    private func observePlayer() {
        statusObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let status = p.timeControlStatus
            Task { @MainActor in
                guard let self else { return }
                self.isPlaying = status == .playing
                self.isBuffering = status == .waitingToPlayAtSpecifiedRate || (self.wantsPlay && self.player.currentItem == nil)
                self.updateNowPlaying()
            }
        }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self, !self.isSeeking else { return }
                let s = time.seconds
                if s.isFinite { self.position = s }
            }
        }
    }

    // MARK: - Audio session

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, policy: .longFormAudio)
        let center = NotificationCenter.default
        center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] n in
            let type = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let opts = (n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init) ?? []
            Task { @MainActor in
                guard let self else { return }
                if type == .ended, opts.contains(.shouldResume), self.wantsPlay {
                    self.resume()
                }
            }
        }
        center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] n in
            let reason = (n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            Task { @MainActor in
                // Headphones / Bluetooth disconnected: pause instead of playing from the speaker.
                if reason == .oldDeviceUnavailable { self?.pause() }
            }
        }
    }

    private func activateSession() {
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    // MARK: - Lock screen, Control Center, headphones, car

    private func configureRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.resume(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in self?.togglePlayPause(); return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(to: e.positionTime)
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let t = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: liveTitle ?? t.artist,
            MPNowPlayingInfoPropertyIsLiveStream: t.isLive,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let a = t.album { info[MPMediaItemPropertyAlbumTitle] = a }
        if let d = duration, !t.isLive { info[MPMediaItemPropertyPlaybackDuration] = d }
        if let art = artwork, art.id == t.id { info[MPMediaItemPropertyArtwork] = art.art }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        let commands = MPRemoteCommandCenter.shared()
        commands.changePlaybackPositionCommand.isEnabled = !t.isLive
    }

    private func loadArtwork(for track: Track) {
        artworkTask?.cancel()
        guard let url = track.artworkURL, artwork?.id != track.id else { return }
        artworkTask = Task { [weak self] in
            guard let (data, _) = try? await HTTP.shared.get(url), let image = UIImage(data: data) else { return }
            guard !Task.isCancelled, let self, self.current?.id == track.id else { return }
            self.artwork = (track.id, MPMediaItemArtwork(boundsSize: image.size) { _ in image })
            self.updateNowPlaying()
        }
    }
}

/// Receives "now playing" titles from radio streams (ICY StreamTitle).
final class MetadataDelegate: NSObject, AVPlayerItemMetadataOutputPushDelegate {
    var onTitle: ((String?) -> Void)?

    func metadataOutput(_ output: AVPlayerItemMetadataOutput, didOutputTimedMetadataGroups groups: [AVTimedMetadataGroup], from track: AVPlayerItemTrack?) {
        let items = groups.flatMap(\.items)
        guard let item = items.first(where: { $0.commonKey == .commonKeyTitle || ($0.identifier?.rawValue.lowercased().contains("streamtitle") ?? false) })
                ?? items.first else { return }
        Task {
            let value = try? await item.load(.stringValue)
            let title = value?.trimmingCharacters(in: .whitespacesAndNewlines)
            self.onTitle?(title?.isEmpty == false ? title : nil)
        }
    }
}
