// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// All entry points share the same availability decision, including context menus and returning pages.
enum CatalogPresentation {
    static func offersPlayback(_ item: MediaItem) -> Bool {
        !item.isMissing && ["Movie", "Episode", "Video", "MusicVideo", "Recording"].contains(item.type)
    }

    static func offersRestart(_ item: MediaItem) -> Bool {
        offersPlayback(item) && item.isResumable
    }

    /// PLAY-17: on the page a finished viewing returned to, a film or episode's Play reads "Play again" and starts at
    /// the beginning; a series or season keeps naming the episode it plays.
    static func playLabel(container: MediaItem, target: MediaItem, finished: Bool = false) -> String {
        if finished && !["Series", "Season"].contains(container.type) { return "Play again" }
        let verb = target.isResumable ? "Resume" : "Play"
        if ["Series", "Season"].contains(container.type) {
            return "\(verb) \(target.episodeLabel) · \(target.name)"
        }
        return verb
    }

    static func missingLabel(_ item: MediaItem, now: Date = Date()) -> String? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return Catalog.missingItemLabel(item: item, today: formatter.string(from: now))
    }
}

/// PLAY-17: the last viewing that played a film or a series' last episode to its end. `serial` orders it against detail
/// pages: only a page that was already open under the player is the page the viewer returns to.
struct FinishedViewings: Equatable {
    struct Viewing: Equatable {
        let serial: Int
        let relatedIDs: Set<String>
        let title: String?
    }
    private(set) var serial = 0
    private(set) var last: Viewing?

    /// The player calls this at a natural end with nothing to play next, before it closes.
    mutating func finished(_ item: MediaItem) {
        serial += 1
        last = Viewing(serial: serial, relatedIDs: Set([item.id, item.seriesId, item.seasonId].compactMap { $0 }), title: item.name)
    }

    /// The line belongs to that return only: leaving the page (its `detailID`) or starting another viewing (nil) ends it.
    mutating func clear(detailID: String? = nil) {
        guard let last, detailID == nil || last.relatedIDs.contains(detailID!) else { return }
        self.last = nil
    }

    /// "Finished. You watched {title} to the end." for a page about `detailID` that opened at `arrival` (the serial then).
    func line(detailID: String, arrival: Int) -> String? {
        guard let last, last.serial > arrival, last.relatedIDs.contains(detailID) else { return nil }
        return DetailFeedback.playbackFinishedLine(title: last.title)
    }
}
