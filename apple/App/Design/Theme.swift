// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit

/// Pictureshow's palette, from `spec/design/tokens.json` (dark), with a light counterpart for iPhone and iPad
/// when the viewer picks Light or System in a light environment. Apple TV is always dark.
enum Theme {
    static let background = dynamic(dark: DesignTokens.background, light: Color(red: 0.965, green: 0.969, blue: 0.984))
    static let surface = dynamic(dark: DesignTokens.surface, light: Color(red: 0.902, green: 0.910, blue: 0.941))
    static let elevated = dynamic(dark: Color(red: 0.165, green: 0.180, blue: 0.239), light: .white)
    static let placeholder = dynamic(dark: DesignTokens.placeholder, light: Color(red: 0.851, green: 0.863, blue: 0.902))
    static let muted = dynamic(dark: DesignTokens.muted, light: Color(red: 0.333, green: 0.353, blue: 0.431))
    static let accent = dynamic(dark: DesignTokens.accent, light: Color(red: 0.278, green: 0.345, blue: 0.788))
    /// Text and glyphs drawn on the accent colour.
    static let onAccent = dynamic(dark: Color(red: 0.078, green: 0.098, blue: 0.239), light: .white)
    static let warning = dynamic(dark: DesignTokens.warning, light: Color(red: 0.62, green: 0.24, blue: 0.10))
    static let outline = DesignTokens.outline

    #if os(tvOS)
    static let gutter: CGFloat = 80
    static let posterWidth: CGFloat = 248
    static let wideWidth: CGFloat = 420
    static let shelfSpacing: CGFloat = 48
    static let cardSpacing: CGFloat = 40
    static let corner: CGFloat = 12
    #else
    static let gutter: CGFloat = 20
    static let posterWidth: CGFloat = 124
    static let wideWidth: CGFloat = 248
    static let shelfSpacing: CGFloat = 28
    static let cardSpacing: CGFloat = 12
    static let corner: CGFloat = 10
    #endif

    private static func dynamic(dark: Color, light: Color) -> Color {
        Color(UIColor { traits in traits.userInterfaceStyle == .light ? UIColor(light) : UIColor(dark) })
    }
}

/// The Pictureshow wordmark: lowercase name with the accent full stop, as on Android and web.
struct Wordmark: View {
    var size: CGFloat = 28
    var body: some View {
        (Text("pictureshow").foregroundStyle(.primary) + Text(".").foregroundStyle(Theme.accent))
            .font(.system(size: size, weight: .heavy, design: .rounded))
            .accessibilityLabel("Pictureshow")
    }
}

/// The main call to action (Play, Resume, Sign in). On tvOS the system button style supplies
/// focus growth and motion; on touch devices it is a filled capsule in the accent colour.
struct PrimaryActionStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .padding(.horizontal, 22).padding(.vertical, 13)
            .frame(minHeight: 48)
            .foregroundStyle(Theme.onAccent)
            .background(Capsule().fill(Theme.accent.opacity(enabled ? 1 : 0.4)))
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(Capsule())
    }
}

/// Secondary actions beside the main one.
struct SecondaryActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(minHeight: 48)
            .foregroundStyle(.primary)
            .background(Capsule().fill(Theme.surface))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Capsule())
    }
}

extension View {
    /// Main action button: native bordered-prominent focus on tvOS, accent capsule on touch.
    @ViewBuilder func primaryAction() -> some View {
        #if os(tvOS)
        self.buttonStyle(TVActionStyle(prominent: true))
        #else
        self.buttonStyle(PrimaryActionStyle())
        #endif
    }

    @ViewBuilder func secondaryAction() -> some View {
        #if os(tvOS)
        self.buttonStyle(TVActionStyle(prominent: false))
        #else
        self.buttonStyle(SecondaryActionStyle())
        #endif
    }

    /// Standard page background, edge to edge.
    func pageBackground() -> some View {
        background(Theme.background.ignoresSafeArea())
    }
}

