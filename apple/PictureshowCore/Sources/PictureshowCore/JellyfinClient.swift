// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Rejects cross-origin redirects before a request's Authorization reaches another server.
private final class OriginRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let origin = task.originalRequest?.url, let target = request.url, ServerURLs.isSameOrigin(origin, target) else { completionHandler(nil); return }
        var redirect = request
        redirect.setValue(task.originalRequest?.value(forHTTPHeaderField: "Authorization"), forHTTPHeaderField: "Authorization")
        completionHandler(redirect)
    }
}

public final class JellyfinClient: @unchecked Sendable {
    public let deviceID: String
    private let session: URLSession
    private var decoder: JSONDecoder { JSONDecoder() }
    private let supportsHEVC: Bool
    private let deviceName: String
    private let version: String
    private let fields = "Overview,Genres,PrimaryImageAspectRatio,ChildCount,RecursiveItemCount"
    /// Injected sessions are intended for tests. Production defaults pin redirects to each request's origin.
    public init(deviceID: String, session: URLSession? = nil, supportsHEVC: Bool = false, deviceName: String = "Apple", version: String = "0.1.0") {
        self.deviceID = deviceID; self.supportsHEVC = supportsHEVC; self.deviceName = deviceName; self.version = version
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 60; configuration.timeoutIntervalForResource = 90
        self.session = session ?? URLSession(configuration: configuration, delegate: OriginRedirects(), delegateQueue: nil)
    }

