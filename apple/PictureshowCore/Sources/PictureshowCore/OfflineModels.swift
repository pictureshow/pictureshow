// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum OfflineError: Error, Equatable, Sendable {
    case invalidArgument(String)
}
public enum OfflinePhase: String, Codable, CaseIterable, Sendable {
    case queued, waitingNetwork, waitingStorage, preparing, transferring, verifying, ready, paused, failed, missing, deleting
}
public enum OfflinePreset: String, Codable, CaseIterable, Sendable {
    case original, video720, video1080, audio128, audio256
}
public enum OfflineMediaKind: String, Codable, Sendable { case movie, episode, audio }
public enum OfflineResourceRole: String, Codable, Sendable { case media, audio, subtitle, artwork }
public enum OfflineServerReach: String, Codable, Sendable { case reachable, unreachable, unknown }
public enum OfflineDetailSource: String, Codable, Sendable { case local, stream, unavailable }

/// Durable download state. Attempt numbers reject late callbacks after pause, delete, or process restart.
public struct OfflineJob: Codable, Equatable, Sendable {
    public var phase: OfflinePhase
    public var attempt: Int64
    public var cellularAllowed: Bool
    public var failure: String?
    public var pausedByCap: Bool
    public init(phase: OfflinePhase = .queued, attempt: Int64 = 0, cellularAllowed: Bool = false, failure: String? = nil, pausedByCap: Bool = false) {
        self.phase = phase
        self.attempt = attempt
        self.cellularAllowed = cellularAllowed
        self.failure = failure
        self.pausedByCap = pausedByCap
    }
    private enum CodingKeys: String, CodingKey { case phase, attempt, cellularAllowed, failure, pausedByCap }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        phase = c.contains(.phase) ? try c.decode(OfflinePhase.self, forKey: .phase) : .queued
        attempt = c.contains(.attempt) ? try c.decode(Int64.self, forKey: .attempt) : 0
        cellularAllowed = c.contains(.cellularAllowed) ? try c.decode(Bool.self, forKey: .cellularAllowed) : false
        failure = c.contains(.failure) ? try c.decodeIfPresent(String.self, forKey: .failure) : nil
        pausedByCap = c.contains(.pausedByCap) ? try c.decode(Bool.self, forKey: .pausedByCap) : false
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(phase, forKey: .phase)
        try c.encode(attempt, forKey: .attempt)
        try c.encode(cellularAllowed, forKey: .cellularAllowed)
        try c.encode(failure, forKey: .failure)
        try c.encode(pausedByCap, forKey: .pausedByCap)
    }
}

public struct OfflineJobEvent: Codable, Equatable, Sendable {
    public var op: String
    public var attempt: Int64?
    public var reason: String?
    public var cellularAllowed: Bool?
    public init(op: String, attempt: Int64? = nil, reason: String? = nil, cellularAllowed: Bool? = nil) {
        self.op = op
        self.attempt = attempt
        self.reason = reason
        self.cellularAllowed = cellularAllowed
    }
    private enum CodingKeys: String, CodingKey { case op, attempt, reason, cellularAllowed }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        op = try c.decode(String.self, forKey: .op)
        attempt = c.contains(.attempt) ? try c.decodeIfPresent(Int64.self, forKey: .attempt) : nil
        reason = c.contains(.reason) ? try c.decodeIfPresent(String.self, forKey: .reason) : nil
        cellularAllowed = c.contains(.cellularAllowed) ? try c.decodeIfPresent(Bool.self, forKey: .cellularAllowed) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(op, forKey: .op)
        try c.encode(attempt, forKey: .attempt)
        try c.encode(reason, forKey: .reason)
        try c.encode(cellularAllowed, forKey: .cellularAllowed)
    }
}

public struct OfflineJobResult: Codable, Equatable, Sendable {
    public var job: OfflineJob?
    public var effects: [String]
    public var ignored: String?
    public init(job: OfflineJob?, effects: [String] = [], ignored: String? = nil) {
        self.job = job
        self.effects = effects
        self.ignored = ignored
    }
    private enum CodingKeys: String, CodingKey { case job, effects, ignored }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        job = try c.decode(OfflineJob?.self, forKey: .job)
        effects = c.contains(.effects) ? try c.decode([String].self, forKey: .effects) : []
        ignored = c.contains(.ignored) ? try c.decodeIfPresent(String.self, forKey: .ignored) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(job, forKey: .job)
        try c.encode(effects, forKey: .effects)
        try c.encode(ignored, forKey: .ignored)
    }
}

/// Committed bytes plus per-job reservations; all values use exact Int64 byte counts.
public struct OfflineBudget: Codable, Equatable, Sendable {
    public var capBytes: Int64?
    public var usedBytes: Int64
    public var reservations: [String: Int64]
    public init(capBytes: Int64? = nil, usedBytes: Int64 = 0, reservations: [String: Int64] = [:]) {
        self.capBytes = capBytes
        self.usedBytes = usedBytes
        self.reservations = reservations
    }
    private enum CodingKeys: String, CodingKey { case capBytes, usedBytes, reservations }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        capBytes = c.contains(.capBytes) ? try c.decodeIfPresent(Int64.self, forKey: .capBytes) : nil
        usedBytes = c.contains(.usedBytes) ? try c.decode(Int64.self, forKey: .usedBytes) : 0
        reservations = c.contains(.reservations) ? try c.decode([String: Int64].self, forKey: .reservations) : [:]
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(capBytes, forKey: .capBytes)
        try c.encode(usedBytes, forKey: .usedBytes)
        try c.encode(reservations, forKey: .reservations)
    }
}

public struct OfflineBudgetResult: Codable, Equatable, Sendable {
    public var budget: OfflineBudget
    public var granted: Bool
    public var reason: String?
    public var pauseJobs: [String]
    public init(budget: OfflineBudget, granted: Bool = true, reason: String? = nil, pauseJobs: [String] = []) {
        self.budget = budget
        self.granted = granted
        self.reason = reason
        self.pauseJobs = pauseJobs
    }
    private enum CodingKeys: String, CodingKey { case budget, granted, reason, pauseJobs }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        budget = try c.decode(OfflineBudget.self, forKey: .budget)
        granted = c.contains(.granted) ? try c.decode(Bool.self, forKey: .granted) : true
        reason = c.contains(.reason) ? try c.decodeIfPresent(String.self, forKey: .reason) : nil
        pauseJobs = c.contains(.pauseJobs) ? try c.decode([String].self, forKey: .pauseJobs) : []
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(budget, forKey: .budget)
        try c.encode(granted, forKey: .granted)
        try c.encode(reason, forKey: .reason)
        try c.encode(pauseJobs, forKey: .pauseJobs)
    }
}

