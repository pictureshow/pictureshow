// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import CryptoKit
import PictureshowCore

/// Where one viewer's downloads live: app-private, protected until first unlock (so a transfer can finish
/// with the screen locked), and kept out of backups (OFF-10).
struct OfflineFiles {
    let root: URL

    static var base: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("Offline", isDirectory: true)
    }

    /// One directory per viewer on one server, named without anything personal in it.
    static func viewerDirectory(accountKey: String) -> String {
        "v-" + SHA256.hash(data: Data(accountKey.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    init(accountKey: String) {
        root = Self.base.appendingPathComponent(Self.viewerDirectory(accountKey: accountKey), isDirectory: true)
    }

    var manifestURL: URL { root.appendingPathComponent("manifest.json") }
    var outboxURL: URL { root.appendingPathComponent("outbox.json") }
    var lastKnownURL: URL { root.appendingPathComponent("lastknown.json") }
    var entries: URL { root.appendingPathComponent("entries", isDirectory: true) }

    func entryDirectory(_ entryID: String) -> URL { entries.appendingPathComponent(offlineEntryDir(entryId: entryID), isDirectory: true) }
    func resourceURL(_ entryID: String, _ resource: OfflineResource) -> URL { entryDirectory(entryID).appendingPathComponent(resource.path) }
    func resumeDataURL(_ entryID: String, _ resourceID: String) -> URL { entryDirectory(entryID).appendingPathComponent(".resume-\(resourceID)") }

    func prepare() throws {
        try Self.makeProtectedDirectory(Self.base)
        try Self.makeProtectedDirectory(root)
        try Self.makeProtectedDirectory(entries)
    }

    static func makeProtectedDirectory(_ url: URL) throws {
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            #if os(iOS)
            try fm.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            #else
            try fm.createDirectory(at: url, withIntermediateDirectories: true)
            #endif
        }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = url
        try? target.setResourceValues(values)
    }

    /// Written whole and atomically, so a kill mid-write leaves the previous version, never half of one.
    func write(_ data: Data, to url: URL) throws {
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: url, options: [.atomic])
        #endif
    }

    /// Bytes on disk for a file or a whole package directory (an HLS download is a directory).
    static func size(of url: URL) -> Int64 {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { return 0 }
        if !isDir.boolValue { return (try? fm.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0 }
        var total: Int64 = 0
        if let walker = fm.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) {
            for case let file as URL in walker {
                let v = try? file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                if v?.isRegularFile == true { total += Int64(v?.fileSize ?? 0) }
            }
        }
        return total
    }

    /// A content hash taken from the file itself, read in chunks. For a package, its files in path order,
    /// each preceded by its relative path, so a renamed or missing segment changes the hash.
    static func sha256(of url: URL) throws -> String {
        var hasher = SHA256()
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { throw CocoaError(.fileNoSuchFile) }
        func feed(_ file: URL) throws {
            let handle = try FileHandle(forReadingFrom: file)
            defer { try? handle.close() }
            while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        }
        if isDir.boolValue {
            let files = (fm.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey])?.allObjects as? [URL] ?? [])
                .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
                .map { ($0.path.replacingOccurrences(of: url.path, with: ""), $0) }
                .sorted { $0.0 < $1.0 }
            for (relative, file) in files { hasher.update(data: Data(relative.utf8)); try feed(file) }
        } else {
            try feed(url)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// The nearest folder of `base` that exists: a volume can't be asked about through a path that isn't there yet, and
    /// Application Support doesn't exist until the app first writes to it. Asking through it made the first download's
    /// limit unknown, so every reservation was refused and the download sat at "Waiting for storage space".
    static var volumeProbe: URL { existingAncestor(of: base) }

    static func existingAncestor(of start: URL) -> URL {
        var url = start
        while !FileManager.default.fileExists(atPath: url.path), url.pathComponents.count > 1 { url = url.deletingLastPathComponent() }
        return url
    }

    static func freeBytes() -> Int64? {
        #if os(tvOS)
        let values = try? volumeProbe.resourceValues(forKeys: [.volumeAvailableCapacityKey])
        return values?.volumeAvailableCapacity.map(Int64.init)
        #else
        let values = try? volumeProbe.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage
        #endif
    }

    static func totalBytes() -> Int64? {
        let values = try? volumeProbe.resourceValues(forKeys: [.volumeTotalCapacityKey])
        return values?.volumeTotalCapacity.map(Int64.init)
    }
}
