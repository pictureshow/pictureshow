// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

/// Executes spec/vectors/subtitle-download.json against RemoteSubtitlePolicy (SUB-DL-01..04).
enum SubtitleDownloadVectors {
    static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) throws -> T {
        guard let value else { throw NSError(domain: "Missing subtitle-download input", code: 1) }
        return try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }
    static func execute(_ operation: String, input i: [String: Any]) throws -> Any {
        func optional<T: Decodable>(_ type: T.Type, _ key: String) throws -> T? { i[key] == nil || i[key] is NSNull ? nil : try decode(type, i[key]) }
        switch operation {
        case "access":
            return RemoteSubtitlePolicy.access(playable: try decode(Bool.self, i["playable"]), isAdministrator: try optional(Bool.self, "isAdministrator"),
                                               subtitleManagement: try optional(Bool.self, "subtitleManagement"), subtitleFetchers: try optional([String].self, "fetchers")).rawValue
        case "failure":
            return try decode([Int?].self, i["statuses"]).map { RemoteSubtitlePolicy.failure(status: $0).rawValue }
        case "initial-language":
            return RemoteSubtitlePolicy.initialLanguage(subtitlePreference: try optional(String.self, "subtitlePreference"), audioPreference: try optional(String.self, "audioPreference"),
                                                        deviceLanguage: try optional(String.self, "deviceLanguage"), cultures: try decode([SubtitleCulture].self, i["cultures"]))
        case "language-choices":
            return RemoteSubtitlePolicy.languageChoices(first: try decode([String].self, i["first"]), cultures: try decode([SubtitleCulture].self, i["cultures"])).map { $0.threeLetter ?? "" }
        case "order":
            return RemoteSubtitlePolicy.order(try decode([RemoteSubtitle].self, i["results"])).map(\.id)
        case "details":
            return RemoteSubtitlePolicy.details(try decode(RemoteSubtitle.self, i["result"]))
        case "title":
            return RemoteSubtitlePolicy.title(try decode(RemoteSubtitle.self, i["result"]))
        case "adopt":
            let adopted = RemoteSubtitlePolicy.adopt(before: try decode([MediaStream].self, i["before"]), after: try decode([MediaStream].self, i["after"]),
                                                     selectedAudio: try decode(Int.self, i["selectedAudio"]), language: try optional(String.self, "language"), format: try optional(String.self, "format"))
            return adopted.map { ["subtitleIndex": $0.subtitleIndex, "audioIndex": $0.audioIndex] as Any } ?? NSNull()
        default:
            throw NSError(domain: "Unknown subtitle-download operation \(operation)", code: 1)
        }
    }
}

