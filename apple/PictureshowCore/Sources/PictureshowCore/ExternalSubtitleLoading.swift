// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Optional text loads independently of A/V readiness and retries while selected.
/// The app cancels work on deselection/release and rejects stale completions after a selection change.
public enum ExternalSubtitleLoading {
    public static func canSelectLocally(transcoding: Bool, subtitle: Bool, currentLocal: Bool, nextLocal: Bool) -> Bool {
        !transcoding || (subtitle && currentLocal && nextLocal)
    }

    public static func retryDelayMs(errorCount: Int) -> Int64 {
        Int64(min(10, max(1, errorCount))) * 1_000
    }

    /// A full-file subtitle retains authored time when server-seek video begins at local zero.
    public static func sourceOffsetMs(fullTimeline: Bool, serverStartMs: Int64) -> Int64 {
        fullTimeline ? serverStartMs : 0
    }

    public static func shouldLoad(selectedIndex: Int?, streamIndex: Int, released: Bool) -> Bool {
        !released && streamIndex >= 0 && selectedIndex == streamIndex
    }
}