/// Section heading for shelves and detail sections.
struct SectionHeader: View {
    let title: String
    var body: some View {
        Text(title)
            #if os(tvOS)
            .font(.title3.weight(.semibold))
            #else
            .font(.title3.weight(.bold))
            #endif
            .foregroundStyle(.primary)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A full-screen message for empty and failed states, with an optional retry.
struct MessageView: View {
    let symbol: String
    let title: String
    var message: String?
    var actionTitle: String?
    var action: (() -> Void)?
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: symbol).font(.system(size: 44, weight: .light)).foregroundStyle(Theme.muted)
            Text(title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
            if let message { Text(message).font(.body).foregroundStyle(Theme.muted).multilineTextAlignment(.center).frame(maxWidth: 520) }
            if let actionTitle, let action {
                Button(actionTitle, action: action).primaryAction().padding(.top, 6)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A thin progress bar for partly watched items.
struct ProgressStrip: View {
    let value: Double
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(.black.opacity(0.45))
                Capsule().fill(Theme.accent).frame(width: max(4, geo.size.width * value))
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}

enum Format {
    static func clock(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds.rounded(.down))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    /// "1h 52m", "14m".
    static func runtime(_ ticks: Int64?) -> String? { Catalog.runtimeLabel(ticks).nilIfBlank }

    /// "38m left" for a partly watched item.
    static func remaining(_ item: MediaItem) -> String? {
        guard let total = item.runTimeTicks, total > 0, item.userData.playbackPositionTicks > 0 else { return nil }
        let left = max(0, total - item.userData.playbackPositionTicks)
        let minutes = max(1, Int(left / 600_000_000))
        return minutes >= 60 ? "\(minutes / 60)h \(minutes % 60)m left" : "\(minutes)m left"
    }

    /// "S1 · E3" style label for episodes, localized words left to the system where possible.
    static func episode(_ item: MediaItem) -> String {
        switch (item.parentIndexNumber, item.indexNumber) {
        case (0?, let e?): return "Special \(e)"
        case (let s?, let e?): return "S\(s), E\(e)"
        case (nil, let e?): return "Episode \(e)"
        default: return ""
        }
    }

    static func endsAt(remainingSeconds: Double) -> String {
        let end = Date().addingTimeInterval(max(0, remainingSeconds))
        return "Ends at " + end.formatted(date: .omitted, time: .shortened)
    }
}

import PictureshowCore

extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}

#if os(tvOS)
/// Apple TV action button. The system bordered styles took the app tint for both fill and label, so a
/// secondary button's words vanished on the dark theme. Focus is white with dark text and a lift, as
/// in the system apps; unfocused, primary is the accent and secondary a translucent pill.
struct TVActionStyle: ButtonStyle {
    var prominent: Bool
    func makeBody(configuration: Configuration) -> some View {
        TVActionBody(configuration: configuration, prominent: prominent)
    }
}

private struct TVActionBody: View {
    let configuration: ButtonStyleConfiguration
    let prominent: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        configuration.label
            .font(.headline)
            .lineLimit(1)
            .padding(.horizontal, 36).padding(.vertical, 18)
            .foregroundStyle(focused ? Color.black : (prominent ? Theme.onAccent : Color.white))
            .background(Capsule().fill(focused ? Color.white : (prominent ? Theme.accent : Color.white.opacity(0.16))))
            .scaleEffect(configuration.isPressed ? 0.97 : (focused ? 1.08 : 1))
            .shadow(color: .black.opacity(focused ? 0.45 : 0), radius: 20, y: 12)
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}
#endif

/// A veil in the page colour behind hero text (title, synopsis, actions) over artwork. It is anchored to the text
/// block, not to a fraction of the art, so it covers the text at any hero height. At 0.9 the muted synopsis keeps
/// at least 4.5:1 over the worst art: pure white under the dark theme (veil needs >= 0.74) or pure black under the
/// light theme (>= 0.82).
struct HeroScrim: ViewModifier {
    var fade: CGFloat = platformValue(tv: 120, touch: 64)
    func body(content: Content) -> some View {
        content.background(alignment: .bottom) {
            VStack(spacing: 0) {
                LinearGradient(colors: [Theme.background.opacity(0), Theme.background.opacity(0.9)], startPoint: .top, endPoint: .bottom)
                    .frame(height: fade)
                Theme.background.opacity(0.9)
            }
            .padding(.top, -fade)
            #if os(tvOS)
            .ignoresSafeArea(edges: .horizontal)
            #endif
            .accessibilityHidden(true)
        }
    }
}

extension View {
    func heroScrim() -> some View { modifier(HeroScrim()) }
}