/// HTTP and source validators stored alongside the partial; totalBytes is required for a ranged resume.
public struct OfflineValidator: Codable, Equatable, Sendable {
    public var lastModified: String?
    public var etag: String?
    public var sourceETag: String?
    public var totalBytes: Int64?
    public init(lastModified: String? = nil, etag: String? = nil, sourceETag: String? = nil, totalBytes: Int64? = nil) {
        self.lastModified = lastModified
        self.etag = etag
        self.sourceETag = sourceETag
        self.totalBytes = totalBytes
    }
    private enum CodingKeys: String, CodingKey { case lastModified, etag, sourceETag, totalBytes }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lastModified = c.contains(.lastModified) ? try c.decodeIfPresent(String.self, forKey: .lastModified) : nil
        etag = c.contains(.etag) ? try c.decodeIfPresent(String.self, forKey: .etag) : nil
        sourceETag = c.contains(.sourceETag) ? try c.decodeIfPresent(String.self, forKey: .sourceETag) : nil
        totalBytes = c.contains(.totalBytes) ? try c.decodeIfPresent(Int64.self, forKey: .totalBytes) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(lastModified, forKey: .lastModified)
        try c.encode(etag, forKey: .etag)
        try c.encode(sourceETag, forKey: .sourceETag)
        try c.encode(totalBytes, forKey: .totalBytes)
    }
}

public struct OfflineResponse: Codable, Equatable, Sendable {
    public var status: Int
    public var contentRange: String?
    public var etag: String?
    public var lastModified: String?
    public var contentLength: Int64?
    public var acceptRanges: String?
    public var sourceETag: String?
    public init(status: Int, contentRange: String? = nil, etag: String? = nil, lastModified: String? = nil, contentLength: Int64? = nil, acceptRanges: String? = nil, sourceETag: String? = nil) {
        self.status = status
        self.contentRange = contentRange
        self.etag = etag
        self.lastModified = lastModified
        self.contentLength = contentLength
        self.acceptRanges = acceptRanges
        self.sourceETag = sourceETag
    }
    private enum CodingKeys: String, CodingKey { case status, contentRange, etag, lastModified, contentLength, acceptRanges, sourceETag }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        status = try c.decode(Int.self, forKey: .status)
        contentRange = c.contains(.contentRange) ? try c.decodeIfPresent(String.self, forKey: .contentRange) : nil
        etag = c.contains(.etag) ? try c.decodeIfPresent(String.self, forKey: .etag) : nil
        lastModified = c.contains(.lastModified) ? try c.decodeIfPresent(String.self, forKey: .lastModified) : nil
        contentLength = c.contains(.contentLength) ? try c.decodeIfPresent(Int64.self, forKey: .contentLength) : nil
        acceptRanges = c.contains(.acceptRanges) ? try c.decodeIfPresent(String.self, forKey: .acceptRanges) : nil
        sourceETag = c.contains(.sourceETag) ? try c.decodeIfPresent(String.self, forKey: .sourceETag) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(status, forKey: .status)
        try c.encode(contentRange, forKey: .contentRange)
        try c.encode(etag, forKey: .etag)
        try c.encode(lastModified, forKey: .lastModified)
        try c.encode(contentLength, forKey: .contentLength)
        try c.encode(acceptRanges, forKey: .acceptRanges)
        try c.encode(sourceETag, forKey: .sourceETag)
    }
}

public struct OfflineResumeDecision: Codable, Equatable, Sendable {
    public var action: String
    public var offset: Int64
    public var totalBytes: Int64?
    public var validator: OfflineValidator?
    public var reason: String?
    public init(action: String, offset: Int64 = 0, totalBytes: Int64? = nil, validator: OfflineValidator? = nil, reason: String? = nil) {
        self.action = action
        self.offset = offset
        self.totalBytes = totalBytes
        self.validator = validator
        self.reason = reason
    }
    private enum CodingKeys: String, CodingKey { case action, offset, totalBytes, validator, reason }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        action = try c.decode(String.self, forKey: .action)
        offset = c.contains(.offset) ? try c.decode(Int64.self, forKey: .offset) : 0
        totalBytes = c.contains(.totalBytes) ? try c.decodeIfPresent(Int64.self, forKey: .totalBytes) : nil
        validator = c.contains(.validator) ? try c.decodeIfPresent(OfflineValidator.self, forKey: .validator) : nil
        reason = c.contains(.reason) ? try c.decodeIfPresent(String.self, forKey: .reason) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(action, forKey: .action)
        try c.encode(offset, forKey: .offset)
        try c.encode(totalBytes, forKey: .totalBytes)
        try c.encode(validator, forKey: .validator)
        try c.encode(reason, forKey: .reason)
    }
}

public struct OfflineBodyEnd: Codable, Equatable, Sendable {
    public var action: String
    public init(action: String) {
        self.action = action
    }
    private enum CodingKeys: String, CodingKey { case action }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        action = try c.decode(String.self, forKey: .action)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(action, forKey: .action)
    }
}

public struct OfflineVerifyInput: Codable, Equatable, Sendable {
    public var actualBytes: Int64
    public var expectedBytes: Int64?
    public var actualSha256: String
    public var expectedSha256: String?
    public var sourceETagBefore: String?
    public var sourceETagNow: String?
    public var expectedRuntimeTicks: Int64?
    public var measuredTicks: Int64?
    public init(actualBytes: Int64, expectedBytes: Int64?, actualSha256: String, expectedSha256: String? = nil, sourceETagBefore: String? = nil, sourceETagNow: String? = nil, expectedRuntimeTicks: Int64? = nil, measuredTicks: Int64? = nil) {
        self.actualBytes = actualBytes
        self.expectedBytes = expectedBytes
        self.actualSha256 = actualSha256
        self.expectedSha256 = expectedSha256
        self.sourceETagBefore = sourceETagBefore
        self.sourceETagNow = sourceETagNow
        self.expectedRuntimeTicks = expectedRuntimeTicks
        self.measuredTicks = measuredTicks
    }
    private enum CodingKeys: String, CodingKey { case actualBytes, expectedBytes, actualSha256, expectedSha256, sourceETagBefore, sourceETagNow, expectedRuntimeTicks, measuredTicks }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        actualBytes = try c.decode(Int64.self, forKey: .actualBytes)
        expectedBytes = try c.decode(Int64?.self, forKey: .expectedBytes)
        actualSha256 = try c.decode(String.self, forKey: .actualSha256)
        expectedSha256 = c.contains(.expectedSha256) ? try c.decodeIfPresent(String.self, forKey: .expectedSha256) : nil
        sourceETagBefore = c.contains(.sourceETagBefore) ? try c.decodeIfPresent(String.self, forKey: .sourceETagBefore) : nil
        sourceETagNow = c.contains(.sourceETagNow) ? try c.decodeIfPresent(String.self, forKey: .sourceETagNow) : nil
        expectedRuntimeTicks = c.contains(.expectedRuntimeTicks) ? try c.decodeIfPresent(Int64.self, forKey: .expectedRuntimeTicks) : nil
        measuredTicks = c.contains(.measuredTicks) ? try c.decodeIfPresent(Int64.self, forKey: .measuredTicks) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(actualBytes, forKey: .actualBytes)
        try c.encode(expectedBytes, forKey: .expectedBytes)
        try c.encode(actualSha256, forKey: .actualSha256)
        try c.encode(expectedSha256, forKey: .expectedSha256)
        try c.encode(sourceETagBefore, forKey: .sourceETagBefore)
        try c.encode(sourceETagNow, forKey: .sourceETagNow)
        try c.encode(expectedRuntimeTicks, forKey: .expectedRuntimeTicks)
        try c.encode(measuredTicks, forKey: .measuredTicks)
    }
}

