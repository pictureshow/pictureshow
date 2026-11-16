// SPDX-License-Identifier: MPL-2.0
import Foundation

/// Serialized Cast state machine. Effects are instructions to the platform sender, never network actions here.
public enum CastHandoff {
    public typealias Object = [String:Any]
    static let null = NSNull()
    static func obj(_ d:Object,_ key:String) -> Object? { d[key] as? Object }
    static func str(_ d:Object,_ key:String) -> String? { d[key] as? String }
    static func num(_ d:Object,_ key:String,_ fallback:Int64 = 0) -> Int64 { (d[key] as? String).flatMap(Int64.init) ?? fallback }
    static func intent(_ raw:Any?) throws -> Object {
        let d = try CastJSON.normalize(raw == nil || raw is NSNull ? [:] : CastJSON.object(raw),defaults:["entry":null,"ticks":"0","paused":true,"queue":[String](),"repeat":"off","shuffle":[String](),"audio":null,"subtitle":null])
        _ = try CastJSON.strings(d["queue"]); _ = try CastJSON.strings(d["shuffle"])
        return d
    }
    static func identity(_ raw:Any?) throws -> Object {
        let d = try CastJSON.object(raw)
        let keys:Set<String> = ["server","viewer","device","session","generation","kind"]
        try CastJSON.fields(d,allowed:keys,required:keys)
        for k in keys { _ = try CastJSON.string(d[k]) }; _ = try CastJSON.long(d["generation"])
        return d
    }
    static func owner(_ input:Object) throws -> Object {
        let id = try identity(input["owner"])
        var d = try CastJSON.normalize(input,defaults:["owner":id,"local":id,"candidate":null,"phase":"active","intent":try intent(nil),"route":"native","generation":id["generation"]!,"reportSequence":"-1","effect":"none","reason":null],required:["owner"])
        d["owner"] = id; d["local"] = try identity(d["local"]); d["intent"] = try intent(d["intent"])
        if let c = d["candidate"], !(c is NSNull) { d["candidate"] = try identity(c) }
        return d
    }
    static func observation(_ input:Object) throws -> Object {
        var d = try CastJSON.normalize(input,defaults:["mediaSession":"-1","generation":"-1","playerState":"idle","idleReason":null,"ticks":"0","durationTicks":null,"entry":null,"activeTracks":[Int](),"queue":[String](),"commands":["play","pause","seek","stop"]])
        if let n = try CastJSON.optionalLong(d["durationTicks"]) { d["durationTicks"] = String(n) }
        _ = try CastJSON.ints(d["activeTracks"]); _ = try CastJSON.strings(d["commands"]); _ = try CastJSON.strings(d["queue"])
        return d
    }
    static func normalize(_ input:Object) throws -> Object {
        var d = try CastJSON.normalize(input,defaults:["owner":[String:Any](),"link":"idle","mediaSession":null,"observed":null,"commands":null,"stop":"none","stopPurpose":null,"cleanup":"none","returnPaused":null,"returnTicks":null,"recovery":null,"notice":null,"emptyStatus":false,"effect":"none","reason":null],required:["owner"])
        d["owner"] = try owner(CastJSON.object(d["owner"]))
        if let raw = d["observed"], !(raw is NSNull) { d["observed"] = try observation(CastJSON.object(raw)) }
        _ = try CastJSON.optionalLong(d["mediaSession"]); _ = try CastJSON.optionalLong(d["returnTicks"])
        if let raw = d["commands"], !(raw is NSNull) {
            let c = try CastJSON.object(raw)
            _ = try identity(c["owner"])
            var normalized = try commandReduce(c,["op":"http","owner":c["owner"]!,"id":"-1"])
            normalized["effect"] = c["effect"] ?? "none"; normalized["rejection"] = c["rejection"] ?? null
            d["commands"] = normalized
        }
        return d
    }
    static func ownerReduce(_ d:Object,_ event:Object) throws -> Object {
        try ControlReducers.reduce(suite:"session-owner",operation:"owner",state:d,event:event)
    }
    static func commandReduce(_ d:Object,_ event:Object) throws -> Object {
        try ControlReducers.reduce(suite:"session-command",operation:"command",state:d,event:event)
    }
    static func viaOwner(_ s:Object,_ event:Object,_ next:(Object) throws -> Object = {$0}) throws -> Object {
        let after = try ownerReduce(obj(s,"owner")!,event)
        var result = s; result["owner"] = after
        if let reason = str(after,"reason"), reason != "remote-may-continue" { result["reason"] = reason; return result }
        result["effect"] = after["effect"]!; result["reason"] = after["reason"]!
        return try next(result)
    }
    static func ownerID(_ s:Object) -> Object { obj(obj(s,"owner")!,"owner")! }
    static func candidate(_ s:Object) -> Object? { obj(obj(s,"owner")!,"candidate") }
    static func phase(_ s:Object) -> String { str(obj(s,"owner")!,"phase")! }
    static func withOwnerIntent(_ s:Object,_ update:(inout Object)->Void) -> Object {
        var result = s, o = obj(s,"owner")!, i = obj(o,"intent")!; update(&i); o["intent"] = i; result["owner"] = o; return result
    }
    static func onItem(_ s:Object,_ entry:String?) -> Object {
        guard let entry else { return s }; return withOwnerIntent(s){$0["entry"] = entry}
    }
    static func atLastPosition(_ s:Object) -> Object {
        guard let o = obj(s,"observed") else { return s }
        return withOwnerIntent(s) { i in i["entry"] = str(o,"entry") ?? i["entry"]!; i["ticks"] = o["ticks"]!; i["paused"] = paused(o) ?? i["paused"]! }
    }
    static func near(_ a:Int64,_ b:Int64,_ tolerance:Int64) -> Bool { let (difference, overflow) = a.subtractingReportingOverflow(b); return !overflow && difference.magnitude <= UInt64(tolerance) }
    static func paused(_ o:Object) -> Bool? { str(o,"playerState") == "playing" ? false : str(o,"playerState") == "paused" ? true : nil }
    static func select(_ s:Object,_ e:Object) throws -> Object {
        var event:Object = ["op":"select","identity":e["identity"] ?? null]
        if let i = e["intent"] { event["intent"] = i }
        return try viaOwner(s,event) { x in
            var x = x
            for k in ["mediaSession","observed","commands","stopPurpose","returnPaused","returnTicks","recovery","notice"] { x[k] = null }
            x["stop"] = "none"; x["emptyStatus"] = false; return x
        }
    }
    static func pick(_ s:Object,_ e:Object) throws -> Object {
        let o = obj(s,"owner")!
        guard let target = obj(e,"identity") else { var s = s; s["reason"] = "invalid-target"; return s }
        var s = s
        if candidate(s) != nil || !["active","failed","disconnected"].contains(phase(s)) { s["reason"] = "handoff-busy"; return s }
        if str(ownerID(s),"kind") != "cast" || phase(s) == "failed" { return try select(s,e) }
        let sameReceiver = str(ownerID(s),"device") == str(target,"device")
        let wanted = obj(e,"intent").flatMap{str($0,"entry")}
        let sameEntry = wanted != nil && wanted == (obj(s,"observed").flatMap{str($0,"entry")} ?? obj(o,"intent").flatMap{str($0,"entry")})
        if phase(s) == "disconnected" {
            if !sameReceiver { s["reason"] = "detached-elsewhere" }
            else { s["effect"] = "reconnect"; s["reason"] = sameEntry ? "already-casting":"rejoin-to-replace" }; return s
        }
        if str(s,"link") != "connected" { s["reason"] = "link-not-ready"; return s }
        if sameReceiver && sameEntry { s["effect"] = "show-controls"; s["reason"] = "already-casting"; return s }
        var previous = o; previous["owner"] = o["local"]!; previous["phase"] = "active"; previous["reportSequence"] = "-1"
        var base = s; base["owner"] = previous
        var selected = try select(base,e)
        if let r = str(selected,"reason") { s["reason"] = r; return s }
        selected["link"] = sameReceiver ? "connected":"idle"; selected["cleanup"] = "none"; selected["effect"] = sameReceiver ? "replace-on-link":"replace-elsewhere"; return selected
    }
    static func freshCommands(_ owner:Object,_ o:Object) throws -> Object {
        try commandReduce(["owner":owner,"capabilities":o["commands"]!,"observed":try intent(["entry":o["entry"]!,"ticks":o["ticks"]!,"paused":paused(o) ?? true]),"durationTicks":String(maxDuration(o))],["op":"http","owner":owner,"id":"-1"])
    }
    static func maxDuration(_ o:Object) -> Int64 { num(o,"durationTicks") > 0 ? num(o,"durationTicks") : Int64.max }
    static func observeCommands(_ c:Object,_ o:Object,_ now:Any) throws -> Object {
        var c = c; let duration = maxDuration(o)
        if c["fresh"] as? Bool == false {
            c["durationTicks"] = String(duration)
            return try commandReduce(c,["op":"refresh","owner":c["owner"]!,"value":String(min(max(0,num(o,"ticks")),duration)),"entry":o["entry"]!,"paused":paused(o) ?? (obj(c,"observed")!["paused"] as! Bool),"capabilities":o["commands"]!])
        }
        if let pending = obj(c,"pending") {
            let ticks = num(o,"ticks"), target = num(pending,"ticks")
            let landed = !(pending["ticks"] is NSNull) && pending["ticks"] != nil && near(ticks,target,CastPolicies.seekToleranceTicks)
            c = try commandReduce(c,["op":"observe","owner":c["owner"]!,"id":pending["id"]!,"nowMs":now,"value":String(min(max(0,landed ? target:ticks),num(c,"durationTicks",Int64.max))),"entry":o["entry"]!,"paused":paused(o) as Any? ?? null,"stopped":false])
        }
        var observed = obj(c,"observed")!; observed["ticks"] = o["ticks"]!; observed["entry"] = o["entry"]!; observed["paused"] = paused(o) ?? observed["paused"]!; c["observed"] = observed
        c["durationTicks"] = String(duration); c["capabilities"] = o["commands"]!; return c
    }
    static func beginStop(_ s:Object,_ purpose:String) throws -> Object {
        guard str(ownerID(s),"kind") == "cast" else { var s = s; s["reason"] = "stale-owner"; return s }
        return try viaOwner(s,["op":"return","identity":ownerID(s)]) { x in
            var x = x
            if x["returnTicks"] is NSNull { x = onItem(x,obj(s,"observed").flatMap{str($0,"entry")}) }
            x["stop"] = "sent"; x["stopPurpose"] = purpose; x["cleanup"] = "pending"
            if x["returnPaused"] is NSNull { x["returnPaused"] = obj(s,"observed").flatMap(paused) ?? obj(obj(s,"owner")!,"intent")!["paused"]! }
            if x["returnTicks"] is NSNull { x["returnTicks"] = obj(s,"observed")?["ticks"] ?? obj(obj(s,"owner")!,"intent")!["ticks"]! }
            return x
        }
    }
    static func stopUnconfirmed(_ s:Object) throws -> Object {
        try viaOwner(s,["op":"remote-stopped","identity":ownerID(s),"confirmed":false]) { x in var x = x; x["stop"] = "unknown"; x["effect"] = str(s,"stopPurpose") == "here" ? "require-choice":"stop-unknown"; return x }
    }
    static func stopConfirmed(_ s:Object) throws -> Object {
        let returning = phase(s) == "returning" ? s : try viaOwner(s,["op":"return","identity":ownerID(s)])
        return try viaOwner(returning,["op":"remote-stopped","identity":ownerID(s),"confirmed":true]) { x in var x = x; x["stop"] = "confirmed"; x["cleanup"] = "pending"; x["effect"] = str(s,"stopPurpose") == "here" ? "resume-here":"cleanup"; x["observed"] = null; return x }
    }
    static func endedNotice(_ reason:String?) -> String { reason == "finished" ? "receiver-finished" : reason == "error" ? "receiver-error":"receiver-stopped" }
    static func receiverEnded(_ s:Object,_ notice:String,_ ticks:Any? = nil,_ entry:String? = nil) throws -> Object {
        var x = onItem(s,entry ?? obj(s,"observed").flatMap{str($0,"entry")})
        x["stopPurpose"] = "ended"; x["returnPaused"] = true; x["returnTicks"] = ticks ?? obj(s,"observed")?["ticks"] ?? obj(obj(s,"owner")!,"intent")!["ticks"]!
        x = try stopConfirmed(x); x["notice"] = notice; return x
    }
    static func observe(_ state:Object,_ e:Object) throws -> Object {
        var s = state
        guard let raw = obj(e,"observation") else { s["reason"] = "invalid-observation"; return s }
        let o = try observation(raw), session = num(o,"mediaSession",-1)
        if session >= 0 { s["emptyStatus"] = false }
        let target = candidate(s) ?? ownerID(s), idle = str(o,"playerState") == "idle" || session < 0
        if str(target,"kind") != "cast" { if str(s,"stop") == "unknown" && idle { s["stop"] = "confirmed"; s["effect"] = "cleanup" } else { s["reason"] = "stale-observation" }; return s }
        if str(s,"link") != "connected" || !["awaiting-ready","returning","return-uncertain","disconnected","active"].contains(phase(s)) { s["reason"] = "stale-observation"; return s }
        let waiting = phase(s) == "awaiting-ready", stopping = ["sent","unknown"].contains(str(s,"stop")!)
        if session < 0 {
            if waiting { s["reason"] = "stale-observation"; return s }
            if stopping { return try stopConfirmed(s) }
            if o["idleReason"] is NSNull && s["emptyStatus"] as? Bool == false { s["emptyStatus"] = true; s["effect"] = "request-status"; s["reason"] = "unconfirmed-empty"; return s }
            return try receiverEnded(s,endedNotice(str(o,"idleReason")))
        }
        if num(o,"generation",-1) != num(target,"generation") {
            let old = num(o,"generation",-1) >= 0 && num(o,"generation",-1) < num(target,"generation")
            if old || waiting { s["reason"] = "stale-observation"; return s }
            if stopping { return try stopConfirmed(s) }
            return try receiverEnded(s,idle ? endedNotice(str(o,"idleReason")):"receiver-taken")
        }
        if let previous = try CastJSON.optionalLong(s["mediaSession"]), session < previous { s["reason"] = "stale-observation"; return s }
        var bound = s; bound["mediaSession"] = o["mediaSession"]!; bound["observed"] = o
        let endedAt = num(o,"ticks") > 0 ? o["ticks"]! : obj(s,"observed")?["ticks"] ?? obj(obj(s,"owner")!,"intent")!["ticks"]!
        let endedOn = str(o,"entry") ?? obj(s,"observed").flatMap{str($0,"entry")}
        switch phase(s) {
        case "awaiting-ready":
            let candidate = candidate(s)!
            switch str(o,"playerState") {
            case "idle":
                if o["idleReason"] is NSNull { bound["reason"] = "not-ready"; return bound }
                return try viaOwner(bound,["op":"fail","identity":candidate]) { x in var x = x; x["recovery"] = str(o,"idleReason") == "error" ? "receiver-load-failed":"receiver-interrupted"; x["observed"] = null; return x }
            case "playing","paused":
                let intended = obj(obj(s,"owner")!,"intent")!["ticks"]!
                if !near(num(o,"ticks"),try CastJSON.long(intended),CastPolicies.readyToleranceTicks) { bound["reason"] = "position-outside-tolerance"; return bound }
                return try viaOwner(bound,["op":"ready","identity":candidate,"ticks":intended]) { x in var x = x; x["commands"] = try freshCommands(candidate,o); x["recovery"] = null; x["effect"] = "release-local"; return x }
            default: bound["reason"] = "not-ready"; return bound
            }
        case "returning","return-uncertain": return idle ? try stopConfirmed(bound):bound
        case "disconnected":
            if idle { return try receiverEnded(bound,endedNotice(str(o,"idleReason")),endedAt,endedOn) }
            var owner = obj(bound,"owner")!; owner["phase"] = "active"; owner["effect"] = "none"; bound["owner"] = owner; bound["commands"] = try freshCommands(target,o); bound["notice"] = null; return bound
        case "active":
            if idle { return try receiverEnded(bound,endedNotice(str(o,"idleReason")),endedAt,endedOn) }
            bound["commands"] = try observeCommands(obj(bound,"commands") ?? freshCommands(target,o),o,e["nowMs"] ?? "0"); return bound
        default: return bound
        }
    }
    public static func reduce(state:Object,event:Object) throws -> Object {
        var s = try normalize(state); s["effect"] = "none"; s["reason"] = null
        let op = try CastJSON.string(event["op"])
        let eventKeys:Set<String> = ["op","identity","intent","observation","id","nowMs","value","entry","sequence","ticks","confirmed","recovery","paused"]
        try CastJSON.fields(event,allowed:eventKeys,required:["op"])
        if let id = event["identity"], !(id is NSNull) { _ = try identity(id) }
        if let i = event["intent"], !(i is NSNull) { _ = try intent(i) }
        for key in ["id","nowMs","sequence","ticks"] { if let v = event[key], !(v is NSNull) { _ = try CastJSON.long(v) } }
        if let o = event["observation"], !(o is NSNull) { _ = try observation(CastJSON.object(o)) }
        for key in ["confirmed","paused"] { if let v = event[key], !(v is NSNull) { _ = try CastJSON.boolean(v) } }
        for key in ["value","entry","recovery"] { if let v = event[key], !(v is NSNull) { _ = try CastJSON.string(v) } }
        switch op {
        case "select": return try select(s,event)
        case "pick": return try pick(s,event)
        case "link-connecting":
            if let c = candidate(s), phase(s) == "selecting" { return try viaOwner(s,["op":"connect","identity":c]) { x in var x = x; x["link"] = "connecting"; return x } }
            s["link"] = "connecting"
        case "link-connected":
            s["emptyStatus"] = false
            if let c = candidate(s) { if phase(s) == "connecting" { return try viaOwner(s,["op":"prepare","identity":c]) { x in var x = x; x["link"] = "connected"; return x } }; s["link"] = "connected"; return s }
            s["link"] = "connected"
            if str(ownerID(s),"kind") == "cast" { s = atLastPosition(s); s["mediaSession"] = null; s["observed"] = null; s["commands"] = try disconnectedCommands(s); s["effect"] = "request-status" }
        case "link-suspended":
            s["link"] = "suspended"; s = atLastPosition(s); s["observed"] = null; s["emptyStatus"] = false; s["commands"] = try disconnectedCommands(s)
        case "link-lost":
            if let c = candidate(s) { return try viaOwner(s,["op":"fail","identity":c]) { x in var x = x; x["link"] = "lost"; x["recovery"] = "link-lost"; return x } }
            if str(s,"stop") == "sent" { s = try stopUnconfirmed(s); s["link"] = "lost"; s["observed"] = null; return s }
            if str(ownerID(s),"kind") == "cast" && phase(s) == "active" { return try viaOwner(s,["op":"disconnect","identity":ownerID(s)]) { x in var x = atLastPosition(x); x["link"] = "lost"; x["observed"] = null; x["mediaSession"] = null; x["commands"] = null; x["notice"] = "link-lost"; x["effect"] = "none"; return x } }
            s["link"] = "lost"; s["observed"] = null
        case "link-failed":
            if let c = candidate(s) { return try viaOwner(s,["op":"fail","identity":c]) { x in var x = x; x["link"] = "lost"; x["recovery"] = "connect-failed"; return x } }
            if str(ownerID(s),"kind") == "cast" && phase(s) == "disconnected" { s["link"] = "lost"; s["notice"] = "connect-failed" } else { s["reason"] = "stale-link" }
        case "local-paused":
            return try viaOwner(s,["op":"local-paused","identity":candidate(s) as Any? ?? null]) { x in withOwnerIntent(x) { i in i["ticks"] = event["ticks"] ?? i["ticks"]!; i["paused"] = event["paused"] ?? i["paused"]! } }
        case "load-failed","load-timeout","cancel":
            let mapped = op == "load-failed" ? "fail" : op == "load-timeout" ? "timeout":"cancel"
            return try viaOwner(s,["op":mapped,"identity":candidate(s) as Any? ?? null]) { x in var x = x; x["recovery"] = event["recovery"] ?? (op == "load-failed" ? "receiver-load-failed":op == "load-timeout" ? "receiver-timeout":"cancelled"); return x }
        case "observe": return try observe(s,event)
        case "progress":
            guard let ticks = try CastJSON.optionalLong(event["ticks"]) else { s["reason"] = "invalid-observation"; return s }
            guard var o = obj(s,"observed"), candidate(s) == nil, str(ownerID(s),"kind") == "cast", phase(s) == "active", str(s,"link") == "connected" else { s["reason"] = "stale-observation"; return s }
            let pending = obj(s,"commands").flatMap{obj($0,"pending")}
            if str(o,"playerState") != "playing" || ticks <= 0 || (pending.flatMap{str($0,"result")} == "sent" && pending?["ticks"] != nil && !(pending?["ticks"] is NSNull)) { s["reason"] = "position-held"; return s }
            let bounded = min(ticks,maxDuration(o)); o["ticks"] = String(bounded); s["observed"] = o
            if var c = obj(s,"commands"), var observed = obj(c,"observed") { observed["ticks"] = String(bounded); c["observed"] = observed; s["commands"] = c }
        case "command":
            guard let c = obj(s,"commands"), str(ownerID(s),"kind") == "cast", phase(s) == "active", str(s,"link") == "connected" else { s["reason"] = "not-attached"; return s }
            let after = try commandReduce(c,["op":"intent","owner":c["owner"]!,"id":event["id"] ?? "0","nowMs":event["nowMs"] ?? "0","value":event["value"] ?? null,"entry":event["entry"] ?? null])
            s["commands"] = after; s["effect"] = str(after,"effect") == "dispatch" ? "send-command":"none"; s["reason"] = after["rejection"]!
        case "command-timeout":
            guard let c = obj(s,"commands") else { s["reason"] = "stale-owner"; return s }
            let after = try commandReduce(c,["op":"timeout","owner":c["owner"]!,"id":event["id"] ?? "0","nowMs":event["nowMs"] ?? "0"])
            s["commands"] = after; s["effect"] = str(after,"effect") == "refresh" ? "request-status":"none"
        case "command-rejected":
            guard var c = obj(s,"commands") else { s["reason"] = "stale-owner"; return s }
            guard var p = obj(c,"pending"), num(p,"id") == num(event,"id"), str(p,"result") == "sent" else { s["reason"] = "stale-command"; return s }
            p["result"] = "rejected"; c["pending"] = p; c["seekTarget"] = null; s["commands"] = c
        case "disconnect":
            return try viaOwner(s,["op":"disconnect","identity":ownerID(s)]) { x in var x = atLastPosition(x); x["link"] = "idle"; x["observed"] = null; x["mediaSession"] = null; x["commands"] = null; x["notice"] = "receiver-continues"; x["effect"] = "detach"; return x }
        case "stop": return try beginStop(s,"end")
        case "play-here": return try beginStop(s,"here")
        case "retry-stop": if str(s,"stop") == "unknown" { return try beginStop(s,str(s,"stopPurpose") ?? "end") }; s["reason"] = "stale-stop"
        case "stop-timeout": if str(s,"stop") == "sent" { return try stopUnconfirmed(s) }; s["reason"] = "stale-stop"
        case "cleanup-done": if str(s,"cleanup") == "pending" { s["cleanup"] = "done" } else { s["reason"] = "stale-cleanup" }
        case "resume-here":
            return try viaOwner(s,["op":"resume-here","identity":event["identity"] ?? null,"confirmed":event["confirmed"] ?? false]) { x in
                var x = withOwnerIntent(x) { i in if !(s["returnPaused"] is NSNull) { i["paused"] = s["returnPaused"]! }; if !(s["returnTicks"] is NSNull) { i["ticks"] = s["returnTicks"]! } }
                x["link"] = str(x,"link") == "connected" ? "connected":"idle"; x["mediaSession"] = null; x["commands"] = null; x["stop"] = str(s,"stop") == "unknown" ? "unknown":"none"; x["recovery"] = null; return x
            }
        case "report":
            if str(ownerID(s),"kind") == "cast" && (str(s,"link") != "connected" || obj(s,"observed") == nil) { s["reason"] = "not-attached"; return s }
            return try viaOwner(s,["op":"report","identity":event["identity"] ?? ownerID(s),"sequence":event["sequence"] ?? "0"])
        case "viewer-switch","replace":
            return try viaOwner(s,["op":op,"identity":event["identity"] ?? null,"intent":event["intent"] ?? null]) { x in var x = x; x["link"] = "idle"; x["mediaSession"] = null; x["observed"] = null; x["commands"] = null; x["stop"] = "none"; x["stopPurpose"] = null; x["effect"] = "detach"; return x }
        default: throw CastJSON.error("unknown cast event \(op)")
        }
        return s
    }
    static func disconnectedCommands(_ s:Object) throws -> Any {
        guard let c = obj(s,"commands") else { return null }
        var after = try commandReduce(c,["op":"disconnect","owner":c["owner"]!]); after["effect"] = "none"; return after
    }
}
