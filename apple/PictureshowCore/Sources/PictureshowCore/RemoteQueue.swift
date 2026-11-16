// SPDX-License-Identifier: MPL-2.0
import Foundation

extension RemoteReducers {
    static func queue(_ raw: [String: Any], _ event: [String: Any]) throws -> [String: Any] {
        try fields(raw, ["queue", "sequence", "current", "upNext"], required: ["queue"])
        var state = raw
        let initialQueue = try object(raw["queue"])
        // A stale revision normalizes and validates without proposing a queue mutation.
        let normalized = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: initialQueue, event: ["op": "position", "revision": "-1", "generation": initialQueue["generation"] ?? "0", "ticks": "0"])
        var q = try object(normalized["queue"])
        var sequence = try raw["sequence"].map(long) ?? 0
        if let current = raw["current"], !(current is NSNull) { _ = try string(current) }
        if let upNext = raw["upNext"] { _ = try strings(upNext) }
        let op = try string(event["op"])
        let keys: [String: Set<String>] = ["open": ["op", "items"], "add-next": ["op", "items", "watching"], "add-last": ["op", "items", "watching"], "previous": ["op"], "observed": ["op", "item"], "clear": ["op"]]
        guard let allowed = keys[op] else { throw failure("unknown remote queue event \(op)") }; try fields(event, allowed, required: ["op"])
        let items = try event["items"].map(strings) ?? []
        func edit(_ operation: String, entries: [String] = [], automatic: [String] = []) throws {
            func made(_ items: [String]) -> [[String: Any]] { items.map { media in sequence &+= 1; return ["id": "r\(sequence)", "media": media] } }
            var e: [String: Any] = ["op": operation, "revision": q["revision"] ?? "0", "generation": q["generation"] ?? "0"]
            if ["play-now", "play-next", "append"].contains(operation) { e["entries"] = made(entries); e["automatic"] = made(automatic) }
            if operation == "next" { e["completion"] = q["completion"] ?? "0" }
            let reduced = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: q, event: e)
            q = try object(reduced["queue"])
        }
        func open(_ items: [String]) throws {
            guard let first = items.first else { return }
            let rest = items.enumerated().compactMap { index, item in index > 0 && item != items[index - 1] ? item : nil }
            try edit("play-now", entries: [first], automatic: rest)
        }
        func clear() throws { q = ["server": try string(q["server"]), "viewer": try string(q["viewer"]), "revision": String(l(q, "revision") &+ 1)] }
        switch op {
        case "open": try open(items)
        case "add-next", "add-last":
            let watching = try string(event["watching"])
            if let named = items.first {
                if s(state, "current") != watching { try open([watching]) }
                let entries = q["entries"] as? [[String: Any]] ?? [], order = a(q, "order"), automatic = a(q, "automatic")
                let media = Dictionary(uniqueKeysWithValues: entries.map { (s($0, "id")!, s($0, "media")!) })
                let current = order.firstIndex(of: s(q, "current") ?? "") ?? -1
                let upcoming = Array(order.dropFirst(current + 1)).filter { !automatic.contains($0) || media[$0] != named }
                let at = op == "add-next" ? 0 : (upcoming.firstIndex(where: { automatic.contains($0) }) ?? upcoming.count)
                let before = at > 0 ? upcoming[at - 1] : s(q, "current"), after = at < upcoming.count ? upcoming[at] : nil
                let repeats = before.flatMap { media[$0] } == named || after.flatMap { media[$0] } == named
                try edit(op == "add-next" ? "play-next" : "append", entries: repeats ? [] : [named], automatic: Array(items.dropFirst()))
            }
        case "previous": if s(q, "current") != nil { try edit("previous") }
        case "observed":
            let item = try string(event["item"])
            if item == s(state, "current") { break }
            if item == a(state, "upNext").first { try edit("next") } else { try clear() }
        case "clear": try clear()
        default: throw failure("unknown remote queue event")
        }
        // Normalize even a newly-cleared/default queue through the shared pure reducer.
        if q["entries"] == nil {
            let result = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: q, event: ["op": "position", "revision": "-1", "generation": q["generation"] ?? "0", "ticks": "0"])
            q = try object(result["queue"])
        }
        let media = Dictionary(uniqueKeysWithValues: (q["entries"] as? [[String: Any]] ?? []).map { (s($0, "id")!, s($0, "media")!) })
        let order = a(q, "order"), current = s(q, "current"), index = order.firstIndex(of: current ?? "") ?? -1
        state = ["queue": q, "sequence": String(sequence), "current": current.flatMap { media[$0] } as Any? ?? null, "upNext": Array(order.dropFirst(index + 1)).compactMap { media[$0] }]
        return state
    }
}
