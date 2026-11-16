// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// The four Live TV destinations (LIVE-01).
enum LiveTab: String, CaseIterable, Identifiable, Hashable {
    case channels = "Channels", guide = "Guide", recordings = "Recordings", schedules = "Schedules"
    var id: String { rawValue }
}

/// Screens pushed inside Live TV.
enum LiveRoute: Hashable {
    case program(LiveProgram)
}

extension AppModel {
    /// The viewer's Live TV client: same session, device and token as their Jellyfin client.
    func liveService(for account: Account) -> LiveTVService { LiveTVClient(client: client(for: account)) }

    /// Tunes a channel through the normal player (LIVE-04): PlaybackInfo with the live-open fields, never a guessed URL.
    func tune(_ channel: LiveChannel) {
        guard let account else { return }
        var item = MediaItem(id: channel.id, name: channel.name, type: "TvChannel")
        item.imageTags = channel.imageTags
        player = PlayerRequest(account: account, item: item, fromStart: true)
    }
}

extension LiveChannel {
    /// The channel's number and name for readers, e.g. "Channel 2, Test A".
    var accessibleName: String { number.map { "Channel \($0), \(name)" } ?? name }
}

enum LiveFormat {
    /// The device's 12/24-hour preference, locale and zone, as all Live TV timestamps use.
    static func time(nowMs: Int64) -> LiveTime {
        let pattern = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "h"
        return LiveTime(twentyFour: !pattern.contains("a"), locale: .current, zone: .current, nowMs: nowMs)
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// "Today", "Tomorrow" or a short weekday and date for the guide's day picker.
    static func day(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInTomorrow(date) { return "Tomorrow" }
        return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }
}

/// Channel logo with the name's initial underneath until a logo really loads (round 3: monogram fallback).
struct LiveChannelMark: View {
    @EnvironmentObject private var app: AppModel
    let channel: LiveChannel
    var size: CGFloat = platformValue(tv: 72, touch: 40)
    var showNumber = true
    @State private var loaded = false

    var body: some View {
        VStack(spacing: 2) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Theme.surface)
                if !loaded {
                    Text(String(channel.name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                        .font(.system(size: size * 0.42, weight: .bold, design: .rounded))
                        .foregroundStyle(Theme.muted)
                }
                if let url = logoURL {
                    ArtworkImage(url: url, placeholderSymbol: "tv", contentMode: .fit, fallbackText: "", loaded: $loaded)
                        .padding(4)
                        .opacity(loaded ? 1 : 0)
                }
            }
            .frame(width: size, height: size)
            if showNumber, let number = channel.number {
                Text(number).font(.caption2.monospacedDigit().weight(.semibold)).foregroundStyle(Theme.muted).lineLimit(1)
            }
        }
        .accessibilityHidden(true)
        .onChange(of: logoURL) { loaded = false }
    }

    private var logoURL: URL? {
        guard let account = app.account, let tag = channel.imageTags["Primary"] else { return nil }
        return app.client(for: account).image(account: account, id: channel.id, type: "Primary", tag: tag, width: Int(size * 3))
    }
}

/// A schedule or recording status in its tone (LIVE-08). Each badge owns both colours, so a focused TV row can't
/// invert its text onto it.
struct LiveStatusBadge: View {
    let label: String
    let tone: RecordingTone

    var body: some View {
        HStack(spacing: 5) {
            switch tone {
            case .live: Circle().fill(Self.liveRed).frame(width: 7, height: 7)
            case .error: Image(systemName: "exclamationmark.triangle.fill").font(.caption2)
            default: EmptyView()
            }
            Text(label).lineLimit(1)
        }
        .font(.caption.weight(.semibold))
        .padding(.horizontal, 8).padding(.vertical, 3)
        .foregroundStyle(foreground)
        .background(Capsule().fill(background))
        .accessibilityElement(children: .combine)
    }

    static let liveRed = Color(red: 1.0, green: 0.42, blue: 0.42)

    private var foreground: Color {
        switch tone {
        case .error: return Color(red: 1.0, green: 0.85, blue: 0.84)
        case .live: return .white
        case .muted: return Color(white: 0.82)
        case .neutral: return Color(white: 0.95)
        }
    }

    private var background: Color {
        switch tone {
        case .error: return Color(red: 0.55, green: 0.10, blue: 0.10)
        case .live: return Color(red: 0.36, green: 0.10, blue: 0.14)
        case .muted: return Color(white: 0.30)
        case .neutral: return Color(red: 0.20, green: 0.23, blue: 0.33)
        }
    }
}

