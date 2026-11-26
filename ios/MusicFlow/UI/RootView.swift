import SwiftUI

struct RootView: View {
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        ZStack {
            AppBackground()
            // All tabs stay alive so each keeps its navigation and scroll position.
            tab(.home) { HomeView() }
            tab(.search) { SearchView() }
            tab(.library) { LibraryView() }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if let toast = router.toast { ToastView(text: toast) }
                if player.current != nil {
                    MiniPlayer()
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                Dock()
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 2)
            .animation(.spring(duration: 0.35), value: player.current?.id)
        }
        .fullScreenCover(isPresented: $router.showPlayer) {
            FullPlayerView().appEnvironment(player, library, router, settings)
        }
        .sheet(item: $router.addToPlaylist) { track in
            AddToPlaylistSheet(track: track).appEnvironment(player, library, router, settings)
        }
        .sheet(isPresented: $router.showSettings) {
            SettingsView().appEnvironment(player, library, router, settings)
        }
        .onChange(of: player.message) { _, message in
            guard let message else { return }
            router.show(message)
            player.message = nil
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private func tab<Content: View>(_ t: Tab, @ViewBuilder content: () -> Content) -> some View {
        content()
            .opacity(router.tab == t ? 1 : 0)
            .allowsHitTesting(router.tab == t)
            .accessibilityHidden(router.tab != t)
    }
}

/// Bottom navigation: three destinations in a glass capsule.
struct Dock: View {
    @EnvironmentObject private var router: Router

    var body: some View {
        HStack(spacing: 4) {
            item(.home, "house.fill", L.t("tab.home"), id: "tab.home")
            item(.search, "magnifyingglass", L.t("tab.search"), id: "tab.search")
            item(.library, "books.vertical.fill", L.t("tab.library"), id: "tab.library")
        }
        .padding(6)
        .background {
            Capsule().fill(Color(hex: 0x14111B, opacity: 0.86))
                .overlay(Capsule().fill(.ultraThinMaterial).opacity(0.5))
                .overlay(Capsule().stroke(Theme.stroke, lineWidth: 1))
        }
    }

    private func item(_ t: Tab, _ icon: String, _ title: String, id: String) -> some View {
        let selected = router.tab == t
        return Button {
            if router.tab == t { return }
            withAnimation(.easeOut(duration: 0.18)) { router.tab = t }
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 18, weight: .semibold))
                Text(title).font(.caption2.weight(.semibold))
            }
            .foregroundStyle(selected ? AnyShapeStyle(Color.white) : AnyShapeStyle(Theme.secondary))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background {
                if selected { Capsule().fill(Theme.accent.opacity(0.9)) }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct MiniPlayer: View {
    @EnvironmentObject private var player: PlayerModel
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var router: Router

    var body: some View {
        if let t = player.current {
            HStack(spacing: 12) {
                Button {
                    router.showPlayer = true
                } label: {
                    HStack(spacing: 12) {
                        ArtworkView(url: t.artworkURL, source: t.source, size: 44, circle: true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                            Text(player.liveTitle ?? t.artist).font(.caption).foregroundStyle(Theme.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("miniplayer")

                Button {
                    library.toggleLike(t)
                } label: {
                    Image(systemName: library.isLiked(t) ? "heart.fill" : "heart")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(library.isLiked(t) ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.white))
                        .frame(width: 36, height: 40)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.t(library.isLiked(t) ? "action.unlike" : "action.like"))

                GradientPlayButton(isPlaying: player.isPlaying, isBuffering: player.isBuffering && !player.isPlaying, size: 42) {
                    player.togglePlayPause()
                }
            }
            .padding(.leading, 8).padding(.trailing, 8).padding(.vertical, 8)
            .glass(cornerRadius: 26)
            .overlay(alignment: .bottom) {
                if let d = player.duration, d > 0, !t.isLive {
                    GeometryReader { g in
                        Capsule().fill(Theme.accent)
                            .frame(width: max(0, min(1, player.position / d)) * g.size.width, height: 2)
                    }
                    .frame(height: 2)
                    .padding(.horizontal, 22)
                    .padding(.bottom, 1)
                }
            }
        }
    }
}

extension View {
    /// Sheets and full-screen covers get the shared objects explicitly.
    func appEnvironment(_ p: PlayerModel, _ l: LibraryStore, _ r: Router, _ s: AppSettings) -> some View {
        environmentObject(p).environmentObject(l).environmentObject(r).environmentObject(s)
    }
}
