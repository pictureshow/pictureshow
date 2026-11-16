// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public let OFFLINE_SYNC_BYTES: Int64 = 16 * 1024 * 1024

public func offlineRangeHeader(partialBytes: Int64, validator: OfflineValidator?) -> String? {
    guard partialBytes > 0, let validator, validator.totalBytes != nil,
          validator.lastModified != nil || validator.etag != nil else { return nil }
    return "bytes=\(partialBytes)-"
}
private func offlineDiffers(_ a: String?, _ b: String?) -> Bool { a != nil && b != nil && a != b }
private extension OfflineValidator {
    func changed(by response: OfflineResponse) -> Bool {
        offlineDiffers(lastModified, response.lastModified) || offlineDiffers(etag, response.etag) || offlineDiffers(sourceETag, response.sourceETag)
    }
    func merged(_ response: OfflineResponse) -> OfflineValidator {
        OfflineValidator(lastModified: response.lastModified ?? lastModified, etag: response.etag ?? etag,
                         sourceETag: response.sourceETag ?? sourceETag, totalBytes: totalBytes)
    }
}
private extension OfflineResponse {
    func validator(total: Int64?) -> OfflineValidator {
        OfflineValidator(lastModified: lastModified, etag: etag, sourceETag: sourceETag, totalBytes: total)
    }
}
private func offlineRangeParts(_ value: String?, pattern: String) -> [String]? {
    guard let value else { return nil }
    let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
    let regex = try! NSRegularExpression(pattern: pattern)
    let range = NSRange(text.startIndex..., in: text)
    guard let match = regex.firstMatch(in: text, range: range), match.range == range else { return nil }
    return (1..<match.numberOfRanges).compactMap { Range(match.range(at: $0), in: text).map { String(text[$0]) } }
}

/// Compare validators even when the server ignores If-Range. The caller performs the returned append/restart/complete/fail action.
public func offlineResumeDecision(partialBytes: Int64, stored: OfflineValidator?, response: OfflineResponse) -> OfflineResumeDecision {
    func restart(_ reason: String) -> OfflineResumeDecision { OfflineResumeDecision(action: "restart", reason: reason) }
    switch response.status {
    case 200:
        return OfflineResumeDecision(action: partialBytes == 0 ? "append" : "restart", totalBytes: response.contentLength,
                                     validator: response.validator(total: response.contentLength), reason: partialBytes == 0 ? nil : "no-range")
    case 206:
        guard let parts = offlineRangeParts(response.contentRange, pattern: #"^bytes (\d+)-(\d+)/(\d+|\*)$"#),
              let start = Int64(parts[0]), let end = Int64(parts[1]), end >= start else { return restart("invalid-range") }
        let total = parts[2] == "*" ? nil : Int64(parts[2])
        guard parts[2] == "*" || total != nil, total == nil || end < total! else { return restart("invalid-range") }
        guard start == partialBytes else { return restart("range-offset") }
        if partialBytes == 0 { return OfflineResumeDecision(action: "append", totalBytes: total, validator: response.validator(total: total)) }
        guard let stored, stored.totalBytes != nil, total == stored.totalBytes else { return restart("range-total") }
        guard !stored.changed(by: response) else { return restart("validator-changed") }
        return OfflineResumeDecision(action: "append", offset: partialBytes, totalBytes: total, validator: stored.merged(response))
    case 416:
        guard let parts = offlineRangeParts(response.contentRange, pattern: #"^bytes \*/(\d+)$"#), let total = Int64(parts[0]),
              total == partialBytes, let stored, stored.totalBytes == total, !stored.changed(by: response) else { return restart("range-unsatisfiable") }
        return OfflineResumeDecision(action: "complete", offset: partialBytes, totalBytes: total, validator: stored.merged(response))
    case 401, 403: return OfflineResumeDecision(action: "fail", reason: "denied")
    case 404: return OfflineResumeDecision(action: "fail", reason: "gone")
    default: return OfflineResumeDecision(action: "fail", reason: "server-\(response.status)")
    }
}

public func offlineBodyEnd(receivedBytes: Int64, totalBytes: Int64?, cleanEof: Bool) -> OfflineBodyEnd {
    guard let totalBytes else { return OfflineBodyEnd(action: cleanEof ? "unknown-length-complete" : "truncated") }
    return OfflineBodyEnd(action: receivedBytes > totalBytes ? "overrun" : receivedBytes == totalBytes ? "complete" : "truncated")
}

/// A conversion may end cleanly at a fragment boundary yet still be short. Allow the greater of 5 seconds and 1%.
public func offlineConversionShort(expectedTicks: Int64?, measuredTicks: Int64?) -> Bool {
    guard let expectedTicks, let measuredTicks, expectedTicks > 0 else { return false }
    return expectedTicks &- measuredTicks > max(50_000_000, expectedTicks / 100)
}

/// First resume after process loss trusts only the sync boundary strictly below the file length.
public func offlineDurableOffset(partialBytes: Int64) -> Int64 {
    partialBytes <= 0 ? 0 : (partialBytes - 1) / OFFLINE_SYNC_BYTES * OFFLINE_SYNC_BYTES
}

public func offlineVerify(_ input: OfflineVerifyInput) -> OfflineVerifyResult {
    let reason: String?
    if let expected = input.expectedBytes, expected != input.actualBytes { reason = "length" }
    else if let expected = input.expectedSha256, expected.lowercased() != input.actualSha256.lowercased() { reason = "hash" }
    else if offlineConversionShort(expectedTicks: input.expectedRuntimeTicks, measuredTicks: input.measuredTicks) { reason = "short" }
    else if offlineDiffers(input.sourceETagBefore, input.sourceETagNow) { reason = "source-changed" }
    else { reason = nil }
    return OfflineVerifyResult(ok: reason == nil, reason: reason)
}
