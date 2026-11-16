// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// One viewer-scoped `POST /SyncPlay/<operation>`. A 2xx only means dispatched: the group event, followed by
/// rendered output, confirms it. Retries belong to a deliberate Join or recovery, never to a mutation.
public struct SyncRequest: Equatable, Sendable {
    public static let operations: Set<String> = [
        "New", "Join", "Leave", "SetNewQueue", "Queue", "MovePlaylistItem", "RemoveFromPlaylist", "SetPlaylistItem",
        "NextItem", "PreviousItem", "SetRepeatMode", "SetShuffleMode", "Pause", "Unpause", "Seek", "Stop", "Buffering",
        "Ready", "Ping", "SetIgnoreWait"
    ]
    public let operation: String
    /// The JSON body, or nil for a bodiless POST.
    public let body: SyncJSON?
    public var path: String { "SyncPlay/" + operation }

    public struct Invalid: Error {}

    init(_ operation: String, _ body: SyncJSON? = nil) throws {
        guard Self.operations.contains(operation) else { throw Invalid() }
        self.operation = operation; self.body = body
    }
    private static func ensure(_ condition: Bool) throws { if !condition { throw Invalid() } }
    private static func blank(_ s: String) -> Bool { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private static func number(_ n: Int64) -> SyncJSON { .literal(String(n)) }
    private static func strings(_ s: [String]) -> SyncJSON { .array(s.map(SyncJSON.string)) }

    public static func create(name: String) throws -> SyncRequest {
        try ensure(!blank(name) && name.count <= 200)
        return try SyncRequest("New", .object([("GroupName", .string(name))]))
    }
    public static func join(groupId: String) throws -> SyncRequest {
        try ensure(!blank(groupId))
        return try SyncRequest("Join", .object([("GroupId", .string(groupId))]))
    }
    public static func leave() -> SyncRequest { try! SyncRequest("Leave") }

    /// Pause, Unpause, Seek (with a position), Stop, NextItem or PreviousItem (with the current playlist entry).
    public static func transport(_ command: String, positionTicks: Int64? = nil, playlistItemId: String? = nil) throws -> SyncRequest {
        switch command {
        case "Seek":
            guard let ticks = positionTicks, ticks >= 0 else { throw Invalid() }
            return try SyncRequest(command, .object([("PositionTicks", number(ticks))]))
        case "NextItem", "PreviousItem":
            guard let entry = playlistItemId, !blank(entry) else { throw Invalid() }
            return try SyncRequest(command, .object([("PlaylistItemId", .string(entry))]))
        case "Pause", "Unpause", "Stop": return try SyncRequest(command)
        default: throw Invalid()
        }
    }
    public static func newQueue(items: [String], currentIndex: Int = 0, startPositionTicks: Int64 = 0) throws -> SyncRequest {
        try ensure(!items.isEmpty && !items.contains(where: blank) && items.indices.contains(currentIndex) && startPositionTicks >= 0)
        return try SyncRequest("SetNewQueue", .object([("PlayingQueue", strings(items)), ("PlayingItemPosition", number(Int64(currentIndex))), ("StartPositionTicks", number(startPositionTicks))]))
    }
    public static func enqueue(items: [String], next: Bool) throws -> SyncRequest {
        try ensure(!items.isEmpty && !items.contains(where: blank))
        return try SyncRequest("Queue", .object([("ItemIds", strings(items)), ("Mode", .string(next ? "QueueNext" : "Queue"))]))
    }
    public static func move(playlistItemId: String, index: Int) throws -> SyncRequest {
        try ensure(!blank(playlistItemId) && index >= 0)
        return try SyncRequest("MovePlaylistItem", .object([("PlaylistItemId", .string(playlistItemId)), ("NewIndex", number(Int64(index)))]))
    }
    public static func remove(playlistItemIds: [String], clearPlaylist: Bool = false, clearPlayingItem: Bool = false) throws -> SyncRequest {
        try ensure(!playlistItemIds.contains(where: blank) && (!playlistItemIds.isEmpty || clearPlaylist))
        return try SyncRequest("RemoveFromPlaylist", .object([("PlaylistItemIds", strings(playlistItemIds)), ("ClearPlaylist", .literal(String(clearPlaylist))), ("ClearPlayingItem", .literal(String(clearPlayingItem)))]))
    }
    public static func select(playlistItemId: String) throws -> SyncRequest {
        try ensure(!blank(playlistItemId))
        return try SyncRequest("SetPlaylistItem", .object([("PlaylistItemId", .string(playlistItemId))]))
    }
    public static func repeatMode(_ mode: String) throws -> SyncRequest {
        try ensure(SyncPlay.repeatModes.contains(mode))
        return try SyncRequest("SetRepeatMode", .object([("Mode", .string(mode))]))
    }
    public static func shuffle(_ shuffled: Bool) -> SyncRequest { try! SyncRequest("SetShuffleMode", .object([("Mode", .string(shuffled ? "Shuffle" : "Sorted"))])) }
    public static func ignoreWait(_ ignore: Bool) -> SyncRequest { try! SyncRequest("SetIgnoreWait", .object([("IgnoreWait", .literal(String(ignore)))])) }
    public static func ping(roundTripMs: Int64) throws -> SyncRequest {
        try ensure((0...1000).contains(roundTripMs))
        return try SyncRequest("Ping", .object([("Ping", number(roundTripMs))]))
    }
    /// The caller supplies the actual renderer entry, position and intent; never a synthetic zero or Ready before preparation.
    public static func readiness(ready: Bool, whenMs: Int64, positionTicks: Int64, isPlaying: Bool, playlistItemId: String) throws -> SyncRequest {
        try ensure(positionTicks >= 0 && !blank(playlistItemId) && (-62_135_596_800_000...253_402_300_799_999).contains(whenMs))
        return try SyncRequest(ready ? "Ready" : "Buffering", .object([
            ("When", .string(SyncPlay.utcTimestamp(whenMs))), ("PositionTicks", number(positionTicks)),
            ("IsPlaying", .literal(String(isPlaying))), ("PlaylistItemId", .string(playlistItemId))
        ]))
    }
}
