// SPDX-License-Identifier: MPL-2.0
import Foundation
import CoreFoundation

/// Pure Cast policy shared by native sender adapters and parity vectors. No SDK or network side effects.
public enum CastPolicies {
    public static let readyToleranceTicks: Int64 = 150_000_000
    public static let seekToleranceTicks: Int64 = 50_000_000
    public static func capability(_ input: [String: Any]) throws -> [String: Any] {
        let d = try CastJSON.normalize(input, defaults: ["surface":"touch", "osSupported":true,"browserSupported":true,"services":"ok","viewerEnabled":true,"localNetworkAllowed":true,"discovery":"idle","targets":0])
        var state = "idle", visible = true, discover = false
        if d["surface"] as? String == "tv" { state = "tv-interface"; visible = false }
        else if d["surface"] as? String == "browser" && d["browserSupported"] as? Bool == false { state = "browser-unsupported" }
        else if d["osSupported"] as? Bool == false { state = "os-too-old" }
        else if d["services"] as? String == "missing" { state = "services-missing" }
        else if d["services"] as? String != "ok" { state = "services-update-required" }
        else if d["viewerEnabled"] as? Bool == false { state = "viewer-disabled" }
        else if d["localNetworkAllowed"] as? Bool == false { state = "permission-denied" }
        else { discover = true; state = (d["targets"] as! Int) > 0 ? "ready" : d["discovery"] as? String == "searching" ? "searching" : d["discovery"] as? String == "finished" ? "no-targets" : "idle" }
        return ["state":state,"visible":visible,"canDiscover":discover,"jellyfinRemote":true]
    }
    public static func controls(_ input: [String: Any]) throws -> [String: Any] {
        let d = try CastJSON.normalize(input, defaults: ["commands":[String](),"durationTicks":NSNull(),"live":false,"seekableStartTicks":NSNull(),"seekableEndTicks":NSNull(),"tracks":[Any](),"activeTracks":[Int]()])
        let commands = try CastJSON.strings(d["commands"]), active = try CastJSON.ints(d["activeTracks"])
        let duration = try CastJSON.optionalLong(d["durationTicks"]), start = try CastJSON.optionalLong(d["seekableStartTicks"]), end = try CastJSON.optionalLong(d["seekableEndTicks"])
        let live = d["live"] as! Bool || (duration ?? 0) <= 0
        let seek = commands.contains("seek") && (!live || (start != nil && end != nil && end! > start!))
        let volume = commands.contains("volume")
        let tracks = try CastJSON.objects(d["tracks"]).map { raw -> [String:Any] in
            let t = try CastJSON.normalize(raw, defaults:["id":0,"type":"","label":"","available":true], required:["id","type","label"])
            let id = t["id"] as! Int, available = t["available"] as! Bool
            return ["id":id,"type":t["type"]!,"label":t["label"]!,"selectable":available && commands.contains("tracks"),"selected":available && active.contains(id)]
        }
        return ["pause":commands.contains("pause"),"seek":seek,"stop":commands.contains("stop"),"volume":volume,"volumeReason":volume ? NSNull() : "receiver-fixed-volume", "timeline":live ? "live":"vod", "seekStartTicks":seek ? String(live ? start! : 0) as Any : NSNull(),"seekEndTicks":seek ? String(live ? end! : duration!) as Any : NSNull(),"tracks":tracks]
    }
    public static func reach(_ input:[String:Any]) throws -> [String:Any] {
        let d = try CastJSON.normalize(input,defaults:["baseUrl":"","vpn":[String:Any]()],required:["baseUrl"])
        let vpn = try CastJSON.normalize(CastJSON.object(d["vpn"]),defaults:["active":false,"ranges":[String]()])
        let url = d["baseUrl"] as! String
        let parts = url.components(separatedBy:"://"), scheme = parts.count > 1 ? parts[0].lowercased() : ""
        let authority = parts.count > 1 ? parts.dropFirst().joined(separator:"://").components(separatedBy:CharacterSet(charactersIn:"/?#"))[0].components(separatedBy:"@").last! : ""
        let host = (authority.hasPrefix("[") ? String(authority.dropFirst()).components(separatedBy:"]")[0] : authority.components(separatedBy:":")[0]).lowercased()
        func result(_ category:String,_ allowed:Bool = false) -> [String:Any] { ["category":category,"allowed":allowed] }
        guard ["http","https"].contains(scheme), !host.isEmpty else { return result("invalid") }
        let v = ipv4(host), v6 = host.contains(":")
        if host == "localhost" || host.hasSuffix(".localhost") || host == "::1" || v.map({$0 >> 24 == 127}) == true { return result("loopback") }
        if v.map({$0 >> 16 == 0xA9FE}) == true || (v6 && ["fe8","fe9","fea","feb"].contains(where:host.hasPrefix)) { return result("link-local") }
        if vpn["active"] as! Bool {
            if let v, try CastJSON.strings(vpn["ranges"]).contains(where: { inRange(v,$0) }) { return result("sender-vpn") }
            if v.map({$0 >> 22 == 401}) == true { return result("sender-vpn") }
            if v == nil { return result("vpn-possible",true) }
        }
        let isPrivate = host.hasSuffix(".local") || host.hasSuffix(".lan") || host.hasSuffix(".home.arpa") || (!host.contains(".") && !v6) || (v6 && (host.hasPrefix("fc") || host.hasPrefix("fd"))) || v.map({$0 >> 24 == 10 || $0 >> 20 == 0xAC1 || $0 >> 16 == 0xC0A8}) == true
        return result((isPrivate ? "private-":"public-")+scheme,true)
    }
    private static func ipv4(_ host:String) -> UInt32? {
        let parts = host.split(separator:".",omittingEmptySubsequences:false)
        guard parts.count == 4 else { return nil }
        var result:UInt32 = 0
        for p in parts { guard let n = UInt32(p), n <= 255, String(n) == p else { return nil }; result = result << 8 | n }
        return result
    }
    private static func inRange(_ address:UInt32,_ cidr:String) -> Bool {
        let p = cidr.components(separatedBy:"/")
        guard let n = ipv4(p[0]), let prefix = Int(p.count > 1 ? p[1]:"32"), (0...32).contains(prefix) else { return false }
        return prefix == 0 || address >> (32-prefix) == n >> (32-prefix)
    }
    public static func reattach(_ input:[String:Any]) throws -> [String:Any] {
        let d = try CastJSON.normalize(input,defaults:["record":NSNull(),"server":"","viewer":"","mediaSession":"-1","playerState":"idle","customData":[String:Any](),"contentUrl":NSNull(),"route":NSNull(),"idleReason":NSNull(),"emptyBefore":false],required:["server","viewer"])
        func result(_ decision:String,_ reason:String? = nil,_ generation:Any = NSNull(),_ entry:Any = NSNull()) -> [String:Any] { ["decision":decision,"reason":reason as Any? ?? NSNull(),"generation":generation,"entry":entry] }
        if d["record"] is NSNull { return result("foreign","no-record") }
        let raw = try CastJSON.object(d["record"])
        let r = try CastJSON.normalize(raw,defaults:["nonce":"","server":"","viewer":"","route":"","device":"","session":"","generation":"0","items":[Any](),"title":NSNull(),"deviceName":NSNull()],required:["nonce","server","viewer","route","device","session","generation","items"])
        let items = try CastJSON.objects(r["items"]).map { try CastJSON.normalize($0,defaults:["entry":"","itemId":"","mediaSourceId":NSNull(),"playSessionId":NSNull(),"method":"DirectPlay","url":""],required:["entry","itemId"]) }
        let custom = try CastJSON.object(d["customData"])
        for value in custom.values { guard value is String else { throw CastJSON.error("customData values must be strings") } }
        if r["server"] as? String != d["server"] as? String || r["viewer"] as? String != d["viewer"] as? String { return result("foreign","other-account") }
        if (r["route"] as! String).trimmingCharacters(in:.whitespacesAndNewlines).isEmpty || r["route"] as? String != d["route"] as? String { return result("foreign","route") }
        let session = try CastJSON.long(d["mediaSession"])
        if session < 0 && d["idleReason"] is NSNull && d["emptyBefore"] as? Bool == false { return result("recheck") }
        if session < 0 || d["playerState"] as? String == "idle" { return result("ended") }
        if custom["nonce"] as? String != r["nonce"] as? String { return result("foreign","nonce") }
        guard let claimed = (custom["generation"] as? String).flatMap(Int64.init), claimed > 0, claimed <= 9_007_199_254_740_992, claimed == (try CastJSON.long(r["generation"])) else { return result("foreign","generation") }
        guard let entry = custom["entry"] as? String, let item = items.first(where:{$0["entry"] as? String == entry}) else { return result("foreign","entry") }
        guard let url = d["contentUrl"] as? String, let base = item["url"] as? String, !base.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, url.components(separatedBy:"?")[0] == base else { return result("foreign","url") }
        return result("adopt",nil,r["generation"]!,entry)
    }
    public static func session(state input:[String:Any],event:[String:Any]) throws -> [String:Any] {
        var state = try CastJSON.normalize(input,defaults:["records":[Any](),"effect":"none","reason":NSNull()])
        func binding(_ raw:Any?) throws -> [String:Any] {
            try CastJSON.normalize(CastJSON.object(raw),defaults:["server":"","viewer":"","device":"","sender":"","generation":"0","phase":"creating","secretRef":NSNull()],required:["server","viewer","device","sender","generation"])
        }
        state["records"] = try CastJSON.objects(state["records"]).map { try binding($0) }
        _ = try binding(event["binding"])
        for key in ["returnedViewer","secretRef"] { if let v = event[key], !(v is NSNull) { _ = try CastJSON.string(v) } }
        try CastJSON.fields(event,allowed:["op","binding","returnedViewer","secretRef"],required:["op","binding"])
        let op = try CastJSON.string(event["op"])
        let ends:Set<String> = ["stop-confirmed","receiver-ended","load-failed","load-timeout","cancel","play-here-unconfirmed","foreign-media","replaced"]
        let leaves:Set<String> = ["forget","viewer-change","sign-out"], keeps:Set<String> = ["stop-unconfirmed","disconnect","link-lost","link-suspended"]
        var e = event
        if op == "provision" { e["op"] = "create" }
        else if ends.contains(op) || leaves.contains(op) {
            try CastJSON.fields(state,allowed:["records","effect","reason"])
            let b = try CastJSON.object(event["binding"])
            try CastJSON.fields(b,allowed:["server","viewer","device","sender","generation","phase","secretRef"],required:["server","viewer","device","sender","generation"])
            let records = try CastJSON.objects(state["records"] ?? [])
            guard let r = records.first(where:{ record in ["server","viewer","device"].allSatisfy { record[$0] as? String == b[$0] as? String } }) else { var s = state; s["effect"] = "none"; s["reason"] = NSNull(); return s }
            let recordGeneration = try CastJSON.long(r["generation"]), bindingGeneration = try CastJSON.long(b["generation"])
            if !leaves.contains(op) && (r["sender"] as? String != b["sender"] as? String || recordGeneration != bindingGeneration) { var s = state; s["effect"] = "none"; s["reason"] = "stale-binding"; return s }
            e = ["op":"end","binding":r.filter{ ["server","viewer","device","sender","generation"].contains($0.key) }]
        } else if keeps.contains(op) { try CastJSON.fields(state,allowed:["records","effect","reason"]); var s = state; s["effect"] = "keep"; s["reason"] = NSNull(); return s }
        else if !["issued","persisted","logout-ok","logout-failed","reconnect"].contains(op) { throw CastJSON.error("Unknown cast session op \(op)") }
        return try ControlReducers.reduce(suite:"session-owner",operation:"temporary",state:state,event:e)
    }
}

