// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One answer from Seerr, as the client needs it.
public struct SeerrHTTPResponse: Sendable {
    public var status: Int
    /// Every Set-Cookie header, one cookie each.
    public var setCookies: [String]
    public var body: Data
    public init(status: Int, setCookies: [String] = [], body: Data = Data()) { self.status = status; self.setCookies = setCookies; self.body = body }
}

/// Sends one request to Seerr. A transport failure (no route, TLS, timeout) throws.
public typealias SeerrTransport = @Sendable (URLRequest) async throws -> SeerrHTTPResponse

/// Seerr's own HTTP: an ephemeral session with redirects refused (a redirect could carry the cookie or key elsewhere),
/// no cookie jar, no cache and no shared headers. The Jellyfin token is never added here.
public enum SeerrHTTP {
    private final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    public static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
    }()

    public static let live: SeerrTransport = { request in
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return SeerrHTTPResponse(status: http.statusCode, setCookies: setCookies(http), body: data)
    }

    /// Foundation joins repeated Set-Cookie headers with commas, which also appear inside Expires dates; HTTPCookie
    /// splits them properly. Each cookie comes back as "name=value".
    public static func setCookies(_ response: HTTPURLResponse) -> [String] {
        var fields: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            if let key = key as? String, let value = value as? String { fields[key] = value }
        }
        guard let url = response.url else { return [] }
        return HTTPCookie.cookies(withResponseHeaderFields: fields, for: url).map { "\($0.name)=\($0.value)" }
    }
}

