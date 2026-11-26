import SwiftUI

/// Full-screen player in the glass style of the Android app.
struct FullPlayerView: View {
    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var showQueue = false
    @State private var addTarget: Track?
    @State private var scrub: Double?

    var body: some View {
        ZStack {
            AppBackground()
            if let t = player.current {
                content(t)
            } else {
                MessageView(systemImage: "music.note", title: L.t("player.nothing"))
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = router.toast { ToastView(text: toast).padding(.bottom, 30) }
        }
        .sheet(isPresented: $showQueue) { QueueView().appEnvironment(player, library, router, settings) }
        .sheet(item: $addTarget) { AddToPlaylistSheet(track: $0).appEnvironment(player, library, router, settings) }
        .preferredColorScheme(.dark)
        .onChange(of: player.current == nil) { _, empty in if empty { dismiss() } }
    }

    private func content(_ t: Track) -> some View {
        VStack(spacing: 0) {
            topBar(t)
            Spacer(minLength: 12)
            artwork(t)
            Spacer(minLength: 16)
            titleRow(t)
            seekBar(t).padding(.top, 18)
            controls.padding(.top, 14)
            bottomRow.padding(.top, 18)
        }
        .padding(.horizontal, 22)
        .padding(.bottom, 12)
        .gesture(DragGesture(minimumDistance: 30).onEnded { v in
            if v.translation.height > 120 && abs(v.translation.width) < 80 { dismiss() }
        })
    }

    private func topBar(_ t: Track) -> some View {
        HStack {
            GlassIconButton(systemName: "chevron.down", label: L.t("player.close")) { dismiss() }
                .accessibilityIdentifier("player.close")
            Spacer()
            Button { showQueue = true } label: {
                VStack(spacing: 2) {
                    Text(L.t("player.now_playing")).font(.footnote.weight(.semibold)).foregroundStyle(.white)
                    Text(sourceLine(t)).font(.caption2).foregroundStyle(Theme.secondary).lineLimit(1)
                }
            }
            .buttonStyle(.plain)
            Spacer()
            Menu {
                Button { showQueue = true } label: { Label(L.t("player.queue"), systemImage: "list.bullet") }
                Button { addTarget = t } label: { Label(L.t("action.add_to_playlist"), systemImage: "music.note.list") }
                if let url = t.shareURL {
                    ShareLink(item: url) { Label(L.t("action.share"), systemImage: "square.and.arrow.up") }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 44, height: 44).glassCircle()
            }
            .accessibilityLabel(L.t("action.more"))
        }
        .padding(.top, 6)
    }

    private func sourceLine(_ t: Track) -> String {
        let source: String
        switch t.source {
        case .youtube: source = "YouTube Music"
        case .radio: source = "Radio Browser"
        case .musicbrainz: source = L.t("source.musicbrainz_via_youtube")
        case nil: source = ""
        }
        return [source, player.streamInfo].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func artwork(_ t: Track) -> some View {
        GeometryReader { g in
            let side = min(g.size.width, g.size.height)
            ZStack {
                Circle().fill(.ultraThinMaterial).overlay(Circle().fill(Theme.glassFill)).overlay(Circle().stroke(Theme.stroke, lineWidth: 1.2))
                    .frame(width: side, height: side)
                ArtworkView(url: t.artworkURL, source: t.source, size: side * 0.84, circle: true)
                    .shadow(color: Theme.magenta.opacity(0.35), radius: 30, y: 12)
                    .scaleEffect(player.isPlaying ? 1 : 0.94)
                    .animation(.spring(duration: 0.5), value: player.isPlaying)
            }
            .frame(width: g.size.width, height: g.size.height)
        }
        .frame(maxHeight: 360)
    }

    private func titleRow(_ t: Track) -> some View {
        HStack(alignment: .center, spacing: 10) {
            GlassIconButton(systemName: "plus", size: 40, label: L.t("action.add_to_playlist")) { addTarget = t }
            VStack(spacing: 4) {
                Text(t.title).font(.title3.weight(.bold)).foregroundStyle(.white).lineLimit(2).multilineTextAlignment(.center)
                    .accessibilityIdentifier("player.title")
                if let live = player.liveTitle {
                    Text(live).font(.subheadline).foregroundStyle(Theme.magenta).lineLimit(1)
                }
                Text(t.artist).font(.subheadline).foregroundStyle(Theme.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            Button {
                let liked = library.toggleLike(t)
                router.show(L.t(liked ? "toast.liked" : "toast.unliked"))
            } label: {
                Image(systemName: library.isLiked(t) ? "heart.fill" : "heart")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(library.isLiked(t) ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.white))
                    .frame(width: 40, height: 40).glassCircle()
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.t(library.isLiked(t) ? "action.unlike" : "action.like"))
        }
    }

    @ViewBuilder
    private func seekBar(_ t: Track) -> some View {
        if t.isLive {
            HStack(spacing: 8) {
                Circle().fill(Theme.magenta).frame(width: 8, height: 8)
                Text(L.t("badge.live_long")).font(.footnote.weight(.bold)).foregroundStyle(.white)
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
            .glassCapsule()
            .frame(height: 44)
        } else {
            let d = player.duration ?? 0
            let shown = scrub ?? player.position
            VStack(spacing: 6) {
                GeometryReader { g in
                    let w = g.size.width
                    let fraction = d > 0 ? max(0, min(1, shown / d)) : 0
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.14)).frame(height: 6)
                        Capsule().fill(Theme.accent).frame(width: w * fraction, height: 6)
                        Circle().fill(.white).frame(width: scrub == nil ? 14 : 20, height: scrub == nil ? 14 : 20)
                            .shadow(color: Theme.magenta.opacity(0.6), radius: 6)
                            .offset(x: w * fraction - (scrub == nil ? 7 : 10))
                    }
                    .frame(height: 24)
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0)
                        .onChanged { v in if d > 0 { scrub = max(0, min(1, v.location.x / w)) * d } }
                        .onEnded { _ in
                            if let s = scrub { player.seek(to: s) }
                            scrub = nil
                        })
                }
                .frame(height: 24)
                .accessibilityElement()
                .accessibilityLabel(L.t("player.position"))
                .accessibilityValue(Format.time(shown))
                .accessibilityAdjustableAction { dir in
                    let step: TimeInterval = dir == .increment ? 10 : -10
                    player.seek(to: max(0, min(d, player.position + step)))
                }
                HStack {
                    Text(Format.time(shown))
                    Spacer()
                    Text(d > 0 ? Format.time(d) : "–:––")
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(Theme.secondary)
            }
        }
    }

    private var controls: some View {
        HStack {
            GlassIconButton(systemName: "backward.fill", size: 58, label: L.t("player.previous")) { player.previous() }
            Spacer()
            GradientPlayButton(isPlaying: player.isPlaying, isBuffering: player.isBuffering && !player.isPlaying, size: 82) {
                player.togglePlayPause()
            }
            Spacer()
            GlassIconButton(systemName: "forward.fill", size: 58, label: L.t("player.next")) { player.next() }
                .accessibilityIdentifier("player.next")
        }
        .padding(.horizontal, 8)
    }

    private var bottomRow: some View {
        HStack {
            toggle("shuffle", on: player.queue.isShuffled, label: L.t("player.shuffle")) { player.toggleShuffle() }
            Spacer()
            Button { showQueue = true } label: {
                Label(L.t("player.queue"), systemImage: "list.bullet")
                    .font(.footnote.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .glassCapsule()
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("player.queue")
            Spacer()
            toggle(player.queue.repeatMode == .one ? "repeat.1" : "repeat", on: player.queue.repeatMode != .off,
                   label: L.t(repeatLabel)) { player.cycleRepeat() }
        }
    }

    private var repeatLabel: String {
        switch player.queue.repeatMode {
        case .off: return "player.repeat_off"
        case .all: return "player.repeat_all"
        case .one: return "player.repeat_one"
        }
    }

    private func toggle(_ icon: String, on: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(on ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Theme.secondary))
                .frame(width: 44, height: 44)
                .background { if on { Circle().fill(Color.white.opacity(0.08)) } }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

struct QueueView: View {
    @EnvironmentObject private var player: PlayerModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                let items = Array(player.queue.items.enumerated())
                ForEach(items, id: \.offset) { i, t in
                    Button {
                        player.jump(to: i)
                    } label: {
                        HStack(spacing: 12) {
                            ArtworkView(url: t.artworkURL, source: t.source, size: 44, circle: t.isLive, cornerRadius: 10)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(t.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    .foregroundStyle(i == player.queue.index ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.white))
                                Text(t.artist).font(.caption).foregroundStyle(Theme.secondary).lineLimit(1)
                            }
                            Spacer()
                            if i == player.queue.index {
                                Image(systemName: player.isPlaying ? "speaker.wave.2.fill" : "speaker.fill").foregroundStyle(Theme.magenta)
                            }
                        }
                    }
                    .listRowBackground(Color.white.opacity(i == player.queue.index ? 0.08 : 0.03))
                }
                .onDelete { offsets in offsets.sorted(by: >).forEach { player.removeFromQueue(at: $0) } }
                .onMove { player.moveInQueue(from: $0, to: $1) }
            }
            .scrollContentBackground(.hidden)
            .background(AppBackground())
            .navigationTitle(L.t("player.queue"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L.t("action.done")) { dismiss() } }
                ToolbarItem(placement: .topBarLeading) { EditButton() }
            }
            .overlay {
                if player.queue.isEmpty { MessageView(systemImage: "list.bullet", title: L.t("player.queue_empty")) }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }
}