public struct OfflineVerifyResult: Codable, Equatable, Sendable {
    public var ok: Bool
    public var reason: String?
    public init(ok: Bool, reason: String? = nil) {
        self.ok = ok
        self.reason = reason
    }
    private enum CodingKeys: String, CodingKey { case ok, reason }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decode(Bool.self, forKey: .ok)
        reason = c.contains(.reason) ? try c.decodeIfPresent(String.self, forKey: .reason) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(ok, forKey: .ok)
        try c.encode(reason, forKey: .reason)
    }
}

/// A relative path under the entry directory. Only verified resources may commit Ready.
public struct OfflineResource: Codable, Equatable, Sendable {
    public var id: String
    public var role: OfflineResourceRole
    public var path: String
    public var required: Bool
    public var serverStreamIndex: Int?
    public var bytes: Int64?
    public var sha256: String?
    public var complete: Bool
    public var validator: OfflineValidator?
    public var mimeType: String?
    public init(id: String, role: OfflineResourceRole, path: String, required: Bool = true, serverStreamIndex: Int? = nil, bytes: Int64? = nil, sha256: String? = nil, complete: Bool = false, validator: OfflineValidator? = nil, mimeType: String? = nil) {
        self.id = id
        self.role = role
        self.path = path
        self.required = required
        self.serverStreamIndex = serverStreamIndex
        self.bytes = bytes
        self.sha256 = sha256
        self.complete = complete
        self.validator = validator
        self.mimeType = mimeType
    }
    private enum CodingKeys: String, CodingKey { case id, role, path, required, serverStreamIndex, bytes, sha256, complete, validator, mimeType }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        role = try c.decode(OfflineResourceRole.self, forKey: .role)
        path = try c.decode(String.self, forKey: .path)
        required = c.contains(.required) ? try c.decode(Bool.self, forKey: .required) : true
        serverStreamIndex = c.contains(.serverStreamIndex) ? try c.decodeIfPresent(Int.self, forKey: .serverStreamIndex) : nil
        bytes = c.contains(.bytes) ? try c.decodeIfPresent(Int64.self, forKey: .bytes) : nil
        sha256 = c.contains(.sha256) ? try c.decodeIfPresent(String.self, forKey: .sha256) : nil
        complete = c.contains(.complete) ? try c.decode(Bool.self, forKey: .complete) : false
        validator = c.contains(.validator) ? try c.decodeIfPresent(OfflineValidator.self, forKey: .validator) : nil
        mimeType = c.contains(.mimeType) ? try c.decodeIfPresent(String.self, forKey: .mimeType) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(role, forKey: .role)
        try c.encode(path, forKey: .path)
        try c.encode(required, forKey: .required)
        try c.encode(serverStreamIndex, forKey: .serverStreamIndex)
        try c.encode(bytes, forKey: .bytes)
        try c.encode(sha256, forKey: .sha256)
        try c.encode(complete, forKey: .complete)
        try c.encode(validator, forKey: .validator)
        try c.encode(mimeType, forKey: .mimeType)
    }
}

/// A server track index. A nil resourceId denotes an embedded track.
public struct OfflineTrack: Codable, Equatable, Sendable {
    public var serverIndex: Int
    public var type: String
    public var label: String
    public var language: String?
    public var isDefault: Bool
    public var isForced: Bool
    public var codec: String?
    public var resourceId: String?
    public var available: Bool
    public init(serverIndex: Int, type: String, label: String, language: String? = nil, isDefault: Bool = false, isForced: Bool = false, codec: String? = nil, resourceId: String? = nil, available: Bool = true) {
        self.serverIndex = serverIndex
        self.type = type
        self.label = label
        self.language = language
        self.isDefault = isDefault
        self.isForced = isForced
        self.codec = codec
        self.resourceId = resourceId
        self.available = available
    }
    private enum CodingKeys: String, CodingKey { case serverIndex, type, label, language, isDefault, isForced, codec, resourceId, available }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverIndex = try c.decode(Int.self, forKey: .serverIndex)
        type = try c.decode(String.self, forKey: .type)
        label = try c.decode(String.self, forKey: .label)
        language = c.contains(.language) ? try c.decodeIfPresent(String.self, forKey: .language) : nil
        isDefault = c.contains(.isDefault) ? try c.decode(Bool.self, forKey: .isDefault) : false
        isForced = c.contains(.isForced) ? try c.decode(Bool.self, forKey: .isForced) : false
        codec = c.contains(.codec) ? try c.decodeIfPresent(String.self, forKey: .codec) : nil
        resourceId = c.contains(.resourceId) ? try c.decodeIfPresent(String.self, forKey: .resourceId) : nil
        available = c.contains(.available) ? try c.decode(Bool.self, forKey: .available) : true
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(serverIndex, forKey: .serverIndex)
        try c.encode(type, forKey: .type)
        try c.encode(label, forKey: .label)
        try c.encode(language, forKey: .language)
        try c.encode(isDefault, forKey: .isDefault)
        try c.encode(isForced, forKey: .isForced)
        try c.encode(codec, forKey: .codec)
        try c.encode(resourceId, forKey: .resourceId)
        try c.encode(available, forKey: .available)
    }
}

