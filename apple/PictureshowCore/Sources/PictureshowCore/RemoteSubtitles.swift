// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Observation

// SUB-DL (spec/features/subtitle-download.md): finding subtitles through the server's remote-subtitle API (Jellyfin
// `RemoteSearch/Subtitles`, backed by a subtitle provider plugin such as OpenSubtitles). The rules match Android's
// RemoteSubtitlePolicy; spec/vectors/subtitle-download.json freezes them.

public struct RemoteSubtitle: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var providerName: String?
    public var name: String?
    public var format: String?
    public var communityRating: Double?
    public var downloadCount: Int?
    public var isHashMatch: Bool?
    public var aiTranslated: Bool?
    public var machineTranslated: Bool?
    public var forced: Bool?
    public var hearingImpaired: Bool?
    public var language: String?
    enum CodingKeys: String, CodingKey {
        case id = "Id", providerName = "ProviderName", name = "Name", format = "Format", communityRating = "CommunityRating"
        case downloadCount = "DownloadCount", isHashMatch = "IsHashMatch", aiTranslated = "AiTranslated"
        case machineTranslated = "MachineTranslated", forced = "Forced", hearingImpaired = "HearingImpaired"
        case language = "ThreeLetterISOLanguageName"
    }
    public init(id: String, providerName: String? = nil, name: String? = nil, format: String? = nil, communityRating: Double? = nil,
                downloadCount: Int? = nil, isHashMatch: Bool? = nil, aiTranslated: Bool? = nil, machineTranslated: Bool? = nil,
                forced: Bool? = nil, hearingImpaired: Bool? = nil, language: String? = nil) {
        self.id = id; self.providerName = providerName; self.name = name; self.format = format; self.communityRating = communityRating
        self.downloadCount = downloadCount; self.isHashMatch = isHashMatch; self.aiTranslated = aiTranslated
        self.machineTranslated = machineTranslated; self.forced = forced; self.hearingImpaired = hearingImpaired; self.language = language
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        providerName = try c.decodeIfPresent(String.self, forKey: .providerName)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        format = try c.decodeIfPresent(String.self, forKey: .format)
        communityRating = try c.decodeIfPresent(Double.self, forKey: .communityRating)
        downloadCount = try c.decodeIfPresent(Int.self, forKey: .downloadCount)
        isHashMatch = try c.decodeIfPresent(Bool.self, forKey: .isHashMatch)
        aiTranslated = try c.decodeIfPresent(Bool.self, forKey: .aiTranslated)
        machineTranslated = try c.decodeIfPresent(Bool.self, forKey: .machineTranslated)
        forced = try c.decodeIfPresent(Bool.self, forKey: .forced)
        hearingImpaired = try c.decodeIfPresent(Bool.self, forKey: .hearingImpaired)
        language = try c.decodeIfPresent(String.self, forKey: .language)
    }
}

/// One entry of the server's `Localization/Cultures`, the languages its providers are asked in.
public struct SubtitleCulture: Codable, Equatable, Sendable, Hashable {
    public var displayName: String
    public var twoLetter: String?
    public var threeLetter: String?
    public var threeLetterNames: [String]
    enum CodingKeys: String, CodingKey {
        case displayName = "DisplayName", twoLetter = "TwoLetterISOLanguageName", threeLetter = "ThreeLetterISOLanguageName"
        case threeLetterNames = "ThreeLetterISOLanguageNames"
    }
    public init(displayName: String, twoLetter: String? = nil, threeLetter: String?, threeLetterNames: [String] = []) {
        self.displayName = displayName; self.twoLetter = twoLetter; self.threeLetter = threeLetter; self.threeLetterNames = threeLetterNames
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        twoLetter = try c.decodeIfPresent(String.self, forKey: .twoLetter)
        threeLetter = try c.decodeIfPresent(String.self, forKey: .threeLetter)
        threeLetterNames = try c.decodeIfPresent([String].self, forKey: .threeLetterNames) ?? []
    }
}

/// Whether this viewer can search at all, decided before any search so the panel can say why not.
public enum RemoteSubtitleAccess: String, Sendable {
    /// Search away.
    case allowed = "Allowed"
    /// The account lacks Jellyfin's "Allow subtitle management" permission; the server would answer 403.
    case notAllowed = "NotAllowed"
    /// No subtitle provider plugin is installed, so every search would come back empty.
    case noProvider = "NoProvider"
    /// A downloaded copy, Live TV or a missing item: nothing on the server to attach a file to.
    case unavailable = "Unavailable"
}

