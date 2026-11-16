// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public let OFFLINE_MAX_TRANSFERS = 2
private let offlineRunning: Set<OfflinePhase> = [.preparing, .transferring, .verifying]
private let offlineAttemptBound: Set<String> = ["prepared", "transfer-complete", "verified", "committed", "verify-failed", "restart-transfer", "fail"]

/// Attempt-bound callbacks cannot change a newer attempt. Only a durable `committed` publishes Ready.
public func reduceOfflineJob(_ job: OfflineJob, _ event: OfflineJobEvent) throws -> OfflineJobResult {
    func result(_ phase: OfflinePhase? = nil, effects: [String] = [], change: (inout OfflineJob) -> Void = { _ in }) -> OfflineJobResult {
        var next = job
        if let phase { next.phase = phase }
        change(&next)
        return OfflineJobResult(job: next, effects: effects)
    }
    let wrong = OfflineJobResult(job: job, ignored: "wrong-phase")
    let ran = offlineRunning.contains(job.phase)
    let stop = ran ? ["stop-transfer"] : []
    if offlineAttemptBound.contains(event.op), event.attempt != job.attempt {
        return OfflineJobResult(job: job, ignored: "stale-attempt")
    }
    switch event.op {
    case "admit":
        return job.phase == .queued ? result(.preparing, effects: ["start-attempt"]) { $0.attempt &+= 1 } : wrong
    case "prepared": return job.phase == .preparing ? result(.transferring) : wrong
    case "transfer-complete": return job.phase == .transferring ? result(.verifying, effects: ["verify"]) : wrong
    case "verified": return job.phase == .verifying ? result(effects: ["commit-ready"]) : wrong
    case "committed": return job.phase == .verifying ? result(.ready, effects: ["publish"]) { $0.failure = nil } : wrong
    case "verify-failed": return job.phase == .verifying ? result(.queued, effects: ["discard-partial"]) { $0.failure = event.reason } : wrong
    case "restart-transfer": return [.transferring, .preparing].contains(job.phase) ? result(effects: ["discard-partial"]) : wrong
    case "network-lost": return ran || job.phase == .queued ? result(.waitingNetwork, effects: stop) : wrong
    case "network-back": return job.phase == .waitingNetwork ? result(.queued) : wrong
    case "storage-denied": return [.preparing, .transferring, .queued].contains(job.phase) ? result(.waitingStorage, effects: stop) : wrong
    case "storage-available": return job.phase == .waitingStorage ? result(.queued) : wrong
    case "pause":
        guard ran || [.queued, .waitingNetwork, .waitingStorage, .failed].contains(job.phase) else { return wrong }
        return result(.paused, effects: stop) { $0.attempt &+= 1; $0.pausedByCap = event.reason == "cap" }
    case "resume": return job.phase == .paused ? result(.queued) { $0.pausedByCap = false } : wrong
    case "delete":
        if job.phase == .deleting { return result(effects: ["delete-owned"]) }
        return result(.deleting, effects: stop + ["delete-owned"]) { $0.attempt &+= 1 }
    case "deleted": return job.phase == .deleting ? OfflineJobResult(job: nil) : wrong
    case "fail": return ran ? result(.failed, effects: stop) { $0.failure = event.reason } : wrong
    case "retry": return job.phase == .failed ? result(.queued) { $0.failure = nil } : wrong
    case "process-restart":
        if ran { return result(.queued) { $0.attempt &+= 1 } }
        return result(effects: job.phase == .deleting ? ["delete-owned"] : [])
    case "missing": return job.phase == .ready ? result(.missing) : wrong
    case "redownload": return [.missing, .failed].contains(job.phase) ? result(.queued, effects: ["discard-partial"]) { $0.failure = nil } : wrong
    case "set-cellular":
        guard ![.ready, .deleting].contains(job.phase) else { return wrong }
        guard let allowed = event.cellularAllowed else { throw OfflineError.invalidArgument("set-cellular needs cellularAllowed") }
        return result { $0.cellularAllowed = allowed }
    default: throw OfflineError.invalidArgument("Unknown offline job operation: \(event.op)")
    }
}

public func offlineAdmit(activeCount: Int, queuedInOrder: [String]) -> [String] {
    Array(queuedInOrder.prefix(max(0, OFFLINE_MAX_TRANSFERS - activeCount)))
}