public struct OfflineLabels: Codable, Equatable, Sendable {
    public var title: String
    public var seriesName: String?
    public var seriesId: String?
    public var seasonId: String?
    public var seasonNumber: Int?
    public var episodeNumber: Int?
    public var album: String?
    /// A track's album: its saved artwork is the album's, since a track rarely has an image of its own.
    public var albumId: String?
    public var artist: String?
    public var trackNumber: Int?
    public var discNumber: Int?
    public var year: Int?
    public var overview: String?
    public init(title: String, seriesName: String? = nil, seriesId: String? = nil, seasonId: String? = nil, seasonNumber: Int? = nil, episodeNumber: Int? = nil, album: String? = nil, albumId: String? = nil, artist: String? = nil, trackNumber: Int? = nil, discNumber: Int? = nil, year: Int? = nil, overview: String? = nil) {
        self.title = title
        self.seriesName = seriesName
        self.seriesId = seriesId
        self.seasonId = seasonId
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
        self.album = album
        self.albumId = albumId
        self.artist = artist
        self.trackNumber = trackNumber
        self.discNumber = discNumber
        self.year = year
        self.overview = overview
    }
    private enum CodingKeys: String, CodingKey { case title, seriesName, seriesId, seasonId, seasonNumber, episodeNumber, album, albumId, artist, trackNumber, discNumber, year, overview }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decode(String.self, forKey: .title)
        seriesName = c.contains(.seriesName) ? try c.decodeIfPresent(String.self, forKey: .seriesName) : nil
        seriesId = c.contains(.seriesId) ? try c.decodeIfPresent(String.self, forKey: .seriesId) : nil
        seasonId = c.contains(.seasonId) ? try c.decodeIfPresent(String.self, forKey: .seasonId) : nil
        seasonNumber = c.contains(.seasonNumber) ? try c.decodeIfPresent(Int.self, forKey: .seasonNumber) : nil
        episodeNumber = c.contains(.episodeNumber) ? try c.decodeIfPresent(Int.self, forKey: .episodeNumber) : nil
        album = c.contains(.album) ? try c.decodeIfPresent(String.self, forKey: .album) : nil
        albumId = c.contains(.albumId) ? try c.decodeIfPresent(String.self, forKey: .albumId) : nil
        artist = c.contains(.artist) ? try c.decodeIfPresent(String.self, forKey: .artist) : nil
        trackNumber = c.contains(.trackNumber) ? try c.decodeIfPresent(Int.self, forKey: .trackNumber) : nil
        discNumber = c.contains(.discNumber) ? try c.decodeIfPresent(Int.self, forKey: .discNumber) : nil
        year = c.contains(.year) ? try c.decodeIfPresent(Int.self, forKey: .year) : nil
        overview = c.contains(.overview) ? try c.decodeIfPresent(String.self, forKey: .overview) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(title, forKey: .title)
        try c.encode(seriesName, forKey: .seriesName)
        try c.encode(seriesId, forKey: .seriesId)
        try c.encode(seasonId, forKey: .seasonId)
        try c.encode(seasonNumber, forKey: .seasonNumber)
        try c.encode(episodeNumber, forKey: .episodeNumber)
        try c.encode(album, forKey: .album)
        try c.encode(albumId, forKey: .albumId)
        try c.encode(artist, forKey: .artist)
        try c.encode(trackNumber, forKey: .trackNumber)
        try c.encode(discNumber, forKey: .discNumber)
        try c.encode(year, forKey: .year)
        try c.encode(overview, forKey: .overview)
    }
}

public struct OfflineEntry: Codable, Equatable, Sendable {
    public var id: String
    public var itemId: String
    public var kind: OfflineMediaKind
    public var sourceId: String?
    public var sourceETag: String?
    public var container: String?
    public var preset: OfflinePreset
    public var runtimeTicks: Int64?
    public var labels: OfflineLabels
    public var resources: [OfflineResource]
    public var tracks: [OfflineTrack]
    public var job: OfflineJob
    public var createdSequence: Int64
    public var warning: String?
    public var pinned: Bool
    public init(id: String, itemId: String, kind: OfflineMediaKind, sourceId: String? = nil, sourceETag: String? = nil, container: String? = nil, preset: OfflinePreset, runtimeTicks: Int64? = nil, labels: OfflineLabels, resources: [OfflineResource] = [], tracks: [OfflineTrack] = [], job: OfflineJob = OfflineJob(), createdSequence: Int64 = 0, warning: String? = nil, pinned: Bool = true) {
        self.id = id
        self.itemId = itemId
        self.kind = kind
        self.sourceId = sourceId
        self.sourceETag = sourceETag
        self.container = container
        self.preset = preset
        self.runtimeTicks = runtimeTicks
        self.labels = labels
        self.resources = resources
        self.tracks = tracks
        self.job = job
        self.createdSequence = createdSequence
        self.warning = warning
        self.pinned = pinned
    }
    private enum CodingKeys: String, CodingKey { case id, itemId, kind, sourceId, sourceETag, container, preset, runtimeTicks, labels, resources, tracks, job, createdSequence, warning, pinned }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        itemId = try c.decode(String.self, forKey: .itemId)
        kind = try c.decode(OfflineMediaKind.self, forKey: .kind)
        sourceId = c.contains(.sourceId) ? try c.decodeIfPresent(String.self, forKey: .sourceId) : nil
        sourceETag = c.contains(.sourceETag) ? try c.decodeIfPresent(String.self, forKey: .sourceETag) : nil
        container = c.contains(.container) ? try c.decodeIfPresent(String.self, forKey: .container) : nil
        preset = try c.decode(OfflinePreset.self, forKey: .preset)
        runtimeTicks = c.contains(.runtimeTicks) ? try c.decodeIfPresent(Int64.self, forKey: .runtimeTicks) : nil
        labels = try c.decode(OfflineLabels.self, forKey: .labels)
        resources = c.contains(.resources) ? try c.decode([OfflineResource].self, forKey: .resources) : []
        tracks = c.contains(.tracks) ? try c.decode([OfflineTrack].self, forKey: .tracks) : []
        job = c.contains(.job) ? try c.decode(OfflineJob.self, forKey: .job) : OfflineJob()
        createdSequence = c.contains(.createdSequence) ? try c.decode(Int64.self, forKey: .createdSequence) : 0
        warning = c.contains(.warning) ? try c.decodeIfPresent(String.self, forKey: .warning) : nil
        pinned = c.contains(.pinned) ? try c.decode(Bool.self, forKey: .pinned) : true
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(itemId, forKey: .itemId)
        try c.encode(kind, forKey: .kind)
        try c.encode(sourceId, forKey: .sourceId)
        try c.encode(sourceETag, forKey: .sourceETag)
        try c.encode(container, forKey: .container)
        try c.encode(preset, forKey: .preset)
        try c.encode(runtimeTicks, forKey: .runtimeTicks)
        try c.encode(labels, forKey: .labels)
        try c.encode(resources, forKey: .resources)
        try c.encode(tracks, forKey: .tracks)
        try c.encode(job, forKey: .job)
        try c.encode(createdSequence, forKey: .createdSequence)
        try c.encode(warning, forKey: .warning)
        try c.encode(pinned, forKey: .pinned)
    }
}