/// Why the panel shows no results, in a form each screen words for itself.
public enum RemoteSubtitleEmpty: String, Sendable {
    case none = "None", noMatches = "NoMatches", notAllowed = "NotAllowed", noProvider = "NoProvider", failed = "Failed", unreachable = "Unreachable"
}

/// The tracks to reopen with once the server lists the downloaded file. Indices are the server's, after the refresh.
public struct AdoptedSubtitle: Equatable, Sendable {
    public var subtitleIndex: Int
    public var audioIndex: Int
    public init(subtitleIndex: Int, audioIndex: Int) { self.subtitleIndex = subtitleIndex; self.audioIndex = audioIndex }
}

public enum RemoteSubtitlePolicy {
    /// Admins and accounts with subtitle management may search. A nil permission or fetcher list means it couldn't be
    /// read; then searching is how we find out, so it's allowed. Permission is checked before the provider.
    public static func access(playable: Bool, isAdministrator: Bool?, subtitleManagement: Bool?, subtitleFetchers: [String]?) -> RemoteSubtitleAccess {
        if !playable { return .unavailable }
        if isAdministrator != true && subtitleManagement == false { return .notAllowed }
        if let subtitleFetchers, subtitleFetchers.isEmpty { return .noProvider }
        return .allowed
    }

    /// What an HTTP status from search or download means for the panel (nil: the server wasn't reached).
    public static func failure(status: Int?) -> RemoteSubtitleEmpty {
        switch status {
        case nil: return .unreachable
        case 401?, 403?: return .notAllowed
        default: return .failed
        }
    }

