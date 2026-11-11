// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

private final class VectorProtocol: URLProtocol {
    static var responses:[[String:Any]]=[]
    static var requests:[[String:Any]]=[]
    override class func canInit(with request:URLRequest)->Bool{true}
    override class func canonicalRequest(for request:URLRequest)->URLRequest{request}
    override func startLoading(){
        do {
            let c=URLComponents(url:request.url!,resolvingAgainstBaseURL:false)!
            var query:[String:[String]]=[:];for q in c.queryItems ?? []{query[q.name,default:[]].append(q.value ?? "")}
            var body=request.httpBody ?? Data()
            if let stream=request.httpBodyStream{stream.open();defer{stream.close()};var bytes=[UInt8](repeating:0,count:4096);while stream.hasBytesAvailable{let n=stream.read(&bytes,maxLength:bytes.count);if n<=0{break};body.append(bytes,count:n)}}
            let captured:[String:Any] = ["method":request.httpMethod!,"path":c.percentEncodedPath,"query":query,"authorization":request.value(forHTTPHeaderField:"Authorization") as Any? ?? NSNull(),"body":body.isEmpty ? NSNull():try JSONSerialization.jsonObject(with:body)]
            let index=Self.requests.count;Self.requests.append(captured)
            guard index<Self.responses.count else{throw ClientError.invalidResponse};let response=Self.responses[index]
            let data=try JSONSerialization.data(withJSONObject:response["body"]!,options:[.fragmentsAllowed])
            client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:response["status"] as! Int,httpVersion:"HTTP/1.1",headerFields:["Content-Type":"application/json"])!,cacheStoragePolicy:.notAllowed);client?.urlProtocol(self,didLoad:data);client?.urlProtocolDidFinishLoading(self)
        }catch{client?.urlProtocol(self,didFailWithError:error)}
    }
    override func stopLoading(){}
}

