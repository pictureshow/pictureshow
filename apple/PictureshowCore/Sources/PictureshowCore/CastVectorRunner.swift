// SPDX-License-Identifier: MPL-2.0
import Foundation

/// Strict shared-vector adapter. Expected values are used only by the projection assertion, never by reducers.
public enum CastVectorRunner {
    public static func execute(suite:String, operation:String, input:[String:Any], context:[String:Any]) throws -> Any {
        guard context.isEmpty else { throw CastJSON.error("Cast simple input context must be empty") }
        return try reduce(suite:suite,operation:operation,state:[:],event:input)
    }
    public static func reduce(suite:String,operation:String,state:[String:Any],event:[String:Any]) throws -> [String:Any] {
        switch (suite,operation) {
        case ("cast-capability","capability"): return try CastPolicies.capability(event)
        case ("cast-capability","controls"): return try CastPolicies.controls(event)
        case ("cast-capability","reach"): return try CastPolicies.reach(event)
        case ("cast-capability","profile"): try CastJSON.fields(event,allowed:[]); return CastMedia.deviceProfile()
        case ("cast-capability","media"): return try CastMedia.projection(event)
        case ("cast-session","session"): return try CastPolicies.session(state:state,event:event)
        case ("cast-session","reattach"): return try CastPolicies.reattach(event)
        case ("cast-handoff","cast"): return try CastHandoff.reduce(state:state,event:event)
        default: throw CastJSON.error("Unknown Cast suite/operation \(suite)/\(operation)")
        }
    }
    public static func run(suite:String,operation:String,id:String,state initial:[String:Any],steps:[[String:Any]]) throws -> Int {
        guard cases[id] == suite+"/"+operation else { throw CastJSON.error("Unknown Cast vector ID or mismatched operation: \(id)") }
        guard !steps.isEmpty else { throw CastJSON.error("Cast trace requires steps") }
        if operation != "cast" && operation != "session" { try CastJSON.fields(initial,allowed:[]) }
        var state = initial
        for (index,step) in steps.enumerated() {
            try CastJSON.fields(step,allowed:["event","expected"],required:["event","expected"])
            let event = try CastJSON.object(step["event"]), expected = try CastJSON.object(step["expected"])
            guard !expected.isEmpty else { throw CastJSON.error("Empty expected projection") }
            let actual = try reduce(suite:suite,operation:operation,state:state,event:event)
            guard projection(expected,matches:actual) else { throw CastJSON.error("\(id)/\(index) expected \(render(expected)); actual \(render(actual))") }
            state = actual
        }
        return steps.count
    }
    static func projection(_ expected:Any,matches actual:Any?) -> Bool {
        guard let actual else { return false }
        if let exp = expected as? [String:Any] { guard let got = actual as? [String:Any] else { return false }; return exp.allSatisfy { projection($0.value,matches:got[$0.key]) } }
        if let exp = expected as? [Any] { guard let got = actual as? [Any], exp.count == got.count else { return false }; return zip(exp,got).allSatisfy { projection($0.0,matches:$0.1) } }
        return render(expected) == render(actual)
    }
    static func render(_ value:Any) -> String { guard let data = try? JSONSerialization.data(withJSONObject:value,options:[.sortedKeys,.fragmentsAllowed]), let s = String(data:data,encoding:.utf8) else { return "<invalid JSON>" }; return s }
    // Reviewed vector identities, containing no inputs or expected values.
    static let cases:[String:String] = [
        "cast-capability.tv-interface-keeps-the-sdk-out":"cast-capability/capability",
        "cast-capability.os-too-old":"cast-capability/capability",
        "cast-capability.browser-unsupported":"cast-capability/capability",
        "cast-capability.services-absent-does-not-crash-or-hide-jellyfin":"cast-capability/capability",
        "cast-capability.services-need-update":"cast-capability/capability",
        "cast-capability.viewer-turned-it-off":"cast-capability/capability",
        "cast-capability.local-network-refused":"cast-capability/capability",
        "cast-capability.idle-until-the-chooser-opens":"cast-capability/capability",
        "cast-capability.searching":"cast-capability/capability",
        "cast-capability.searched-and-found-nothing":"cast-capability/capability",
        "cast-capability.targets-win-over-the-search-state":"cast-capability/capability",
        "cast-capability.tv-beats-every-other-block":"cast-capability/capability",
        "cast-capability.vod-with-everything":"cast-capability/controls",
        "cast-capability.fixed-volume-receiver-says-why":"cast-capability/controls",
        "cast-capability.unknown-duration-is-live-never-zero-length":"cast-capability/controls",
        "cast-capability.zero-duration-is-live":"cast-capability/controls",
        "cast-capability.live-window-bounds-seek":"cast-capability/controls",
        "cast-capability.empty-live-window-can-not-seek":"cast-capability/controls",
        "cast-capability.unavailable-track-can-not-read-as-selected":"cast-capability/controls",
        "cast-capability.selected-comes-from-the-receiver":"cast-capability/controls",
        "cast-capability.loopback-can-not-reach":"cast-capability/reach",
        "cast-capability.localhost-can-not-reach":"cast-capability/reach",
        "cast-capability.ipv6-loopback":"cast-capability/reach",
        "cast-capability.link-local-can-not-reach":"cast-capability/reach",
        "cast-capability.vpn-route-can-not-reach":"cast-capability/reach",
        "cast-capability.cgnat-overlay-while-on-vpn":"cast-capability/reach",
        "cast-capability.cgnat-without-a-vpn-is-not-judged":"cast-capability/reach",
        "cast-capability.name-on-a-vpn-may-not-reach":"cast-capability/reach",
        "cast-capability.address-outside-the-vpn-route":"cast-capability/reach",
        "cast-capability.private-http":"cast-capability/reach",
        "cast-capability.private-class-b":"cast-capability/reach",
        "cast-capability.not-private-class-b":"cast-capability/reach",
        "cast-capability.private-https":"cast-capability/reach",
        "cast-capability.lan-name":"cast-capability/reach",
        "cast-capability.public-https":"cast-capability/reach",
        "cast-capability.not-a-url":"cast-capability/reach",
        "cast-capability.a-name-starting-like-link-local-is-a-name":"cast-capability/reach",
        "cast-capability.a-name-starting-like-private-v6-is-a-name":"cast-capability/reach",
        "cast-capability.profile-is-the-receiver-table":"cast-capability/profile",
        "cast-capability.direct-media-carries-the-token-for-the-receiver-only":"cast-capability/media",
        "cast-capability.transcode-is-hls":"cast-capability/media",
        "cast-session.provisioned-session-is-the-one-the-load-uses":"cast-session/session",
        "cast-session.another-users-session-is-never-used":"cast-session/session",
        "cast-session.one-session-per-identity-until-the-last-is-cleaned-up":"cast-session/session",
        "cast-session.stop-confirmed-revokes":"cast-session/session",
        "cast-session.receiver-ending-it-revokes":"cast-session/session",
        "cast-session.failed-load-revokes":"cast-session/session",
        "cast-session.load-timeout-revokes":"cast-session/session",
        "cast-session.cancel-revokes":"cast-session/session",
        "cast-session.foreign-media-revokes":"cast-session/session",
        "cast-session.forgetting-it-revokes":"cast-session/session",
        "cast-session.viewer-change-revokes":"cast-session/session",
        "cast-session.sign-out-revokes":"cast-session/session",
        "cast-session.disconnect-keeps-it-while-the-receiver-plays":"cast-session/session",
        "cast-session.lost-link-keeps-it":"cast-session/session",
        "cast-session.unconfirmed-stop-keeps-it-until-the-viewer-chooses":"cast-session/session",
        "cast-session.offline-revocation-stays-pending-and-blocks-a-new-session":"cast-session/session",
        "cast-session.ending-while-the-session-is-being-issued-waits-for-it":"cast-session/session",
        "cast-session.sign-out-with-a-stale-generation-still-revokes":"cast-session/session",
        "cast-session.sign-out-with-a-stale-sender-still-revokes":"cast-session/session",
        "cast-session.viewer-change-with-a-stale-generation-still-revokes":"cast-session/session",
        "cast-session.forget-with-a-stale-sender-still-revokes":"cast-session/session",
        "cast-session.a-late-stop-for-an-older-viewing-leaves-the-newer-session":"cast-session/session",
        "cast-session.second-end-while-revoking-keeps-the-logout":"cast-session/session",
        "cast-session.second-end-while-pending-revoke-stays-pending":"cast-session/session",
        "cast-session.second-end-while-pending-persist-stays":"cast-session/session",
        "cast-session.second-end-while-waiting-for-the-issue-changes-nothing":"cast-session/session",
        "cast-session.ending-twice-has-nothing-left-to-end":"cast-session/session",
        "cast-session.stored-route-missing-is-foreign":"cast-session/reattach",
        "cast-session.stored-url-missing-is-foreign":"cast-session/reattach",
        "cast-session.reattach-our-own-media":"cast-session/reattach",
        "cast-session.reattach-with-nothing-stored-is-foreign":"cast-session/reattach",
        "cast-session.reattach-another-viewer-is-foreign":"cast-session/reattach",
        "cast-session.reattach-another-server-is-foreign":"cast-session/reattach",
        "cast-session.reattach-idle-receiver-has-ended":"cast-session/reattach",
        "cast-session.reattach-one-empty-status-asks-again":"cast-session/reattach",
        "cast-session.reattach-second-empty-status-has-ended":"cast-session/reattach",
        "cast-session.reattach-empty-with-a-reason-has-ended":"cast-session/reattach",
        "cast-session.reattach-empty-on-another-route-is-foreign-before-asking":"cast-session/reattach",
        "cast-session.reattach-crafted-data-without-the-nonce-is-foreign":"cast-session/reattach",
        "cast-session.reattach-wrong-nonce-is-foreign":"cast-session/reattach",
        "cast-session.reattach-different-generation-is-foreign":"cast-session/reattach",
        "cast-session.reattach-huge-generation-is-foreign":"cast-session/reattach",
        "cast-session.reattach-negative-generation-is-foreign":"cast-session/reattach",
        "cast-session.reattach-non-numeric-generation-is-foreign":"cast-session/reattach",
        "cast-session.replayed-nonce-with-another-url-is-foreign":"cast-session/reattach",
        "cast-session.replayed-nonce-without-a-url-is-foreign":"cast-session/reattach",
        "cast-session.replayed-nonce-on-another-route-is-foreign":"cast-session/reattach",
        "cast-session.reattach-with-no-route-joined-is-foreign":"cast-session/reattach",
        "cast-session.reattach-entry-we-never-loaded-is-foreign":"cast-session/reattach",
        "cast-session.a-late-receiver-ended-for-an-older-viewing-leaves-the-newer-session":"cast-session/session",
        "cast-session.a-late-load-failure-for-an-older-viewing-leaves-the-newer-session":"cast-session/session",
        "cast-session.a-late-end-from-another-sender-leaves-the-session":"cast-session/session",
        "cast-session.replaced-revokes-the-viewing-it-replaced":"cast-session/session",
        "cast-handoff.local-pause-comes-before-the-load-and-ready-commits":"cast-handoff/cast",
        "cast-handoff.load-can-not-precede-the-local-pause":"cast-handoff/cast",
        "cast-handoff.position-outside-tolerance-keeps-local-owner":"cast-handoff/cast",
        "cast-handoff.older-generation-status-is-dropped":"cast-handoff/cast",
        "cast-handoff.load-refused-leaves-a-paused-local-resume":"cast-handoff/cast",
        "cast-handoff.load-failed-default-recovery":"cast-handoff/cast",
        "cast-handoff.receiver-never-ready-times-out":"cast-handoff/cast",
        "cast-handoff.receiver-idle-error-fails-the-handoff":"cast-handoff/cast",
        "cast-handoff.receiver-idle-interrupted-fails-the-handoff":"cast-handoff/cast",
        "cast-handoff.cancel-from-selecting":"cast-handoff/cast",
        "cast-handoff.link-lost-during-handoff":"cast-handoff/cast",
        "cast-handoff.older-media-session-dropped-newer-rebinds":"cast-handoff/cast",
        "cast-handoff.pause-confirms-only-when-the-receiver-says-so":"cast-handoff/cast",
        "cast-handoff.buffering-never-confirms-pause":"cast-handoff/cast",
        "cast-handoff.seek-confirms-near-its-target-and-shows-the-real-position":"cast-handoff/cast",
        "cast-handoff.command-timeout-asks-the-receiver-and-disables-commands":"cast-handoff/cast",
        "cast-handoff.command-rejected-matches-its-own-id":"cast-handoff/cast",
        "cast-handoff.unsupported-command-is-refused":"cast-handoff/cast",
        "cast-handoff.unknown-duration-never-reads-as-zero-length":"cast-handoff/cast",
        "cast-handoff.disconnect-leaves-the-receiver-playing-and-reattach-reads-it":"cast-handoff/cast",
        "cast-handoff.reattach-finds-the-viewing-finished":"cast-handoff/cast",
        "cast-handoff.sender-death-reattach-is-a-new-process-with-no-old-clock":"cast-handoff/cast",
        "cast-handoff.sender-death-reattach-ignores-someone-elses-media":"cast-handoff/cast",
        "cast-handoff.suspended-link-pauses-controls-until-it-returns":"cast-handoff/cast",
        "cast-handoff.link-lost-while-casting-keeps-the-owner-and-tells-the-viewer":"cast-handoff/cast",
        "cast-handoff.stop-casting-confirmed-by-the-receiver":"cast-handoff/cast",
        "cast-handoff.stop-confirmed-by-an-empty-receiver":"cast-handoff/cast",
        "cast-handoff.stop-confirmed-by-someone-elses-media":"cast-handoff/cast",
        "cast-handoff.unknown-stop-needs-an-explicit-choice":"cast-handoff/cast",
        "cast-handoff.unknown-stop-can-be-retried":"cast-handoff/cast",
        "cast-handoff.receiver-answers-after-an-unknown-stop":"cast-handoff/cast",
        "cast-handoff.stop-timeout-without-a-stop-is-stale":"cast-handoff/cast",
        "cast-handoff.link-lost-while-stopping-is-unknown":"cast-handoff/cast",
        "cast-handoff.play-here-keeps-a-paused-viewing-paused":"cast-handoff/cast",
        "cast-handoff.play-here-keeps-a-playing-viewing-playing":"cast-handoff/cast",
        "cast-handoff.play-here-with-no-answer-asks-first":"cast-handoff/cast",
        "cast-handoff.play-here-from-a-local-owner-is-refused":"cast-handoff/cast",
        "cast-handoff.receiver-finishes-by-itself":"cast-handoff/cast",
        "cast-handoff.receiver-error-tells-the-viewer":"cast-handoff/cast",
        "cast-handoff.receiver-stopped-elsewhere-tells-the-viewer":"cast-handoff/cast",
        "cast-handoff.two-empty-receiver-statuses-end-the-viewing":"cast-handoff/cast",
        "cast-handoff.finishing-with-no-media-left-still-says-finished":"cast-handoff/cast",
        "cast-handoff.idle-status-without-our-stamp-is-not-someone-else":"cast-handoff/cast",
        "cast-handoff.someone-else-takes-the-receiver":"cast-handoff/cast",
        "cast-handoff.reports-belong-to-the-attached-owner":"cast-handoff/cast",
        "cast-handoff.reports-after-the-handoff-need-a-live-status":"cast-handoff/cast",
        "cast-handoff.viewer-switch-detaches":"cast-handoff/cast",
        "cast-handoff.stale-candidate-target-is-refused":"cast-handoff/cast",
        "cast-handoff.one-empty-status-then-media-carries-on":"cast-handoff/cast",
        "cast-handoff.an-empty-status-with-a-reason-ends-at-once":"cast-handoff/cast",
        "cast-handoff.pick-with-nothing-casting-is-an-ordinary-select":"cast-handoff/cast",
        "cast-handoff.pick-after-a-failed-handoff-is-an-ordinary-select":"cast-handoff/cast",
        "cast-handoff.pick-same-receiver-same-item-shows-the-controls":"cast-handoff/cast",
        "cast-handoff.pick-same-item-goes-by-what-the-receiver-reports":"cast-handoff/cast",
        "cast-handoff.pick-same-receiver-other-item-replaces-on-the-same-link":"cast-handoff/cast",
        "cast-handoff.pick-another-receiver-replaces-and-leaves-this-link":"cast-handoff/cast",
        "cast-handoff.pick-while-detached-same-receiver-same-item-reconnects":"cast-handoff/cast",
        "cast-handoff.pick-while-detached-same-receiver-other-item-rejoins-first":"cast-handoff/cast",
        "cast-handoff.pick-while-detached-elsewhere-is-refused":"cast-handoff/cast",
        "cast-handoff.pick-while-the-link-is-suspended-is-refused":"cast-handoff/cast",
        "cast-handoff.pick-while-stopping-is-refused":"cast-handoff/cast",
        "cast-handoff.pick-during-a-handoff-is-refused":"cast-handoff/cast",
        "cast-handoff.link-failed-during-a-connect-is-its-own-failure":"cast-handoff/cast",
        "cast-handoff.link-failed-while-reconnecting-keeps-the-viewing":"cast-handoff/cast",
        "cast-handoff.link-failed-with-nothing-on-its-way-changes-nothing":"cast-handoff/cast",
        "cast-handoff.position-moves-the-observation-and-the-seek-base-while-playing":"cast-handoff/cast",
        "cast-handoff.position-holds-still-while-paused":"cast-handoff/cast",
        "cast-handoff.position-holds-still-while-loading":"cast-handoff/cast",
        "cast-handoff.position-never-moves-to-zero":"cast-handoff/cast",
        "cast-handoff.position-stops-at-the-duration":"cast-handoff/cast",
        "cast-handoff.position-waits-for-a-first-status":"cast-handoff/cast",
        "cast-handoff.position-ignored-without-the-link":"cast-handoff/cast",
        "cast-handoff.position-ignored-while-a-handoff-waits-to-start":"cast-handoff/cast",
        "cast-handoff.position-ignored-once-stopping":"cast-handoff/cast",
        "cast-handoff.position-waits-for-a-seek-to-land":"cast-handoff/cast",
        "cast-handoff.position-a-relative-seek-starts-from-it":"cast-handoff/cast",
        "cast-handoff.position-stop-saves-it":"cast-handoff/cast",
        "cast-handoff.position-play-here-returns-to-it":"cast-handoff/cast",
        "cast-handoff.position-a-retried-stop-keeps-the-first-reading":"cast-handoff/cast",
        "cast-handoff.position-disconnect-keeps-it-for-a-later-return":"cast-handoff/cast",
        "cast-handoff.position-a-suspended-link-keeps-it":"cast-handoff/cast",
        "cast-handoff.position-a-lost-link-keeps-it":"cast-handoff/cast",
        "cast-handoff.position-rejoining-keeps-it":"cast-handoff/cast",
        "cast-handoff.position-a-receiver-ending-at-zero-keeps-the-last-it-said":"cast-handoff/cast",
        "cast-handoff.position-a-receiver-ending-with-a-position-is-believed":"cast-handoff/cast",
        "cast-handoff.position-a-receiver-taken-keeps-the-last-it-said":"cast-handoff/cast",
        "cast-handoff.position-play-here-after-the-queue-moved-returns-to-the-item-it-was-read-from":"cast-handoff/cast",
        "cast-handoff.position-disconnect-after-the-queue-moved-keeps-the-item":"cast-handoff/cast",
        "cast-handoff.position-a-receiver-ending-on-a-later-item-keeps-the-item":"cast-handoff/cast",
        "cast-handoff.position-a-receiver-ending-without-a-queue-move-keeps-the-item":"cast-handoff/cast",
        "cast-handoff.position-a-retried-play-here-keeps-the-first-item":"cast-handoff/cast",
        "cast-handoff.position-a-receiver-taken-after-the-queue-moved-keeps-the-item":"cast-handoff/cast",
        "cast-handoff.local-pause-carries-whether-it-was-already-paused":"cast-handoff/cast",
        "cast-handoff.local-pause-carries-whether-it-was-still-playing":"cast-handoff/cast",
        "cast-handoff.local-pause-with-no-player-keeps-the-picks-paused-state":"cast-handoff/cast",
    ]
}