/// Account-scoped durable inventory. Disk writes, atomic replacement, and backup exclusion belong to the engine.
public struct OfflineManifest: Codable, Equatable, Sendable {
    public var schema: Int
    public var entries: [OfflineEntry]
    public var nextSequence: Int64
    public var budget: OfflineBudget
    public var wifiOnly: Bool
    public var autoCleanWatched: Bool
    public var pendingByteCleanup: [String]
    /// Albums and playlists downloaded whole (MUS-10); their tracks are ordinary entries. Older manifests have none.
    public var collections: [OfflineCollection]
    public init(schema: Int = OFFLINE_MANIFEST_SCHEMA, entries: [OfflineEntry] = [], nextSequence: Int64 = 1, budget: OfflineBudget = OfflineBudget(), wifiOnly: Bool = true, autoCleanWatched: Bool = false, pendingByteCleanup: [String] = [], collections: [OfflineCollection] = []) {
        self.schema = schema
        self.entries = entries
        self.nextSequence = nextSequence
        self.budget = budget
        self.wifiOnly = wifiOnly
        self.autoCleanWatched = autoCleanWatched
        self.pendingByteCleanup = pendingByteCleanup
        self.collections = collections
    }
    private enum CodingKeys: String, CodingKey { case schema, entries, nextSequence, budget, wifiOnly, autoCleanWatched, pendingByteCleanup, collections }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = c.contains(.schema) ? try c.decode(Int.self, forKey: .schema) : OFFLINE_MANIFEST_SCHEMA
        entries = c.contains(.entries) ? try c.decode([OfflineEntry].self, forKey: .entries) : []
        nextSequence = c.contains(.nextSequence) ? try c.decode(Int64.self, forKey: .nextSequence) : 1
        budget = c.contains(.budget) ? try c.decode(OfflineBudget.self, forKey: .budget) : OfflineBudget()
        wifiOnly = c.contains(.wifiOnly) ? try c.decode(Bool.self, forKey: .wifiOnly) : true
        autoCleanWatched = c.contains(.autoCleanWatched) ? try c.decode(Bool.self, forKey: .autoCleanWatched) : false
        pendingByteCleanup = c.contains(.pendingByteCleanup) ? try c.decode([String].self, forKey: .pendingByteCleanup) : []
        collections = c.contains(.collections) ? try c.decode([OfflineCollection].self, forKey: .collections) : []
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(schema, forKey: .schema)
        try c.encode(entries, forKey: .entries)
        try c.encode(nextSequence, forKey: .nextSequence)
        try c.encode(budget, forKey: .budget)
        try c.encode(wifiOnly, forKey: .wifiOnly)
        try c.encode(autoCleanWatched, forKey: .autoCleanWatched)
        try c.encode(pendingByteCleanup, forKey: .pendingByteCleanup)
        try c.encode(collections, forKey: .collections)
    }
}

public struct OfflineManifestResult: Codable, Equatable, Sendable {
    public var manifest: OfflineManifest
    public var ok: Bool
    public var reason: String?
    public var deletePaths: [String]
    public var changedEntries: [String]
    public init(manifest: OfflineManifest, ok: Bool = true, reason: String? = nil, deletePaths: [String] = [], changedEntries: [String] = []) {
        self.manifest = manifest
        self.ok = ok
        self.reason = reason
        self.deletePaths = deletePaths
        self.changedEntries = changedEntries
    }
    private enum CodingKeys: String, CodingKey { case manifest, ok, reason, deletePaths, changedEntries }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        manifest = try c.decode(OfflineManifest.self, forKey: .manifest)
        ok = c.contains(.ok) ? try c.decode(Bool.self, forKey: .ok) : true
        reason = c.contains(.reason) ? try c.decodeIfPresent(String.self, forKey: .reason) : nil
        deletePaths = c.contains(.deletePaths) ? try c.decode([String].self, forKey: .deletePaths) : []
        changedEntries = c.contains(.changedEntries) ? try c.decode([String].self, forKey: .changedEntries) : []
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(manifest, forKey: .manifest)
        try c.encode(ok, forKey: .ok)
        try c.encode(reason, forKey: .reason)
        try c.encode(deletePaths, forKey: .deletePaths)
        try c.encode(changedEntries, forKey: .changedEntries)
    }
}

public struct OfflineCleanPreview: Codable, Equatable, Sendable {
    public var entryIds: [String]
    public var titles: [String]
    public var bytes: Int64
    public init(entryIds: [String], titles: [String], bytes: Int64) {
        self.entryIds = entryIds
        self.titles = titles
        self.bytes = bytes
    }
    private enum CodingKeys: String, CodingKey { case entryIds, titles, bytes }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entryIds = try c.decode([String].self, forKey: .entryIds)
        titles = try c.decode([String].self, forKey: .titles)
        bytes = try c.decode(Int64.self, forKey: .bytes)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(entryIds, forKey: .entryIds)
        try c.encode(titles, forKey: .titles)
        try c.encode(bytes, forKey: .bytes)
    }
}

/// Last-known server progress. Codable preserves positionTicks as a canonical decimal JSON string.
public struct OfflineUserData: Codable, Equatable, Sendable {
    public var positionTicks: Int64
    public var played: Bool
    public var playCount: Int
    public var lastPlayedDate: String?
    public init(positionTicks: Int64 = 0, played: Bool = false, playCount: Int = 0, lastPlayedDate: String? = nil) {
        self.positionTicks = positionTicks
        self.played = played
        self.playCount = playCount
        self.lastPlayedDate = lastPlayedDate
    }
    private enum CodingKeys: String, CodingKey { case positionTicks, played, playCount, lastPlayedDate }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        positionTicks = c.contains(.positionTicks) ? try c.offlineDecimal(.positionTicks, optional: false)! : 0
        played = c.contains(.played) ? try c.decode(Bool.self, forKey: .played) : false
        playCount = c.contains(.playCount) ? try c.decode(Int.self, forKey: .playCount) : 0
        lastPlayedDate = c.contains(.lastPlayedDate) ? try c.decodeIfPresent(String.self, forKey: .lastPlayedDate) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(String(positionTicks), forKey: .positionTicks)
        try c.encode(played, forKey: .played)
        try c.encode(playCount, forKey: .playCount)
        try c.encode(lastPlayedDate, forKey: .lastPlayedDate)
    }
}

public struct OfflineUserDataWrite: Codable, Equatable, Sendable {
    public var positionTicks: Int64?
    public var played: Bool?
    public init(positionTicks: Int64? = nil, played: Bool? = nil) {
        self.positionTicks = positionTicks
        self.played = played
    }
    private enum CodingKeys: String, CodingKey { case positionTicks, played }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        positionTicks = c.contains(.positionTicks) ? try c.offlineDecimal(.positionTicks, optional: true) : nil
        played = c.contains(.played) ? try c.decodeIfPresent(Bool.self, forKey: .played) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(positionTicks.map(String.init), forKey: .positionTicks)
        try c.encode(played, forKey: .played)
    }
}

public struct OfflineConflict: Codable, Equatable, Sendable {
    public var serverPositionTicks: Int64
    public var serverPlayed: Bool
    public var localPositionTicks: Int64?
    public var localPlayed: Bool?
    public var fields: [String]
    public init(serverPositionTicks: Int64, serverPlayed: Bool, localPositionTicks: Int64?, localPlayed: Bool?, fields: [String]) {
        self.serverPositionTicks = serverPositionTicks
        self.serverPlayed = serverPlayed
        self.localPositionTicks = localPositionTicks
        self.localPlayed = localPlayed
        self.fields = fields
    }
    private enum CodingKeys: String, CodingKey { case serverPositionTicks, serverPlayed, localPositionTicks, localPlayed, fields }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverPositionTicks = try c.offlineDecimal(.serverPositionTicks, optional: false)!
        serverPlayed = try c.decode(Bool.self, forKey: .serverPlayed)
        guard c.contains(.localPositionTicks) else {
            throw DecodingError.keyNotFound(CodingKeys.localPositionTicks, .init(codingPath: c.codingPath, debugDescription: "Missing localPositionTicks"))
        }
        localPositionTicks = try c.offlineDecimal(.localPositionTicks, optional: true)
        localPlayed = try c.decode(Bool?.self, forKey: .localPlayed)
        fields = try c.decode([String].self, forKey: .fields)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(String(serverPositionTicks), forKey: .serverPositionTicks)
        try c.encode(serverPlayed, forKey: .serverPlayed)
        try c.encode(localPositionTicks.map(String.init), forKey: .localPositionTicks)
        try c.encode(localPlayed, forKey: .localPlayed)
        try c.encode(fields, forKey: .fields)
    }
}