    /// Best first: made for this exact file (hash match), then human translations, then most downloaded, then best
    /// rated. Ties keep the provider's order; blank and repeated ids are dropped.
    public static func order(_ results: [RemoteSubtitle]) -> [RemoteSubtitle] {
        var seen = Set<String>()
        let kept = results.filter { !$0.id.trimmingCharacters(in: .whitespaces).isEmpty && seen.insert($0.id).inserted }
        return kept.enumerated().sorted { a, b in
            let x = a.element, y = b.element
            let hashX = x.isHashMatch == true, hashY = y.isHashMatch == true
            if hashX != hashY { return hashX }
            let machineX = x.machineTranslated == true || x.aiTranslated == true, machineY = y.machineTranslated == true || y.aiTranslated == true
            if machineX != machineY { return !machineX }
            if (x.downloadCount ?? 0) != (y.downloadCount ?? 0) { return (x.downloadCount ?? 0) > (y.downloadCount ?? 0) }
            if (x.communityRating ?? 0) != (y.communityRating ?? 0) { return (x.communityRating ?? 0) > (y.communityRating ?? 0) }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// The facts a row shows under its name, as stable tokens each platform words for itself, in display order:
    /// `hash-match`, `hearing-impaired`, `forced`, `machine-translated`, `format:<FORMAT>`, `provider:<name>`, `downloads:<n>`.
    public static func details(_ result: RemoteSubtitle) -> [String] {
        var tokens: [String] = []
        if result.isHashMatch == true { tokens.append("hash-match") }
        if result.hearingImpaired == true { tokens.append("hearing-impaired") }
        if result.forced == true { tokens.append("forced") }
        if result.machineTranslated == true || result.aiTranslated == true { tokens.append("machine-translated") }
        if let format = result.format?.trimmingCharacters(in: .whitespaces), !format.isEmpty { tokens.append("format:\(format.uppercased())") }
        if let provider = result.providerName?.trimmingCharacters(in: .whitespaces), !provider.isEmpty { tokens.append("provider:\(provider)") }
        if let count = result.downloadCount, count > 0 { tokens.append("downloads:\(count)") }
        return tokens
    }

    /// A row's title: the release name when the provider gives one, otherwise the provider and format.
    public static func title(_ result: RemoteSubtitle) -> String {
        if let name = result.name?.trimmingCharacters(in: .whitespaces), !name.isEmpty { return name }
        let parts = [result.providerName?.trimmingCharacters(in: .whitespaces), result.format?.trimmingCharacters(in: .whitespaces).uppercased()]
            .compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? result.id : parts.joined(separator: " · ")
    }

    /// Any language code the server or device uses (2- or 3-letter, `en-US`, `fra`) mapped to the culture's 3-letter
    /// search code; an unknown 3-letter code passes through, anything else is nil.
    public static func searchCode(_ code: String?, cultures: [SubtitleCulture]) -> String? {
        guard let raw = code?.trimmingCharacters(in: .whitespaces).lowercased(),
              let wanted = raw.split(separator: "-", omittingEmptySubsequences: false).first.map(String.init)?
                .split(separator: "_", omittingEmptySubsequences: false).first.map(String.init),
              !wanted.isEmpty else { return nil }
        let culture = cultures.first { culture in
            culture.threeLetter?.lowercased() == wanted || culture.twoLetter?.lowercased() == wanted ||
                culture.threeLetterNames.contains { $0.lowercased() == wanted }
        }
        if let code = culture?.threeLetter?.lowercased() { return code }
        return wanted.count == 3 ? wanted : nil
    }

    /// The language a search starts in: the viewer's subtitle preference, else audio, else the device language, else
    /// English. Each goes through the server's cultures; one the server doesn't know is skipped.
    public static func initialLanguage(subtitlePreference: String?, audioPreference: String?, deviceLanguage: String?, cultures: [SubtitleCulture]) -> String {
        for candidate in [subtitlePreference, audioPreference, deviceLanguage, "eng"] {
            guard let code = searchCode(candidate, cultures: cultures) else { continue }
            if cultures.isEmpty || cultures.contains(where: { $0.threeLetter?.lowercased() == code }) { return code }
        }
        return "eng"
    }

    /// Widely used subtitle languages in Jellyfin's search codes, listed after the viewer's own so a remote reaches
    /// them without scrolling through some 500 cultures.
    public static let commonLanguages = ["eng", "spa", "fre", "ger", "por", "ita", "dut", "pol", "rus", "ara", "chi", "jpn", "kor", "tur", "swe", "heb"]

    /// The language list: the start language and the viewer's preferred ones, then `commonLanguages`, then every other
    /// culture by name. Each code appears once; cultures without a code or name are left out.
    public static func languageChoices(first: [String], cultures: [SubtitleCulture]) -> [SubtitleCulture] {
        var codes = Set<String>()
        let usable = cultures.filter { culture in
            guard let code = culture.threeLetter?.trimmingCharacters(in: .whitespaces).lowercased(), !code.isEmpty,
                  !culture.displayName.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
            return codes.insert(code).inserted
        }
        // Common codes go through the cultures like any preference: Jellyfin 12 searches "fra", not "fre".
        var headCodes = Set<String>()
        let head = (first + commonLanguages.compactMap { searchCode($0, cultures: cultures) }).compactMap { code in
            usable.first { $0.threeLetter!.lowercased() == code.lowercased() }
        }.filter { headCodes.insert($0.threeLetter!.lowercased()).inserted }
        let rest = usable.filter { !headCodes.contains($0.threeLetter!.lowercased()) }.sorted { $0.displayName.lowercased() < $1.displayName.lowercased() }
        return head + rest
    }

    /// After a download the server refreshes the item and lists the file as a new external stream. Jellyfin numbers
    /// external files before embedded streams and renumbers everything, so the embedded indices shift too. The new
    /// stream is an external subtitle in `after` that nothing in `before` accounts for, preferring `language` and then
    /// `format`; the selected audio keeps its identity. Nil while the refreshed list doesn't show a new subtitle yet.
    public static func adopt(before: [MediaStream], after: [MediaStream], selectedAudio: Int, language: String?, format: String?) -> AdoptedSubtitle? {
        let mapping = mapStreams(before: before, after: after)
        let accounted = Set(mapping.values)
        let added = after.filter { $0.type == "Subtitle" && $0.isExternal && !accounted.contains($0.index) }
        guard !added.isEmpty else { return nil }
        let wantedLanguage = language?.lowercased()
        let wantedFormat = format.map { subtitleCodec($0.lowercased()) }
        func rank(_ s: MediaStream) -> (Int, Int, Int) {
            (s.language?.lowercased() == wantedLanguage ? 1 : 0,
             wantedFormat != nil && subtitleCodec(s.codec?.lowercased()) == wantedFormat! ? 1 : 0,
             -s.index)
        }
        guard let chosen = added.max(by: { rank($0) < rank($1) }) else { return nil }
        let audio = selectedAudio < 0 ? selectedAudio : (mapping[selectedAudio] ?? -1)
        return AdoptedSubtitle(subtitleIndex: chosen.index, audioIndex: audio)
    }

    /// Old index to new index for every stream in `before` that is still listed: embedded streams by order within
    /// their type, external ones by what they are.
    public static func mapStreams(before: [MediaStream], after: [MediaStream]) -> [Int: Int] {
        var mapping: [Int: Int] = [:]
        var types: [String?] = []
        for stream in before + after where !types.contains(stream.type) { types.append(stream.type) }
        for type in types {
            let oldEmbedded = before.filter { $0.type == type && !$0.isExternal }.sorted { $0.index < $1.index }
            let newEmbedded = after.filter { $0.type == type && !$0.isExternal }.sorted { $0.index < $1.index }
            for (old, new) in zip(oldEmbedded, newEmbedded) { mapping[old.index] = new.index }
            var remaining = after.filter { $0.type == type && $0.isExternal }.sorted { $0.index < $1.index }
            for old in before.filter({ $0.type == type && $0.isExternal }).sorted(by: { $0.index < $1.index }) {
                guard let match = remaining.firstIndex(where: { signature($0) == signature(old) }) else { continue }
                mapping[old.index] = remaining.remove(at: match).index
            }
        }
        return mapping
    }

    private static func signature(_ s: MediaStream) -> [String?] {
        [s.type, subtitleCodec(s.codec?.lowercased()), s.language?.lowercased(), s.title, String(s.isForced), String(s.isHearingImpaired)]
    }

    private static func subtitleCodec(_ codec: String?) -> String? {
        switch codec {
        case "srt", "subrip": return "subrip"
        case "ssa", "ass": return "ass"
        case "vtt", "webvtt": return "webvtt"
        default: return codec
        }
    }
}

/// A server answer, or the HTTP status that refused it (nil: the server wasn't reached or answered nonsense).
public enum SubtitleCall<T: Sendable>: Sendable {
    case ok(T)
    case failed(Int?)
}

/// What the server says about this viewer before a search.
public struct RemoteSubtitleViewer: Equatable, Sendable {
    public var access: RemoteSubtitleAccess
    public var subtitleLanguage: String?
    public var audioLanguage: String?
}

/// SUB-DL wire calls. Search can take a while (the provider is asked live), so it gets the long timeout.
extension JellyfinClient {
    private func subtitleCall<T: Sendable>(_ work: () async throws -> T) async -> SubtitleCall<T> {
        do { return .ok(try await work()) }
        catch ClientError.invalidCredentials { return .failed(401) }
        catch ClientError.server(let status) { return .failed(status) }
        catch { return .failed(nil) }
    }

    /// SUB-DL-01: decide before searching. Reading permission and providers needs no admin rights. Also returns the
    /// viewer's server-side language preferences, which choose the first search language (SUB-DL-02).
    public func subtitleAccess(account: Account, itemType: String?, playable: Bool) async -> RemoteSubtitleViewer {
        guard playable else { return RemoteSubtitleViewer(access: .unavailable) }
        struct Policy: Decodable { let IsAdministrator: Bool?; let EnableSubtitleManagement: Bool? }
        struct Configuration: Decodable { let SubtitleLanguagePreference: String?; let AudioLanguagePreference: String? }
        struct Me: Decodable { let Policy: Policy?; let Configuration: Configuration? }
        struct Named: Decodable { let Name: String? }
        struct Options: Decodable { let SubtitleFetchers: [Named]? }
        let me: Me?
        if case let .ok(value) = await subtitleCall({ try JSONDecoder().decode(Me.self, from: await request(account: account, path: "Users/Me")) }) { me = value } else { me = nil }
        let contentType = itemType == "Episode" ? "tvshows" : "movies"
        let options: Options?
        if case let .ok(value) = await subtitleCall({
            try JSONDecoder().decode(Options.self, from: await request(account: account, path: "Libraries/AvailableOptions",
                                                                      query: [URLQueryItem(name: "libraryContentType", value: contentType)], includeUserID: false))
        }) { options = value } else { options = nil }
        let fetchers = options?.SubtitleFetchers.map { $0.map { $0.Name ?? "" } }
        func blankless(_ s: String?) -> String? { s.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } }
        return RemoteSubtitleViewer(
            access: RemoteSubtitlePolicy.access(playable: true, isAdministrator: me?.Policy?.IsAdministrator,
                                                subtitleManagement: me?.Policy?.EnableSubtitleManagement, subtitleFetchers: fetchers),
            subtitleLanguage: blankless(me?.Configuration?.SubtitleLanguagePreference),
            audioLanguage: blankless(me?.Configuration?.AudioLanguagePreference))
    }

    public func subtitleCultures(account: Account) async -> [SubtitleCulture] {
        if case let .ok(value) = await subtitleCall({ try JSONDecoder().decode([SubtitleCulture].self, from: await request(account: account, path: "Localization/Cultures", includeUserID: false)) }) { return value }
        return []
    }

    public func searchSubtitles(account: Account, itemID: String, language: String) async -> SubtitleCall<[RemoteSubtitle]> {
        let call = await subtitleCall {
            try JSONDecoder().decode([RemoteSubtitle].self, from: await request(account: account, path: "Items/\(ServerURLs.encode(itemID))/RemoteSearch/Subtitles/\(ServerURLs.encode(language))", includeUserID: false))
        }
        if case let .ok(results) = call { return .ok(RemoteSubtitlePolicy.order(results)) }
        return call
    }

    /// The server saves the file and queues a refresh of the item; the new stream shows up a moment later.
    public func downloadSubtitle(account: Account, itemID: String, subtitleID: String) async -> SubtitleCall<Bool> {
        await subtitleCall {
            _ = try await request(account: account, path: "Items/\(ServerURLs.encode(itemID))/RemoteSearch/Subtitles/\(ServerURLs.encode(subtitleID))", method: "POST", includeUserID: false)
            return true
        }
    }

    /// The item's streams for `mediaSourceID` as the server lists them now.
    public func subtitleStreams(account: Account, itemID: String, mediaSourceID: String?) async -> [MediaStream]? {
        struct Item: Decodable { let MediaSources: [MediaSource]?; let MediaStreams: [MediaStream]? }
        struct Page: Decodable { let Items: [Item]? }
        guard case let .ok(page) = await subtitleCall({
            try JSONDecoder().decode(Page.self, from: await request(account: account, path: "Items", query: [
                URLQueryItem(name: "ids", value: itemID), URLQueryItem(name: "fields", value: "MediaSources"),
                URLQueryItem(name: "enableImages", value: "false"), URLQueryItem(name: "enableUserData", value: "false")]))
        }), let item = page.Items?.first else { return nil }
        let sources = item.MediaSources ?? []
        let source = sources.first { $0.id == mediaSourceID } ?? sources.first
        return source?.mediaStreams ?? item.MediaStreams
    }
}

public enum RemoteSubtitlePhase: Sendable { case checking, blocked, searching, results, downloading, added, notListed }

/// Where the search runs: the item, and in the player the source and streams it's playing now.
public struct RemoteSubtitleTarget: Sendable {
    public var itemID: String
    public var itemType: String?
    public var playable: Bool
    public var mediaSourceID: String?
    public var streams: [MediaStream]
    public var selectedAudio: Int
    public init(itemID: String, itemType: String?, playable: Bool, mediaSourceID: String? = nil, streams: [MediaStream] = [], selectedAudio: Int = -1) {
        self.itemID = itemID; self.itemType = itemType; self.playable = playable; self.mediaSourceID = mediaSourceID
        self.streams = streams; self.selectedAudio = selectedAudio
    }
}

/// SUB-DL: one search panel's lifetime, shared by the player and the title page. It checks access first (SUB-DL-01),
/// searches in the viewer's language (SUB-DL-02), and after a download waits for the server to list the file so the
/// caller can switch to it (SUB-DL-04).
@MainActor @Observable
public final class RemoteSubtitleSearch {
    public private(set) var phase: RemoteSubtitlePhase = .checking
    public private(set) var access: RemoteSubtitleAccess = .allowed
    public private(set) var language = "eng"
    public private(set) var languages: [SubtitleCulture] = []
    public private(set) var results: [RemoteSubtitle] = []
    /// Why `results` is empty once a search finished, or why a download failed.
    public private(set) var empty: RemoteSubtitleEmpty = .none
    public private(set) var chosen: RemoteSubtitle?
    public private(set) var adopted: AdoptedSubtitle?
    /// Adopted stream list, so a player can reopen against what the server lists now.
    public private(set) var adoptedStreams: [MediaStream] = []