    public func discover(address: String) async throws -> Server {
        let candidates = try ServerURLs.candidates(address)
        var lastError: Error = ClientError.network
        for url in candidates {
            do {
                let data = try await request(server: Server(url: url), path: "System/Info/Public", timeout: candidates.count == 1 ? 60 : 3)
                struct Info: Decodable { let ProductName: String; let Version: String; let Id: String; let ServerName: String }
                let info = try decoder.decode(Info.self, from: data)
                guard info.ProductName == "Jellyfin Server" else { throw ClientError.notJellyfin }
                guard Self.supportedVersion(info.Version) else { throw ClientError.unsupportedVersion }
                return Server(url: url, id: info.Id, name: info.ServerName, version: info.Version)
            } catch is CancellationError { throw CancellationError() }
            catch { if case ClientError.network = error { if lastError is ClientError, (lastError as? ClientError) != .network { continue } }; lastError = error }
        }
        throw lastError
    }
    public static func supportedVersion(_ version: String) -> Bool {
        let parts = version.split(separator: ".").prefix(3).map { $0.prefix(while: { $0.isNumber }) }.map { Int($0) ?? -1 }
        guard parts.count == 3, parts.allSatisfy({ $0 >= 0 }) else { return false }
        return parts.lexicographicallyPrecedes([10, 10, 0]) == false
    }
    public func signIn(server: Server, username: String, password: String) async throws -> Account {
        let data = try await request(server: server, path: "Users/AuthenticateByName", method: "POST", body: ["Username": username, "Pw": password])
        return try account(from: data, server: server)
    }
    public func initiateQuickConnect(server: Server) async throws -> QuickConnectState {
        let enabled = try decoder.decode(Bool.self, from: await request(server: server, path: "QuickConnect/Enabled"))
        guard enabled else { throw ClientError.quickConnectDisabled }
        let state = try decoder.decode(QuickConnectState.self, from: await request(server: server, path: "QuickConnect/Initiate", method: "POST"))
        guard state.code.count == 6, state.code.allSatisfy({ $0.isNumber }), !state.secret.isEmpty else { throw ClientError.invalidResponse }
        return state
    }
    public func quickConnectStatus(server: Server, secret: String) async throws -> QuickConnectState {
        try decoder.decode(QuickConnectState.self, from: await request(server: server, path: "QuickConnect/Connect", query: [URLQueryItem(name: "secret", value: secret)]))
    }
    public func authorizeQuickConnect(account: Account, code: String) async throws {
        guard code.count == 6, code.allSatisfy({ $0.isNumber }) else { throw ClientError.invalidResponse }
        let approved = try decoder.decode(Bool.self, from: await request(account: account, path: "QuickConnect/Authorize", method: "POST", query: [URLQueryItem(name: "code", value: code)], includeUserID: false))
        guard approved else { throw ClientError.invalidResponse }
    }
    public func authenticateQuickConnect(server: Server, secret: String) async throws -> Account {
        let data = try await request(server: server, path: "Users/AuthenticateWithQuickConnect", method: "POST", body: ["Secret": secret])
        return try account(from: data, server: server)
    }
    private func account(from data: Data, server: Server) throws -> Account {
        struct AuthUser: Decodable { let Id: String; let Name: String }
        struct Auth: Decodable { let AccessToken: String; let User: AuthUser }
        let auth = try decoder.decode(Auth.self, from: data)
        guard !auth.AccessToken.isEmpty, !auth.User.Id.isEmpty else { throw ClientError.invalidResponse }
        return Account(server: server, userId: auth.User.Id, username: auth.User.Name, accessToken: auth.AccessToken, deviceID: deviceID)
    }
    public func validate(account: Account) async throws {
        _ = try await request(account: account, path: "Users/Me")
    }
    public func logout(account: Account) async throws {
        _ = try await request(account: account, path: "Sessions/Logout", method: "POST")
    }
    public func views(account: Account) async throws -> [MediaItem] { try await page(account: account, path: "UserViews").items }
    public func resume(account: Account) async throws -> [MediaItem] {
        try await page(account: account, path: "UserItems/Resume", query: [URLQueryItem(name: "mediaTypes", value: "Video"), URLQueryItem(name: "limit", value: "24")]).items
    }
    public func nextUp(account: Account, seriesID: String? = nil) async throws -> [MediaItem] {
        try await page(account: account, path: "Shows/NextUp", query: [URLQueryItem(name: "limit", value: "24"), URLQueryItem(name: "seriesId", value: seriesID)].filter { $0.value != nil }).items
    }
    public func latest(account: Account, parentID: String) async throws -> [MediaItem] {
        try decoder.decode([MediaItem].self, from: await request(account: account, path: "Items/Latest", query: [URLQueryItem(name: "parentId", value: parentID), URLQueryItem(name: "limit", value: "24"), URLQueryItem(name: "fields", value: fields)]))
    }
    public func home(account: Account) async throws -> [HomeRow] {
        try await homeContent(account: account).rows
    }
    /// One failed shelf never hides healthy shelves. Expired sign-in and cancellation still abort the load.
    public func homeContent(account: Account) async throws -> HomeContent {
        var warnings: [String] = []
        func section(_ title: String, _ load: () async throws -> [MediaItem]) async throws -> [MediaItem] {
            do { return try await load() }
            catch is CancellationError { throw CancellationError() }
            catch ClientError.invalidCredentials { throw ClientError.invalidCredentials }
            catch { warnings.append("\(title): \(error.localizedDescription)"); return [] }
        }
        let views = try await section("Libraries") { try await self.views(account: account) }
        let resume = try await section("Continue watching") { try await self.resume(account: account) }
        let nextUp = try await section("Next up") { try await self.nextUp(account: account) }
        var latest: [String:[MediaItem]] = [:]
        for view in views where ["movies", "tvshows"].contains(view.collectionType ?? "") {
            latest[view.id] = try await section("Latest in \(view.name)") { try await self.latest(account: account, parentID: view.id) }
        }
        return HomeContent(rows: Catalog.assembleHome(views: views, resume: resume, nextUp: nextUp, latest: latest), warnings: warnings)
    }
    public func item(account: Account, id: String) async throws -> MediaItem {
        var detail = try decoder.decode(MediaItem.self, from: await request(account: account, path: "Items/\(ServerURLs.encode(id))"))
        guard ["Movie", "Episode", "Video", "Series", "Season", "BoxSet", "Playlist", "Folder", "CollectionFolder"].contains(detail.type) else { return detail }
        // The batch projection is required even for playable titles: Jellyfin 12 can return
        // ambiguous primary UserData. A failed projection must keep freshness-sensitive actions
        // behind Retry rather than publish stale resume/watched state as a successful detail.
        let projected = try decoder.decode(ItemPage.self, from: await request(account: account, path: "Items", query: [URLQueryItem(name: "ids", value: id)]))
        guard let item = projected.items.first(where: { $0.id == id }) else { throw ClientError.invalidResponse }
        detail.userData = item.userData
        return detail
    }
    public func browse(account: Account, parentID: String, startIndex: Int = 0, limit: Int = 60) async throws -> [MediaItem] {
        try await browsePage(account: account, parentID: parentID, startIndex: startIndex, limit: limit).items
    }
    public func browsePage(account: Account, parentID: String, startIndex: Int = 0, limit: Int = 60, sortBy: String = "SortName", descending: Bool = false, recursive: Bool = false, includeItemTypes: String? = nil, ids: [String] = []) async throws -> ItemPage {
        let query: [URLQueryItem]
        if !ids.isEmpty {
            query = [URLQueryItem(name: "ids", value: ids.joined(separator: ",")), URLQueryItem(name: "limit", value: String(min(200,max(1,limit)))), URLQueryItem(name: "enableUserData", value: "true"), URLQueryItem(name: "fields", value: "ChildCount,RecursiveItemCount")]
        } else {
            query = [URLQueryItem(name: "parentId", value: parentID), URLQueryItem(name: "recursive", value: String(recursive)), URLQueryItem(name: "includeItemTypes", value: includeItemTypes), URLQueryItem(name: "sortBy", value: sortBy), URLQueryItem(name: "sortOrder", value: descending ? "Descending" : "Ascending"), URLQueryItem(name: "startIndex", value: String(max(0,startIndex))), URLQueryItem(name: "limit", value: String(min(200,max(1,limit)))), URLQueryItem(name: "enableTotalRecordCount", value: "true"), URLQueryItem(name: "fields", value: fields)].filter { $0.value != nil }
        }
        return try decoder.decode(ItemPage.self, from: await request(account: account, path: "Items", query: query))
    }
    public func search(account: Account, query: String, kind: SearchKind) async throws -> ItemPage {
        let term = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)).trimmingCharacters(in: .whitespacesAndNewlines)
        guard term.count >= 2 else { return try decoder.decode(ItemPage.self, from: Data("{}".utf8)) }
        var parameters = [URLQueryItem(name: "searchTerm", value: term), URLQueryItem(name: "limit", value: "24")]
        if kind != .people { parameters += [URLQueryItem(name: "recursive", value: "true"), URLQueryItem(name: "includeItemTypes", value: kind.rawValue), URLQueryItem(name: "enableTotalRecordCount", value: "true"), URLQueryItem(name: "fields", value: "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount")] }
        return try decoder.decode(ItemPage.self, from: await request(account: account, path: kind == .people ? "Persons" : "Items", query: parameters))
    }
    public func personItems(account: Account, personID: String, startIndex: Int = 0, limit: Int = 60) async throws -> ItemPage {
        try decoder.decode(ItemPage.self, from: await request(account: account, path: "Items", query: [URLQueryItem(name: "personIds", value: personID), URLQueryItem(name: "recursive", value: "true"), URLQueryItem(name: "includeItemTypes", value: "Movie,Series"), URLQueryItem(name: "sortBy", value: "ProductionYear,SortName"), URLQueryItem(name: "sortOrder", value: "Descending"), URLQueryItem(name: "startIndex", value: String(max(0,startIndex))), URLQueryItem(name: "limit", value: String(min(200,max(1,limit)))), URLQueryItem(name: "enableTotalRecordCount", value: "true"), URLQueryItem(name: "fields", value: "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount")]))
    }
    public func played(account: Account, itemID: String, value: Bool) async throws -> ItemUserData { try decoder.decode(ItemUserData.self, from: await request(account: account, path: "UserPlayedItems/\(ServerURLs.encode(itemID))", method: value ? "POST" : "DELETE")) }
    public func favorite(account: Account, itemID: String, value: Bool) async throws -> ItemUserData { try decoder.decode(ItemUserData.self, from: await request(account: account, path: "UserFavoriteItems/\(ServerURLs.encode(itemID))", method: value ? "POST" : "DELETE")) }
    public func publicUsers(server: Server) async throws -> [PublicUser] { try decoder.decode([PublicUser].self, from: await request(server: server, path: "Users/Public")) }
    public func quickConnectEnabled(server: Server) async throws -> Bool { try decoder.decode(Bool.self, from: await request(server: server, path: "QuickConnect/Enabled")) }
    public func configuration(account: Account) async throws -> UserConfiguration {
        struct Response: Decodable { let Configuration: [String:JSONValue] }
        let data = try await request(account: account, path: "Users/\(ServerURLs.encode(account.userId))", includeUserID: false)
        return UserConfiguration(fields: try decoder.decode(Response.self, from: data).Configuration)
    }
    public func saveConfiguration(account: Account, configuration: UserConfiguration) async throws {
        let data = try JSONEncoder().encode(configuration.fields)
        let fields = try JSONSerialization.jsonObject(with: data) as! [String:Any]
        _ = try await request(account: account, path: "Users/Configuration", method: "POST", body: fields)
    }
    public func playbackExtras(account: Account, itemID: String) async throws -> MediaItem { try decoder.decode(MediaItem.self, from: await request(account: account, path: "Items/\(ServerURLs.encode(itemID))", query: [URLQueryItem(name: "fields", value: "Chapters,Trickplay")])) }
    public func segments(account: Account, itemID: String) async throws -> [MediaSegment] {
        struct Page: Decodable { let Items: [MediaSegment] }
        return try decoder.decode(Page.self, from: await request(account: account, path: "MediaSegments/\(ServerURLs.encode(itemID))")).Items
    }
    public func nextEpisode(account: Account, item: MediaItem) async throws -> MediaItem? {
        guard item.type == "Episode", let series = item.seriesId, !series.isEmpty else { return nil }
        let page = try decoder.decode(ItemPage.self, from: await request(account: account, path: "Shows/\(ServerURLs.encode(series))/Episodes", query: [URLQueryItem(name: "adjacentTo", value: item.id), URLQueryItem(name: "isMissing", value: "false"), URLQueryItem(name: "enableUserData", value: "true"), URLQueryItem(name: "fields", value: "Overview")]))
        return PlaybackExtras.adjacentEpisode(current: item, items: page.items)
    }
    public func chapterImageURL(account: Account, itemID: String, index: Int, tag: String) -> URL? { try? ServerURLs.endpoint(account.server, path: "Items/\(ServerURLs.encode(itemID))/Images/Chapter/\(index)", query: [URLQueryItem(name: "tag", value: tag), URLQueryItem(name: "maxWidth", value: "320")]) }
    public func trickplaySheetURL(account: Account, itemID: String, mediaSourceID: String, width: Int, sheet: Int) -> URL? { try? ServerURLs.endpoint(account.server, path: "Videos/\(ServerURLs.encode(itemID))/Trickplay/\(width)/\(sheet).jpg", query: [URLQueryItem(name: "mediaSourceId", value: mediaSourceID)]) }
    public func seasons(account: Account, seriesID: String) async throws -> [MediaItem] {
        try await page(account: account, path: "Shows/\(ServerURLs.encode(seriesID))/Seasons").items
    }
    public func episodes(account: Account, seriesID: String, seasonID: String) async throws -> [MediaItem] {
        try await page(account: account, path: "Shows/\(ServerURLs.encode(seriesID))/Episodes", query: [URLQueryItem(name: "seasonId", value: seasonID)]).items
    }
    private func page(account: Account, path: String, query: [URLQueryItem] = []) async throws -> ItemPage {
        try decoder.decode(ItemPage.self, from: await request(account: account, path: path, query: query + [URLQueryItem(name: "fields", value: fields)]))
    }
    public func imageURL(account: Account, item: MediaItem, maxWidth: Int = 600, maxHeight: Int? = nil, wide: Bool = false) -> URL? {
        var imageID = item.id; var type = "Primary"; var tag = item.imageTags["Primary"]
        if wide || item.type == "Episode" {
            if let thumb = item.imageTags["Thumb"] { type = "Thumb"; tag = thumb }
            else if item.type == "Episode", let primary = item.imageTags["Primary"] { tag = primary }
            else if let own = item.backdropImageTags.first { type = "Backdrop"; tag = own }
            else if let parent = item.parentBackdropItemId, let value = item.parentBackdropImageTags.first { imageID = parent; type = "Backdrop"; tag = value }
            else if let parent = item.parentThumbItemId, let value = item.parentThumbImageTag { imageID = parent; type = "Thumb"; tag = value }
        } else if tag == nil {
            if let series = item.seriesId, let value = item.seriesPrimaryImageTag { imageID = series; tag = value }
            else if let value = item.backdropImageTags.first { type = "Backdrop"; tag = value }
            else if let value = item.imageTags["Thumb"] { type = "Thumb"; tag = value }
        }
        guard let tag, !tag.isEmpty else { return nil }
        let image = try? ServerURLs.endpoint(account.server, path: "Items/\(ServerURLs.encode(imageID))/Images/\(type)", query: [URLQueryItem(name: "tag", value: tag), URLQueryItem(name: "maxWidth", value: String(max(1, maxWidth)))] + (maxHeight.map { [URLQueryItem(name: "maxHeight", value: String(max(1, $0)))] } ?? []) + [URLQueryItem(name: "quality", value: "85")])
        guard let image, var parts = URLComponents(url: image, resolvingAgainstBaseURL: false) else { return nil }
        parts.percentEncodedQuery = parts.percentEncodedQuery?.replacingOccurrences(of: "%20", with: "+")
        return parts.url
    }
    /// Authenticates artwork only within this account's server origin, including redirects.
    public func imageData(account: Account, url: URL) async throws -> Data {
        guard ServerURLs.isSameOrigin(account.server.url, url) else { throw ClientError.unsafeStreamURL }
        var request = URLRequest(url: ServerURLs.strippingCredentials(url), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 60)
        request.setValue(AuthorizationHeader.build(deviceID: account.deviceID, deviceName: deviceName, version: version, token: account.accessToken), forHTTPHeaderField: "Authorization")
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        return try await responseData(for: request, server: account.server)
    }
    public func prepare(account: Account, itemID: String, startTicks: Int64 = 0, audioIndex: Int? = nil, subtitleIndex: Int? = nil, forceTranscode: Bool = false, mediaSourceID: String? = nil, preferences: PlaybackPreferences = PlaybackPreferences(), forceHLS: Bool = false, externalTextSubtitle: Bool = false) async throws -> PlayDecision {
        let videoSubtitle = externalTextSubtitle ? -1 : subtitleIndex
        let body = PlaybackRequests.body(userID: account.userId, profile: AppleProfile.json(supportsHEVC: supportsHEVC, maxStreamingBitrate: preferences.maxStreamingBitrate), maxStreamingBitrate: preferences.maxStreamingBitrate, startTicks: max(0,startTicks), mediaSourceID: mediaSourceID, audioIndex: audioIndex, subtitleIndex: videoSubtitle, enableDirectPlay: !forceTranscode && !forceHLS && (videoSubtitle ?? -1) < 0, enableDirectStream: false, allowVideoStreamCopy: !forceTranscode && (videoSubtitle ?? -1) < 0, allowAudioStreamCopy: !forceTranscode, alwaysBurnIn: true)
        return try await negotiate(account: account, itemID: itemID, body: body, startTicks: startTicks, mediaSourceID: mediaSourceID, audioIndex: audioIndex, subtitleIndex: subtitleIndex)
    }
    /// The portable reference profile uses the same wire negotiation as the Apple runtime. Only profile
    /// construction and deliberate platform request flags differ; source binding and decisions are shared.
    public func prepareReference(account: Account, itemID: String, capabilities: DeviceCapabilities, startMs: Int64 = 0, mediaSourceID: String? = nil, audioIndex: Int? = nil, subtitleIndex: Int? = nil, enableDirectPlay: Bool = true, enableDirectStream: Bool = true, allowVideoStreamCopy: Bool = true, allowAudioStreamCopy: Bool = true, requireVideoStreamCopy: Bool = false) async throws -> PlayDecision {
        let body = PlaybackRequests.body(userID: account.userId, profile: ReferenceDeviceProfile.build(capabilities), maxStreamingBitrate: capabilities.settings?.maxStreamingBitrate ?? 200_000_000, startTicks: startMs * 10_000, mediaSourceID: mediaSourceID, audioIndex: audioIndex, subtitleIndex: subtitleIndex, enableDirectPlay: enableDirectPlay, enableDirectStream: enableDirectStream, allowVideoStreamCopy: allowVideoStreamCopy, allowAudioStreamCopy: allowAudioStreamCopy)
        let decision = try await negotiate(account: account, itemID: itemID, body: body, startTicks: startMs * 10_000, mediaSourceID: mediaSourceID, audioIndex: audioIndex, subtitleIndex: subtitleIndex)
        if requireVideoStreamCopy { guard let recovered = PlaybackRecovery.preserveVideo(decision, subtitleIndex: subtitleIndex) else { throw ClientError.noCompatibleSource }; return recovered }
        return decision
    }
    private func negotiate(account: Account, itemID: String, body originalBody: [String:Any], startTicks: Int64, mediaSourceID: String?, audioIndex: Int?, subtitleIndex: Int?) async throws -> PlayDecision {
        var body = originalBody
        let path = "Items/\(ServerURLs.encode(itemID))/PlaybackInfo"
        var info = try decoder.decode(PlaybackInfo.self, from: await request(account: account, path: path, method: "POST", body: body, includeUserID: false))
        var boundSourceID = mediaSourceID
        // No URL from the discovery response is opened, so this step cannot start an encoder.
        // Retain the bound response's session for reports and account-scoped encoder cleanup.
        if mediaSourceID == nil, audioIndex != nil || subtitleIndex != nil, info.errorCode == nil {
            let preferred = info.mediaSources.first { $0.supportsDirectPlay || $0.supportsDirectStream || !($0.transcodingUrl ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? info.mediaSources.first
            if let id = preferred?.id, !id.isEmpty {
                boundSourceID = id; body["MediaSourceId"] = id
                info = try decoder.decode(PlaybackInfo.self, from: await request(account: account, path: path, method: "POST", body: body, includeUserID: false))
            }
        }
        return try PlayDecisions.decide(account: account, itemID: itemID, response: info, startTicks: startTicks, audioIndex: audioIndex, subtitleIndex: subtitleIndex, mediaSourceID: boundSourceID, deviceName: deviceName, version: version)
    }
    public func sendReport(account: Account, kind: String, body: [String:Any]) async throws {
        let paths = ["start":"Sessions/Playing", "progress":"Sessions/Playing/Progress", "stopped":"Sessions/Playing/Stopped"]
        guard let path = paths[kind] else { throw ClientError.invalidResponse }
        _ = try await request(account: account, path: path, method: "POST", body: body, includeUserID: false)
    }
    public func reportStart(account: Account, decision: PlayDecision, positionTicks: Int64, paused: Bool = false) async throws { try await sendReport(account: account, kind: "start", body: PlaybackReports.progress(decision: decision, positionTicks: max(0,positionTicks), paused: paused, audioIndex: decision.audioIndex, subtitleIndex: decision.subtitleIndex, isMuted: decision.audioIndex == -1)) }
    public func reportProgress(account: Account, decision: PlayDecision, positionTicks: Int64, paused: Bool = false) async throws { try await sendReport(account: account, kind: "progress", body: PlaybackReports.progress(decision: decision, positionTicks: max(0,positionTicks), paused: paused, audioIndex: decision.audioIndex, subtitleIndex: decision.subtitleIndex, isMuted: decision.audioIndex == -1)) }
    public func reportStopped(account: Account, decision: PlayDecision, positionTicks: Int64, failed: Bool = false) async throws { try await sendReport(account: account, kind: "stopped", body: PlaybackReports.stopped(decision: decision, positionTicks: max(0,positionTicks), failed: failed)) }
    public func stopEncoding(account: Account, decision: PlayDecision) async throws { guard decision.method == .transcode, let id = decision.playSessionId else { return }; try await stopEncoding(account: account, playSessionID: id) }
    public func stopEncoding(account: Account, playSessionID: String) async throws {
        _ = try await request(account: account, path: "Videos/ActiveEncodings", method: "DELETE", query: [URLQueryItem(name: "deviceId", value: account.deviceID), URLQueryItem(name: "playSessionId", value: playSessionID)], includeUserID: false)
    }
    private func request(account: Account, path: String, method: String = "GET", query: [URLQueryItem] = [], body: [String:Any]? = nil, includeUserID: Bool = true) async throws -> Data {
        try await request(server: account.server, path: path, method: method, query: query + (includeUserID ? [URLQueryItem(name: "userId", value: account.userId)] : []), body: body, token: account.accessToken, identity: account.deviceID)
    }
    private func request(server: Server, path: String, method: String = "GET", query: [URLQueryItem] = [], body: [String:Any]? = nil, token: String? = nil, identity: String? = nil, timeout: TimeInterval = 60) async throws -> Data {
        let url = try ServerURLs.endpoint(server, path: path, query: query)
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        request.setValue(AuthorizationHeader.build(deviceID: identity ?? deviceID, deviceName: deviceName, version: version, token: token), forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return try await responseData(for: request, server: server)
    }
    private func responseData(for request: URLRequest, server: Server) async throws -> Data {
        let data: Data; let response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        catch { if Task.isCancelled { throw CancellationError() }; throw ClientError.network }
        guard let http = response as? HTTPURLResponse else { throw ClientError.invalidResponse }
        guard let responseURL = http.url, ServerURLs.isSameOrigin(server.url, responseURL) else { throw ClientError.unsafeStreamURL }
        if http.statusCode == 401 { throw ClientError.invalidCredentials }
        guard (200..<300).contains(http.statusCode) else { throw ClientError.server(http.statusCode) }
        return data
    }
}