/// Progress for one server, viewer, and item. `writing` preserves the in-flight payload when newer local progress arrives.
public struct OfflineOutboxEntry: Codable, Equatable, Sendable {
    public var server: String
    public var viewer: String
    public var itemId: String
    public var base: OfflineUserData
    public var sequence: Int64
    public var positionTicks: Int64?
    public var played: Bool?
    public var phase: String
    public var conflict: OfflineConflict?
    public var writing: OfflineUserDataWrite?
    public init(server: String, viewer: String, itemId: String, base: OfflineUserData, sequence: Int64 = 0, positionTicks: Int64? = nil, played: Bool? = nil, phase: String = "dirty", conflict: OfflineConflict? = nil, writing: OfflineUserDataWrite? = nil) {
        self.server = server
        self.viewer = viewer
        self.itemId = itemId
        self.base = base
        self.sequence = sequence
        self.positionTicks = positionTicks
        self.played = played
        self.phase = phase
        self.conflict = conflict
        self.writing = writing
    }
    private enum CodingKeys: String, CodingKey { case server, viewer, itemId, base, sequence, positionTicks, played, phase, conflict, writing }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        server = try c.decode(String.self, forKey: .server)
        viewer = try c.decode(String.self, forKey: .viewer)
        itemId = try c.decode(String.self, forKey: .itemId)
        base = try c.decode(OfflineUserData.self, forKey: .base)
        sequence = c.contains(.sequence) ? try c.offlineDecimal(.sequence, optional: false)! : 0
        positionTicks = c.contains(.positionTicks) ? try c.offlineDecimal(.positionTicks, optional: true) : nil
        played = c.contains(.played) ? try c.decodeIfPresent(Bool.self, forKey: .played) : nil
        phase = c.contains(.phase) ? try c.decode(String.self, forKey: .phase) : "dirty"
        conflict = c.contains(.conflict) ? try c.decodeIfPresent(OfflineConflict.self, forKey: .conflict) : nil
        writing = c.contains(.writing) ? try c.decodeIfPresent(OfflineUserDataWrite.self, forKey: .writing) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(server, forKey: .server)
        try c.encode(viewer, forKey: .viewer)
        try c.encode(itemId, forKey: .itemId)
        try c.encode(base, forKey: .base)
        try c.encode(String(sequence), forKey: .sequence)
        try c.encode(positionTicks.map(String.init), forKey: .positionTicks)
        try c.encode(played, forKey: .played)
        try c.encode(phase, forKey: .phase)
        try c.encode(conflict, forKey: .conflict)
        try c.encode(writing, forKey: .writing)
    }
}

public struct OfflineOutboxEvent: Codable, Equatable, Sendable {
    public var op: String
    public var server: String?
    public var viewer: String?
    public var itemId: String?
    public var positionTicks: Int64?
    public var played: Bool?
    public var sequence: Int64?
    public var current: OfflineUserData?
    public var base: OfflineUserData?
    public var status: Int?
    public init(op: String, server: String? = nil, viewer: String? = nil, itemId: String? = nil, positionTicks: Int64? = nil, played: Bool? = nil, sequence: Int64? = nil, current: OfflineUserData? = nil, base: OfflineUserData? = nil, status: Int? = nil) {
        self.op = op
        self.server = server
        self.viewer = viewer
        self.itemId = itemId
        self.positionTicks = positionTicks
        self.played = played
        self.sequence = sequence
        self.current = current
        self.base = base
        self.status = status
    }
    private enum CodingKeys: String, CodingKey { case op, server, viewer, itemId, positionTicks, played, sequence, current, base, status }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        op = try c.decode(String.self, forKey: .op)
        server = c.contains(.server) ? try c.decodeIfPresent(String.self, forKey: .server) : nil
        viewer = c.contains(.viewer) ? try c.decodeIfPresent(String.self, forKey: .viewer) : nil
        itemId = c.contains(.itemId) ? try c.decodeIfPresent(String.self, forKey: .itemId) : nil
        positionTicks = c.contains(.positionTicks) ? try c.decodeIfPresent(Int64.self, forKey: .positionTicks) : nil
        played = c.contains(.played) ? try c.decodeIfPresent(Bool.self, forKey: .played) : nil
        sequence = c.contains(.sequence) ? try c.decodeIfPresent(Int64.self, forKey: .sequence) : nil
        current = c.contains(.current) ? try c.decodeIfPresent(OfflineUserData.self, forKey: .current) : nil
        base = c.contains(.base) ? try c.decodeIfPresent(OfflineUserData.self, forKey: .base) : nil
        status = c.contains(.status) ? try c.decodeIfPresent(Int.self, forKey: .status) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(op, forKey: .op)
        try c.encode(server, forKey: .server)
        try c.encode(viewer, forKey: .viewer)
        try c.encode(itemId, forKey: .itemId)
        try c.encode(positionTicks, forKey: .positionTicks)
        try c.encode(played, forKey: .played)
        try c.encode(sequence, forKey: .sequence)
        try c.encode(current, forKey: .current)
        try c.encode(base, forKey: .base)
        try c.encode(status, forKey: .status)
    }
}

/// The replacement record and next sync effect. A nil entry removes the acknowledged record.
public struct OfflineOutboxResult: Codable, Equatable, Sendable {
    public var entry: OfflineOutboxEntry?
    public var effect: String
    public var write: OfflineUserDataWrite?
    public var ignored: String?
    public init(entry: OfflineOutboxEntry?, effect: String = "none", write: OfflineUserDataWrite? = nil, ignored: String? = nil) {
        self.entry = entry
        self.effect = effect
        self.write = write
        self.ignored = ignored
    }
    private enum CodingKeys: String, CodingKey { case entry, effect, write, ignored }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entry = try c.decode(OfflineOutboxEntry?.self, forKey: .entry)
        effect = c.contains(.effect) ? try c.decode(String.self, forKey: .effect) : "none"
        write = c.contains(.write) ? try c.decodeIfPresent(OfflineUserDataWrite.self, forKey: .write) : nil
        ignored = c.contains(.ignored) ? try c.decodeIfPresent(String.self, forKey: .ignored) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(entry, forKey: .entry)
        try c.encode(effect, forKey: .effect)
        try c.encode(write, forKey: .write)
        try c.encode(ignored, forKey: .ignored)
    }
}

