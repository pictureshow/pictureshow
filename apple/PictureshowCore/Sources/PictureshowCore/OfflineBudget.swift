// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public let OFFLINE_DEFAULT_CAP_BYTES: Int64 = 10 * 1024 * 1024 * 1024
public let OFFLINE_FREE_RESERVE_BYTES: Int64 = 512 * 1024 * 1024

/// Unknown quota requires an explicit viewer-selected cap.
public func offlineDefaultCap(quotaBytes: Int64?) -> Int64? {
    guard let quotaBytes, quotaBytes >= 0 else { return nil }
    return min(OFFLINE_DEFAULT_CAP_BYTES, quotaBytes / 5)
}

extension OfflineBudget {
    public func reserve(job: String, bytes: Int64, freeBytes: Int64?) throws -> OfflineBudgetResult {
        guard bytes >= 0 else { throw OfflineError.invalidArgument("Negative reservation") }
        if bytes == 0 { return OfflineBudgetResult(budget: self) }
        guard let cap = capBytes else { return OfflineBudgetResult(budget: self, granted: false, reason: "cap-required") }
        let reserved = reservations.values.reduce(0, &+)
        if bytes > cap &- usedBytes &- reserved { return OfflineBudgetResult(budget: self, granted: false, reason: "cap") }
        if let freeBytes, freeBytes &- reserved &- bytes < OFFLINE_FREE_RESERVE_BYTES {
            return OfflineBudgetResult(budget: self, granted: false, reason: "disk")
        }
        var next = self
        next.reservations[job] = (reservations[job] ?? 0) &+ bytes
        return OfflineBudgetResult(budget: next)
    }
    public func commit(job: String, bytes: Int64) throws -> OfflineBudgetResult {
        guard bytes >= 0 else { throw OfflineError.invalidArgument("Negative commit") }
        if bytes == 0 { return OfflineBudgetResult(budget: self) }
        let remaining = reservations[job] ?? 0
        guard bytes <= remaining else { return OfflineBudgetResult(budget: self, granted: false, reason: "unreserved") }
        var next = self
        next.usedBytes = usedBytes &+ bytes
        if remaining == bytes { next.reservations.removeValue(forKey: job) } else { next.reservations[job] = remaining - bytes }
        return OfflineBudgetResult(budget: next)
    }
    public func release(job: String) -> OfflineBudgetResult {
        var next = self; next.reservations.removeValue(forKey: job)
        return OfflineBudgetResult(budget: next)
    }
    public func discard(job: String, committedBytes: Int64) throws -> OfflineBudgetResult {
        guard committedBytes >= 0 else { throw OfflineError.invalidArgument("Negative discard") }
        var next = self; next.usedBytes = max(0, usedBytes &- committedBytes); next.reservations.removeValue(forKey: job)
        return OfflineBudgetResult(budget: next)
    }
    /// Release newest reservations first; committed bytes are never evicted.
    public func setCap(capBytes: Int64?, jobNewestFirst: [String]) -> OfflineBudgetResult {
        var next = self; next.capBytes = capBytes
        guard let capBytes else { return OfflineBudgetResult(budget: next) }
        var paused: [String] = [], seen = Set<String>()
        for job in jobNewestFirst + reservations.keys.sorted() where seen.insert(job).inserted {
            if usedBytes &+ next.reservations.values.reduce(0, &+) <= capBytes { break }
            if next.reservations.removeValue(forKey: job) != nil { paused.append(job) }
        }
        return OfflineBudgetResult(budget: next, reason: usedBytes > capBytes ? "used-over-cap" : nil, pauseJobs: paused)
    }
}
