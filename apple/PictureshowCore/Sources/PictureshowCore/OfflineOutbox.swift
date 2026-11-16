// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import CryptoKit

private extension OfflineOutboxEntry {
    func ignore(_ reason: String) -> OfflineOutboxResult { OfflineOutboxResult(entry: self, ignored: reason) }
    func conflictRecord(_ current: OfflineUserData, fields: [String]) -> OfflineConflict {
        OfflineConflict(serverPositionTicks: current.positionTicks, serverPlayed: current.played,
                        localPositionTicks: positionTicks, localPlayed: played, fields: fields)
    }
}
private func offlineLocalChange(_ entry: OfflineOutboxEntry?, _ event: OfflineOutboxEvent, apply: (inout OfflineOutboxEntry) throws -> Void) throws -> OfflineOutboxResult {
    var record: OfflineOutboxEntry
    if let entry { record = entry }
    else if let server = event.server, let viewer = event.viewer, let itemId = event.itemId, let base = event.base {
        record = OfflineOutboxEntry(server: server, viewer: viewer, itemId: itemId, base: base, sequence: event.sequence ?? 0)
    } else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
    if (event.server != nil && event.server != record.server) || (event.viewer != nil && event.viewer != record.viewer) { return record.ignore("other-account") }
    if event.itemId != nil && event.itemId != record.itemId { return record.ignore("other-item") }
    let sequence = event.sequence ?? record.sequence
    if sequence < record.sequence { return record.ignore("old-sequence") }
    if record.phase == "conflict" {
        guard event.op == "viewing", sequence != record.sequence else { return record.ignore("conflict") }
        record.base = event.base ?? record.base; record.phase = "dirty"; record.conflict = nil; record.positionTicks = nil; record.played = nil
    } else if record.phase == "synced" {
        record.base = event.base ?? record.base; record.phase = "dirty"; record.positionTicks = nil; record.played = nil
    }
    record.sequence = sequence
    try apply(&record)
    return OfflineOutboxResult(entry: record)
}
private func offlineServerState(_ entry: OfflineOutboxEntry, _ current: OfflineUserData) -> OfflineOutboxResult {
    if entry.phase == "conflict" { return entry.ignore("conflict") }
    var fields: [String] = [], position: Int64?, played: Bool?
    if let desired = entry.positionTicks, current.positionTicks != desired {
        if current.positionTicks == entry.base.positionTicks || current.positionTicks == entry.writing?.positionTicks { position = desired }
        else { fields.append("position") }
    }
    if let desired = entry.played, current.played != desired {
        if current.played == entry.base.played || current.played == entry.writing?.played { played = desired }
        else { fields.append("played") }
    }
    let conflict = fields.isEmpty ? nil : entry.conflictRecord(current, fields: fields)
    var next = entry
    if position != nil || played != nil {
        let write = OfflineUserDataWrite(positionTicks: position, played: played)
        next.phase = "writing"; next.conflict = conflict; next.writing = write
        return OfflineOutboxResult(entry: next, effect: "write", write: write)
    }
    if let conflict {
        next.phase = "conflict"; next.conflict = conflict; next.writing = nil
        return OfflineOutboxResult(entry: next, effect: "conflict")
    }
    return OfflineOutboxResult(entry: nil, effect: "ack")
}
private func offlineReadback(_ entry: OfflineOutboxEntry, _ current: OfflineUserData) -> OfflineOutboxResult {
    let held = entry.conflict?.fields ?? []
    let written = entry.writing ?? OfflineUserDataWrite(positionTicks: held.contains("position") ? nil : entry.positionTicks,
                                                        played: held.contains("played") ? nil : entry.played)
    var failed: [String] = []
    if written.positionTicks != nil && written.positionTicks != current.positionTicks { failed.append("position") }
    if written.played != nil && written.played != current.played { failed.append("played") }
    var next = entry; next.writing = nil
    if !failed.isEmpty {
        next.phase = "conflict"; next.conflict = entry.conflictRecord(current, fields: held + failed)
        return OfflineOutboxResult(entry: next, effect: "conflict")
    }
    if entry.conflict != nil { next.phase = "conflict"; return OfflineOutboxResult(entry: next, effect: "ack") }
    if written.positionTicks == entry.positionTicks && written.played == entry.played { return OfflineOutboxResult(entry: nil, effect: "ack") }
    // A newer viewing arrived while the write was in flight. Its values need a fresh server read.
    next.phase = "dirty"; next.base = current
    return OfflineOutboxResult(entry: next, effect: "read")
}

/// Durable per-account progress, with server-wins conflicts and read-before-retry after an ambiguous write.
public func reduceOfflineOutbox(_ entry: OfflineOutboxEntry?, _ event: OfflineOutboxEvent) throws -> OfflineOutboxResult {
    func current() throws -> OfflineUserData {
        guard let current = event.current else { throw OfflineError.invalidArgument("\(event.op) needs current") }; return current
    }
    switch event.op {
    case "viewing": return try offlineLocalChange(entry, event) { $0.positionTicks = event.positionTicks ?? $0.positionTicks }
    case "completed": return try offlineLocalChange(entry, event) { $0.played = true; $0.positionTicks = 0 }
    case "played-intent":
        return try offlineLocalChange(entry, event) {
            guard let played = event.played else { throw OfflineError.invalidArgument("played-intent needs played") }
            $0.played = played; if !played { $0.positionTicks = 0 }
        }
    case "reconnect":
        guard let entry else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
        guard event.server == entry.server, event.viewer == entry.viewer else { return entry.ignore("other-account") }
        return OfflineOutboxResult(entry: entry, effect: "read")
    case "server-state":
        guard let entry else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
        return try offlineServerState(entry, current())
    case "write-result":
        guard let entry else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
        guard entry.phase == "writing" else { return entry.ignore("wrong-phase") }
        return OfflineOutboxResult(entry: entry, effect: event.status.map { (200...299).contains($0) } == true ? "readback" : "read")
    case "readback":
        guard let entry else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
        guard entry.phase == "writing" else { return entry.ignore("wrong-phase") }
        return try offlineReadback(entry, current())
    case "process-restart": return OfflineOutboxResult(entry: entry, effect: entry?.phase == "writing" ? "read" : "none")
    case "online-viewing":
        let current = try current()
        guard let entry else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
        if entry.phase != "conflict" && current.positionTicks == entry.base.positionTicks && current.played == entry.base.played { return OfflineOutboxResult(entry: entry) }
        return OfflineOutboxResult(entry: nil)
    case "dismiss-conflict":
        guard let entry else { return OfflineOutboxResult(entry: nil, ignored: "no-entry") }
        guard entry.phase == "conflict" else { return entry.ignore("wrong-phase") }
        return OfflineOutboxResult(entry: nil)
    default: throw OfflineError.invalidArgument("Unknown offline outbox operation: \(event.op)")
    }
}

public func offlineResumeTicks(entry: OfflineOutboxEntry?, lastKnown: OfflineUserData?) -> Int64 {
    if let entry, entry.phase != "conflict" {
        if entry.played == true { return 0 }
        if let position = entry.positionTicks { return position }
    }
    return lastKnown.flatMap { $0.played ? nil : $0.positionTicks } ?? 0
}
public func offlineSha256Hex(bytes: [UInt8]) -> String { SHA256.hash(data: Data(bytes)).map { String(format: "%02x", $0) }.joined() }
public func offlineDownloadSetDevice(installationDeviceId: String, serverId: String, userId: String) -> String {
    "pictureshow-dl-" + offlineSha256Hex(bytes: Array("\(installationDeviceId)\0\(serverId)\0\(userId)".utf8)).prefix(32)
}