public struct OfflinePolicy: Codable, Equatable, Sendable {
    public var enableDownloads: Bool
    public var enablePlayback: Bool
    public var videoTranscoding: Bool
    public var audioTranscoding: Bool
    public init(enableDownloads: Bool, enablePlayback: Bool, videoTranscoding: Bool, audioTranscoding: Bool) {
        self.enableDownloads = enableDownloads
        self.enablePlayback = enablePlayback
        self.videoTranscoding = videoTranscoding
        self.audioTranscoding = audioTranscoding
    }
    private enum CodingKeys: String, CodingKey { case enableDownloads, enablePlayback, videoTranscoding, audioTranscoding }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enableDownloads = try c.decode(Bool.self, forKey: .enableDownloads)
        enablePlayback = try c.decode(Bool.self, forKey: .enablePlayback)
        videoTranscoding = try c.decode(Bool.self, forKey: .videoTranscoding)
        audioTranscoding = try c.decode(Bool.self, forKey: .audioTranscoding)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(enableDownloads, forKey: .enableDownloads)
        try c.encode(enablePlayback, forKey: .enablePlayback)
        try c.encode(videoTranscoding, forKey: .videoTranscoding)
        try c.encode(audioTranscoding, forKey: .audioTranscoding)
    }
}

/// One item after container expansion, with server policy, recording status, and optional size evidence.
public struct OfflineCandidate: Codable, Equatable, Sendable {
    public var itemId: String
    public var type: String
    public var isInfinite: Bool
    public var sizeBytes: Int64?
    public var bitrate: Int?
    public var runtimeTicks: Int64?
    public var canDownload: Bool?
    public var isInProgress: Bool
    public var recordingStatus: String?
    public init(itemId: String, type: String, isInfinite: Bool = false, sizeBytes: Int64? = nil, bitrate: Int? = nil, runtimeTicks: Int64? = nil, canDownload: Bool? = nil, isInProgress: Bool = false, recordingStatus: String? = nil) {
        self.itemId = itemId
        self.type = type
        self.isInfinite = isInfinite
        self.sizeBytes = sizeBytes
        self.bitrate = bitrate
        self.runtimeTicks = runtimeTicks
        self.canDownload = canDownload
        self.isInProgress = isInProgress
        self.recordingStatus = recordingStatus
    }
    private enum CodingKeys: String, CodingKey { case itemId, type, isInfinite, sizeBytes, bitrate, runtimeTicks, canDownload, isInProgress, recordingStatus }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        itemId = try c.decode(String.self, forKey: .itemId)
        type = try c.decode(String.self, forKey: .type)
        isInfinite = c.contains(.isInfinite) ? try c.decode(Bool.self, forKey: .isInfinite) : false
        sizeBytes = c.contains(.sizeBytes) ? try c.decodeIfPresent(Int64.self, forKey: .sizeBytes) : nil
        bitrate = c.contains(.bitrate) ? try c.decodeIfPresent(Int.self, forKey: .bitrate) : nil
        runtimeTicks = c.contains(.runtimeTicks) ? try c.decodeIfPresent(Int64.self, forKey: .runtimeTicks) : nil
        canDownload = c.contains(.canDownload) ? try c.decodeIfPresent(Bool.self, forKey: .canDownload) : nil
        isInProgress = c.contains(.isInProgress) ? try c.decode(Bool.self, forKey: .isInProgress) : false
        recordingStatus = c.contains(.recordingStatus) ? try c.decodeIfPresent(String.self, forKey: .recordingStatus) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(itemId, forKey: .itemId)
        try c.encode(type, forKey: .type)
        try c.encode(isInfinite, forKey: .isInfinite)
        try c.encode(sizeBytes, forKey: .sizeBytes)
        try c.encode(bitrate, forKey: .bitrate)
        try c.encode(runtimeTicks, forKey: .runtimeTicks)
        try c.encode(canDownload, forKey: .canDownload)
        try c.encode(isInProgress, forKey: .isInProgress)
        try c.encode(recordingStatus, forKey: .recordingStatus)
    }
}

public struct OfflinePresetChoice: Codable, Equatable, Sendable {
    public var preset: OfflinePreset
    public var available: Bool
    public var reason: String?
    public var estimateBytes: Int64?
    public var exactSize: Bool
    public init(preset: OfflinePreset, available: Bool, reason: String? = nil, estimateBytes: Int64? = nil, exactSize: Bool = false) {
        self.preset = preset
        self.available = available
        self.reason = reason
        self.estimateBytes = estimateBytes
        self.exactSize = exactSize
    }
    private enum CodingKeys: String, CodingKey { case preset, available, reason, estimateBytes, exactSize }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        preset = try c.decode(OfflinePreset.self, forKey: .preset)
        available = try c.decode(Bool.self, forKey: .available)
        reason = c.contains(.reason) ? try c.decodeIfPresent(String.self, forKey: .reason) : nil
        estimateBytes = c.contains(.estimateBytes) ? try c.decodeIfPresent(Int64.self, forKey: .estimateBytes) : nil
        exactSize = c.contains(.exactSize) ? try c.decode(Bool.self, forKey: .exactSize) : false
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(preset, forKey: .preset)
        try c.encode(available, forKey: .available)
        try c.encode(reason, forKey: .reason)
        try c.encode(estimateBytes, forKey: .estimateBytes)
        try c.encode(exactSize, forKey: .exactSize)
    }
}

public struct OfflineSelection: Codable, Equatable, Sendable {
    public var itemIds: [String]
    public var skipped: [String]
    public var knownBytes: Int64
    public var estimated: Bool
    public var unknownCount: Int
    public init(itemIds: [String], skipped: [String], knownBytes: Int64, estimated: Bool, unknownCount: Int) {
        self.itemIds = itemIds
        self.skipped = skipped
        self.knownBytes = knownBytes
        self.estimated = estimated
        self.unknownCount = unknownCount
    }
    private enum CodingKeys: String, CodingKey { case itemIds, skipped, knownBytes, estimated, unknownCount }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        itemIds = try c.decode([String].self, forKey: .itemIds)
        skipped = try c.decode([String].self, forKey: .skipped)
        knownBytes = try c.decode(Int64.self, forKey: .knownBytes)
        estimated = try c.decode(Bool.self, forKey: .estimated)
        unknownCount = try c.decode(Int.self, forKey: .unknownCount)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(itemIds, forKey: .itemIds)
        try c.encode(skipped, forKey: .skipped)
        try c.encode(knownBytes, forKey: .knownBytes)
        try c.encode(estimated, forKey: .estimated)
        try c.encode(unknownCount, forKey: .unknownCount)
    }
}

/// An ordered, unencoded request description; the transport supplies the server URL and current credentials.
public struct OfflineRequest: Codable, Equatable, Sendable {
    public var path: String
    public var query: [OfflineQueryParameter]
    public var method: String
    public init(path: String, query: [OfflineQueryParameter], method: String = "GET") {
        self.path = path
        self.query = query
        self.method = method
    }
    private enum CodingKeys: String, CodingKey { case path, query, method }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        path = try c.decode(String.self, forKey: .path)
        query = try c.decode([OfflineQueryParameter].self, forKey: .query)
        method = c.contains(.method) ? try c.decode(String.self, forKey: .method) : "GET"
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(path, forKey: .path)
        try c.encode(query, forKey: .query)
        try c.encode(method, forKey: .method)
    }
}

