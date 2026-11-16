// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import CoreFoundation

public let OFFLINE_MANIFEST_SCHEMA = 1
public let OFFLINE_PART_SUFFIX = ".part"

public func offlineEntryId(itemId: String, preset: OfflinePreset) -> String { "\(itemId):\(preset.rawValue)" }
public func offlineEntryDir(entryId: String) -> String {
    // Kotlin maps UTF-16 characters, so a supplementary scalar becomes two underscores.
    let units = entryId.utf16.map { unit -> UInt16 in
        (unit >= 65 && unit <= 90) || (unit >= 97 && unit <= 122) || (unit >= 48 && unit <= 57) || [95, 46, 45].contains(unit) ? unit : 95
    }
    if units.isEmpty || units.allSatisfy({ $0 == 46 }) { return String(repeating: "_", count: max(1, units.count)) }
    return String(decoding: units, as: UTF16.self)
}
public func offlineSafePath(path: String) -> Bool {
    guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\"), !path.contains("\0") else { return false }
    let units = Array(path.utf16)
    if units.count >= 2, units[1] == 58, let scalar = UnicodeScalar(units[0]),
       [.uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter].contains(scalar.properties.generalCategory) { return false }
    return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
}
private extension OfflineResource {
    var neededForReady: Bool { required || role == .media }
}
public func offlineReadyBlockers(entry: OfflineEntry) -> [String] {
    var blockers: [String] = []
    if entry.job.phase != .verifying { blockers.append("job-phase") }
    if !entry.resources.contains(where: { $0.role == .media }) { blockers.append("no-media") }
    for resource in entry.resources where resource.neededForReady {
        if !resource.complete { blockers.append("incomplete:\(resource.id)") }
        else if resource.bytes == nil || resource.sha256 == nil { blockers.append("unverified:\(resource.id)") }
    }
    for resource in entry.resources where !offlineSafePath(path: resource.path) { blockers.append("unsafe:\(resource.id)") }
    if entry.labels.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { blockers.append("no-title") }
    return blockers
}

extension OfflineManifest {
    /// The only durable step to Ready. Persist this result before publishing the entry.
    public func commitReady(entryId: String, attempt: Int64) -> OfflineManifestResult {
        guard let entry = entries.first(where: { $0.id == entryId }) else { return OfflineManifestResult(manifest: self, ok: false, reason: "no-entry") }
        let blockers = offlineReadyBlockers(entry: entry)
        guard blockers.isEmpty else { return OfflineManifestResult(manifest: self, ok: false, reason: blockers.joined(separator: ",")) }
        // This fixed reducer operation cannot throw; attempts can still be refused.
        let reduced = try! reduceOfflineJob(entry.job, OfflineJobEvent(op: "committed", attempt: attempt))
        guard reduced.ignored == nil, let job = reduced.job else { return OfflineManifestResult(manifest: self, ok: false, reason: reduced.ignored) }
        var next = self
        next.entries = entries.map { original in var e = original; if e.id == entryId { e.job = job }; return e }
        return OfflineManifestResult(manifest: next, changedEntries: [entryId])
    }
    /// Inventory is supplied by the engine. Files in the root itself are outside the entry namespace and remain untouched.
    public func reconcile(inventory: [String: Int64]) -> OfflineManifestResult {
        let nextEntries = entries.map { entry -> OfflineEntry in
            var next = entry
            let dir = offlineEntryDir(entryId: entry.id)
            func onDisk(_ resource: OfflineResource) -> Bool {
                guard offlineSafePath(path: resource.path), let size = inventory["\(dir)/\(resource.path)"] else { return false }
                return resource.bytes == nil || resource.bytes == size
            }
            if entry.job.phase == .ready && entry.resources.contains(where: { $0.neededForReady && !onDisk($0) }) {
                next.job = try! reduceOfflineJob(entry.job, OfflineJobEvent(op: "missing")).job ?? entry.job
            } else if [.preparing, .transferring, .verifying].contains(entry.job.phase) {
                next.job = try! reduceOfflineJob(entry.job, OfflineJobEvent(op: "process-restart")).job ?? entry.job
            }
            next.resources = entry.resources.map { r in var n = r; if r.complete && !onDisk(r) { n.complete = false }; return n }
            return next
        }
        var byDir: [String: OfflineEntry] = [:]
        for entry in nextEntries { byDir[offlineEntryDir(entryId: entry.id)] = entry }
        var deleted: [String] = [], kept: Int64 = 0
        for (path, size) in inventory {
            guard let slash = path.firstIndex(of: "/"), offlineSafePath(path: path) else { continue }
            let dir = String(path[..<slash]), rest = String(path[path.index(after: slash)...])
            let entry = byDir[dir]
            let owned = entry.map { $0.job.phase != .ready || rest.hasSuffix(OFFLINE_PART_SUFFIX) || $0.resources.contains(where: { $0.path == rest }) } ?? false
            if owned { kept &+= size } else { deleted.append(path) }
        }
        let remainingDirs = Set(inventory.keys.map { path in path.firstIndex(of: "/").map { String(path[..<$0]) } ?? "" })
        var next = self; next.entries = nextEntries; next.budget.usedBytes = kept; next.budget.reservations = [:]
        next.pendingByteCleanup = pendingByteCleanup.filter { remainingDirs.contains(offlineEntryDir(entryId: $0)) }
        return OfflineManifestResult(manifest: next, deletePaths: deleted.sorted(), changedEntries: entries.indices.filter { entries[$0] != nextEntries[$0] }.map { entries[$0].id })
    }
    public func watchedPreview(played: Set<String>) -> OfflineCleanPreview {
        let qualified = entries.filter { $0.job.phase == .ready && played.contains($0.itemId) }
        return OfflineCleanPreview(entryIds: qualified.map(\.id), titles: qualified.map { $0.labels.title }, bytes: qualified.reduce(0) { $0 &+ $1.committedBytes() })
    }
}

/// Unknown newer schemas and corrupt data return nil; the engine keeps the original file for recovery.
public func offlineDecodeManifest(text: String) -> OfflineManifest? {
    guard let data = text.data(using: .utf8), let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
    if let schema = root["schema"] {
        guard let n = schema as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(),
              n.doubleValue == Double(n.intValue), n.intValue >= 0, n.intValue <= OFFLINE_MANIFEST_SCHEMA else { return nil }
    }
    guard var manifest = try? JSONDecoder().decode(OfflineManifest.self, from: data) else { return nil }
    manifest.schema = OFFLINE_MANIFEST_SCHEMA
    return manifest
}
public func offlineEncodeManifest(_ manifest: OfflineManifest) throws -> String {
    String(decoding: try JSONEncoder().encode(manifest), as: UTF8.self)
}

extension OfflineEntry {
    public func trackSwitch(type: String, serverIndex: Int?) -> String {
        guard let serverIndex else { return "local" }
        guard let track = tracks.first(where: { $0.type == type && $0.serverIndex == serverIndex }), track.available else { return "reconnect" }
        guard let id = track.resourceId else { return "local" }
        return resources.contains { $0.id == id && $0.complete } ? "local" : "reconnect"
    }
    public func committedBytes() -> Int64 { resources.reduce(0) { $0 &+ ($1.bytes ?? 0) } }
}
