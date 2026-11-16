// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// A wide tile that does something other than open a page: plays a trailer or an extra, or opens a trailer elsewhere.
private struct ActionCard: View {
    @EnvironmentObject private var app: AppModel
    let art: MediaItem?
    let title: String
    let subtitle: String?
    let symbol: String
    let action: () -> Void

    private var width: CGFloat { Theme.wideWidth }

    @ViewBuilder private var artwork: some View {
        Group {
            if let art {
                ArtworkImage(url: app.account.flatMap { a in app.client?.artworkURL(account: a, item: art, kind: .wide, width: Int(width * 2)) }, placeholderSymbol: symbol)
            } else {
                ZStack { Theme.placeholder; Image(systemName: symbol).font(.system(size: 34, weight: .light)).foregroundStyle(Theme.muted) }
            }
        }
        .frame(width: width, height: width * 9 / 16)
        .overlay(alignment: .bottomLeading) {
            Image(systemName: "play.circle.fill").font(.title2).foregroundStyle(.white).shadow(radius: 4).padding(10)
        }
        #if os(iOS)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        #endif
    }

    private var caption: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption.weight(.semibold)).lineLimit(1)
            if let subtitle { Text(subtitle).font(.caption2).foregroundStyle(Theme.muted).lineLimit(1) }
        }
        .frame(width: width, alignment: .leading)
    }

    var body: some View {
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            Button(action: action) { artwork }.buttonStyle(.card)
            caption
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel([title, subtitle].compactMap { $0 }.joined(separator: ", "))
        #else
        Button(action: action) { VStack(alignment: .leading, spacing: 8) { artwork; caption } }
            .buttonStyle(.plain)
            .accessibilityLabel([title, subtitle].compactMap { $0 }.joined(separator: ", "))
        #endif
    }
}

private struct ActionRow<Content: View>: View {
    let title: String
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: title).padding(.horizontal, Theme.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: Theme.cardSpacing) { content() }
                    .padding(.horizontal, Theme.gutter)
                    #if os(tvOS)
                    .padding(.vertical, 24)
                    #endif
            }
            .scrollClipDisabled()
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }
}

/// CAT-30: local trailers play in the app's player; online ones open in YouTube or the browser.
struct TrailersShelf: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.openURL) private var openURL
    let item: MediaItem
    let trailers: [TrailerChoice]
    @State private var failed = false

    var body: some View {
        ActionRow(title: trailers.count == 1 ? "Trailer" : "Trailers") {
            ForEach(trailers) { trailer in
                ActionCard(art: trailer.item ?? item, title: trailer.title, subtitle: trailer.item.flatMap { Format.runtime($0.runTimeTicks) } ?? (trailer.youTubeID != nil ? "YouTube" : "Online"),
                           symbol: "film") { open(trailer) }
            }
        }
        .alert("Can't open this trailer", isPresented: $failed) { Button("OK", role: .cancel) {} } message: {
            Text(Self.unavailableMessage)
        }
    }

    static let unavailableMessage = "This trailer is online. Install YouTube (or a web browser) on this device to watch it."

    private func open(_ trailer: TrailerChoice) {
        if let local = trailer.item { app.play(local, fromStart: true); return }
        let targets = Self.openTargets(trailer)
        guard !targets.isEmpty else { failed = true; return }
        tryOpen(targets[...])
    }

    private func tryOpen(_ targets: ArraySlice<URL>) {
        guard let first = targets.first else { failed = true; return }
        openURL(first) { accepted in if !accepted { tryOpen(targets.dropFirst()) } }
    }

    /// Where an online trailer can open, best first. Apple TV has no browser, so a YouTube video goes to the YouTube
    /// app's own scheme there; iPhone and iPad hand the web address to the system, which opens the YouTube app when
    /// it is installed and the browser otherwise.
    static func openTargets(_ trailer: TrailerChoice) -> [URL] {
        var targets: [URL] = []
        #if os(tvOS)
        if let id = trailer.youTubeID, let url = URL(string: "youtube://watch/\(id)") { targets.append(url) }
        if let id = trailer.youTubeID, let url = URL(string: "youtube://www.youtube.com/watch?v=\(id)") { targets.append(url) }
        #else
        if let id = trailer.youTubeID, let url = URL(string: "https://www.youtube.com/watch?v=\(id)") { targets.append(url) }
        else if let text = trailer.url, let url = URL(string: text) { targets.append(url) }
        #endif
        return targets
    }
}

/// CAT-31: behind-the-scenes, featurettes, interviews, deleted scenes and other extras.
struct ExtrasShelf: View {
    @EnvironmentObject private var app: AppModel
    let extras: [MediaItem]

    var body: some View {
        ActionRow(title: "Extras") {
            ForEach(extras) { extra in
                ActionCard(art: extra, title: extra.name, subtitle: Self.subtitle(extra), symbol: "film.stack") { app.play(extra) }
            }
        }
    }

    static func subtitle(_ extra: MediaItem) -> String {
        [Discovery.extraLabel(extra.extraType), Format.runtime(extra.runTimeTicks)].compactMap { $0 }.joined(separator: " · ")
    }
}