public struct Fmp4Box: Codable, Equatable, Sendable {
    public var type: String
    public var offset: Int64
    public var size: Int64
    public init(type: String, offset: Int64, size: Int64) {
        self.type = type
        self.offset = offset
        self.size = size
    }
    private enum CodingKeys: String, CodingKey { case type, offset, size }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try c.decode(String.self, forKey: .type)
        offset = try c.decode(Int64.self, forKey: .offset)
        size = try c.decode(Int64.self, forKey: .size)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(type, forKey: .type)
        try c.encode(offset, forKey: .offset)
        try c.encode(size, forKey: .size)
    }
}

/// Fragment timing and file extent. Parsing a moof sets offset to zero; the engine supplies its actual file extent.
public struct Fmp4Fragment: Codable, Equatable, Sendable {
    public var moofOffset: Int64
    public var totalSize: Int64
    public var durationInTimescale: Int64
    public var earliestTime: Int64
    public init(moofOffset: Int64, totalSize: Int64, durationInTimescale: Int64, earliestTime: Int64) {
        self.moofOffset = moofOffset
        self.totalSize = totalSize
        self.durationInTimescale = durationInTimescale
        self.earliestTime = earliestTime
    }
    private enum CodingKeys: String, CodingKey { case moofOffset, totalSize, durationInTimescale, earliestTime }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        moofOffset = try c.decode(Int64.self, forKey: .moofOffset)
        totalSize = try c.decode(Int64.self, forKey: .totalSize)
        durationInTimescale = try c.decode(Int64.self, forKey: .durationInTimescale)
        earliestTime = try c.decode(Int64.self, forKey: .earliestTime)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(moofOffset, forKey: .moofOffset)
        try c.encode(totalSize, forKey: .totalSize)
        try c.encode(durationInTimescale, forKey: .durationInTimescale)
        try c.encode(earliestTime, forKey: .earliestTime)
    }
}

public struct Fmp4Track: Codable, Equatable, Sendable {
    public var id: Int
    public var timescale: Int64
    public var trexDefaultDuration: Int64
    public init(id: Int, timescale: Int64, trexDefaultDuration: Int64) {
        self.id = id
        self.timescale = timescale
        self.trexDefaultDuration = trexDefaultDuration
    }
    private enum CodingKeys: String, CodingKey { case id, timescale, trexDefaultDuration }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        timescale = try c.decode(Int64.self, forKey: .timescale)
        trexDefaultDuration = try c.decode(Int64.self, forKey: .trexDefaultDuration)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(timescale, forKey: .timescale)
        try c.encode(trexDefaultDuration, forKey: .trexDefaultDuration)
    }
}

public struct OfflineReconnectFacts: Codable, Equatable, Sendable {
    public var server: String
    public var playbackAllowed: Bool?
    public var trackOnServer: Bool?
    public init(server: String = "unknown", playbackAllowed: Bool? = nil, trackOnServer: Bool? = nil) {
        self.server = server
        self.playbackAllowed = playbackAllowed
        self.trackOnServer = trackOnServer
    }
    private enum CodingKeys: String, CodingKey { case server, playbackAllowed, trackOnServer }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        server = c.contains(.server) ? try c.decode(String.self, forKey: .server) : "unknown"
        playbackAllowed = c.contains(.playbackAllowed) ? try c.decodeIfPresent(Bool.self, forKey: .playbackAllowed) : nil
        trackOnServer = c.contains(.trackOnServer) ? try c.decodeIfPresent(Bool.self, forKey: .trackOnServer) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(server, forKey: .server)
        try c.encode(playbackAllowed, forKey: .playbackAllowed)
        try c.encode(trackOnServer, forKey: .trackOnServer)
    }
}

public struct OfflineReconnectDecision: Codable, Equatable, Sendable {
    public var outcome: String
    public var metered: Bool
    public init(outcome: String, metered: Bool = false) {
        self.outcome = outcome
        self.metered = metered
    }
    private enum CodingKeys: String, CodingKey { case outcome, metered }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        outcome = try c.decode(String.self, forKey: .outcome)
        metered = c.contains(.metered) ? try c.decode(Bool.self, forKey: .metered) : false
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(outcome, forKey: .outcome)
        try c.encode(metered, forKey: .metered)
    }
}

public struct OfflineServerTrack: Codable, Equatable, Sendable {
    public var index: Int
    public var type: String
    public var language: String?
    public init(index: Int, type: String, language: String? = nil) {
        self.index = index
        self.type = type
        self.language = language
    }
    private enum CodingKeys: String, CodingKey { case index, type, language }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decode(Int.self, forKey: .index)
        type = try c.decode(String.self, forKey: .type)
        language = c.contains(.language) ? try c.decodeIfPresent(String.self, forKey: .language) : nil
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(index, forKey: .index)
        try c.encode(type, forKey: .type)
        try c.encode(language, forKey: .language)
    }
}

public struct OfflineServerSource: Codable, Equatable, Sendable {
    public var id: String?
    public var tracks: [OfflineServerTrack]
    public init(id: String? = nil, tracks: [OfflineServerTrack] = []) {
        self.id = id
        self.tracks = tracks
    }
    private enum CodingKeys: String, CodingKey { case id, tracks }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.contains(.id) ? try c.decodeIfPresent(String.self, forKey: .id) : nil
        tracks = c.contains(.tracks) ? try c.decode([OfflineServerTrack].self, forKey: .tracks) : []
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(tracks, forKey: .tracks)
    }
}

public struct OfflineHandoffSelection: Codable, Equatable, Sendable {
    public var audio: Int?
    public var subtitle: Int?
    public init(audio: Int?, subtitle: Int?) {
        self.audio = audio
        self.subtitle = subtitle
    }
    private enum CodingKeys: String, CodingKey { case audio, subtitle }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        audio = try c.decode(Int?.self, forKey: .audio)
        subtitle = try c.decode(Int?.self, forKey: .subtitle)
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(audio, forKey: .audio)
        try c.encode(subtitle, forKey: .subtitle)
    }
}

extension KeyedDecodingContainer {
    func offlineDecimal(_ key: Key, optional: Bool) throws -> Int64? {
        if optional, try decodeNil(forKey: key) { return nil }
        let text = try decode(String.self, forKey: key)
        guard let value = Int64(text), String(value) == text else {
            throw DecodingError.dataCorruptedError(forKey: key, in: self, debugDescription: "Expected canonical decimal Int64 string")
        }
        return value
    }
}

/// Query parameters retain the Kotlin wire order. URL encoding belongs to the transport.
public struct OfflineQueryParameter: Codable, Equatable, Sendable {
    public var name: String
    public var value: String
    public init(_ name: String, _ value: String) { self.name = name; self.value = value }
}