    public var languageName: String { languages.first { $0.threeLetter?.lowercased() == language }?.displayName ?? language }

    @ObservationIgnored private let client: JellyfinClient
    @ObservationIgnored private let account: Account
    @ObservationIgnored private let deviceLanguage: String?
    @ObservationIgnored private let pollInterval: Duration
    @ObservationIgnored private let listTimeout: Duration
    @ObservationIgnored private var target: RemoteSubtitleTarget?
    @ObservationIgnored private var job: Task<Void, Never>?

    public init(client: JellyfinClient, account: Account, deviceLanguage: String?, pollInterval: Duration = .seconds(1), listTimeout: Duration = .seconds(30)) {
        self.client = client; self.account = account; self.deviceLanguage = deviceLanguage
        self.pollInterval = pollInterval; self.listTimeout = listTimeout
    }

    public func open(_ target: RemoteSubtitleTarget) {
        self.target = target
        job?.cancel()
        phase = .checking; access = .allowed; results = []; empty = .none; chosen = nil; adopted = nil; adoptedStreams = []
        job = Task { [weak self] in
            guard let self else { return }
            let viewer = await client.subtitleAccess(account: account, itemType: target.itemType, playable: target.playable)
            if Task.isCancelled { return }
            guard viewer.access == .allowed else { phase = .blocked; access = viewer.access; return }
            let cultures = await client.subtitleCultures(account: account)
            if Task.isCancelled { return }
            let preferences = [viewer.subtitleLanguage, viewer.audioLanguage, deviceLanguage]
            let start = RemoteSubtitlePolicy.initialLanguage(subtitlePreference: preferences[0], audioPreference: preferences[1], deviceLanguage: preferences[2], cultures: cultures)
            let first = [start] + preferences.compactMap { RemoteSubtitlePolicy.searchCode($0, cultures: cultures) } + [start]
            language = start
            languages = RemoteSubtitlePolicy.languageChoices(first: first, cultures: cultures)
            await search(target, language: start)
        }
    }