/// A scripted server: each request is answered by the first route whose method and path prefix match.
private final class ScriptedServer: URLProtocol {
    struct Route { let method: String; let path: String; let answer: (URLRequest) -> (Int, String)? }
    static var routes: [Route] = []
    static var log: [String] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        Self.log.append("\(method) \(path)" + (request.url!.query.map { "?\($0)" } ?? ""))
        guard let answer = Self.routes.first(where: { $0.method == method && path.hasSuffix($0.path) })?.answer(request) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost)); return
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: answer.0, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(answer.1.utf8)); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class PlayerExtrasTests: XCTestCase {
    let server = Server(url: URL(string: "https://media.example.test/jf")!)
    var account: Account { Account(server: server, userId: "viewer", username: "Sam", accessToken: "token", deviceID: "phone") }
    var client: JellyfinClient {
        let c = URLSessionConfiguration.ephemeral; c.protocolClasses = [ScriptedServer.self]
        return JellyfinClient(deviceID: "phone", session: URLSession(configuration: c))
    }
    override func setUp() { ScriptedServer.routes = []; ScriptedServer.log = [] }
    func route(_ method: String, _ path: String, _ status: Int, _ body: String) { ScriptedServer.routes.append(.init(method: method, path: path) { _ in (status, body) }) }

    // MARK: AUTH-15

    func testShortCodeIsRefusedWithoutAnyRequest() async {
        let result = await client.approveQuickConnect(account: account, input: "abc12")
        XCTAssertEqual(result, .invalidInput)
        XCTAssertTrue(ScriptedServer.log.isEmpty)
    }

    func testApprovalChecksEnabledThenAuthorizesTheNormalizedCodeWithTheViewersToken() async {
        var authorization: String?
        route("GET", "/QuickConnect/Enabled", 200, "true")
        ScriptedServer.routes.append(.init(method: "POST", path: "/QuickConnect/Authorize") { request in authorization = request.value(forHTTPHeaderField: "Authorization"); return (200, "true") })
        let result = await client.approveQuickConnect(account: account, input: " ab3 9k1\n")
        XCTAssertEqual(result, .approved)
        XCTAssertEqual(ScriptedServer.log, ["GET /jf/QuickConnect/Enabled", "POST /jf/QuickConnect/Authorize?code=AB39K1"])
        XCTAssertTrue(authorization?.contains("Token=\"token\"") == true)
    }

    func testA401AfterEnabledTellsTurnedOffFromEnded() async {
        var enabledCalls = 0
        ScriptedServer.routes.append(.init(method: "GET", path: "/QuickConnect/Enabled") { _ in enabledCalls += 1; return (200, enabledCalls == 1 ? "true" : "false") })
        route("POST", "/QuickConnect/Authorize", 401, "")
        let turnedOff = await client.approveQuickConnect(account: account, input: "ABC123")
        XCTAssertEqual(turnedOff, .disabled)
        ScriptedServer.routes = []; route("GET", "/QuickConnect/Enabled", 200, "true"); route("POST", "/QuickConnect/Authorize", 401, "")
        let ended = await client.approveQuickConnect(account: account, input: "ABC123")
        XCTAssertEqual(ended, .signInRequired)
    }

    func testDisabledWrongAndOfflineOutcomes() async {
        route("GET", "/QuickConnect/Enabled", 200, "false")
        let disabled = await client.approveQuickConnect(account: account, input: "ABC123")
        XCTAssertEqual(disabled, .disabled)
        XCTAssertEqual(ScriptedServer.log.count, 1, "Turned off: no Authorize")
        ScriptedServer.routes = []; route("GET", "/QuickConnect/Enabled", 200, "true"); route("POST", "/QuickConnect/Authorize", 404, "")
        let wrong = await client.approveQuickConnect(account: account, input: "ABC123")
        XCTAssertEqual(wrong, .wrongOrExpired)
        ScriptedServer.routes = []
        let offline = await client.approveQuickConnect(account: account, input: "ABC123")
        XCTAssertEqual(offline, .offline)
    }

    // MARK: SUB-DL

    private let me = #"{"Policy":{"IsAdministrator":false,"EnableSubtitleManagement":true},"Configuration":{"SubtitleLanguagePreference":"fra"}}"#
    private let options = #"{"SubtitleFetchers":[{"Name":"Open Subtitles"}]}"#
    private let cultures = #"[{"DisplayName":"English","TwoLetterISOLanguageName":"en","ThreeLetterISOLanguageName":"eng"},{"DisplayName":"French","TwoLetterISOLanguageName":"fr","ThreeLetterISOLanguageName":"fra","ThreeLetterISOLanguageNames":["fra","fre"]}]"#

    private func settle(_ search: RemoteSubtitleSearch, until done: (RemoteSubtitleSearch) -> Bool) async throws {
        for _ in 0..<400 where !done(search) { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertTrue(done(search), "Search never settled: \(search.phase)")
    }

    func testPermissionIsCheckedBeforeProviderAndBlocksTheSearch() async throws {
        route("GET", "/Users/Me", 200, #"{"Policy":{"IsAdministrator":false,"EnableSubtitleManagement":false}}"#)
        route("GET", "/Libraries/AvailableOptions", 200, #"{"SubtitleFetchers":[]}"#)
        let search = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: "en")
        search.open(RemoteSubtitleTarget(itemID: "film", itemType: "Movie", playable: true))
        try await settle(search) { $0.phase == .blocked }
        XCTAssertEqual(search.access, .notAllowed)
        XCTAssertFalse(ScriptedServer.log.contains { $0.contains("RemoteSearch") }, "No search once access says no")
    }

    func testFirstSearchUsesTheViewersSubtitleLanguageThroughCulturesAndOrdersResults() async throws {
        route("GET", "/Users/Me", 200, me); route("GET", "/Libraries/AvailableOptions", 200, options); route("GET", "/Localization/Cultures", 200, cultures)
        route("GET", "/RemoteSearch/Subtitles/fra", 200, #"[{"Id":"a","DownloadCount":900,"MachineTranslated":true},{"Id":"b","DownloadCount":5},{"Id":"c","IsHashMatch":true}]"#)
        let search = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: "en")
        search.open(RemoteSubtitleTarget(itemID: "film", itemType: "Movie", playable: true))
        try await settle(search) { $0.phase == .results }
        XCTAssertEqual(search.language, "fra")
        XCTAssertEqual(search.languageName, "French")
        XCTAssertEqual(search.results.map(\.id), ["c", "b", "a"])
        XCTAssertEqual(search.languages.first?.threeLetter, "fra")
    }

    func testSearchStatusDecidesTheEmptyReason() async throws {
        route("GET", "/Users/Me", 200, me); route("GET", "/Libraries/AvailableOptions", 200, options); route("GET", "/Localization/Cultures", 200, cultures)
        route("GET", "/RemoteSearch/Subtitles/fra", 403, "")
        let search = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: nil)
        search.open(RemoteSubtitleTarget(itemID: "film", itemType: "Movie", playable: true))
        try await settle(search) { $0.phase == .results }
        XCTAssertEqual(search.empty, .notAllowed)
        route("GET", "/RemoteSearch/Subtitles/eng", 200, "[]")
        search.choose(language: "ENG")
        try await settle(search) { $0.phase == .results && $0.language == "eng" && $0.empty == .noMatches }
    }

    func testDownloadWaitsForTheServerToListTheFileAndMapsTheAudio() async throws {
        route("GET", "/Users/Me", 200, me); route("GET", "/Libraries/AvailableOptions", 200, options); route("GET", "/Localization/Cultures", 200, cultures)
        route("GET", "/RemoteSearch/Subtitles/fra", 200, #"[{"Id":"x","Format":"srt","ThreeLetterISOLanguageName":"fre"}]"#)
        var polls = 0
        ScriptedServer.routes.append(.init(method: "POST", path: "/RemoteSearch/Subtitles/x") { _ in (204, "") })
        ScriptedServer.routes.append(.init(method: "GET", path: "/Items") { _ in
            polls += 1
            // Not listed on the first poll; then external first, everything renumbered.
            let streams = polls < 2 ? #"[{"Type":"Video","Index":0},{"Type":"Audio","Index":1}]"#
                : #"[{"Type":"Subtitle","Index":0,"Language":"fre","Codec":"srt","IsExternal":true},{"Type":"Video","Index":1},{"Type":"Audio","Index":2}]"#
            return (200, #"{"Items":[{"MediaSources":[{"Id":"src","MediaStreams":"# + streams + "}]}]}")
        })
        let before = try JSONDecoder().decode([MediaStream].self, from: Data(#"[{"Type":"Video","Index":0},{"Type":"Audio","Index":1}]"#.utf8))
        let search = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: nil, pollInterval: .milliseconds(20), listTimeout: .seconds(2))
        search.open(RemoteSubtitleTarget(itemID: "film", itemType: "Movie", playable: true, mediaSourceID: "src", streams: before, selectedAudio: 1))
        try await settle(search) { $0.phase == .results }
        var handed: AdoptedSubtitle?
        search.download(search.results[0]) { adopted, _ in handed = adopted }
        try await settle(search) { $0.phase == .added }
        XCTAssertEqual(handed, AdoptedSubtitle(subtitleIndex: 0, audioIndex: 2))
        XCTAssertGreaterThanOrEqual(polls, 2)
    }

    func testNeverListedSaysSoAndAFailedDownloadKeepsTheResults() async throws {
        route("GET", "/Users/Me", 200, me); route("GET", "/Libraries/AvailableOptions", 200, options); route("GET", "/Localization/Cultures", 200, cultures)
        route("GET", "/RemoteSearch/Subtitles/fra", 200, #"[{"Id":"x"},{"Id":"y"}]"#)
        route("POST", "/RemoteSearch/Subtitles/x", 500, "")
        route("POST", "/RemoteSearch/Subtitles/y", 204, "")
        route("GET", "/Items", 200, #"{"Items":[{"MediaSources":[{"Id":"src","MediaStreams":[{"Type":"Video","Index":0}]}]}]}"#)
        let search = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: nil, pollInterval: .milliseconds(20), listTimeout: .milliseconds(150))
        search.open(RemoteSubtitleTarget(itemID: "film", itemType: "Movie", playable: true, mediaSourceID: "src"))
        try await settle(search) { $0.phase == .results }
        search.download(search.results[0])
        try await settle(search) { $0.phase == .results && $0.empty == .failed }
        XCTAssertEqual(search.results.map(\.id), ["x", "y"], "A failed download keeps the results")
        search.download(search.results[1])
        try await settle(search) { $0.phase == .notListed }
        XCTAssertNil(search.adopted)
    }

    func testOfflineOrLiveIsUnavailableWithoutAnyRequest() async throws {
        let search = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: nil)
        search.open(RemoteSubtitleTarget(itemID: "film", itemType: "Movie", playable: false))
        try await settle(search) { $0.phase == .blocked }
        XCTAssertEqual(search.access, .unavailable)
        XCTAssertTrue(ScriptedServer.log.isEmpty)
    }
}
