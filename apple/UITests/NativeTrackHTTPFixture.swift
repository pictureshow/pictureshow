// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Network
import XCTest

/// Finite ordinary-API setup for native menu tests. No focus or acceptance verdicts.
/// The supplied MP4 is served unchanged, not looped or represented as 45 decoders.
final class NativeTrackHTTPFixture {
    static let itemID = "native-track-fixture-title"
    static let sourceID = "native-track-fixture-source"
    static let selectedAudioIndex = 12
    static let selectedSubtitleIndex = 35
    static let trackCount = 45
    enum SetupError: Error {
        case mediaFileRequired, unreadableMedia, emptyMedia, invalidDuration
        case listenerNotReady, alreadyStarted, controlMissing(String)
    }
    struct RequestRecord {
        let method: String
        let path: String
        let audioIndex: Int?
        let subtitleIndex: Int?
        let positionTicks: Int64?
        let paused: Bool?
    }
    struct Snapshot {
        let requests: [RequestRecord]
        var playbackInfoCount: Int { requests.filter { $0.path.hasSuffix("/PlaybackInfo") }.count }
        var mediaRequestCount: Int { requests.filter { $0.path == "/Videos/" + NativeTrackHTTPFixture.itemID + "/stream" }.count }
        var subtitleRequestCount: Int { requests.filter { $0.path.hasPrefix("/fixture-subtitles/") }.count }
        var startCount: Int { requests.filter { $0.path == "/Sessions/Playing" }.count }
        var progressCount: Int { requests.filter { $0.path == "/Sessions/Playing/Progress" }.count }
        var stoppedCount: Int { requests.filter { $0.path == "/Sessions/Playing/Stopped" }.count }
        var menuWorkerRequests: [RequestRecord] { requests.filter { $0.path.hasPrefix("/fixture-menu-worker/") } }
        var encodingStopCount: Int { requests.filter { $0.method == "DELETE" && $0.path == "/Videos/ActiveEncodings" }.count }
    }
    private let media: Data
    private let durationTicks: Int64
    private let listener: NWListener
    private let queue = DispatchQueue(label: "pictureshow.native-track-fixture")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var records: [RequestRecord] = []
    private var started = false
    private var port: UInt16?
    private var menuWorkers: [Int: [NWConnection]] = [:]
    private var releasedMenuWorkers = Set<Int>()
    private var allMenuWorkersReleased = false
    private var playbackInfoHeld = false
    private var heldPlaybackInfo: [(NWConnection, Data)] = []
    private var sourceVersion = 0
    private var nextPlaybackFails = false
    func holdInitialPlaybackInfo() { lock.lock(); playbackInfoHeld = true; lock.unlock() }
    func useReplacementSource() { lock.lock(); sourceVersion += 1; lock.unlock() }
    func failNextPlaybackInfo() { lock.lock(); nextPlaybackFails = true; lock.unlock() }
    private func consumePlaybackFailure() -> Bool {
        lock.lock(); defer { lock.unlock() }
        let value = nextPlaybackFails; nextPlaybackFails = false; return value
    }
    func releasePlaybackInfo() {
        lock.lock(); playbackInfoHeld = false; let held = heldPlaybackInfo; heldPlaybackInfo = []; lock.unlock()
        for (connection, data) in held { respond(connection, contentType: "application/json", data: data) }
    }
    func holdMenuWorker(_ generation: Int) {
        lock.lock(); releasedMenuWorkers.remove(generation); lock.unlock()
    }
    func releaseMenuWorker(_ generation: Int) {
        lock.lock(); releasedMenuWorkers.insert(generation); let held = menuWorkers.removeValue(forKey: generation) ?? []; lock.unlock()
        for connection in held { respond(connection, data: Data()) }
    }
    func releaseAllMenuWorkers() {
        lock.lock(); allMenuWorkersReleased = true; let held = menuWorkers.values.flatMap { $0 }; menuWorkers = [:]; lock.unlock()
        for connection in held { respond(connection, data: Data()) }
    }
    var address: String? {
        lock.lock(); defer { lock.unlock() }
        return port.map { "http://127.0.0.1:\($0)" }
    }
    var snapshot: Snapshot {
        lock.lock(); defer { lock.unlock() }
        return Snapshot(requests: records)
    }
    /// PICTURESHOW_TEST_MEDIA_FILE belongs to the XCTest runner environment, not
    /// the app. Its path is neither returned in receipts nor logged by this helper.
    init(mediaFile: URL? = nil, durationSeconds: Double = 5) throws {
        let file: URL
        if let mediaFile { file = mediaFile }
        else if let value = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_MEDIA_FILE"], !value.isEmpty {
            file = URL(fileURLWithPath: value)
        } else { throw SetupError.mediaFileRequired }
        guard file.isFileURL, let bytes = try? Data(contentsOf: file, options: .mappedIfSafe) else { throw SetupError.unreadableMedia }
        guard !bytes.isEmpty else { throw SetupError.emptyMedia }
        guard durationSeconds.isFinite, durationSeconds > 0, durationSeconds <= 86_400 else { throw SetupError.invalidDuration }
        media = bytes; durationTicks = Int64(durationSeconds * 10_000_000)
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters, on: .any)
    }
    @discardableResult func start() throws -> String {
        lock.lock()
        guard !started else { lock.unlock(); throw SetupError.alreadyStarted }
        started = true; lock.unlock()
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if let self { self.lock.lock(); self.port = self.listener.port?.rawValue; self.lock.unlock() }
                ready.signal()
            case .failed, .cancelled: ready.signal()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.lock.lock(); self.connections.append(connection); self.lock.unlock()
            connection.start(queue: self.queue)
            self.receive(connection, buffered: Data())
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success, let address else { stop(); throw SetupError.listenerNotReady }
        return address
    }
    func stop() {
        listener.cancel()
        lock.lock(); let open = connections; connections = []; port = nil; lock.unlock()
        for connection in open { connection.cancel() }
    }
    deinit { stop() }

    @MainActor func configureLaunch(_ app: XCUIApplication) throws {
        guard let address else { throw SetupError.listenerNotReady }
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = address
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = "Native fixture viewer"
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = "synthetic-native-fixture-password"
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = Self.itemID
    }
    /// Reuses the owner's existing activate helper. Wait failures are setup errors,
    /// not test assertions or selected/focused-state judgments.
    @MainActor func launchSignedInDetail(_ app: XCUIApplication, activate: (XCUIElement) -> Void) throws {
        try configureLaunch(app); app.launch()
        let connect = app.buttons["connect"]
        guard connect.waitForExistence(timeout: 15) else { throw SetupError.controlMissing("connect on a fresh clone") }
        activate(connect)
        let signIn = app.buttons["signIn"]
        guard signIn.waitForExistence(timeout: 15) else { throw SetupError.controlMissing("signIn") }
        #if os(tvOS)
        if app.keyboards.firstMatch.exists { XCUIRemote.shared.press(.menu) }
        #else
        let done = app.buttons["dismissSignInKeyboard"]
        if done.exists && done.isHittable { done.tap() }
        if !signIn.isHittable { app.swipeUp() }
        #endif
        activate(signIn)
        #if os(tvOS)
        guard app.buttons["openSettings"].waitForExistence(timeout: 15) else { throw SetupError.controlMissing("authenticated navigation") }
        #else
        guard app.buttons.matching(identifier: "Settings").firstMatch.waitForExistence(timeout: 15)
            || app.tabBars.buttons["Settings"].waitForExistence(timeout: 15) else { throw SetupError.controlMissing("authenticated adaptive shell") }
        #endif
        app.terminate(); app.launch()
        guard app.buttons["play"].waitForExistence(timeout: 20) else { throw SetupError.controlMissing("play after persisted-account launch") }
    }
    private func receive(_ connection: NWConnection, buffered: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            var bytes = buffered; if let data { bytes.append(data) }
            guard bytes.count <= 1_048_576 else { self.respond(connection, status: "413 Payload Too Large"); return }
            guard let separator = bytes.range(of: Data("\r\n\r\n".utf8)) else {
                if complete || error != nil { connection.cancel() } else { self.receive(connection, buffered: bytes) }
                return
            }
            let lines = String(decoding: bytes[..<separator.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
            let line = (lines.first ?? "").split(separator: " ")
            guard line.count == 3, let url = URL(string: "http://fixture" + line[1]) else { self.respond(connection, status: "400 Bad Request"); return }
            var headers: [String: String] = [:]
            for line in lines.dropFirst() {
                let pair = line.split(separator: ":", maxSplits: 1)
                if pair.count == 2 { headers[String(pair[0]).lowercased()] = pair[1].trimmingCharacters(in: .whitespaces) }
            }
            guard headers["transfer-encoding"] == nil else { self.respond(connection, status: "400 Bad Request"); return }
            let length = headers["content-length"].flatMap(Int.init) ?? 0
            guard length >= 0, length <= 1_048_576 else { self.respond(connection, status: "413 Payload Too Large"); return }
            guard bytes.count - separator.upperBound >= length else {
                if complete || error != nil { connection.cancel() } else { self.receive(connection, buffered: bytes) }
                return
            }
            let payload = bytes.subdata(in: separator.upperBound..<(separator.upperBound + length))
            let body = (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any] ?? [:]
            self.route(connection, method: String(line[0]), path: url.path, headers: headers, body: body)
        }
    }
    private func route(_ connection: NWConnection, method: String, path: String, headers: [String: String], body: [String: Any]) {
        let record = RequestRecord(method: method, path: path, audioIndex: (body["AudioStreamIndex"] as? NSNumber)?.intValue,
                                   subtitleIndex: (body["SubtitleStreamIndex"] as? NSNumber)?.intValue,
                                   positionTicks: (body["PositionTicks"] as? NSNumber)?.int64Value,
                                   paused: body["IsPaused"] as? Bool)
        lock.lock(); records.append(record); lock.unlock()
        if path.hasPrefix("/fixture-menu-worker/") {
            let generation = path.split(separator: "/").dropFirst().first.flatMap { Int($0) } ?? -1
            lock.lock()
            let released = allMenuWorkersReleased || releasedMenuWorkers.contains(generation)
            if !released { menuWorkers[generation, default: []].append(connection) }
            lock.unlock()
            if released { respond(connection, data: Data()) }
            return
        }
        if path == "/Videos/" + Self.itemID + "/stream" {
            serveMedia(connection, method: method, range: headers["range"]); return
        }
        if path.hasPrefix("/fixture-subtitles/"), path.hasSuffix(".vtt") {
            let stream = Int(URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent)
            guard let stream, (23...45).contains(stream) else { respond(connection, status: "404 Not Found"); return }
            let cue = Data("WEBVTT\n\n00:00.000 --> 23:59:59.999\nSynthetic native menu fixture caption.\n".utf8)
            respond(connection, contentType: "text/vtt; charset=utf-8", data: cue, head: method == "HEAD"); return
        }
        let response: Any
        switch path {
        case "/System/Info/Public": response = ["ProductName": "Jellyfin Server", "Version": "12.0.0", "Id": "native-track-fixture", "ServerName": "Native track fixture"]
        case "/Users/AuthenticateByName": response = ["AccessToken": "synthetic-native-fixture-token", "User": ["Id": "native-fixture-viewer", "Name": "Native fixture viewer"]]
        case "/QuickConnect/Enabled": response = false
        case "/Users/Public": response = [] as [String]
        case "/Users/Me", "/Users/native-fixture-viewer": response = ["Id": "native-fixture-viewer", "Name": "Native fixture viewer", "Configuration": ["SubtitleMode": "Default", "SubtitleLanguagePreference": "eng"]]
        case "/Items/" + Self.itemID: response = movie()
        case "/Items/" + Self.itemID + "/PlaybackInfo":
            response = consumePlaybackFailure() ? ["ErrorCode": "NoCompatibleStream"] as [String: Any]
                : ["MediaSources": [source()], "PlaySessionId": "native-fixture-play-session"]
        case "/Items": response = ["Items": [movie()], "TotalRecordCount": 1]
        case "/Items/Latest": response = [movie()]
        case "/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped", "/Sessions/Logout", "/Videos/ActiveEncodings", "/Users/Configuration": response = [:] as [String: String]
        case "/UserPlayedItems/" + Self.itemID, "/UserFavoriteItems/" + Self.itemID: response = userData()
        default: response = ["Items": [], "TotalRecordCount": 0] as [String: Any]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: response, options: [.fragmentsAllowed]) else { respond(connection, status: "500 Internal Server Error"); return }
        if path.hasSuffix("/PlaybackInfo") {
            lock.lock(); let held = playbackInfoHeld
            if held { heldPlaybackInfo.append((connection, data)) }
            lock.unlock()
            if held { return }
        }
        respond(connection, contentType: "application/json", data: data, head: method == "HEAD")
    }
    private func userData() -> [String: Any] { ["Played": false, "IsFavorite": false, "PlaybackPositionTicks": 0] }
    private func movie() -> [String: Any] {
        ["Id": Self.itemID, "Name": "Synthetic native track menu film", "Type": "Movie", "ProductionYear": 2026,
         "Overview": "A synthetic source for native track-menu setup.", "RunTimeTicks": durationTicks,
         "UserData": userData(), "MediaSources": [source()], "Chapters": []]
    }
    private func source() -> [String: Any] {
        lock.lock(); let version = sourceVersion; lock.unlock()
        let suffix = version == 0 ? "" : " replacement \(version)"
        var streams: [[String: Any]] = [["Index": 0, "Type": "Video", "Codec": "h264", "Width": 320, "Height": 180]]
        streams += (1...22).map { index in
            ["Index": index, "Type": "Audio", "Codec": "aac", "Language": "eng", "Channels": 2,
             "Title": "Synthetic audio option \(index)\(suffix)", "DisplayTitle": "Synthetic audio option \(index)\(suffix)",
             "IsDefault": index == Self.selectedAudioIndex] as [String: Any]
        }
        streams += (23...45).map { index in
            ["Index": index, "Type": "Subtitle", "Codec": "vtt", "Language": "eng", "IsExternal": true,
             "IsTextSubtitleStream": true, "DeliveryMethod": "External", "DeliveryUrl": "/fixture-subtitles/\(index).vtt",
             "Title": "Synthetic caption option \(index)\(suffix)", "DisplayTitle": "Synthetic caption option \(index)\(suffix)",
             "IsDefault": index == Self.selectedSubtitleIndex] as [String: Any]
        }
        return ["Id": Self.sourceID, "Container": "mp4", "SupportsDirectPlay": true, "SupportsDirectStream": false,
                "SupportsTranscoding": false, "DefaultAudioStreamIndex": Self.selectedAudioIndex,
                "DefaultSubtitleStreamIndex": Self.selectedSubtitleIndex, "RunTimeTicks": durationTicks, "MediaStreams": streams]
    }
    private func serveMedia(_ connection: NWConnection, method: String, range: String?) {
        guard method == "GET" || method == "HEAD" else { respond(connection, status: "405 Method Not Allowed"); return }
        var lower = 0, upper = media.count - 1
        if let range {
            guard range.lowercased().hasPrefix("bytes="), !range.contains(",") else {
                respond(connection, status: "416 Range Not Satisfiable", headers: ["Content-Range": "bytes */\(media.count)"]); return
            }
            let bounds = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
            guard bounds.count == 2 else { respond(connection, status: "416 Range Not Satisfiable", headers: ["Content-Range": "bytes */\(media.count)"]); return }
            if bounds[0].isEmpty {
                guard let suffix = Int(bounds[1]), suffix > 0 else { respond(connection, status: "416 Range Not Satisfiable", headers: ["Content-Range": "bytes */\(media.count)"]); return }
                lower = media.count - min(media.count, suffix)
            } else {
                guard let start = Int(bounds[0]), start >= 0, start < media.count else { respond(connection, status: "416 Range Not Satisfiable", headers: ["Content-Range": "bytes */\(media.count)"]); return }
                lower = start
                if !bounds[1].isEmpty {
                    guard let end = Int(bounds[1]), end >= lower else { respond(connection, status: "416 Range Not Satisfiable", headers: ["Content-Range": "bytes */\(media.count)"]); return }
                    upper = min(end, media.count - 1)
                }
            }
        }
        var headers = ["Accept-Ranges": "bytes"]
        if range != nil { headers["Content-Range"] = "bytes \(lower)-\(upper)/\(media.count)" }
        respond(connection, status: range == nil ? "200 OK" : "206 Partial Content", contentType: "video/mp4",
                headers: headers, data: media.subdata(in: lower..<(upper + 1)), head: method == "HEAD")
    }
    private func respond(_ connection: NWConnection, status: String = "200 OK", contentType: String = "application/octet-stream", headers: [String: String] = [:], data: Data = Data(), head: Bool = false) {
        var response = Data("HTTP/1.1 \(status)\r\nContent-Type: \(contentType)\r\nContent-Length: \(data.count)\r\nConnection: close\r\n".utf8)
        for name in headers.keys.sorted() { response.append(Data("\(name): \(headers[name]!)\r\n".utf8)) }
        response.append(Data("\r\n".utf8)); if !head { response.append(data) }
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }
}
