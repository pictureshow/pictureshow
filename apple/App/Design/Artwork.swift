// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit
import CryptoKit
import PictureshowCore

/// Loads server artwork with the viewer's credentials (only ever sent to that viewer's server origin),
/// decodes it off the main thread and keeps it in memory and on disk.
actor ArtworkLoader {
    static let shared = ArtworkLoader()
    private let memory = NSCache<NSURL, UIImage>()
    private var inFlight: [URL: Task<UIImage?, Never>] = [:]
    private let directory: URL = {
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("artwork", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    init() { memory.totalCostLimit = 160 * 1024 * 1024 }

    func cached(_ url: URL) -> UIImage? { memory.object(forKey: url as NSURL) }

    func image(_ url: URL, account: Account, client: JellyfinClient) async -> UIImage? {
        if let image = memory.object(forKey: url as NSURL) { return image }
        if let task = inFlight[url] { return await task.value }
        let file = directory.appendingPathComponent(Self.name(url))
        let task = Task<UIImage?, Never> {
            if let data = try? Data(contentsOf: file), let image = Self.decode(data) { return image }
            guard let data = try? await client.imageData(account: account, url: url), let image = Self.decode(data) else { return nil }
            try? data.write(to: file, options: .atomic)
            return image
        }
        inFlight[url] = task
        let image = await task.value
        inFlight[url] = nil
        if let image { memory.setObject(image, forKey: url as NSURL, cost: Int(image.size.width * image.size.height * image.scale * image.scale * 4)) }
        return image
    }

    private static func name(_ url: URL) -> String {
        SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func decode(_ data: Data) -> UIImage? {
        guard let image = UIImage(data: data) else { return nil }
        return image.preparingForDisplay() ?? image
    }
}

/// Which picture of an item to show.
enum ArtworkKind: Equatable {
    case poster, wide, backdrop, logo, thumb
}

extension JellyfinClient {
    /// URL for a kind of artwork, falling back the way Jellyfin's own clients do
    /// (an episode without a thumbnail shows its series art, a movie without a backdrop shows nothing).
    func artworkURL(account: Account, item: MediaItem, kind: ArtworkKind, width: Int) -> URL? {
        switch kind {
        case .poster: return imageURL(account: account, item: item, maxWidth: width)
        case .wide, .thumb: return imageURL(account: account, item: item, maxWidth: width, wide: true)
        case .backdrop:
            if let tag = item.backdropImageTags.first { return image(account: account, id: item.id, type: "Backdrop/0", tag: tag, width: width) }
            if let parent = item.parentBackdropItemId, let tag = item.parentBackdropImageTags.first { return image(account: account, id: parent, type: "Backdrop/0", tag: tag, width: width) }
            return nil
        case .logo:
            if let tag = item.imageTags["Logo"] { return image(account: account, id: item.id, type: "Logo", tag: tag, width: width) }
            return nil
        }
    }

    func image(account: Account, id: String, type: String, tag: String?, width: Int) -> URL? {
        try? ServerURLs.endpoint(account.server, path: "Items/\(ServerURLs.encode(id))/Images/\(type)",
                                 query: (tag.map { [URLQueryItem(name: "tag", value: $0)] } ?? []) + [URLQueryItem(name: "maxWidth", value: String(width)), URLQueryItem(name: "quality", value: "85")])
    }
}

/// An artwork image that fills its frame, with a quiet placeholder while it loads or when there is none.
struct ArtworkImage: View {
    @EnvironmentObject private var app: AppModel
    let url: URL?
    var placeholderSymbol: String = "film"
    var contentMode: ContentMode = .fill
    /// Shown instead of the grey placeholder (used for title logos, where a tile would look broken).
    var fallbackText: String?
    /// Set when real art arrives, so a caller can drop a scrim that only makes sense over a picture.
    var loaded: Binding<Bool>?
    @State private var image: UIImage?

    var body: some View {
        // A filled image reports its overflowing size to the layout, which widened whole screens
        // (the Home hero ran off the iPhone). Filling a clear base keeps the size the caller proposed.
        // .clipped() trims the drawing but not touches or the accessibility frame, so a filled
        // poster also claimed taps meant for its neighbour. The overflow is decorative: no touches,
        // no VoiceOver frame; the clear base is the shape that takes the tap.
        Color.clear
            .overlay(alignment: contentMode == .fit ? .leading : .center) {
                content
                    .allowsHitTesting(false)
                    .accessibilityHidden(contentMode == .fill)
            }
            .clipped()
            .contentShape(Rectangle())
            .task(id: url) { await load() }
    }

    @ViewBuilder private var content: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                    .transition(.opacity)
            } else if let fallbackText {
                Text(fallbackText).font(platformValue(tv: .system(size: 64, weight: .bold), touch: .largeTitle.weight(.bold)))
                    .lineLimit(2).minimumScaleFactor(0.6).frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Theme.placeholder
                Image(systemName: placeholderSymbol).font(.system(size: 28, weight: .light)).foregroundStyle(Theme.muted.opacity(0.6))
            }
        }
    }

    private func load() async {
        guard let url, let account = app.account, let client = app.client else { image = nil; loaded?.wrappedValue = false; return }
        if let cached = await ArtworkLoader.shared.cached(url) { image = cached; loaded?.wrappedValue = true; return }
        let result = await ArtworkLoader.shared.image(url, account: account, client: client)
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.2)) { image = result; loaded?.wrappedValue = result != nil }
    }
}
