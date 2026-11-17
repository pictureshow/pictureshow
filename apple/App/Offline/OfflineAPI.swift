// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// The few server calls downloads make. Every call names the token it uses, because two are in play:
/// the viewer's own sign-in (to read and write their place), and the download set's own token (OFF-10),
/// which fetches media and is revoked when the viewer signs out.
struct OfflineAPI {
    enum Failure: Error, Equatable {
        /// The server answered with an error status: the download failed, and Retry is offered.
        case server(Int)
        /// No connection, or an answer that isn't this server's (a captive portal): wait for network.
        case unreachable
        case signedOut
    }

    struct Policy: Equatable { var downloads, playback, video, audio: Bool }
    struct UserData: Equatable { var positionTicks: Int64; var played: Bool; var playCount: Int; var lastPlayedDate: String? }

    let deviceName: String
    let version: String
    var session: URLSession = .shared

    func authorization(deviceID: String, token: String?) -> String {
        AuthorizationHeader.build(deviceID: deviceID, deviceName: deviceName, version: version, token: token)
    }

    private func call(_ server: Server, _ path: String, query: [URLQueryItem] = [], auth: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        let url = try ServerURLs.endpoint(server, path: path, query: query)
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.httpMethod = method
        request.setValue(auth, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body { request.httpBody = body; request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        catch { if Task.isCancelled { throw CancellationError() }; throw Failure.unreachable }
        guard let http = response as? HTTPURLResponse, let final = http.url, ServerURLs.isSameOrigin(server.url, final) else { throw Failure.unreachable }
        // A sign-in page answers 200 too; it isn't this server.
        if (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased().contains("text/html") { throw Failure.unreachable }
        if http.statusCode == 401 { throw Failure.signedOut }
        guard (200..<300).contains(http.statusCode) else { throw Failure.server(http.statusCode) }
        return data
    }

    private func object(_ data: Data) throws -> [String: Any] {
        guard let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.unreachable }
        return value
    }

    private func viewer(_ account: Account) -> String { authorization(deviceID: account.deviceID, token: account.accessToken) }

    func policy(_ account: Account) async throws -> Policy {
        let me = try object(await call(account.server, "Users/Me", auth: viewer(account)))
        let p = me["Policy"] as? [String: Any] ?? [:]
        func flag(_ key: String) -> Bool { p[key] as? Bool ?? false }
        return Policy(downloads: flag("EnableContentDownloading"), playback: flag("EnableMediaPlayback"),
                      video: flag("EnableVideoPlaybackTranscoding"), audio: flag("EnableAudioPlaybackTranscoding"))
    }

    struct ItemFacts: Equatable { var size: Int64?; var bitrate: Int?; var canDownload: Bool?; var isInfinite: Bool }

    /// The default source's size and the item's own download right; also how a broken transfer asks "is the server there?".
    func itemFacts(_ account: Account, itemID: String) async throws -> ItemFacts {
        let d = try object(await call(account.server, "Users/\(account.userId)/Items/\(itemID)", auth: viewer(account)))
        let source = (d["MediaSources"] as? [[String: Any]])?.first ?? [:]
        return ItemFacts(size: (source["Size"] as? NSNumber)?.int64Value, bitrate: (source["Bitrate"] as? NSNumber)?.intValue,
                         canDownload: d["CanDownload"] as? Bool, isInfinite: source["IsInfiniteStream"] as? Bool ?? false)
    }

    /// A track as an album or playlist download needs it (MUS-02): its source's size and download right, and the names and
    /// album its saved copy keeps for showing it with no server.
    struct TrackFacts {
        var item: MediaItem
        var album: String?
        var albumId: String?
        var artist: String?
        var size: Int64?
        var canDownload: Bool?
        var candidate: OfflineCandidate {
            let source = item.mediaSources.first
            return OfflineCandidate(itemId: item.id, type: item.type, isInfinite: source?.isInfiniteStream ?? false, sizeBytes: size,
                                    bitrate: source?.bitrate.map { Int(clamping: $0) }, runtimeTicks: item.runTimeTicks ?? source?.runTimeTicks, canDownload: canDownload)
        }
    }

    /// Every track's facts in one call per hundred, in the order asked; a track the server no longer has is left out.
    func tracks(_ account: Account, ids: [String]) async throws -> [TrackFacts] {
        var seen = Set<String>()
        let unique = ids.filter { seen.insert($0).inserted }
        var found: [String: TrackFacts] = [:]
        for start in stride(from: 0, to: unique.count, by: 100) {
            let batch = Array(unique[start..<min(start + 100, unique.count)])
            let body = try object(await call(account.server, "Users/\(account.userId)/Items", query: [
                URLQueryItem(name: "ids", value: batch.joined(separator: ",")),
                URLQueryItem(name: "fields", value: "MediaSources,MediaStreams,CanDownload"),
                URLQueryItem(name: "limit", value: String(batch.count)),
            ], auth: viewer(account)))
            for raw in body["Items"] as? [[String: Any]] ?? [] {
                guard let data = try? JSONSerialization.data(withJSONObject: raw), let item = try? JSONDecoder().decode(MediaItem.self, from: data) else { continue }
                let artists = raw["Artists"] as? [String] ?? []
                let source = (raw["MediaSources"] as? [[String: Any]])?.first ?? [:]
                found[item.id] = TrackFacts(item: item, album: raw["Album"] as? String, albumId: raw["AlbumId"] as? String,
                                            artist: artists.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: ", ").nilIfEmpty ?? raw["AlbumArtist"] as? String,
                                            size: (source["Size"] as? NSNumber)?.int64Value, canDownload: raw["CanDownload"] as? Bool)
            }
        }
        return unique.compactMap { found[$0] }
    }

    /// A sync writes only after the login proves it is still the same viewer.
    func sameViewer(_ account: Account) async throws -> Bool {
        let me = try object(await call(account.server, "Users/Me", auth: viewer(account)))
        let id = (me["Id"] as? String ?? "").replacingOccurrences(of: "-", with: "").lowercased()
        return id == account.userId.replacingOccurrences(of: "-", with: "").lowercased()
    }

    /// Strict: an answer without both the position and the played flag isn't taken.
    func userData(_ account: Account, itemID: String) async throws -> UserData {
        let d = try object(await call(account.server, "UserItems/\(itemID)/UserData", query: [URLQueryItem(name: "userId", value: account.userId)], auth: viewer(account)))
        guard let position = (d["PlaybackPositionTicks"] as? NSNumber)?.int64Value, let played = d["Played"] as? Bool else { throw Failure.unreachable }
        return UserData(positionTicks: position, played: played, playCount: (d["PlayCount"] as? NSNumber)?.intValue ?? 0, lastPlayedDate: d["LastPlayedDate"] as? String)
    }

    /// Dirty fields only; a field left out stays as the server has it.
    func writeUserData(_ account: Account, itemID: String, positionTicks: Int64?, played: Bool?) async throws -> Int {
        var body: [String: Any] = [:]
        if let positionTicks { body["PlaybackPositionTicks"] = positionTicks }
        if let played { body["Played"] = played }
        do {
            _ = try await call(account.server, "UserItems/\(itemID)/UserData", query: [URLQueryItem(name: "userId", value: account.userId)],
                               auth: viewer(account), method: "POST", body: try JSONSerialization.data(withJSONObject: body))
            return 200
        } catch Failure.server(let status) { return status }
    }

    /// Only the server's own two answers count; anything else means it wasn't reached.
    func quickConnectEnabled(_ server: Server, deviceID: String) async throws -> Bool {
        let text = String(decoding: try await call(server, "QuickConnect/Enabled", auth: authorization(deviceID: deviceID, token: nil)), as: UTF8.self)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        switch text { case "true": return true; case "false": return false; default: throw Failure.unreachable }
    }

    /// Issues a token for the download set's own device id without asking the viewer anything:
    /// the viewer's session authorizes the code.
    func issueByQuickConnect(_ account: Account, deviceID: String) async throws -> String {
        let anonymous = authorization(deviceID: deviceID, token: nil)
        let start = try object(await call(account.server, "QuickConnect/Initiate", auth: anonymous, method: "POST"))
        guard let code = start["Code"] as? String, let secret = start["Secret"] as? String else { throw Failure.unreachable }
        _ = try await call(account.server, "QuickConnect/Authorize", query: [URLQueryItem(name: "code", value: code), URLQueryItem(name: "userId", value: account.userId)],
                           auth: viewer(account), method: "POST")
        let body = try JSONSerialization.data(withJSONObject: ["Secret": secret])
        return try token(await call(account.server, "Users/AuthenticateWithQuickConnect", auth: anonymous, method: "POST", body: body), account: account)
    }

    func issueByPassword(_ account: Account, deviceID: String, password: String) async throws -> String {
        let body = try JSONSerialization.data(withJSONObject: ["Username": account.username, "Pw": password])
        return try token(await call(account.server, "Users/AuthenticateByName", auth: authorization(deviceID: deviceID, token: nil), method: "POST", body: body), account: account)
    }

    private func token(_ data: Data, account: Account) throws -> String {
        let auth = try object(data)
        guard let token = auth["AccessToken"] as? String, let user = auth["User"] as? [String: Any], let id = user["Id"] as? String,
              id.replacingOccurrences(of: "-", with: "").lowercased() == account.userId.replacingOccurrences(of: "-", with: "").lowercased()
        else { throw Failure.unreachable }
        return token
    }

    func revoke(_ server: Server, deviceID: String, token: String) async throws {
        _ = try await call(server, "Sessions/Logout", auth: authorization(deviceID: deviceID, token: token), method: "POST")
    }

    /// Stops a conversion we walked away from. Best effort.
    func stopConversion(_ server: Server, deviceID: String, token: String, playSessionID: String) async {
        _ = try? await call(server, "Videos/ActiveEncodings", query: [URLQueryItem(name: "deviceId", value: deviceID), URLQueryItem(name: "playSessionId", value: playSessionID)],
                            auth: authorization(deviceID: deviceID, token: token), method: "DELETE")
    }
}

private extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }
