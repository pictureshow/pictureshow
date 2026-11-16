// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// All entry points share the same availability decision, including context menus and returning pages.
enum CatalogPresentation {
    static func offersPlayback(_ item: MediaItem) -> Bool {
        !item.isMissing && ["Movie", "Episode", "Video", "MusicVideo"].contains(item.type)
    }

    static func offersRestart(_ item: MediaItem) -> Bool {
        offersPlayback(item) && item.isResumable
    }

    static func playLabel(container: MediaItem, target: MediaItem) -> String {
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
