// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

private final class StubProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    static var responseURL: URL?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try Self.handler!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: Self.responseURL ?? request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class ClientTests: XCTestCase {
    var client: JellyfinClient!
    let server = Server(url: URL(string: "https://example.test/base")!)
    var account: Account { Account(server: server, userId: "user", username: "Viewer", accessToken: "private", deviceID: "account-device") }
    override func setUp() {
        let c = URLSessionConfiguration.ephemeral; c.protocolClasses = [StubProtocol.self]
        client = JellyfinClient(deviceID: "install-device", session: URLSession(configuration: c))
    }
    override func tearDown() { StubProtocol.handler = nil; StubProtocol.responseURL = nil }
    func json(_ text: String) -> Data { Data(text.utf8) }
    func body(_ request: URLRequest) throws -> [String:Any] {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var b = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let n = stream.read(&b, maxLength: b.count); if n <= 0 { break }; data.append(b, count: n) }
        }
        return try JSONSerialization.jsonObject(with: data) as! [String:Any]
    }
    func testReportAttemptRecordsActualStatusAndFixedFailureCategories() async throws {
        let body: [String:Any] = ["ItemId":"item", "PositionTicks": 123_450_000]
        for code in [204, 503, 401] {
            StubProtocol.handler = { request in
                XCTAssertEqual(request.url?.path, "/base/Sessions/Playing/Progress")
                XCTAssertEqual(try self.body(request)["PositionTicks"] as? Int, 123_450_000)
                return (code, Data())
            }
            let result = await client.sendReportAttempt(account: account, kind: "progress", body: body)
            XCTAssertEqual(result.status, code)
            XCTAssertEqual(result.failure, code == 204 ? "none" : "http")
            XCTAssertEqual(result.succeeded, code == 204)
            XCTAssertGreaterThanOrEqual(result.elapsedMs, 0)
        }
        for (code, category) in [(URLError.timedOut,"timeout"), (.cancelled,"cancelled"), (.notConnectedToInternet,"network")] {
            StubProtocol.handler = { _ in throw URLError(code) }
            let result = await client.sendReportAttempt(account: account, kind: "stop", body: body)
            XCTAssertNil(result.status); XCTAssertEqual(result.failure, category)
        }
        StubProtocol.responseURL = URL(string: "https://foreign.example.test/response")!
        StubProtocol.handler = { _ in (204, Data()) }
        let unsafe = await client.sendReportAttempt(account: account, kind: "start", body: body)
        XCTAssertEqual(unsafe.status, 204); XCTAssertEqual(unsafe.failure, "invalidresponse")
    }

    func testArtworkAuthenticatesOnlySameOriginAndRejectsForeignResponses() async throws {
        var calls = 0
        let bytes = Data([0xFF, 0xD8, 0xFF, 0xD9])
        StubProtocol.handler = { request in
            calls += 1
            XCTAssertEqual(request.url?.host, "example.test")
            XCTAssertFalse(request.url!.absoluteString.contains("legacy-token"))
            let authorization = request.value(forHTTPHeaderField: "Authorization")!
            XCTAssertTrue(authorization.contains("account-device"))
            XCTAssertFalse(authorization.contains("install-device"))
            return (200, bytes)
        }
        do {
            _ = try await client.imageData(account: account, url: URL(string: "https://foreign.test/image.jpg")!)
            XCTFail("External image URL must fail before any authenticated request")
        } catch { XCTAssertEqual(error as? ClientError, .unsafeStreamURL) }
        XCTAssertEqual(calls, 0)
        let image = URL(string: "https://example.test/base/Items/i/Images/Primary?ApiKey=legacy-token&tag=one")!
        let result = try await client.imageData(account: account, url: image)
        XCTAssertEqual(result, bytes)
        XCTAssertEqual(calls, 1)
        // Production OriginRedirects rejects a foreign redirect before following it. This injected
        // session additionally verifies the final-response defense, independent of that delegate.
        StubProtocol.responseURL = URL(string: "https://foreign.test/image.jpg")!
        do {
            _ = try await client.imageData(account: account, url: image)
            XCTFail("A foreign final response must not be accepted")
        } catch { XCTAssertEqual(error as? ClientError, .unsafeStreamURL) }
        XCTAssertEqual(calls, 2)
    }
    // Ports AccountDeviceRequestsTest / DetailResumeHttpTest: account identity and list UserData survive process recreation.
    func testDetailUsesListUserDataAndAccountBoundIdentity() async throws {
        var paths: [String] = []
        StubProtocol.handler = { request in
            paths.append(request.url!.path)
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")!.contains("account-device"))
            XCTAssertFalse(request.value(forHTTPHeaderField: "Authorization")!.contains("install-device"))
            if request.url!.path.hasSuffix("/Items/i") { return (200, self.json(#"{"Id":"i","Name":"Movie","Type":"Movie","Overview":"Keep detail","UserData":{"PlaybackPositionTicks":0}}"#)) }
            XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.first(where: { $0.name == "ids" })!.value, "i")
            return (200, self.json(#"{"Items":[{"Id":"i","UserData":{"PlaybackPositionTicks":700000000}}]}"#))
        }
        let item = try await client.item(account: account, id: "i")
        XCTAssertEqual(item.userData.playbackPositionTicks, 700_000_000)
        XCTAssertEqual(item.overview, "Keep detail"); XCTAssertEqual(paths, ["/base/Items/i", "/base/Items"])
    }
    // Ports PlaybackClientTest: actual POST wire JSON negotiates a truthful Apple profile and selected tracks.
    func testPlaybackNegotiatesHLSAndReportsResumeWithAccountIdentity() async throws {
        var reports: [String:[String:Any]] = [:]
        StubProtocol.handler = { request in
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")!.contains("account-device"))
            let body = try self.body(request)
            if request.url!.path.hasSuffix("PlaybackInfo") {
                XCTAssertEqual(body["StartTimeTicks"] as? Int, 300_000_000)
                XCTAssertEqual(body["AudioStreamIndex"] as? Int, 2); XCTAssertEqual(body["SubtitleStreamIndex"] as? Int, 3)
                XCTAssertEqual(body["EnableDirectPlay"] as? Bool, false); XCTAssertEqual(body["EnableDirectStream"] as? Bool, false)
                let profile = body["DeviceProfile"] as! [String:Any]
                let direct = profile["DirectPlayProfiles"] as! [[String:Any]]
                XCTAssertEqual(direct[0]["VideoCodec"] as? String, "h264")
                XCTAssertFalse((direct[0]["Container"] as? String ?? "").contains("mkv"))
                let hls = profile["TranscodingProfiles"] as! [[String:Any]]
                XCTAssertEqual(hls[0]["Protocol"] as? String, "hls")
                return (200, self.json(#"{"PlaySessionId":"play-session","MediaSources":[{"Id":"src","TranscodingUrl":"/videos/i/master.m3u8?StartTimeTicks=300000000&ApiKey=private"}]}"#))
            }
            reports[request.url!.path] = body
            return (204, Data())
        }
        let decision = try await client.prepare(account: account, itemID: "i", startTicks: 300_000_000, audioIndex: 2, subtitleIndex: 3)
        try await client.reportStart(account: account, decision: decision, positionTicks: decision.startTicks)
        try await client.reportProgress(account: account, decision: decision, positionTicks: decision.serverPositionTicks(playerSeconds: 42), paused: true)
        try await client.reportStopped(account: account, decision: decision, positionTicks: decision.serverPositionTicks(playerSeconds: 43))
        XCTAssertEqual(reports["/base/Sessions/Playing/Progress"]?["PositionTicks"] as? Int, 420_000_000)
        XCTAssertEqual(reports["/base/Sessions/Playing/Progress"]?["IsPaused"] as? Bool, true)
        XCTAssertEqual(reports["/base/Sessions/Playing/Stopped"]?["PlaySessionId"] as? String, "play-session")
        XCTAssertEqual(reports["/base/Sessions/Playing/Stopped"]?["PositionTicks"] as? Int, 430_000_000)
    }
    func testAudioTrackHLSKeepsVideoCopyAndAppliesUserBitrate() async throws {
        StubProtocol.handler = { request in
            let body = try self.body(request)
            XCTAssertEqual(body["EnableDirectPlay"] as? Bool, false)
            XCTAssertEqual(body["AllowVideoStreamCopy"] as? Bool, true, "Audio changes must not universally force video encode")
            XCTAssertEqual(body["AllowAudioStreamCopy"] as? Bool, true)
            XCTAssertEqual(body["MaxStreamingBitrate"] as? Int, 4_000_000)
            XCTAssertEqual((body["DeviceProfile"] as? [String:Any])?["MaxStreamingBitrate"] as? Int, 4_000_000)
            XCTAssertEqual(body["MediaSourceId"] as? String, "source")
            return (200, self.json(#"{"MediaSources":[{"Id":"source","TranscodingUrl":"/Videos/movie/master.m3u8?VideoCodec=copy&AudioCodec=aac"}],"PlaySessionId":"bound"}"#))
        }
        let decision = try await client.prepare(account: account, itemID: "movie", audioIndex: 2, subtitleIndex: -1, mediaSourceID: "source", preferences: PlaybackPreferences(bitrateMbps: 4), forceHLS: true)
        XCTAssertEqual(decision.method, .transcode)
        XCTAssertEqual(decision.playSessionId, "bound")
        XCTAssertTrue(decision.url.absoluteString.contains("VideoCodec=copy"))
    }
    func testFailedResumeShelfDoesNotHideLibrariesNextUpOrLatest() async throws {
        StubProtocol.handler = { request in
            switch request.url!.lastPathComponent {
            case "UserViews": return (200, self.json(#"{"Items":[{"Id":"library","Name":"Movies","CollectionType":"movies"}]}"#))
            case "Resume": return (503, Data())
            case "NextUp": return (200, self.json(#"{"Items":[{"Id":"episode","Name":"Episode"}]}"#))
            case "Latest": return (200, self.json(#"[{"Id":"movie","Name":"Movie"}]"#))
            default: XCTFail("Unexpected endpoint"); return (500, Data())
            }
        }
        let content = try await client.homeContent(account: account)
        XCTAssertEqual(content.rows.map(\.id), ["nextup", "libraries", "latest:library"])
        XCTAssertEqual(content.warnings.count, 1); XCTAssertTrue(content.warnings[0].hasPrefix("Continue watching:"))
    }
    func testStopEncodingOnlyTouchesThisAccountsDeviceAndPlaySession() async throws {
        let decision = try PlayDecisions.decide(account: account, itemID: "i", response: PlaybackInfo(mediaSources: [MediaSource(transcodingUrl: "/videos/i/master.m3u8")], playSessionId: "ours"))
        var called = false
        StubProtocol.handler = { request in
            called = true
            XCTAssertEqual(request.httpMethod, "DELETE"); XCTAssertEqual(request.url!.path, "/base/Videos/ActiveEncodings")
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            XCTAssertEqual(query.first(where: { $0.name == "deviceId" })?.value, "account-device")
            XCTAssertEqual(query.first(where: { $0.name == "playSessionId" })?.value, "ours")
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")!.contains("account-device"))
            return (204, Data())
        }
        try await client.stopEncoding(account: account, decision: decision)
        XCTAssertTrue(called)
    }
    // Replays the observed Jellyfin 12 negotiation rule: unbound PlaybackInfo uses the
    // default external subrip track despite SubtitleStreamIndex=-1. Binding MediaSourceId
    // makes Off and explicit tracks authoritative. Removing the binding returns HLS and fails.
    func testExplicitSubtitleChoiceBindsMediaSourceBeforeNegotiation() async throws {
        var sourceBindings: [String?] = []
        StubProtocol.handler = { request in
            let body = try self.body(request)
            let binding = body["MediaSourceId"] as? String
            sourceBindings.append(binding)
            let selected = binding == "mp4-timed-text" ? (body["SubtitleStreamIndex"] as? Int ?? 0) : 0
            let direct = selected == -1 && (body["EnableDirectPlay"] as? Bool == true)
            if (body["SubtitleStreamIndex"] as? Int ?? -1) >= 0 {
                XCTAssertEqual(body["EnableDirectPlay"] as? Bool, false)
                XCTAssertEqual(body["AlwaysBurnInSubtitleWhenTranscoding"] as? Bool, true)
            }
            var source: [String:Any] = ["Id":"mp4-timed-text", "Container":"mov", "SupportsDirectPlay":direct,
                "DefaultSubtitleStreamIndex":selected,
                "MediaStreams":[["Type":"Subtitle", "Index":0, "Codec":"subrip", "IsExternal":true], ["Type":"Subtitle", "Index":1, "Codec":"mov_text"], ["Type":"Video", "Index":2, "Codec":"h264"], ["Type":"Audio", "Index":3, "Codec":"aac"]]]
            if !direct { source["TranscodingUrl"] = "/videos/film/master.m3u8?VideoCodec=h264&SubtitleStreamIndex=\(selected)&SubtitleMethod=Encode&TranscodeReasons=SubtitleCodecNotSupported" }
            return (200, try JSONSerialization.data(withJSONObject: ["MediaSources":[source], "PlaySessionId":"timed-text-session"]))
        }
        let off = try await client.prepare(account: account, itemID: "film", subtitleIndex: -1)
        XCTAssertEqual(off.method, .directPlay)
        XCTAssertEqual(off.url.path, "/base/Videos/film/stream")
        XCTAssertEqual(off.subtitleIndex, -1)
        let selected = try await client.prepare(account: account, itemID: "film", subtitleIndex: 1)
        XCTAssertEqual(selected.method, .transcode)
        XCTAssertEqual(selected.subtitleIndex, 1)
        XCTAssertEqual(selected.source.defaultSubtitleStreamIndex, 1)
        XCTAssertEqual(sourceBindings, [nil, "mp4-timed-text", nil, "mp4-timed-text"])
    }
    func testExpiredSessionIsAnErrorRatherThanEmptyHome() async throws {
        StubProtocol.handler = { _ in (401, Data()) }
        do { _ = try await client.home(account: account); XCTFail("Expected expired credentials") }
        catch { XCTAssertEqual(error as? ClientError, .invalidCredentials) }
    }
    func testSameViewerDeviceRenewalApprovesCodeWithOldAccountIdentity() async throws {
        StubProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/base/QuickConnect/Authorize")
            XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems, [URLQueryItem(name: "code", value: "123456")])
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")!.contains("account-device"))
            return (200, self.json("true"))
        }
        try await client.authorizeQuickConnect(account: account, code: "123456")
    }
    func testPasswordAndQuickConnectUseStableInstallIdentityAndNeverURLCredentials() async throws {
        var calls: [String] = []
        StubProtocol.handler = { request in
            calls.append(request.url!.path)
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")!.contains("install-device"))
            XCTAssertFalse(request.url!.absoluteString.contains("password"))
            if request.url!.path.hasSuffix("AuthenticateByName") {
                let b = try self.body(request); XCTAssertEqual(b["Pw"] as? String, "password")
            }
            if request.url!.path.hasSuffix("Enabled") { return (200, self.json("true")) }
            if request.url!.lastPathComponent == "Initiate" || request.url!.lastPathComponent == "Connect" { return (200, self.json(#"{"Code":"123456","Secret":"ephemeral","Authenticated":true}"#)) }
            if request.url!.path.hasSuffix("AuthenticateWithQuickConnect") { XCTAssertEqual(try self.body(request)["Secret"] as? String, "ephemeral") }
            return (200, self.json(#"{"User":{"Id":"u","Name":"Viewer"},"AccessToken":"private"}"#))
        }
        let a = try await client.signIn(server: server, username: "Viewer", password: "password")
        XCTAssertEqual(a.deviceID, "install-device")
        let pending = try await client.initiateQuickConnect(server: server)
        let state = try await client.quickConnectStatus(server: server, secret: pending.secret)
        XCTAssertTrue(state.authenticated)
        let q = try await client.authenticateQuickConnect(server: server, secret: state.secret)
        XCTAssertEqual(q.deviceID, "install-device"); XCTAssertEqual(calls.count, 5)
    }
}
