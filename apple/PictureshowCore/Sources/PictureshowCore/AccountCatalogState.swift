// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Account-owned catalog state: explicit sign-out removes private state but retains the sign-in card.
public struct AccountCatalogState: Codable, Equatable {
    public struct Card: Codable, Equatable {
        public var id: String
        public var token: String
        public var expired: Bool
        public var signedOut: Bool
    }
    public var accounts: [Card]
    public var current: String?
    public var searches: [String]
    public var preferences: [String]
    public var pending: [String]
    public var retired: [String]
    public init(ids: [String], current: String?) {
        accounts = ids.map { Card(id: $0, token: "token-\($0)", expired: false, signedOut: false) }
        self.current = current; searches = ids; preferences = ids; pending = ids; retired = ids
    }
    public mutating func signOut(_ id: String) {
        guard let index = accounts.firstIndex(where: { $0.id == id }) else { return }
        accounts[index].token = ""; accounts[index].expired = true; accounts[index].signedOut = true
        if current == id { current = nil }
        searches.removeAll { $0 == id }; preferences.removeAll { $0 == id }; pending.removeAll { $0 == id }; retired.removeAll { $0 == id }
    }
    public mutating func remove(_ id: String) { signOut(id); accounts.removeAll { $0.id == id } }
    public mutating func signIn(_ id: String) {
        accounts.removeAll { $0.id == id }; accounts.append(Card(id: id, token: "fresh-\(id)", expired: false, signedOut: false)); current = id
    }
    public static func execute(input: [String: Any]) throws -> Any {
        var state = AccountCatalogState(ids: input["accounts"] as! [String], current: input["current"] as? String)
        var persisted = try JSONEncoder().encode(state)
        for step in input["steps"] as! [[String: Any]] {
            switch step["action"] as? String {
            case "sign-out": state.signOut(step["account"] as! String)
            case "remove": state.remove(step["account"] as! String)
            case "sign-in": state.signIn(step["account"] as! String)
            case "reload": state = try JSONDecoder().decode(Self.self, from: persisted)
            default: throw ClientError.invalidResponse
            }
            persisted = try JSONEncoder().encode(state)
        }
        state = try JSONDecoder().decode(Self.self, from: persisted)
        return ["accounts": try HomeVectorRunner.object(state.accounts), "current": state.current as Any? ?? NSNull(), "searches": state.searches, "preferences": state.preferences, "pending": state.pending, "retired": state.retired]
    }
}

/// Server acknowledgement remains authoritative when the device's cache or sync marker cannot be written.
public enum HomePreferenceCommit {
    public struct Result {
        public let preferences: HomePreferences
        public let warned: Bool
        public let pending: Bool
    }
    public static func read(remote: HomePreferences, saveLocal: (HomePreferences, Bool) throws -> Void) -> Result {
        do { try saveLocal(remote, false); return Result(preferences: remote, warned: false, pending: false) }
        catch { return Result(preferences: remote, warned: true, pending: false) }
    }
    public static func change(current: HomePreferences, next: HomePreferences, saveLocal: (HomePreferences, Bool) throws -> Void, saveRemote: () async throws -> Void) async -> Result {
        do { try saveLocal(next, true) } catch { return Result(preferences: current, warned: true, pending: false) }
        do {
            try await saveRemote()
            do { try saveLocal(next, false); return Result(preferences: next, warned: false, pending: false) }
            catch { return Result(preferences: next, warned: true, pending: true) }
        } catch { return Result(preferences: next, warned: true, pending: true) }
    }
}
