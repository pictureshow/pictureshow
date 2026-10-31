// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// AVKit gives this URL to an AirPlay receiver. It must be reachable from that receiver.
/// Query credentials are deliberately confined to the media boundary, never displayed or logged.
public enum ServerPlaybackURL {
    public enum Failure: LocalizedError {
        case crossOrigin, unsupportedHeaders, invalidURL
        public var errorDescription: String? {
            switch self {
            case .crossOrigin: return "The server returned media outside its origin. Go back and choose another version."
            case .unsupportedHeaders: return "This source requires custom network headers that AVKit and AirPlay cannot forward. Choose a server-hosted version."
            case .invalidURL: return "The server did not provide a usable media address. Go back and try again."
            }
        }
    }
    public static func make(decisionURL: URL, server: URL, token: String, requiredHeaders: [String: String] = [:]) throws -> URL {
        guard decisionURL.scheme?.lowercased() == server.scheme?.lowercased(),
              decisionURL.host?.lowercased() == server.host?.lowercased(),
              port(decisionURL) == port(server), decisionURL.user == nil, decisionURL.password == nil else { throw Failure.crossOrigin }
        // AVURLAsset's undocumented HTTPHeaderFields key is not an AirPlay transport contract.
        // Do not silently ignore a source that requires a custom upstream header.
        guard requiredHeaders.allSatisfy({ ["authorization", "x-emby-token"].contains($0.key.lowercased()) }) else { throw Failure.unsupportedHeaders }
        guard var parts = URLComponents(url: decisionURL, resolvingAgainstBaseURL: false), !token.isEmpty else { throw Failure.invalidURL }
        let credentials = ["apikey", "api_key", "x-emby-token", "token", "access_token", "accesstoken"]
        parts.queryItems = (parts.queryItems ?? []).filter { !credentials.contains($0.name.lowercased()) } + [URLQueryItem(name: "ApiKey", value: token)]
        parts.fragment = nil
        guard let url = parts.url else { throw Failure.invalidURL }
        return url
    }
    private static func port(_ url: URL) -> Int { url.port ?? (url.scheme?.lowercased() == "https" ? 443 : 80) }
}
