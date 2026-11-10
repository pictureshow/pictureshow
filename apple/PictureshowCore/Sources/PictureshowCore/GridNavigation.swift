// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum GridVerticalMove: Equatable, Sendable {
    case card(Int), hold, stay, above, pageRetry
}

/// CAT-19: vertical navigation preserves the column, with a bounded hold for a page in flight.
public enum GridNavigation {
    public static let holdCapMillis: Int64 = 1_500
    public static let unboundedTitleFontScale: Float = 1.15
    public static func verticalMove(index: Int, columns: Int, count: Int, down: Bool, loadingMore: Bool, pageFailed: Bool = false) -> GridVerticalMove {
        guard columns > 0, index >= 0, index < count else { return .stay }
        if !down { return index >= columns ? .card(index - columns) : .above }
        let last = count - 1
        // Subtraction also avoids overflow when a malformed caller supplies a very large column count.
        if columns <= last - index { return .card(index + columns) }
        if loadingMore { return .hold }
        if last / columns > index / columns { return .card(last) }
        return pageFailed ? .pageRetry : .stay
    }
    public static func holdRelease(index: Int, columns: Int, count: Int, pageFailed: Bool = false) -> GridVerticalMove {
        verticalMove(index: index, columns: columns, count: count, down: true, loadingMore: false, pageFailed: pageFailed)
    }
    public static func stripDetail(sortBy: String, descending: Bool) -> String {
        let title = ["SortName": "Name", "DateCreated": "Date added", "PremiereDate": "Release date"][sortBy] ?? "Name"
        return "\(title), \(descending ? "descending" : "ascending")"
    }
    public static func fixedHeadCollapses(fontScale: Float, contentFocused: Bool, assistiveServiceInUse: Bool) -> Bool {
        contentFocused && !assistiveServiceInUse && fontScale > unboundedTitleFontScale
    }
    public static func fixedHeadInList(fontScale: Float, assistiveServiceInUse: Bool) -> Bool {
        assistiveServiceInUse && fontScale > unboundedTitleFontScale
    }
}

public enum ServerLabels {
    public static func label(_ server: Server, all: [Server]) -> String {
        let name = server.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return server.url.absoluteString }
        var seen = Set<String>()
        let repeats = all.filter { seen.insert("\($0.id)\u{0}\($0.url.absoluteString)").inserted }
            .filter { $0.name.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(name) == .orderedSame }.count > 1
        return repeats ? "\(name) · \(server.url.absoluteString)" : name
    }
}

/// PLAY-15/16: a displayed countdown follows playback rate and a suspended card, rather than a second clock.
public enum NextEpisodeCard {
    public static func seconds(wallSeconds: Int64, creditsCountdown: Bool, mediaLeftMs: Int64, speed: Double, autoPlay: Bool, ended: Bool, suspended: Bool) -> Int64? {
        guard autoPlay, !suspended || ended else { return nil }
        guard mediaLeftMs > 0, speed > 0 else { return wallSeconds }
        let value = ceil(Double(mediaLeftMs) / speed / 1_000)
        let toEnd = value >= Double(Int64.max) ? Int64.max : value.isNaN ? 0 : Int64(value)
        return creditsCountdown ? min(wallSeconds, toEnd) : toEnd
    }
}
