import SwiftUI

/// MusicFlow's glass look, matching the Android app: near-black background with soft
/// coloured glows, frosted panels and a violet→magenta accent.
enum Theme {
    static let background = Color(hex: 0x0D0B12)
    static let violet = Color(hex: 0x7C3AED)
    static let magenta = Color(hex: 0xD946EF)
    static let ember = Color(hex: 0xF97316)
    static let ocean = Color(hex: 0x3B5BDB)
    static let lilac = Color(hex: 0x8B5CF6)

    static let accent = LinearGradient(colors: [violet, magenta], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let stroke = Color.white.opacity(0.14)
    static let glassFill = Color.white.opacity(0.06)
    static let secondary = Color.white.opacity(0.62)
    static let tertiary = Color.white.opacity(0.4)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// Background with the four glows of the app icon.
struct AppBackground: View {
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            ZStack {
                Theme.background
                glow(Theme.ember, 0.42, at: CGPoint(x: w * 0.05, y: h * 0.06), radius: w * 0.75)
                glow(Theme.magenta, 0.38, at: CGPoint(x: w * 0.98, y: h * 0.04), radius: w * 0.8)
                glow(Theme.lilac, 0.30, at: CGPoint(x: w * 0.8, y: h * 0.55), radius: w * 0.9)
                glow(Theme.ocean, 0.30, at: CGPoint(x: w * 0.05, y: h * 0.95), radius: w * 0.8)
            }
        }
        .ignoresSafeArea()
    }

    private func glow(_ c: Color, _ a: Double, at p: CGPoint, radius: CGFloat) -> some View {
        RadialGradient(colors: [c.opacity(a), c.opacity(0)], center: .center, startRadius: 0, endRadius: radius)
            .frame(width: radius * 2, height: radius * 2)
            .position(p)
    }
}

struct GlassBackground<S: Shape>: View {
    let shape: S
    var body: some View {
        shape.fill(.ultraThinMaterial)
            .overlay(shape.fill(Theme.glassFill))
            .overlay(shape.stroke(Theme.stroke, lineWidth: 1))
    }
}

extension View {
    func glass(cornerRadius: CGFloat = 22) -> some View {
        background(GlassBackground(shape: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)))
    }

    func glassCircle() -> some View {
        background(GlassBackground(shape: Circle()))
    }

    func glassCapsule() -> some View {
        background(GlassBackground(shape: Capsule()))
    }
}

/// Round frosted icon button (back, settings, menu…).
struct GlassIconButton: View {
    let systemName: String
    var size: CGFloat = 44
    var label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .glassCircle()
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// The violet→magenta play/pause button.
struct GradientPlayButton: View {
    let isPlaying: Bool
    var isBuffering = false
    var size: CGFloat = 72
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(Theme.accent)
                    .shadow(color: Theme.magenta.opacity(0.45), radius: size * 0.22, y: size * 0.06)
                Circle().stroke(Color.white.opacity(0.25), lineWidth: 1)
                if isBuffering {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: size * 0.36, weight: .bold))
                        .foregroundStyle(.white)
                        .offset(x: isPlaying ? 0 : size * 0.03)
                }
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L.t(isPlaying ? "player.pause" : "player.play"))
        .accessibilityIdentifier("player.playpause")
    }
}

/// Selectable pill (search sources, genres). Selected: gradient; otherwise glass.
struct PillChip: View {
    let title: String
    var systemImage: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage).font(.footnote.weight(.semibold)) }
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background {
                if selected {
                    Capsule().fill(Theme.accent)
                } else {
                    GlassBackground(shape: Capsule())
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct SectionHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.title3.weight(.bold)).foregroundStyle(.white)
            if let subtitle { Text(subtitle).font(.footnote).foregroundStyle(Theme.secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Honest empty / error state.
struct MessageView: View {
    let systemImage: String
    let title: String
    var detail: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 64, height: 64)
                .glassCircle()
            Text(title).font(.headline).foregroundStyle(.white).multilineTextAlignment(.center)
            if let detail { Text(detail).font(.subheadline).foregroundStyle(Theme.secondary).multilineTextAlignment(.center) }
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18).padding(.vertical, 10)
                    .background(Capsule().fill(Theme.accent))
                    .buttonStyle(.plain)
                    .padding(.top, 4)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
    }
}