/// One viewer's connection to Seerr. Calls go through `transport` only (see `SeerrHTTP`): the Jellyfin token never goes
/// to Seerr, and Seerr's cookie or key never goes anywhere else.
public actor SeerrClient {
    public nonisolated let config: SeerrConfig
    private var secret: SeerrSecret
    private let transport: SeerrTransport
    /// Called with the secret a renewal replaced and the renewed one; the store saves it only while the connection exists.
    private let saveSecret: @Sendable (SeerrSecret, SeerrSecret) async -> Void
    private var renewal: Task<SeerrSecret, Error>?

    public init(config: SeerrConfig, secret: SeerrSecret, transport: @escaping SeerrTransport = SeerrHTTP.live,
                saveSecret: @escaping @Sendable (SeerrSecret, SeerrSecret) async -> Void = { _, _ in }) {
        self.config = config
        self.secret = secret
        self.transport = transport
        self.saveSecret = saveSecret
    }

    private static func raw(_ transport: SeerrTransport, base: String, path: String, method: String, body: Data?, headers: [(String, String)]) async throws -> SeerrHTTPResponse {
        guard let url = URL(string: "\(base)/api/v1/\(path)") else { throw SeerrError.address }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        do {
            return try await transport(request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as SeerrError {
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw SeerrError.unreachable
        }
    }

    private func raw(_ path: String, method: String = "GET", body: Data? = nil, lookup: Bool = false) async throws -> SeerrHTTPResponse {
        let headers = try seerrAuthHeaders(mode: config.mode, secret: secret, userId: config.user.id, lookup: lookup)
        return try await Self.raw(transport, base: config.url, path: path, method: method, body: body, headers: headers)
    }

    /// Jellyfin mode renews a lapsed 30-day session once with the saved Jellyfin password. Seerr answers a missing
    /// session 401 and a stale one 403, the same 403 as a missing permission, so a 401/403 is checked against
    /// /auth/me (which needs no permission) before renewing.
    private func call(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        let used = secret
        var response = try await raw(path, method: method, body: body)
        if config.mode == .jellyfin, response.status == 401 || response.status == 403 {
            let expired: Bool
            if path == "auth/me" { expired = true } else {
                let me = try await raw("auth/me").status
                expired = me == 401 || me == 403
            }
            if expired {
                try await renew(from: used)
                response = try await raw(path, method: method, body: body)
            }
        }
        if !(200...299).contains(response.status) || response.status == 202 {
            throw seerrError(status: response.status, body: String(decoding: response.body, as: UTF8.self))
        }
        return response.body
    }

    private func renew(from used: SeerrSecret) async throws {
        if let renewal { _ = try await Self.mapRenewal { try await renewal.value }; return }
        // Another call already renewed after this one started: just retry with the new secret.
        guard secret == used else { return }
        guard !used.password.isEmpty else { throw SeerrError.expired }
        let transport = transport, url = config.url
        let task = Task { try await Self.login(url: url, username: used.username, password: used.password, transport: transport).secret }
        renewal = task
        defer { renewal = nil }
        let renewed = try await Self.mapRenewal { try await task.value }
        secret = renewed
        await saveSecret(used, renewed)
    }

    /// A changed Jellyfin password means signing in to Seerr again, not a wrong password now.
    private static func mapRenewal(_ work: () async throws -> SeerrSecret) async throws -> SeerrSecret {
        do { return try await work() } catch SeerrError.password { throw SeerrError.expired }
    }

    static func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do { return try JSONDecoder().decode(type, from: data) } catch { throw SeerrError.server }
    }

    public func me() async throws -> SeerrUser { try Self.decode(SeerrUser.self, await call("auth/me")) }
    public func trending(page: Int = 1) async throws -> SeerrPage { try Self.decode(SeerrPage.self, await call("discover/trending?page=\(page)")) }
    public func search(_ query: String, page: Int = 1) async throws -> SeerrPage {
        let words = encodeSeerrQuery(query.trimmingCharacters(in: .whitespacesAndNewlines))
        return try Self.decode(SeerrPage.self, await call("search?query=\(words)&page=\(page)"))
    }

    public func detail(type: String, id: Int) async throws -> SeerrTitle {
        guard ["movie", "tv"].contains(type), id > 0 else { throw SeerrError.server }
        var title = try Self.decode(SeerrTitle.self, await call("\(type)/\(id)"))
        title.mediaType = type
        return title
    }

    /// The viewer's own requests, newest first.
    public func requests(skip: Int = 0, take: Int = 20) async throws -> SeerrRequests {
        try Self.decode(SeerrRequests.self, await call("request?take=\(take)&skip=\(skip)&filter=all&sort=added&requestedBy=\(config.user.id)"))
    }

    /// A movie, or the chosen series seasons; each season must still be requestable by `seerrSelectableSeasons`.
    public func request(_ title: SeerrTitle, seasons: [Int] = []) async throws -> SeerrRequest {
        guard ["movie", "tv"].contains(title.mediaType) else { throw SeerrError.server }
        let selectable = Set(seerrSelectableSeasons(title))
        if title.mediaType == "tv" { guard !seasons.isEmpty, seasons.allSatisfy(selectable.contains) else { throw SeerrError.alreadyRequested } }
        var payload: [String: Any] = ["mediaType": title.mediaType, "mediaId": title.id]
        if title.mediaType == "tv" { payload["seasons"] = Array(Set(seasons)).sorted() }
        let body = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        return try Self.decode(SeerrRequest.self, await call("request", method: "POST", body: body))
    }

    /// Signs in with the viewer's Jellyfin name and password; Seerr must already have Jellyfin set up.
    public static func login(url: String, username: String, password: String, transport: @escaping SeerrTransport = SeerrHTTP.live) async throws -> (user: SeerrUser, secret: SeerrSecret) {
        guard let base = normalizeSeerrURL(url) else { throw SeerrError.address }
        let body = try JSONSerialization.data(withJSONObject: ["username": username, "password": password], options: [.sortedKeys])
        let response = try await raw(transport, base: base, path: "auth/jellyfin", method: "POST", body: body, headers: [])
        guard (200...299).contains(response.status) else {
            throw seerrError(status: response.status, body: String(decoding: response.body, as: UTF8.self), login: true)
        }
        guard let cookie = seerrSessionCookie(response.setCookies) else { throw SeerrError.server }
        return (try decode(SeerrUser.self, response.body), SeerrSecret(cookie: cookie, username: username, password: password))
    }

    /// An API key acts as Seerr's admin unless X-API-User names someone, so the key is only usable once the viewer's
    /// own Seerr user is found by Jellyfin user id across every page of users. No match fails closed with `.notLinked`:
    /// requests are never made as the admin or anyone else.
    public static func apiKey(url: String, key: String, jellyfinUserId: String, transport: @escaping SeerrTransport = SeerrHTTP.live) async throws -> SeerrConfig {
        guard let base = normalizeSeerrURL(url) else { throw SeerrError.address }
        let headers = try seerrAuthHeaders(mode: .apiKey, secret: SeerrSecret(apiKey: key), userId: 0, lookup: true)
        var skip = 0
        while true {
            let response = try await raw(transport, base: base, path: "user?take=100&skip=\(skip)", method: "GET", body: nil, headers: headers)
            guard (200...299).contains(response.status) else {
                throw seerrError(status: response.status, body: String(decoding: response.body, as: UTF8.self), apiKey: true)
            }
            let users = try decode(SeerrUsers.self, response.body)
            if let user = seerrLinkedUser(users.results, jellyfinUserId: jellyfinUserId) { return SeerrConfig(url: base, mode: .apiKey, user: user) }
            skip += users.results.count
            if users.results.isEmpty || skip >= users.pageInfo.results { break }
        }
        throw SeerrError.notLinked
    }

    /// Signs in or links the key, then asks Seerr who the connection really acts as (X-API-User included) before
    /// anything is saved. In API-key mode a different answer than the linked user fails closed.
    public static func connect(url: String, apiKey: String?, username: String, password: String, jellyfinUserId: String,
                               transport: @escaping SeerrTransport = SeerrHTTP.live) async throws -> (config: SeerrConfig, secret: SeerrSecret) {
        guard let base = normalizeSeerrURL(url) else { throw SeerrError.address }
        let config: SeerrConfig, secret: SeerrSecret
        if let apiKey {
            let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            config = try await Self.apiKey(url: base, key: key, jellyfinUserId: jellyfinUserId, transport: transport)
            secret = SeerrSecret(apiKey: key)
        } else {
            let signedIn = try await login(url: base, username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password, transport: transport)
            config = SeerrConfig(url: base, mode: .jellyfin, user: signedIn.user)
            secret = signedIn.secret
        }
        let me = try await SeerrClient(config: config, secret: secret, transport: transport).me()
        if config.mode == .apiKey && me.id != config.user.id { throw SeerrError.notLinked }
        var verified = config
        verified.user = me
        return (verified, secret)
    }
}
