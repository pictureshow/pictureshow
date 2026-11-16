// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// AUTH-15: a signed-in phone or tablet approves the Quick Connect code another device shows, with its own token.
public enum QuickConnectApproval {
    /// Whitespace is harmless when pasting; punctuation and non-ASCII lookalikes are never guessed.
    public static func normalize(_ input: String) -> String { String(input.filter { !$0.isWhitespace }).uppercased() }

    /// Six ASCII letters or digits after normalizing; anything else is refused before any request.
    public static func valid(_ input: String) -> Bool {
        let code = normalize(input)
        return code.count == 6 && code.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
    }

    /// Stable presentation keys: each screen supplies its own copy, never a raw status or server body.
    public enum Result: String, Sendable {
        case approved = "APPROVED", invalidInput = "INVALID_INPUT", wrongOrExpired = "WRONG_OR_EXPIRED", disabled = "DISABLED"
        case offline = "OFFLINE", signInRequired = "SIGN_IN_REQUIRED", failed = "FAILED"
    }

    /// What `POST /QuickConnect/Authorize` answered: the status and its `true`/`false` body (nil when unreadable).
    public static func result(status: Int, approved: Bool?) -> Result {
        if (200..<300).contains(status) && approved == true { return .approved }
        if (200..<300).contains(status) && approved == false || [400, 404, 410].contains(status) { return .wrongOrExpired }
        if status == 401 { return .signInRequired }
        return .failed
    }
}

extension JellyfinClient {
    /// Approves `input` as `account` after checking Quick Connect is on. A 401 from Authorize re-checks Enabled, since
    /// Jellyfin also answers 401 when Quick Connect was turned off in between.
    public func approveQuickConnect(account: Account, input: String) async -> QuickConnectApproval.Result {
        guard QuickConnectApproval.valid(input) else { return .invalidInput }
        if account.signedOut { return .signInRequired }
        enum Answer { case status(Int, String), offline }
        func call(_ path: String, method: String = "GET", query: [URLQueryItem] = []) async -> Answer {
            do {
                let data = try await request(account: account, path: path, method: method, query: query, includeUserID: false)
                return .status(200, String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines))
            } catch ClientError.invalidCredentials { return .status(401, "") }
            catch ClientError.server(let status) { return .status(status, "") }
            catch ClientError.network { return .offline }
            catch is CancellationError { return .offline }
            catch { return .status(0, "") }
        }
        func enabled() async -> Bool?? {
            switch await call("QuickConnect/Enabled") {
            case .offline: return .none
            case let .status(status, body): return .some((200..<300).contains(status) ? Bool(body) : nil)
            }
        }
        switch await enabled() {
        case .none: return .offline
        case .some(false?): return .disabled
        case .some(nil): return .failed
        case .some(true?): break
        }
        switch await call("QuickConnect/Authorize", method: "POST", query: [URLQueryItem(name: "code", value: QuickConnectApproval.normalize(input))]) {
        case .offline: return .offline
        case let .status(status, body):
            if status == 401, case .some(false?) = await enabled() { return .disabled }
            return QuickConnectApproval.result(status: status, approved: Bool(body))
        }
    }
}

/// PLAY-08 (TV review T-10): a track type with nothing to pick says so in one line instead of a lone Off.
public enum TrackMenuNotice {
    /// `tracks` counts every listed track of that type. Nil when there is at least one, and for other types.
    public static func empty(type: String, tracks: Int) -> String? {
        guard tracks <= 0 else { return nil }
        switch type.lowercased() {
        case "subtitle": return "This video has no subtitles."
        case "audio": return "This video has no audio tracks."
        default: return nil
        }
    }
}

/// PLAY-03 (TV review T-3): controls shown again within 30 s of hiding focus the action that last had focus.
public enum ControlsLastAction {
    public static let windowMs: Int64 = 30_000
    /// A clock that went backwards, or no recorded hide, never restores.
    public static func restores(hiddenAtMs: Int64?, nowMs: Int64) -> Bool {
        guard let hiddenAtMs else { return false }
        let elapsed = nowMs - hiddenAtMs
        return elapsed >= 0 && elapsed <= windowMs
    }
}

/// TOUCH-11 (phone review P-4): Back closes an open sheet or menu first; otherwise it leaves the player, whether or not
/// the controls are showing. Hiding the controls is a tap on the video, never a Back.
public enum TouchPlayerBack {
    public static func action(sheetOpen: Bool, menuOpen: Bool) -> String {
        if sheetOpen { return "close-sheet" }
        if menuOpen { return "close-menu" }
        return "exit"
    }
}