final class SpecVectorTests:XCTestCase {
    func decode<T:Decodable>(_ type:T.Type,_ value:Any)throws->T{try JSONDecoder().decode(type,from:JSONSerialization.data(withJSONObject:value,options:[.fragmentsAllowed]))}
    func integer(_ d:[String:Any],_ key:String,_ fallback:Int64=0)->Int64{(d[key] as? NSNumber)?.int64Value ?? fallback}
    func optionalInt(_ d:[String:Any],_ key:String)->Int?{(d[key] as? NSNumber)?.intValue}
    func text(_ d:[String:Any],_ key:String,_ fallback:String="")->String{d[key] as? String ?? fallback}
    func bool(_ d:[String:Any],_ key:String,_ fallback:Bool=false)->Bool{d[key] as? Bool ?? fallback}
    func required<T:Decodable>(_ type:T.Type,_ d:[String:Any],_ key:String)throws->T {
        guard let value=d[key] else { throw NSError(domain:"Missing vector input \(key)",code:1) }
        return try decode(type,value)
    }
    func defaulted<T:Decodable>(_ type:T.Type,_ d:[String:Any],_ key:String,_ fallback:T)throws->T {
        guard let value=d[key] else { return fallback }
        return try decode(type,value)
    }
    func json(_ value:Any)throws->String{String(data:try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys,.fragmentsAllowed]),encoding:.utf8)!}
    func errorName(_ error:Error)->String{switch error as? ClientError{case .serverRefused:return "SERVER_REFUSED";case .sourceNotFound:return "SOURCE_NOT_FOUND";case .noCompatibleSource:return "NO_COMPATIBLE_SOURCE";case .unsafeStreamURL:return "UNSAFE_STREAM_URL";default:return "UNEXPECTED_\(error)"}}
    func decisionJSON(_ d:PlayDecision,urlParts:Bool=false)->[String:Any]{["method":d.method.rawValue,"mediaSourceId":d.source.id as Any? ?? NSNull(),"url":urlParts ? parts(d.url):d.url.absoluteString as Any,"headers":d.headers,"playSessionId":d.playSessionId as Any? ?? NSNull(),"transcodeReasons":d.transcodeReasons]}
    func parts(_ url:URL)->[String:Any]{let c=URLComponents(url:url,resolvingAgainstBaseURL:false)!;var q:[String:[String]]=[:];for x in c.queryItems ?? []{q[x.name,default:[]].append(x.value ?? "")};return ["scheme":c.scheme!,"host":c.host!,"port":c.port ?? (c.scheme == "https" ? 443:80),"path":c.percentEncodedPath,"query":q]}
    func testTrackLabelsVectors() async throws { try await runVectors(operations: ["track-label", "track-labels"]) }
    func testAllSharedVectors() async throws { try await runVectors() }
    func testRC28PolicyVectors() async throws {
        try await runVectors(operations: ["caption-index", "detail-ready-feedback", "detail-request-cue-active", "detail-pending-presentation-visible", "detail-pending-presentation-label", "detail-held-label-presentation", "detail-feedback-source", "detail-quiet-departing-change", "detail-quiet-request-rediscovery", "detail-reader-focus-clear", "detail-retry-description", "detail-retry-failure-cue", "detail-retry-attempt-succeeded", "detail-retry-cue-can-wait", "detail-completion-presented-played", "detail-completion-intent-retained", "detail-raw-read-acknowledgement", "detail-played-request-notice", "detail-request-once-feedback", "visibility-play"])
    }
    func testRC29DetailPolicyVectors() async throws {
        try await runVectors(operations: ["detail-completion-may-apply", "detail-pending-feedback", "detail-pending-plain-label", "catalog-arrival-animation"])
    }
    func testPlaybackInformationVectors() async throws {
        try await runVectors(operations: ["reason", "method", "subtitle", "bitrate", "speed"])
    }
    func testF4PolicyVectors() async throws {
        try await runVectors(operations: ["detail-completion-read", "detail-cold-pending", "detail-requested-label", "catalog-refresh-revision", "detail-read-band", "detail-session-read", "detail-read-failure", "detail-request-cue-active", "detail-confirmed-leaf", "detail-read-dependencies", "seek-step", "playback-report-fields"])
    }
    func testF4AdaptersRejectUnknownOperationsAndMalformedInputs() async throws {
        let invalid: [(String, [String: Any])] = [
            ("unknown-f4-policy", [:]),
            ("seek-step", ["positionMs": 10, "deltaMs": 1, "durationMs": 100]),
            ("playback-report-fields", ["kind": "start", "positionTicks": 0, "subtitle": NSNull(), "audioViewer": false, "subtitleViewer": false]),
            ("detail-cold-pending", ["loading": true, "firstArrival": true, "canQueue": true, "returningFromPlayback": NSNull()]),
            ("detail-request-cue-active", ["active": true, "readerLeftAction": false, "requested": "true"])
        ]
        for (operation, input) in invalid {
            do {
                _ = try await execute(operation, input: input, context: [:])
                XCTFail("Accepted unknown operation or malformed input: \(operation)")
            } catch { }
        }
    }
    func testRC24PolicyVectors() async throws {
        try await runVectors(operations: ["caption-index", "detail-action-state", "detail-held-label", "detail-unavailable-label", "detail-ready-feedback", "detail-read-lifecycle", "detail-metadata-handoff", "detail-host-focus-handoff", "detail-arrival-pending", "report-track-index", "track-selection-unchanged", "subtitle-selection-local"])
    }
    private func runVectors(operations: Set<String>? = nil) async throws {
        let root:URL
        if let override=ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"]{root=URL(fileURLWithPath:override)}else{var p=URL(fileURLWithPath:#filePath);for _ in 0..<5{p.deleteLastPathComponent()};root=p.appendingPathComponent("spec")}
        let vectors=root.appendingPathComponent("vectors");let files=try XCTUnwrap(FileManager.default.enumerator(at:vectors,includingPropertiesForKeys:nil)).compactMap{$0 as? URL}.filter{$0.pathExtension == "json"}.sorted{$0.path<$1.path}
        XCTAssertFalse(files.isEmpty,"No shared vectors at \(vectors.path)")
        // Validate every envelope and global case identity before any operation can perform mock HTTP work.
        var ids=Set<String>()
        let controlSuites:Set<String>=["session-owner","session-command","queue-edit","queue-order","playback-mode"]
        let musicSuites:Set<String>=["music-catalog","music-playlists","music-quality","music-queue","music-reports","music-session"]
        let castSuites:Set<String>=["cast-capability","cast-session","cast-handoff"]
        let remoteSuites:Set<String>=["remote-wire","remote-targets","remote-receive","remote-reconnect","remote-queue"]
        let traceSuites = controlSuites.union(musicSuites).union(castSuites).union(remoteSuites)
        let allowedSuites:Set<String>=Set(["reference","application","recovery","playback-information","track-labels","grid-navigation"]).union(traceSuites)
        var discoveredControlSuites=Set<String>()
        let documents=try files.map { file -> ([String:Any],[[String:Any]]) in
            let document=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:file)) as? [String:Any])
            guard try required(Int.self,document,"schemaVersion") == 1 else { throw NSError(domain:"Unknown vector schema in \(file.lastPathComponent)",code:1) }
            let suite=try required(String.self,document,"suite")
            guard allowedSuites.contains(suite) else { throw NSError(domain:"Unknown vector suite \(suite)",code:1) }
            if traceSuites.contains(suite) {
                guard discoveredControlSuites.insert(suite).inserted else { throw NSError(domain:"Duplicate control suite \(suite)",code:1) }
            }
            let cases=try XCTUnwrap(document["cases"] as? [[String:Any]])
            guard !cases.isEmpty else { throw NSError(domain:"Empty vector suite \(file.lastPathComponent)",code:1) }
            for vector in cases {
                let id=try required(String.self,vector,"id"),operation=try required(String.self,vector,"operation")
                guard !id.isEmpty,!operation.isEmpty,ids.insert(id).inserted else { throw NSError(domain:"Malformed or duplicate vector \(id)",code:1) }
                try SpecCaseManifest.validate(id:id,suite:suite,operation:operation)
                if traceSuites.contains(suite) {
                    let allowedOperations:[String:Set<String>]=[
                        "session-owner":["owner","temporary"], "session-command":["command"],
                        "queue-edit":["queue","store"], "queue-order":["queue","transfer"],
                        "playback-mode":["mode"],
                        "music-catalog":["section","artist-albums","order","discs","artist-line","page","list-state","playable","image","genre-tracks"],
                        "music-playlists":["create-body","rename-body","playlist","id-batches"],
                        "music-quality":["ceiling","profile","output"], "music-queue":["queue","entries","permutation","window","plan","decision"],
                        "music-reports":["report"], "music-session":["session"],
                        "cast-capability":["capability","controls","reach","profile","media"], "cast-session":["session","reattach"], "cast-handoff":["cast"],
                        "remote-wire":["request","cap-note","parse","capabilities","advertised","socket-url","targets-path","relay-rights"],
                        "remote-targets":["targets","track-selected"], "remote-receive":["receive"], "remote-reconnect":["controller"], "remote-queue":["queue"]
                    ]
                    guard allowedOperations[suite]?.contains(operation) == true else { throw NSError(domain:"Unknown operation \(suite)/\(operation)",code:1) }
                    if musicSuites.contains(suite), !["queue", "session", "report", "playlist"].contains(operation) {
                        guard vector["input"] is [String:Any],vector["expected"] != nil,vector["steps"] == nil,vector["state"] == nil else { throw NSError(domain:"Malformed music vector \(id)",code:1) }
                        continue
                    }
                    guard vector["state"] is [String:Any], let steps=vector["steps"] as? [[String:Any]], !steps.isEmpty,
                          vector["input"] == nil, vector["expected"] == nil else { throw NSError(domain:"Malformed control vector \(id) or unknown operation \(operation)",code:1) }
                    for step in steps {
                        guard step["event"] is [String:Any], let expected=step["expected"] as? [String:Any], !expected.isEmpty else { throw NSError(domain:"Malformed control step in \(id)",code:1) }
                    }
                } else {
                    guard vector["input"] is [String:Any],vector["expected"] != nil else { throw NSError(domain:"Malformed vector \(id)",code:1) }
                }
            }
            return (document,cases)
        }
        guard ids == Set(SpecCaseManifest.contracts.keys) else { throw NSError(domain:"Missing known vector IDs: \(Set(SpecCaseManifest.contracts.keys).subtracting(ids).sorted())",code:1) }
        guard discoveredControlSuites == traceSuites else { throw NSError(domain:"Missing control suites: \(traceSuites.subtracting(discoveredControlSuites).sorted())",code:1) }
        var count=0;var counts:[String:Int]=[:];var stepsBySuite:[String:Int]=[:];var failures:[String]=[]
        for (document,cases) in documents{
            let suite=text(document,"suite")
            for vector in cases {
                if let operations, !operations.contains(text(vector,"operation")) { continue }
                if traceSuites.contains(suite), vector["steps"] != nil {
                    guard operations == nil else { continue }
                    let id=text(vector,"id")
                    do {
                        let steps: Int
                        if musicSuites.contains(suite) { steps = try MusicVectorRunner.run(suite:suite,operation:text(vector,"operation"),id:id,state:vector["state"] as! [String:Any],steps:vector["steps"] as! [[String:Any]]) }
                        else if castSuites.contains(suite) { steps = try CastVectorRunner.run(suite:suite,operation:text(vector,"operation"),id:id,state:vector["state"] as! [String:Any],steps:vector["steps"] as! [[String:Any]]) }
                        else if remoteSuites.contains(suite) { steps = try RemoteVectorRunner.run(suite:suite,operation:text(vector,"operation"),id:id,state:vector["state"] as! [String:Any],steps:vector["steps"] as! [[String:Any]]) }
                        else { steps = try ControlTraceRunner.run(suite:suite,operation:text(vector,"operation"),id:id,state:vector["state"] as! [String:Any],steps:vector["steps"] as! [[String:Any]]) }
                        if steps > 0 { stepsBySuite[suite,default:0] += steps }
                    } catch { failures.append("\(id): \(error)") }
                    count+=1;counts[suite,default:0]+=1
                    continue
                }
                let id=text(vector,"id")
                do {
                    let result: Any
                    if musicSuites.contains(suite) { result = try MusicVectorRunner.execute(suite:suite,operation:text(vector,"operation"),input:vector["input"] as! [String:Any],context:document["context"] as? [String:Any] ?? [:]) }
                    else { result = try await execute(text(vector,"operation"),input:vector["input"] as! [String:Any],context:document["context"] as? [String:Any] ?? [:]) }
                    let actual=try json(result),expected=try json(vector["expected"]!)
                    let matches = musicSuites.contains(suite) && vector["exact"] == nil ? MusicVectorTests.projection(vector["expected"]!,matches:result) : actual == expected
                    if !matches { failures.append("\(id)\n expected: \(expected)\n actual: \(actual)") }
                } catch { failures.append("\(id): \(error)") }
                count+=1;counts[suite,default:0]+=1
            }
        }
        XCTAssertGreaterThan(count, 0)
        print("\(operations == nil ? "Shared" : "Targeted") vectors: \(count) cases executed \(counts), control trace steps \(stepsBySuite), \(failures.count) failures; no skipped cases within the selected cohort.")
        XCTAssertTrue(failures.isEmpty,failures.joined(separator:"\n"))
    }
    func execute(_ operation:String,input i:[String:Any],context:[String:Any])async throws->Any{
        let serverJSON=context["server"] as? [String:Any] ?? [:];let identity=context["identity"] as? [String:Any] ?? [:];let credentials=context["account"] as? [String:Any] ?? [:]
        let server=Server(url:URL(string:text(serverJSON,"url","https://media.example.test/jellyfin"))!);let account=Account(server:server,userId:text(credentials,"userId","viewer"),username:"Viewer",accessToken:text(credentials,"token","fixture-token"),deviceID:text(credentials,"deviceId",text(identity,"deviceId","install")))
        let client=JellyfinClient(deviceID:text(identity,"deviceId","install"),deviceName:text(identity,"deviceName","Fixture TV"),version:text(identity,"version","0.1.0"))
        switch operation{
        case "detail-pending-presentation-visible":return DetailFeedback.pendingPresentationVisible(waiting:try required(Bool.self,i,"waiting"),resumed:try required(Bool.self,i,"resumed"),published:try required(Bool.self,i,"published"))
        case "detail-pending-presentation-label":return DetailFeedback.pendingPresentationLabel(held:try required(Bool.self,i,"held"),requested:try required(Bool.self,i,"requested"),available:try required(Bool.self,i,"available"),plain:try required(String.self,i,"plain"),pending:try required(String.self,i,"pending"))
        case "detail-held-label-presentation":return DetailFeedback.heldLabelPresented(selectedWhileHeld:try required(Bool.self,i,"selectedWhileHeld"),heldLabelRequested:try required(Bool.self,i,"heldLabelRequested"))
        case "detail-feedback-source":return DetailFeedback.feedbackSource(currentPage:try required(Bool.self,i,"currentPage"),selectedAction:try required(Bool.self,i,"selectedAction"))
        case "detail-quiet-departing-change":return DetailFeedback.quietDepartingChange(handoff:try required(Bool.self,i,"handoff"),action:try required(Bool.self,i,"action"),onlyLabelChanged:try required(Bool.self,i,"onlyLabelChanged"))
        case "detail-quiet-request-rediscovery":return DetailFeedback.quietRequestRediscovery(action:try required(Bool.self,i,"action"),onlyLabelChanged:try required(Bool.self,i,"onlyLabelChanged"),rediscovery:try required(Bool.self,i,"rediscovery"))
        case "detail-reader-focus-clear":return DetailFeedback.readerFocusMayClear(currentPage:try required(Bool.self,i,"currentPage"),ownedHeader:try required(Bool.self,i,"ownedHeader"),sameNativeNode:try required(Bool.self,i,"sameNativeNode"))
        case "detail-retry-description":return DetailFeedback.retryDescription(reason:try required(String.self,i,"reason"))
        case "detail-retry-failure-cue":return DetailFeedback.retryFailureCue(baseline:try required(Int64?.self,i,"baseline"),attempt:try required(Int64.self,i,"attempt"),loading:try required(Bool.self,i,"loading"),failed:try required(Bool.self,i,"failed"),resumed:try required(Bool.self,i,"resumed"))
        case "detail-retry-attempt-succeeded":return DetailFeedback.retryAttemptSucceeded(baseline:try required(Int64.self,i,"baseline"),attempt:try required(Int64.self,i,"attempt"),loading:try required(Bool.self,i,"loading"),failed:try required(Bool.self,i,"failed"))
        case "detail-retry-cue-can-wait":return DetailFeedback.retryCueCanWait(accessibilityEnabled:try required(Bool.self,i,"accessibilityEnabled"),elapsedMillis:try required(Int64.self,i,"elapsedMillis"))
        case "detail-completion-presented-played":return DetailFeedback.completionPresentedPlayed(played:try required(Bool.self,i,"played"),completionIntent:try required(Bool.self,i,"completionIntent"))
        case "detail-completion-intent-retained":return DetailFeedback.completionIntentRetained(reportFailed:try required(Bool.self,i,"reportFailed"),reportPending:try required(Bool.self,i,"reportPending"),rawReadSucceeded:try required(Bool.self,i,"rawReadSucceeded"))
        case "detail-raw-read-acknowledgement":return DetailFeedback.rawReadMatchesAcknowledgement(raw:try required(ItemUserData?.self,i,"raw"),acknowledged:try required(ItemUserData.self,i,"acknowledged"))
        case "detail-played-request-notice":return DetailFeedback.playedRequestNotice(wanted:try required(Bool.self,i,"wanted"),current:try required(Bool.self,i,"current")) as Any? ?? NSNull()
        case "detail-request-once-feedback":
            struct Step: Decodable { let request: Int64?; let eligible: Bool; let submitted: Bool }
            var feedback = DetailRequestOnceFeedback()
            return try required([Step].self,i,"steps").map { feedback.update(request:$0.request,eligible:$0.eligible,submitted:$0.submitted) }
        case "visibility-play":return PlaybackVisibility.playWhenReady(requested:try required(Bool.self,i,"requested"),foregroundRequired:try required(Bool.self,i,"foregroundRequired"),foreground:try required(Bool.self,i,"foreground"),pauseLatched:try required(Bool.self,i,"pauseLatched"))
        case "detail-completion-read":return DetailFeedback.readCanReplaceCompletion(completionItem:try required(String.self,i,"completionItem"),completionRead:try required(Int64.self,i,"completionRead"),item:try required(String.self,i,"item"),read:try required(Int64.self,i,"read"),succeeded:try required(Bool.self,i,"succeeded"))
        case "detail-cold-pending":return DetailFeedback.needsPendingControls(loading:try required(Bool.self,i,"loading"),firstArrival:try required(Bool.self,i,"firstArrival"),canQueue:try required(Bool.self,i,"canQueue"),returningFromPlayback:try defaulted(Bool.self,i,"returningFromPlayback",false))
        case "detail-requested-label":return DetailFeedback.requestedActionLabel(target:try required(String.self,i,"target"),newlyAccepted:try required(Bool.self,i,"newlyAccepted"))
        case "catalog-refresh-revision":return DetailFeedback.catalogRevisionNeedsRefresh(previous:try required(Int64?.self,i,"previous"),current:try required(Int64.self,i,"current"))
        case "detail-read-band":return DetailFeedback.readBandText(retrying:try required(Bool.self,i,"retrying"),reason:try required(String.self,i,"reason"))
        case "detail-session-read":return DetailFeedback.sessionNeedsRead(newRevision:try required(Bool.self,i,"newRevision"),refreshed:try required(Bool.self,i,"refreshed"),container:try required(Bool.self,i,"container"),alreadyConfirmed:try required(Bool.self,i,"alreadyConfirmed"))
        case "detail-read-failure":return DetailFeedback.readHasFailure(readFailed:try required(Bool.self,i,"readFailed"),reason:try required(String?.self,i,"reason"),mutationReady:try required(Bool.self,i,"mutationReady"))
        case "detail-request-cue-active":return DetailFeedback.requestCueRemainsActive(active:try required(Bool.self,i,"active"),readerLeftAction:try required(Bool.self,i,"readerLeftAction"),requested:try defaulted(Bool.self,i,"requested",true),presented:try defaulted(Bool.self,i,"presented",true))
        case "detail-confirmed-leaf":return DetailFeedback.hasConfirmedLeafRead(readSucceeded:try required(Bool.self,i,"readSucceeded"),sameUserData:try required(Bool.self,i,"sameUserData"),container:try required(Bool.self,i,"container"))
        case "detail-read-dependencies":return DetailFeedback.readRequiresDependents(type:try required(String.self,i,"type"))
        case "seek-step":return SeekSteps.target(positionMs:try required(Int64.self,i,"positionMs"),pendingMs:try required(Int64?.self,i,"pendingMs"),deltaMs:try required(Int64.self,i,"deltaMs"),durationMs:try required(Int64.self,i,"durationMs"))
        case "playback-report-fields":return PlaybackReportFields.describe(kind:try required(String.self,i,"kind"),positionTicks:try required(Int64.self,i,"positionTicks"),audio:try required(Int?.self,i,"audio"),subtitle:try required(Int?.self,i,"subtitle"),audioViewer:try required(Bool.self,i,"audioViewer"),subtitleViewer:try required(Bool.self,i,"subtitleViewer"))
        case "external-subtitle-loading":return ["retryDelayMs":ExternalSubtitleLoading.retryDelayMs(errorCount:try required(Int.self,i,"errorCount")),"sourceOffsetMs":ExternalSubtitleLoading.sourceOffsetMs(fullTimeline:try required(Bool.self,i,"fullTimeline"),serverStartMs:try required(Int64.self,i,"serverStartMs"))]
        case "detail-empty":return DetailAvailability.showEmptyState(itemType:try required(String?.self,i,"itemType"),loading:try required(Bool.self,i,"loading"),readSucceeded:try required(Bool.self,i,"readSucceeded"),readFailed:try required(Bool.self,i,"readFailed"),episodeCount:try required(Int.self,i,"episodeCount"),seasonCount:try required(Int.self,i,"seasonCount"))
        case "detail-action-state":return DetailFeedback.actionDescription(currentState:try required(String?.self,i,"currentState"),available:try required(Bool.self,i,"available")) as Any? ?? NSNull()
        case "detail-held-label":return DetailFeedback.heldActionLabel(target:try required(String.self,i,"target"))
        case "detail-unavailable-label":return DetailFeedback.unavailableActionLabel(target:try required(String.self,i,"target"))
        case "detail-ready-feedback":
            struct Step: Decodable { let held: Bool; let focused: String?; let resumed: Bool; let usable: Bool?; let presented: Bool?; let cancelled: Bool?; let nativePresented: String?; let submitted: Bool? }
            var feedback = DetailReadyFeedback()
            return try required([Step].self,i,"steps").map { feedback.update(held:$0.held,focused:$0.focused,resumed:$0.resumed,usable:$0.usable ?? true,presented:$0.presented ?? false,cancelled:$0.cancelled ?? false,nativePresented:$0.nativePresented,submitted:$0.submitted ?? true) as Any? ?? NSNull() }
        case "detail-read-lifecycle":
            var lifecycle = DetailReadLifecycle()
            return try required([Bool].self,i,"resumes").map { _ in lifecycle.resumed() }
        case "detail-metadata-handoff":return DetailFeedback.metadataAwaitsHandoff(hasActions:try required(Bool.self,i,"hasActions"),handoffFinished:try required(Bool.self,i,"handoffFinished"),returningFromPlayback:try required(Bool.self,i,"returningFromPlayback"),failed:try required(Bool.self,i,"failed"))
        case "detail-host-focus-handoff":return DetailFeedback.suppressesEmptyHostFocus(ordinaryArrivalWithActions:try required(Bool.self,i,"ordinaryArrivalWithActions"),handoffFinished:try required(Bool.self,i,"handoffFinished"),returningFromPlayback:try required(Bool.self,i,"returningFromPlayback"),recoveryHandoff:try required(Bool.self,i,"recoveryHandoff"),failed:try required(Bool.self,i,"failed"))
        case "detail-arrival-pending":
            var pending = true
            return try required([Bool].self,i,"restored").map { pending = DetailFeedback.arrivalStillPending(wasPending:pending,handoffFinished:$0); return pending }
        case "report-track-index":return TrackSelectionReporting.index(selectedIndex:try required(Int?.self,i,"selectedIndex"),chosenByViewer:try required(Bool.self,i,"chosenByViewer")) as Any? ?? NSNull()
        case "track-selection-unchanged":return TrackSelection.unchanged(currentIndex:try required(Int.self,i,"currentIndex"),pickedIndex:try required(Int.self,i,"pickedIndex"))
        case "subtitle-selection-local":return ExternalSubtitleLoading.canSelectLocally(transcoding:try required(Bool.self,i,"transcoding"),subtitle:try required(Bool.self,i,"subtitle"),currentLocal:try required(Bool.self,i,"currentLocal"),nextLocal:try required(Bool.self,i,"nextLocal"))
        case "detail-failure-key":return DetailFeedback.failureKey(readRecovery:try required(Bool.self,i,"readRecovery"),retrying:try required(Bool.self,i,"retrying"),writeAttempt:try required(Int64?.self,i,"writeAttempt")) as Any? ?? NSNull()
        case "detail-failure-presentation":
            struct Step:Decodable { let key:String?;let message:String? }
            var previous:DetailFailurePresentation?
            return try required([Step].self,i,"steps").map { step -> Any in
                previous=DetailFeedback.presentFailure(previous:previous,key:step.key,message:step.message)
                return previous?.message as Any? ?? NSNull()
            }
        case "detail-request-priority":return DetailFeedback.requestIsUrgent(newlyAccepted:try required(Bool.self,i,"newlyAccepted"),requested:try required(Bool.self,i,"requested"),available:try required(Bool.self,i,"available"))
        case "catalog-arrival-animation":return DetailFeedback.catalogArrivalUsesAnimation(accessibilityEnabled:try required(Bool.self,i,"accessibilityEnabled"),sourceIsPlayback:try required(Bool.self,i,"sourceIsPlayback"),destinationIsCatalog:try required(Bool.self,i,"destinationIsCatalog"),returning:try defaulted(Bool.self,i,"returning",false))
        case "detail-completion-may-apply":return DetailFeedback.completionMayApply(reportChoiceRevision:try required(Int64?.self,i,"reportChoiceRevision"),currentChoiceRevision:try required(Int64.self,i,"currentChoiceRevision"))
        case "detail-pending-feedback":
            struct Step: Decodable { let waiting: Bool; let graceExpired: Bool; let focused: String?; let resumed: Bool; let cancelled: Bool; let submitted: Bool? }
            var feedback = DetailPendingFeedback()
            return try required([Step].self,i,"steps").map { step -> Any in
                let cue = feedback.update(waiting:step.waiting,graceExpired:step.graceExpired,focused:step.focused,resumed:step.resumed,cancelled:step.cancelled)
                if cue != nil, step.submitted ?? true { feedback.submitted() }
                return cue as Any? ?? NSNull()
            }
        case "detail-pending-plain-label":return DetailFeedback.pendingPlainLabel(target:try required(String.self,i,"target"),known:try required(Bool.self,i,"known"),played:try required(Bool.self,i,"played"),favorite:try required(Bool.self,i,"favorite"))
        case "detail-retain-season-focus":return DetailAvailability.retainsSeasonFocus(selectedSection:try required(String.self,i,"selectedSection"),restored:try required(Bool.self,i,"restored"),semanticsRevealed:try required(Bool.self,i,"semanticsRevealed"),selectedId:try required(String.self,i,"selectedId"),seasonIds:try required([String].self,i,"seasonIds"))
        case "detail-ready-cue":return DetailFeedback.readyCue(actionLabel:try required(String.self,i,"label"))
        case "next-countdown":return NextEpisodeCard.seconds(wallSeconds:try required(Int64.self,i,"wallSeconds"),creditsCountdown:try required(Bool.self,i,"creditsCountdown"),mediaLeftMs:try required(Int64.self,i,"mediaLeftMs"),speed:try required(Double.self,i,"speed"),autoPlay:try required(Bool.self,i,"autoPlay"),ended:try required(Bool.self,i,"ended"),suspended:try required(Bool.self,i,"suspended")) as Any? ?? NSNull()
        case "track-label": return PlaybackTrackLabels.label(try required(MediaStream.self,i,"stream"), trackNumber: optionalInt(i,"trackNumber"), showDefault: bool(i,"showDefault",true))
        case "track-labels": return PlaybackTrackLabels.labels(try required([MediaStream].self,i,"streams"))
        case "server-label": return ServerLabels.label(try required(Server.self,i,"server"),all:try required([Server].self,i,"all"))
        case "grid-vertical-move", "grid-vertical-outcome", "grid-hold-release":
            let move = GridNavigation.verticalMove(index:try required(Int.self,i,"index"),columns:try required(Int.self,i,"columns"),count:try required(Int.self,i,"count"),down:operation == "grid-hold-release" ? true : try required(Bool.self,i,"down"),loadingMore:operation == "grid-hold-release" ? false : try defaulted(Bool.self,i,"loadingMore",false),pageFailed:try defaulted(Bool.self,i,"pageFailed",false))
            if operation == "grid-vertical-move" { if case let .card(index) = move { return index }; return NSNull() }
            switch move { case let .card(index):return ["outcome":"card","index":index];case .hold:return ["outcome":"hold"];case .stay:return ["outcome":"stay"];case .above:return ["outcome":"above"];case .pageRetry:return ["outcome":"page-retry"] }
        case "grid-strip-detail":return GridNavigation.stripDetail(sortBy:try required(String.self,i,"sortBy"),descending:try required(Bool.self,i,"descending"))
        case "fixed-head-collapses":return GridNavigation.fixedHeadCollapses(fontScale:try required(Float.self,i,"fontScale"),contentFocused:try required(Bool.self,i,"contentFocused"),assistiveServiceInUse:try required(Bool.self,i,"assistiveServiceInUse"))
        case "fixed-head-in-list":return GridNavigation.fixedHeadInList(fontScale:try required(Float.self,i,"fontScale"),assistiveServiceInUse:try required(Bool.self,i,"assistiveServiceInUse"))
        case "conversion-notice":
            let method=try required(String.self,i,"method")
            guard PlayMethod(rawValue:method) != nil else { throw NSError(domain:"Unknown play method \(method)",code:1) }
            let notice = ConversionNotice.of(method:method,url:try required(String.self,i,"url"),source:try i["source"].map { try decode(MediaSource.self,$0) } ?? MediaSource(id:nil),reasons:try required([String].self,i,"reasons"),subtitleIndex:try defaulted(Int.self,i,"subtitleIndex",-1),device:try required(String.self,i,"device"))
            return notice.map { "\($0.title)\n\($0.reason)\n\($0.fix)" } ?? "none"
        case "reason":return PlaybackInformation.reason(try required(String.self,i,"reason"),qualityMbps:try defaulted(Int?.self,i,"qualityMbps",nil))
        case "method":return PlaybackInformation.method(try required(String.self,i,"method"),url:try required(String.self,i,"url"))
        case "subtitle":return PlaybackInformation.subtitle(source:try decode(MediaSource.self,i["source"]!),selectedIndex:try required(Int.self,i,"index"))
        case "bitrate":return PlaybackInformation.bitrate(try required(Int64?.self,i,"bitrate"))
        case "speed":return String(PlaybackSpeeds.canApply(try required(Double.self,i,"speed"),encodedOutput:try required(Bool.self,i,"encoded")))
        case "detail-arrival-publication":return DetailFeedback.arrivalAwaitsAction(loading:try required(Bool.self,i,"loading"),contentRevealed:try required(Bool.self,i,"contentRevealed"),returningFromPlayback:try required(Bool.self,i,"returningFromPlayback"))
        case "settings":let p=try decode(PlaybackPreferences.self,i);return ["bitrateMbps":p.bitrateMbps as Any? ?? NSNull(),"passthrough":p.passthrough,"subtitleSize":p.subtitleSize.rawValue,"subtitleBackground":p.subtitleBackground.rawValue,"segmentModes":Dictionary(uniqueKeysWithValues:p.segmentModes.map{($0.key.rawValue,$0.value.rawValue)}),"maxStreamingBitrate":p.maxStreamingBitrate,"passthroughEnabled":p.passthrough]
        case "validate-bitrate":do{try PlaybackPreferences.validateBitrate(optionalInt(i,"bitrateMbps"));return ["valid":true]}catch{return ["valid":false]}
        case "default-stream":return PlaybackSelection.defaultStream(streams:try decode([MediaStream].self,i["streams"]!),type:text(i,"type"),serverIndex:optionalInt(i,"serverIndex"),preferredLanguage:text(i,"preferredLanguage"))
        case "resume-start":return PlaybackSelection.startPositionMillis(ticks:integer(i,"ticks"),resume:bool(i,"resume"),explicitPositionMs:(i["explicitPositionMs"] as? NSNumber)?.int64Value)
        case "end-position":return ["bounded":PlaybackEndPosition.bounded(integer(i,"positionMs"),durationMs:integer(i,"durationMs")),"completed":PlaybackEndPosition.completed(integer(i,"positionMs"),durationMs:integer(i,"durationMs"))]
        case "caption-language":let config=try (i["configuration"] as? [String:Any]).map{UserConfiguration(fields:try decode([String:JSONValue].self,$0))};return Captions.language(enabled:bool(i,"enabled"),configuration:config,deviceLanguage:text(i,"tvLanguage")) as Any? ?? NSNull()
        case "caption-index":return Captions.index(source:try decode(MediaSource.self,i["source"]!),preferredLanguage:text(i,"preferredLanguage")) as Any? ?? NSNull()
        case "active-segment":let modes=Dictionary(uniqueKeysWithValues:(i["modes"] as! [String:String]).compactMap{k,v in SegmentType(rawValue:k).flatMap{t in SegmentMode(rawValue:v).map{(t,$0)}}});return PlaybackExtras.activeSegment(try decode([MediaSegment].self,i["segments"]!),positionMs:integer(i,"positionMs"),modes:modes)?.id as Any? ?? NSNull()
        case "next-trigger":return PlaybackExtras.nextTrigger(durationMs:integer(i,"durationMs"),segments:try decode([MediaSegment].self,i["segments"]!)) as Any? ?? NSNull()
        case "next-adjacency":let current=try decode(MediaItem.self,i["item"]!);let nextClient=mockClient(["responses":[["status":200,"body":["Items":i["items"]!]]]],identity:identity);let next=try await nextClient.nextEpisode(account:account,item:current);if current.type == "Episode",let series=current.seriesId,!series.isEmpty {XCTAssertEqual(VectorProtocol.requests.count,1);XCTAssertEqual(VectorProtocol.requests.first?["path"] as? String,"/jellyfin/Shows/\(ServerURLs.encode(series))/Episodes");XCTAssertEqual(VectorProtocol.requests.first?["query"] as? [String:[String]],["userId":[account.userId],"adjacentTo":[current.id],"isMissing":["false"],"enableUserData":["true"],"fields":["Overview"]])}else{XCTAssertTrue(VectorProtocol.requests.isEmpty)};return next?.id as Any? ?? NSNull()
        case "user-configuration":var c=UserConfiguration(fields:try decode([String:JSONValue].self,i["fields"]!));if let change=i["change"] as? [String:Any]{c=try c.withPreference(text(change,"name"),value:text(change,"value"))};let fields=try JSONSerialization.jsonObject(with:JSONEncoder().encode(c.fields));return ["audioLanguage":c.audioLanguage,"subtitleLanguage":c.subtitleLanguage,"subtitleMode":c.subtitleMode,"fields":fields]
        case "address":do{return ["urls":try ServerURLs.candidates(text(i,"value")).map(\.absoluteString)]}catch{return ["error":"IllegalArgumentException"]}
        case "authorization":return AuthorizationHeader.build(deviceID:text(i,"deviceId",text(identity,"deviceId","install")),deviceName:text(i,"deviceName",text(identity,"deviceName","Fixture TV")),version:text(i,"version",text(identity,"version","0.1.0")),token:i["token"] as? String)
        case "sameOrigin":return URL(string:text(i,"url")).map{ServerURLs.isSameOrigin(server.url,$0)} ?? false
        case "resolve":return try ServerURLs.resolve(server:server,reference:text(i,"url")).absoluteString
        case "stripApiKey":return ServerURLs.strippingCredentials(URL(string:text(i,"url"))!).absoluteString
        case "image":return client.imageURL(account:account,item:try decode(MediaItem.self,i["item"]!),maxWidth:Int(integer(i,"width")),maxHeight:optionalInt(i,"height"),wide:bool(i,"wide"))?.absoluteString as Any? ?? NSNull()
        case "chapterImage":return client.chapterImageURL(account:account,itemID:text(i,"itemId"),index:Int(integer(i,"index")),tag:text(i,"tag"))!.absoluteString
        case "trickplaySheet":return client.trickplaySheetURL(account:account,itemID:text(i,"itemId"),mediaSourceID:text(i,"mediaSourceId"),width:Int(integer(i,"width")),sheet:Int(integer(i,"sheet")))!.absoluteString
        case "decision":do{return decisionJSON(try PlayDecisions.decide(account:account,itemID:text(i,"itemId"),response:decode(PlaybackInfo.self,i["response"]!),mediaSourceID:i["mediaSourceId"] as? String,deviceName:text(identity,"deviceName","Fixture TV"),version:text(identity,"version","0.1.0")))}catch{return ["unplayable":errorName(error)]}
        case "ticks":return Ticks.from(nanoseconds:integer(i,"nanoseconds"))
        case "reports":var source=MediaSource(id:"source");source.liveStreamId="live";let d=PlayDecision(itemID:"film",url:URL(string:"https://media.example.test/unused")!,headers:[:],method:PlayMethod(rawValue:text(i,"method"))!,source:source,playSessionId:"session",startTicks:0,transcodeReasons:[]);return ["progress":PlaybackReports.progress(decision:d,positionTicks:integer(i,"positionMs")*10_000,paused:bool(i,"paused"),audioIndex:optionalInt(i,"audioStreamIndex"),subtitleIndex:optionalInt(i,"subtitleStreamIndex"),canSeek:bool(i,"canSeek")),"stopped":PlaybackReports.stopped(decision:d,positionTicks:integer(i,"positionMs")*10_000,failed:bool(i,"failed"))]
        case "profile":return ReferenceDeviceProfile.build(try decode(DeviceCapabilities.self,i["capabilities"]!))
        case "request":let c=try decode(DeviceCapabilities.self,i["capabilities"]!);return PlaybackRequests.body(userID:account.userId,profile:ReferenceDeviceProfile.build(c),maxStreamingBitrate:c.settings?.maxStreamingBitrate ?? 200_000_000,startTicks:integer(i,"startMs")*10_000,mediaSourceID:i["mediaSourceId"] as? String,audioIndex:optionalInt(i,"audioStreamIndex"),subtitleIndex:optionalInt(i,"subtitleStreamIndex"),enableDirectPlay:bool(i,"enableDirectPlay",true),enableDirectStream:bool(i,"enableDirectStream",true),allowVideoStreamCopy:bool(i,"allowVideoStreamCopy",true),allowAudioStreamCopy:bool(i,"allowAudioStreamCopy",true))
        case "validateBitrate":do{return try DeviceSettings.validateBitrate(Int(integer(i,"value")))}catch{return ["error":"IllegalArgumentException"]}
        case "compatibility":return profileFailures(ReferenceDeviceProfile.build(try decode(DeviceCapabilities.self,i["capabilities"]!)),i["combo"] as! [String:Any])
        case "homeRows":return Catalog.assembleHome(views:try decode([MediaItem].self,i["views"]!),resume:try decode([MediaItem].self,i["resume"]!),nextUp:try decode([MediaItem].self,i["nextUp"]!),latest:try decode([String:[MediaItem]].self,i["latest"]!)).map { ["key":$0.id,"title":$0.title,"items":$0.items.map(\.id),"library":$0.library,"wide":$0.wide] }
        case "timelineThroughTrackSwitch":let responses=i["responses"] as! [[String:Any]];let positions=(i["positionsMs"] as! [NSNumber]).map(\.int64Value);let decisions=try responses.enumerated().map { index,response in try PlayDecisions.decide(account:account,itemID:text(i,"itemId"),response:decode(PlaybackInfo.self,response),startTicks:(index == 0 ? integer(i,"startMs"):positions[1])*10_000) };return ["initialSeekMs":decisions.map{Int64($0.initialSeekSeconds*1000)},"positionTicks":positions.enumerated().map{index,position in decisions[index<2 ? 0:1].serverPositionTicks(playerSeconds:Double(position)/1000)},"sessions":decisions.map{$0.playSessionId!}]
        case "http":return try await http(i,account:account,identity:identity)
        case "seek-required","seek-start","seek-url","audio-encodes","preserve-video":let v=i["decision"] as! [String:Any];let d=PlayDecision(itemID:text(v,"itemId"),url:URL(string:text(v,"url"))!,headers:v["headers"] as! [String:String],method:PlayMethod(rawValue:text(v,"method"))!,source:try decode(MediaSource.self,v["source"]!),playSessionId:v["playSessionId"] as? String,startTicks:0,transcodeReasons:v["transcodeReasons"] as! [String]);switch operation{case "seek-required":return PlaybackRecovery.serverSeekRequired(d);case "seek-start":return PlaybackRecovery.serverSeekStart(d,targetMs:integer(i,"targetMs"));case "seek-url":return parts(PlaybackRecovery.serverSeekURL(d,startMs:integer(i,"startMs")));case "audio-encodes":return PlaybackRecovery.audioAlreadyEncodes(d);default:return PlaybackRecovery.preserveVideo(d,subtitleIndex:optionalInt(i,"subtitleStreamIndex")).map{decisionJSON($0,urlParts:true)} ?? ["unplayable":"NO_COMPATIBLE_SOURCE"]}
        case "prepare-preserve-video":return try await prepareRecovery(i,context:context,account:account,identity:identity)
        default:throw NSError(domain:"Unknown vector operation \(operation)",code:1)
        }
    }
    func mockClient(_ i:[String:Any],identity:[String:Any])->JellyfinClient{VectorProtocol.responses=i["responses"] as? [[String:Any]] ?? [];VectorProtocol.requests=[];let config=URLSessionConfiguration.ephemeral;config.protocolClasses=[VectorProtocol.self];return JellyfinClient(deviceID:text(identity,"deviceId","install"),session:URLSession(configuration:config),deviceName:text(identity,"deviceName","Fixture TV"),version:text(identity,"version","0.1.0"))}
    func http(_ i:[String:Any],account:Account,identity:[String:Any])async throws->Any{
        let action=text(i,"action");guard ["catalog.views","catalog.resume","catalog.nextUp","catalog.latest","catalog.seasons","catalog.episodes","catalog.item","catalog.items","catalog.played","catalog.favorite","search","personItems","prepare","configuration","saveConfiguration","stopEncoding","reportStart","reportProgress","reportStopped"].contains(action) else { throw NSError(domain:"Unknown HTTP action \(action)",code:1) };let client=mockClient(i,identity:identity);var result:[String:Any]=["kind":"success"]
        do {switch action {
        case "catalog.views":_ = try await client.views(account:account)
        case "catalog.resume":_ = try await client.resume(account:account)
        case "catalog.nextUp":_ = try await client.nextUp(account:account,seriesID:i["seriesId"] as? String)
        case "catalog.latest":_ = try await client.latest(account:account,parentID:text(i,"parentId"))
        case "catalog.seasons":_ = try await client.seasons(account:account,seriesID:text(i,"seriesId"))
        case "catalog.episodes":_ = try await client.episodes(account:account,seriesID:text(i,"seriesId"),seasonID:text(i,"seasonId"))
        case "catalog.item":result["resumeTicks"] = try await client.item(account:account,id:text(i,"itemId")).userData.playbackPositionTicks
        case "catalog.items":_ = try await client.browsePage(account:account,parentID:text(i,"parentId","library"),startIndex:Int(integer(i,"startIndex")),limit:Int(integer(i,"limit",60)),sortBy:text(i,"sortBy","SortName"),descending:bool(i,"descending"),recursive:bool(i,"recursive"),includeItemTypes:i["includeItemTypes"] as? String,ids:i["ids"] as? [String] ?? [])
        case "catalog.played":_ = try await client.played(account:account,itemID:text(i,"itemId"),value:bool(i,"value"))
        case "catalog.favorite":_ = try await client.favorite(account:account,itemID:text(i,"itemId"),value:bool(i,"value"))
        case "search":let map:[String:SearchKind]=["MOVIES":.movies,"SHOWS":.shows,"EPISODES":.episodes,"COLLECTIONS":.collections,"PEOPLE":.people];_ = try await client.search(account:account,query:text(i,"query"),kind:map[text(i,"kind")]!)
        case "personItems":_ = try await client.personItems(account:account,personID:text(i,"personId"),startIndex:Int(integer(i,"startIndex")),limit:Int(integer(i,"limit")))
        case "prepare":do{result["outcome"]=decisionJSON(try await client.prepareReference(account:account,itemID:text(i,"itemId"),capabilities:decode(DeviceCapabilities.self,i["capabilities"]!),startMs:integer(i,"startMs"),mediaSourceID:i["mediaSourceId"] as? String,audioIndex:optionalInt(i,"audioStreamIndex"),subtitleIndex:optionalInt(i,"subtitleStreamIndex")))}catch let e as ClientError where [.serverRefused,.sourceNotFound,.noCompatibleSource,.unsafeStreamURL].contains(e){result["outcome"]=["unplayable":errorName(e)]}
        case "configuration":let configuration=try await client.configuration(account:account);result["configuration"]=try JSONSerialization.jsonObject(with:JSONEncoder().encode(configuration.fields))
        case "saveConfiguration":try await client.saveConfiguration(account:account,configuration:UserConfiguration(fields:decode([String:JSONValue].self,i["fields"]!)))
        case "stopEncoding":try await client.stopEncoding(account:account,playSessionID:text(i,"playSessionId"))
        case "reportStart","reportProgress","reportStopped":try await client.sendReport(account:account,kind:["reportStart":"start","reportProgress":"progress","reportStopped":"stopped"][action]!,body:i["body"] as! [String:Any])
        default:throw NSError(domain:"Unknown HTTP action \(action)",code:1)
        }}catch{let kind:String;switch error as? ClientError{case .invalidCredentials:kind=action.hasPrefix("catalog.") || action == "search" || action == "personItems" ? "EXPIRED":"INVALID_CREDENTIALS";case .server:kind="SERVER";case .network:kind="NETWORK";default:kind="INVALID_RESPONSE"};result=["kind":"failure","errorKind":kind]}
        return ["requests":VectorProtocol.requests,"result":result]
    }
    func prepareRecovery(_ i:[String:Any],context:[String:Any],account:Account,identity:[String:Any])async throws->Any{
        let client=mockClient(["responses":[["status":200,"body":i["response"]!]]],identity:identity);let r=i["request"] as! [String:Any];let outcome:[String:Any]
        do{outcome=decisionJSON(try await client.prepareReference(account:account,itemID:"film",capabilities:decode(DeviceCapabilities.self,context["capabilities"]!),startMs:integer(r,"startMs"),mediaSourceID:text(r,"mediaSourceId"),audioIndex:optionalInt(r,"audioStreamIndex"),subtitleIndex:optionalInt(r,"subtitleStreamIndex"),enableDirectPlay:bool(r,"enableDirectPlay"),enableDirectStream:bool(r,"enableDirectStream"),allowVideoStreamCopy:bool(r,"allowVideoStreamCopy"),allowAudioStreamCopy:bool(r,"allowAudioStreamCopy"),requireVideoStreamCopy:bool(r,"requireVideoStreamCopy")),urlParts:true)}catch{outcome=["unplayable":errorName(error)]}
        let body=VectorProtocol.requests.first?["body"] as? [String:Any] ?? [:];let names=["UserId","MaxStreamingBitrate","StartTimeTicks","MediaSourceId","AudioStreamIndex","SubtitleStreamIndex","EnableDirectPlay","EnableDirectStream","AllowVideoStreamCopy","AllowAudioStreamCopy"]
        return ["request":Dictionary(uniqueKeysWithValues:names.map{($0,body[$0] ?? NSNull())}),"outcome":outcome]
    }
    /// Independent reduced server evaluator, matching the shared Kotlin test model, not the native decoder.
    func profileFailures(_ profile:[String:Any],_ combo:[String:Any])->[String]{
        func contains(_ list:String?,_ value:String)->Bool{guard let list,!list.isEmpty else{return true};let values=value.lowercased().split(separator:",").map{$0.trimmingCharacters(in:.whitespaces)};return list.lowercased().split(separator:",").contains{values.contains($0.trimmingCharacters(in:.whitespaces))}}
        var failures=Set<String>();let container=text(combo,"container");let video=(profile["DirectPlayProfiles"] as! [[String:Any]]).filter{text($0,"Type") == "Video"};let containers=video.filter{contains($0["Container"] as? String,container)}
        if containers.isEmpty{failures.insert("ContainerNotSupported")}
        let matching=containers.filter{ p in (combo["videoCodec"] as? String).map{contains(p["VideoCodec"] as? String,$0)} ?? true}
        if !containers.isEmpty && matching.isEmpty{failures.insert("VideoCodecNotSupported")}
        if !matching.isEmpty,let audio=combo["audioCodec"] as? String,!matching.contains(where:{contains($0["AudioCodec"] as? String,audio)}){failures.insert("AudioCodecNotSupported")}
        func satisfied(_ c:[String:Any])->Bool{
            let property=text(c,"Property");let keys=["AudioChannels":"audioChannels","VideoBitDepth":"bitDepth","VideoProfile":"videoProfile","VideoRangeType":"rangeType","Width":"width","Height":"height","VideoFramerate":"frameRate","VideoLevel":"level"]
            let raw=keys[property].flatMap{combo[$0]};var actual:String?=raw as? String;if let n=raw as? NSNumber{actual=n.stringValue};if property == "VideoFramerate",actual == nil{actual="0"}
            guard let actual else{return !bool(c,"IsRequired")};let expected=text(c,"Value")
            switch text(c,"Condition"){case "Equals":return actual.caseInsensitiveCompare(expected) == .orderedSame;case "NotEquals":return actual.caseInsensitiveCompare(expected) != .orderedSame;case "LessThanEqual":return (Double(actual) ?? .infinity)<=(Double(expected) ?? -.infinity);case "GreaterThanEqual":return (Double(actual) ?? -.infinity)>=(Double(expected) ?? .infinity);case "EqualsAny":let options=expected.lowercased().components(separatedBy:"|");return options.contains(actual.lowercased()) || (property == "VideoRangeType" && actual == "HDR10Plus" && options.contains("hdr10"));default:return false}
        }
        let reasons=["AudioChannels":"AudioChannelsNotSupported","VideoBitDepth":"VideoBitDepthNotSupported","VideoProfile":"VideoProfileNotSupported","VideoRangeType":"VideoRangeTypeNotSupported","Width":"VideoResolutionNotSupported","Height":"VideoResolutionNotSupported","VideoFramerate":"VideoFramerateNotSupported","VideoLevel":"VideoLevelNotSupported"]
        for (type,key) in [("Video","videoCodec"),("VideoAudio","audioCodec")]{guard let codec=combo[key] as? String else{continue};for p in profile["CodecProfiles"] as! [[String:Any]] where text(p,"Type") == type && contains(p["Codec"] as? String,codec) && (p["Container"] == nil || contains(p["Container"] as? String,container)){if (p["ApplyConditions"] as! [[String:Any]]).allSatisfy(satisfied){for c in (p["Conditions"] as! [[String:Any]]) where !satisfied(c){failures.insert(reasons[text(c,"Property")] ?? "UnknownReason")}}}}
        for subtitle in combo["subtitles"] as? [String] ?? []{let deliverable=(profile["SubtitleProfiles"] as! [[String:Any]]).contains{p in text(p,"Format").caseInsensitiveCompare(subtitle) == .orderedSame && ((text(p,"Method") == "Embed" && contains(p["Container"] as? String,container)) || (text(p,"Method") == "External" && ["srt","subrip","ass","ssa","vtt","webvtt","ttml","mov_text"].contains(subtitle)))};if !deliverable{failures.insert("SubtitleCodecNotSupported")}}
        return failures.sorted()
    }
}