    /// Search again in another language. Ignored while a download is running.
    public func choose(language code: String) {
        guard let target, phase != .downloading else { return }
        job?.cancel()
        job = Task { [weak self] in await self?.search(target, language: code.lowercased()) }
    }

    public func retry() {
        guard let target else { return }
        if phase == .blocked || phase == .checking { open(target) } else { choose(language: language) }
    }

    /// Download `result`. In the player `onAdopted` gets the refreshed indices to reopen with; on a title page there is
    /// no stream to switch, and the panel just confirms.
    public func download(_ result: RemoteSubtitle, onAdopted: @escaping @MainActor (AdoptedSubtitle, [MediaStream]) -> Void = { _, _ in }) {
        guard let target, phase != .downloading else { return }
        job?.cancel()
        phase = .downloading; chosen = result; empty = .none
        job = Task { [weak self] in
            guard let self else { return }
            // Before the download: the streams the server listed, which the refreshed list is compared against.
            var before = target.streams
            if before.isEmpty { before = await client.subtitleStreams(account: account, itemID: target.itemID, mediaSourceID: target.mediaSourceID) ?? [] }
            if case let .failed(status) = await client.downloadSubtitle(account: account, itemID: target.itemID, subtitleID: result.id) {
                if Task.isCancelled { return }
                phase = .results; empty = RemoteSubtitlePolicy.failure(status: status)
                return
            }
            let clock = ContinuousClock(), deadline = clock.now.advanced(by: listTimeout)
            while !Task.isCancelled {
                if let after = await client.subtitleStreams(account: account, itemID: target.itemID, mediaSourceID: target.mediaSourceID),
                   let found = RemoteSubtitlePolicy.adopt(before: before, after: after, selectedAudio: target.selectedAudio, language: result.language, format: result.format) {
                    if Task.isCancelled { return }
                    phase = .added; adopted = found; adoptedStreams = after
                    onAdopted(found, after)
                    return
                }
                if clock.now.advanced(by: pollInterval) > deadline { break }
                try? await Task.sleep(for: pollInterval)
            }
            if !Task.isCancelled { phase = .notListed }
        }
    }

    public func close() { job?.cancel(); job = nil }

    private func search(_ target: RemoteSubtitleTarget, language code: String) async {
        phase = .searching; language = code; results = []; empty = .none; chosen = nil
        let call = await client.searchSubtitles(account: account, itemID: target.itemID, language: code)
        if Task.isCancelled { return }
        switch call {
        case let .ok(found): phase = .results; results = found; empty = found.isEmpty ? .noMatches : .none
        case let .failed(status): phase = .results; empty = RemoteSubtitlePolicy.failure(status: status)
        }
    }
}