internal enum CastJSON {
    static func error(_ message:String) -> NSError { NSError(domain:"CastPolicy",code:1,userInfo:[NSLocalizedDescriptionKey:message]) }
    static func fields(_ d:[String:Any],allowed:Set<String>,required:Set<String> = []) throws {
        guard Set(d.keys).isSubset(of:allowed), required.isSubset(of:Set(d.keys)) else { throw error("unknown or missing fields: \(Set(d.keys).subtracting(allowed)); required \(required.subtracting(d.keys))") }
    }
    static func object(_ raw:Any?) throws -> [String:Any] { guard let d = raw as? [String:Any] else { throw error("expected object") }; return d }
    static func objects(_ raw:Any?) throws -> [[String:Any]] { guard let d = raw as? [[String:Any]] else { throw error("expected object array") }; return d }
    static func string(_ raw:Any?) throws -> String { guard let s = raw as? String else { throw error("expected string") }; return s }
    static func strings(_ raw:Any?) throws -> [String] { guard let s = raw as? [String] else { throw error("expected string array") }; return s }
    static func ints(_ raw:Any?) throws -> [Int] { guard let a = raw as? [Any] else { throw error("expected integer array") }; return try a.map { try small($0) } }
    static func boolean(_ raw:Any?) throws -> Bool { guard let n = raw as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw error("expected boolean") }; return n.boolValue }
    static func small(_ raw:Any?) throws -> Int { guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.rounded(.towardZero) == n.doubleValue, n.doubleValue >= Double(Int32.min), n.doubleValue <= Double(Int32.max) else { throw error("expected integer") }; return n.intValue }
    static func long(_ raw:Any?) throws -> Int64 { guard let s = raw as? String, let n = Int64(s), String(n) == s else { throw error("expected decimal Int64 string") }; return n }
    static func optionalLong(_ raw:Any?) throws -> Int64? { guard let raw, !(raw is NSNull) else { return nil }; return try long(raw) }
    static func normalize(_ d:[String:Any],defaults:[String:Any],required:Set<String> = []) throws -> [String:Any] {
        try fields(d,allowed:Set(defaults.keys),required:required)
        var result = defaults
        for (k,v) in d {
            let base = defaults[k]!
            if base is NSNull {
                if !(v is NSNull) {
                    if ["durationTicks","seekableStartTicks","seekableEndTicks","returnTicks","mediaSession"].contains(k) { _ = try long(v) }
                    else if k == "returnPaused" { _ = try boolean(v) }
                    else if ["audio","subtitle"].contains(k) { _ = try small(v) }
                    else if ["entry","idleReason","contentUrl","route","title","deviceName","stopPurpose","recovery","notice","reason","mediaSourceId","playSessionId","secretRef"].contains(k) { _ = try string(v) }
                }
                result[k] = v; continue
            }
            if let n = base as? NSNumber {
                if CFGetTypeID(n) == CFBooleanGetTypeID() { guard let x = v as? NSNumber, CFGetTypeID(x) == CFBooleanGetTypeID() else { throw error("\(k): expected boolean") } }
                else { _ = try small(v) }
            } else if base is String { _ = try string(v); if k.hasSuffix("Ticks") || ["generation","mediaSession","id","nowMs","sequence"].contains(k) { _ = try long(v) } }
            else if base is [String:Any] { _ = try object(v) }
            else if base is [Any] { guard v is [Any] else { throw error("\(k): expected array") } }
            result[k] = v
        }
        return result
    }
}
