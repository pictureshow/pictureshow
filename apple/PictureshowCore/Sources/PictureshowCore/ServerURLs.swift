// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum ClientError: Error, LocalizedError, Equatable {
    case invalidAddress, notJellyfin, unsupportedVersion, invalidCredentials, network, invalidResponse, server(Int), serverRefused, noCompatibleSource, sourceNotFound, unsafeStreamURL, quickConnectDisabled
    public var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Enter an HTTP or HTTPS server address without credentials, a query or fragment."
        case .notJellyfin: return "This address is not a Jellyfin server."
        case .unsupportedVersion: return "Jellyfin 10.10.0 or newer is required."
        case .invalidCredentials: return "The server did not accept this sign-in. Sign in again."
        case .network: return "Could not reach the server. Check your connection and try again."
        case .invalidResponse: return "The server returned an unreadable response."
        case .server(let status): return "The server could not complete the request (\(status))."
        case .serverRefused: return "The server refused playback."
        case .noCompatibleSource: return "This video cannot be played on this device."
        case .sourceNotFound: return "The server did not return the chosen version."
        case .unsafeStreamURL: return "The server returned a stream outside its origin."
        case .quickConnectDisabled: return "Quick Connect is disabled on this server. Use your username and password."
        }
    }
}

public enum ServerURLs {
    static let credentialNames = Set(["apikey", "api_key", "x-emby-token", "x-mediabrowser-token"])
    public static func encode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
    public static func candidates(_ input: String) throws -> [URL] {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
        guard !text.isEmpty, !text.contains(where: { $0.isWhitespace || $0.asciiValue.map { $0 < 32 || $0 == 127 } == true }), !text.contains("\\") else { throw ClientError.invalidAddress }
        let explicit = text.contains("://")
        let raw = explicit ? text : "https://" + text
        guard let c = URLComponents(string: raw), let host = c.host, !host.isEmpty,
              ["http", "https"].contains(c.scheme?.lowercased() ?? ""), c.user == nil, c.password == nil, c.query == nil, c.fragment == nil,
              c.port.map({ (1...65535).contains($0) }) ?? true else { throw ClientError.invalidAddress }
        let authority = raw.components(separatedBy: "://")[1].components(separatedBy: "/")[0]
        guard !authority.contains("%"), !authority.hasSuffix(":"), c.url != nil else { throw ClientError.invalidAddress }
        let schemes = explicit ? [c.scheme!.lowercased()] : ["https", "http"]
        return try schemes.flatMap { scheme -> [URL] in
            let ports = c.port.map { [$0] } ?? (scheme == "https" ? [443, 8920] : [80, 8096])
            return try ports.map { port in
                var candidate = c; candidate.scheme = scheme
                candidate.port = ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) ? nil : port
                guard let url = candidate.url else { throw ClientError.invalidAddress }
                return url
            }
        }
    }
    public static func endpoint(_ server: Server, path: String, query: [URLQueryItem] = []) throws -> URL {
        let base = server.url.absoluteString.replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
        guard var c = URLComponents(string: base + "/" + path) else { throw ClientError.invalidAddress }
        c.queryItems = query.isEmpty ? nil : query
        guard let url = c.url else { throw ClientError.invalidAddress }
        return url
    }
    public static func resolve(server: Server, reference: String) throws -> URL {
        guard !reference.isEmpty, !reference.contains(where: { $0.isWhitespace || $0.asciiValue.map { $0 < 32 || $0 == 127 } == true }), !reference.contains("\\") else { throw ClientError.unsafeStreamURL }
        let value: String
        if reference.hasPrefix("//") { value = (server.url.scheme ?? "https") + ":" + reference }
        else if reference.range(of: "^[A-Za-z][A-Za-z0-9+.-]*:", options: .regularExpression) != nil { value = reference }
        else { value = server.url.absoluteString.replacingOccurrences(of: "/+$", with: "", options: .regularExpression) + "/" + String(reference.drop(while: { $0 == "/" })) }
        guard let url = URL(string: value) else { throw ClientError.unsafeStreamURL }
        return url
    }
    public static func isSameOrigin(_ origin: URL, _ target: URL) -> Bool {
        func valid(_ url: URL) -> Bool {
            let value = url.absoluteString
            let authority = value.components(separatedBy: "://").dropFirst().first?.components(separatedBy: "/")[0].components(separatedBy: "?")[0].components(separatedBy: "#")[0] ?? ""
            guard !authority.isEmpty, !authority.contains("%"), !authority.hasSuffix(":"), !value.contains("\\"), !value.contains(where: { $0.isWhitespace }), let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
                  let host = c.host, !host.isEmpty, c.user == nil, c.password == nil,
                  ["http", "https"].contains(c.scheme?.lowercased() ?? ""), c.port.map({ (1...65535).contains($0) }) ?? true else { return false }
            return true
        }
        return valid(origin) && valid(target) && origin.scheme?.lowercased() == target.scheme?.lowercased() && origin.host?.lowercased() == target.host?.lowercased() && (origin.port ?? (origin.scheme?.lowercased() == "https" ? 443 : 80)) == (target.port ?? (target.scheme?.lowercased() == "https" ? 443 : 80))
    }
    public static func strippingCredentials(_ url: URL) -> URL {
        guard var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        c.percentEncodedQuery = c.percentEncodedQuery?.components(separatedBy: "&").filter { part in
            let key = part.components(separatedBy: "=")[0].replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? ""
            return !credentialNames.contains(key.lowercased())
        }.joined(separator: "&").nilIfEmpty
        return c.url ?? url
    }
}

extension Array { var nilIfEmpty: Self? { isEmpty ? nil : self } }

public enum AuthorizationHeader {
    public static func build(deviceID: String, deviceName: String = "Apple", version: String = "0.1.0", token: String? = nil) -> String {
        var values = [("Client", "Pictureshow"), ("Device", deviceName), ("DeviceId", deviceID), ("Version", version)]
        if let token { values.append(("Token", token)) }
        return "MediaBrowser " + values.map { "\($0.0)=\"\(ServerURLs.encode($0.1))\"" }.joined(separator: ", ")
    }
}