/// Stale, offline and failed-read notices (LIVE-11, round 3): a warning glyph, readable text and a real Retry.
struct LiveWarning: View {
    let text: String
    var retry: (() -> Void)?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.warning)
            Text(text).font(platformValue(tv: Font.callout, touch: .footnote)).foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if let retry {
                Button("Retry", action: retry)
                    #if os(tvOS)
                    .secondaryAction()
                    #else
                    .buttonStyle(.bordered)
                    #endif
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.warning.opacity(0.18)))
        .accessibilityElement(children: .contain)
    }
}

/// The shared empty-state: icon, heading, a readable explanation and one real action (round 3).
struct LiveEmptyView: View {
    let presentation: LiveEmptyPresentation
    let act: (LiveEmptyAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: "tv").font(.system(size: platformValue(tv: 52, touch: 36), weight: .light)).foregroundStyle(Theme.muted)
                .accessibilityHidden(true)
            Text(presentation.title).font(platformValue(tv: Font.title3.weight(.semibold), touch: .headline))
                .accessibilityAddTraits(.isHeader)
            Text(presentation.body).font(platformValue(tv: Font.body, touch: .subheadline)).foregroundStyle(Theme.muted)
                .frame(maxWidth: platformValue(tv: 900, touch: 520), alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Button(presentation.action.label) { act(presentation.action) }.primaryAction().padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Theme.gutter)
        .padding(.vertical, platformValue(tv: 40, touch: 24))
    }
}

/// A tvOS row/card focus treatment: white fill and dark text when focused, as the system apps.
#if os(tvOS)
struct LiveTVRowStyle: ButtonStyle {
    var corner: CGFloat = 14
    func makeBody(configuration: Configuration) -> some View { LiveTVRowBody(configuration: configuration, corner: corner) }
}

private struct LiveTVRowBody: View {
    let configuration: ButtonStyleConfiguration
    let corner: CGFloat
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    var body: some View {
        configuration.label
            .foregroundStyle(focused ? Color.black : Color.white)
            .environment(\.liveFocused, focused)
            .background(RoundedRectangle(cornerRadius: corner, style: .continuous).fill(focused ? Color.white : Color.white.opacity(0.07)))
            .opacity(enabled ? 1 : 0.5)
            .scaleEffect(configuration.isPressed ? 0.98 : (focused ? 1.03 : 1))
            .shadow(color: .black.opacity(focused ? 0.4 : 0), radius: 14, y: 8)
            .animation(.easeOut(duration: 0.12), value: focused)
    }
}
#endif

private struct LiveFocusedKey: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    /// Set inside a focused tvOS Live TV row, so secondary text can follow the inverted colours.
    var liveFocused: Bool {
        get { self[LiveFocusedKey.self] }
        set { self[LiveFocusedKey.self] = newValue }
    }
}

/// Secondary text that stays readable on a focused (white) tvOS row.
struct LiveSecondary: View {
    let text: String
    var lines = 1
    @Environment(\.liveFocused) private var focused
    var body: some View {
        Text(text).font(platformValue(tv: Font.callout, touch: .subheadline))
            .foregroundStyle(focused ? Color.black.opacity(0.7) : Theme.muted)
            .lineLimit(lines)
    }
}

/// The route target: Live TV for whoever is signed in now.
struct LiveTVRoute: View {
    @EnvironmentObject private var app: AppModel
    var body: some View {
        if let account = app.account { LiveTVScreen(account: account) }
    }
}

/// The Live TV card beside the libraries (LIVE-01): the same 16:9 tile, a TV glyph and the name.
struct LiveTVTile: View {
    var width: CGFloat? = platformValue(tv: 420, touch: 220)

    var body: some View {
        #if os(tvOS)
        NavigationLink(value: Route.liveTV) { tile }.buttonStyle(.card)
        #else
        NavigationLink(value: Route.liveTV) { tile.clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous)) }.buttonStyle(.plain)
        #endif
    }

    private var tile: some View {
        base
            .background(Theme.placeholder)
            .overlay {
                Image(systemName: "tv.and.mediabox").font(.system(size: platformValue(tv: 64, touch: 40), weight: .light)).foregroundStyle(Theme.muted)
            }
            .overlay(alignment: .bottomLeading) {
                Text("Live TV").font(.headline).foregroundStyle(Color.primary).padding(platformValue(tv: 20, touch: 12))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Live TV")
            .accessibilityAddTraits(.isButton)
    }

    @ViewBuilder private var base: some View {
        if let width {
            Color.clear.frame(width: width, height: width * 9 / 16)
        } else {
            Color.clear.aspectRatio(16 / 9, contentMode: .fit).frame(maxWidth: .infinity)
        }
    }
}
