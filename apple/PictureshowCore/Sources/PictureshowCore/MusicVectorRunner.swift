// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Strict shared fixture adapter. `input` is the operation input, or {state,event} for a trace step.
/// It computes actual values only; expected projections belong to the independent test harness.
public enum MusicVectorRunner {
    public static let operations: [String: Set<String>] = [
        "music-catalog": ["section", "artist-albums", "genre-tracks", "order", "discs", "artist-line", "page", "list-state", "playable", "image"],
        "music-queue": ["queue", "entries", "permutation", "window", "plan", "decision"],
        "music-session": ["session"], "music-reports": ["report"],
        "music-quality": ["ceiling", "profile", "output"],
        "music-playlists": ["playlist", "create-body", "rename-body", "id-batches"]
    ]
    public static func execute(suite: String, operation: String, input i: [String: Any], context: [String: Any]) throws -> Any {
        guard operations[suite]?.contains(operation) == true else { throw MusicInput.failure("unknown music suite/operation \(suite)/\(operation)") }
        // A caller may carry a source label, but no expected result or fixture branch enters production logic.
        if ["queue", "session", "report", "playlist"].contains(operation) {
            try MusicInput.fields(i, ["state", "event"], required: ["state", "event"])
            let state = try MusicInput.object(i["state"]), event = try MusicInput.object(i["event"])
            switch operation {
            case "queue": return try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: state, event: event)
            case "session": return try MusicReducers.session(state: state, event: event)
            case "report": return try MusicReducers.report(state: state, event: event)
            default: return try MusicReducers.playlist(state: state, event: event)
            }
        }
        switch operation {
        case "section":
            try MusicInput.fields(i, ["section", "library", "start"], required: ["section", "library"])
            guard let section = MusicSection(rawValue: try MusicInput.string(i, "section")) else { throw MusicInput.failure("unknown music section") }
            return MusicCatalog.section(section, library: try MusicInput.string(i, "library"), start: try MusicInput.int(i, "start", default: 0)).json
        case "artist-albums":
            try MusicInput.fields(i, ["artist", "role", "library"], required: ["artist", "role"])
            guard let role = MusicArtistRole(rawValue: try MusicInput.string(i, "role")) else { throw MusicInput.failure("unknown artist role") }
            return MusicCatalog.artist(try MusicInput.string(i, "artist"), role: role, library: try MusicInput.optionalString(i, "library")).json
        case "genre-tracks":
            try MusicInput.fields(i, ["genre", "library", "start"], required: ["genre", "library"])
            return MusicCatalog.genre(try MusicInput.string(i, "genre"), library: try MusicInput.string(i, "library"), tracks: true, start: try MusicInput.int(i, "start", default: 0)).json
        case "order", "discs", "playable":
            try MusicInput.fields(i, ["tracks"], required: ["tracks"])
            let tracks = try MusicInput.decode([MusicTrack].self, i["tracks"]!)
            if operation == "order" { return MusicCatalog.order(tracks).map(\.id) }
            if operation == "playable" { return tracks.filter(\.isAvailable).map(\.id) }
            return MusicCatalog.discs(tracks).map(\.json)
        case "artist-line":
            try MusicInput.fields(i, ["track", "albumArtist"], required: ["track"])
            let track = try MusicInput.decode(MusicTrack.self, i["track"]!)
            return try track.artistLine(albumCredit: MusicInput.optionalString(i, "albumArtist")) as Any? ?? NSNull()
        case "page":
            try MusicInput.fields(i, ["pages"], required: ["pages"])
            var page = MusicPageState()
            for p in try MusicInput.objects(i, "pages") { try MusicInput.fields(p, ["ids", "total"], required: ["ids", "total"]); page.merge(ids: try MusicInput.strings(p, "ids"), total: try MusicInput.int(p, "total")) }
            return ["ids": page.ids, "offset": page.offset, "total": page.total, "ended": page.ended]
        case "list-state":
            try MusicInput.fields(i, ["loading", "items", "kind", "status"], required: ["loading", "items"])
            let kind = try MusicInput.optionalString(i, "kind")
            guard kind == nil || ["INVALID_ADDRESS", "NOT_JELLYFIN", "UNSUPPORTED_VERSION", "INVALID_CREDENTIALS", "NETWORK", "SERVER", "EXPIRED", "INVALID_RESPONSE", "LOCAL_STORAGE"].contains(kind!) else { throw MusicInput.failure("unknown error kind") }
            return try MusicCatalog.listState(loading: MusicInput.bool(i, "loading"), items: MusicInput.int(i, "items"), errorKind: kind, status: i["status"] == nil ? nil : MusicInput.int(i, "status"))
        case "image":
            try MusicInput.fields(i, ["item"], required: ["item"])
            return try MusicInput.decode(MusicTrack.self, i["item"]!).imageURL(server: URL(string: "http://s")!, width: 100)?.absoluteString as Any? ?? NSNull()
        case "entries":
            try MusicInput.fields(i, ["media", "first"], required: ["media", "first"])
            return try MusicQueue.entries(MusicInput.strings(i, "media"), firstSequence: MusicInput.long(i, "first"))
        case "permutation":
            try MusicInput.fields(i, ["ids", "seed"], required: ["ids", "seed"])
            return try MusicQueue.permutation(MusicInput.strings(i, "ids"), seed: MusicInput.long(i, "seed"))
        case "window":
            // Normalization uses the queue policy without mutating the caller's queue.
            let normalized = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: i, event: ["op": "pause", "revision": "-1"])
            let q = try MusicInput.object(normalized["queue"])
            let order = try MusicInput.strings(q, "order"), current = try MusicInput.optionalString(q, "current"), mode = try MusicInput.string(q, "repeat")
            return ["window": MusicQueue.window(order: order, current: current, repeatMode: mode, failed: Set(try MusicInput.strings(q, "failed"))), "repeats": MusicQueue.outputRepeats(order: order, current: current, repeatMode: mode)]
        case "plan":
            try MusicInput.fields(i, ["held", "playing", "target"], required: ["held", "playing", "target"])
            return try MusicQueue.plan(held: MusicInput.strings(i, "held"), playing: MusicInput.optionalString(i, "playing"), target: MusicInput.strings(i, "target")).json
        case "decision":
            try MusicInput.fields(i, ["context", "queueEntries", "queueContext", "remoteAttached", "castAttached"], required: ["context", "queueEntries", "queueContext"])
            return try MusicQueue.playDecision(context: MusicInput.optionalString(i, "context"), queueEntries: MusicInput.int(i, "queueEntries"), queueContext: MusicInput.optionalString(i, "queueContext"), remoteAttached: MusicInput.bool(i, "remoteAttached"), castAttached: MusicInput.bool(i, "castAttached"))
        case "id-batches":
            try MusicInput.fields(i, ["ids"], required: ["ids"])
            let batches = MusicCatalog.idBatches(try MusicInput.strings(i, "ids"))
            return ["sizes": batches.map(\.count), "firsts": batches.compactMap(\.first), "maxJoinedLength": batches.map { $0.joined(separator: ",").count }.max() ?? 0]
        case "create-body":
            try MusicInput.fields(i, ["name", "viewer", "items", "isPublic"], required: ["name", "viewer", "items"])
            return try MusicReducers.createPlaylistBody(name: MusicInput.string(i, "name"), viewer: MusicInput.string(i, "viewer"), items: MusicInput.strings(i, "items"), isPublic: MusicInput.bool(i, "isPublic"))
        case "rename-body":
            try MusicInput.fields(i, ["name"], required: ["name"])
            return try MusicReducers.renamePlaylistBody(name: MusicInput.string(i, "name"))
        case "ceiling":
            try MusicInput.fields(i, ["quality", "metered"], required: ["quality", "metered"])
            return try MusicQuality.ceiling(quality: MusicInput.string(i, "quality"), metered: MusicInput.bool(i, "metered"))
        case "profile":
            try MusicInput.fields(i, ["decoders", "ceiling"], required: ["decoders", "ceiling"])
            let decoders = try MusicInput.strings(i, "decoders")
            guard Set(decoders).isSubset(of: Set(ReferenceDeviceProfile.audioOrder)) else { throw MusicInput.failure("unknown audio decoder") }
            let p = try MusicQuality.referenceDeviceProfile(decoders: Set(decoders), ceiling: MusicInput.int(i, "ceiling"))
            let direct = p["DirectPlayProfiles"] as! [[String: Any]], conversion = p["TranscodingProfiles"] as! [[String: Any]]
            return ["MaxStreamingBitrate": p["MaxStreamingBitrate"]!, "MaxStaticMusicBitrate": p["MaxStaticMusicBitrate"]!, "MusicStreamingTranscodingBitrate": p["MusicStreamingTranscodingBitrate"]!, "direct": direct.map { "\($0["Container"]!):\($0["AudioCodec"]!)" }, "conversion": conversion.map { "\($0["Container"]!):\($0["AudioCodec"]!):\($0["Protocol"]!):\($0["VideoCodec"]!)" }]
        case "output":
            try MusicInput.fields(i, ["source", "method", "route", "ceiling", "reasons"], required: ["source", "method", "route", "ceiling"])
            let source = try MusicInput.object(i["source"])
            let streams = source["MediaStreams"] == nil ? [] : try MusicInput.objects(source, "MediaStreams")
            let audio = streams.first { $0["Type"] as? String == "Audio" }
            let method = try MusicInput.string(i, "method")
            guard ["DirectPlay", "DirectStream", "Transcode"].contains(method) else { throw MusicInput.failure("unknown play method") }
            return try MusicQuality.output(codec: audio.flatMap { try MusicInput.optionalString($0, "Codec") }, container: MusicInput.optionalString(source, "Container"), method: method, route: MusicInput.string(i, "route"), ceiling: MusicInput.int(i, "ceiling"), reasons: MusicInput.strings(i, "reasons", default: [])).json
        default: throw MusicInput.failure("unknown music operation")
        }
    }
}
