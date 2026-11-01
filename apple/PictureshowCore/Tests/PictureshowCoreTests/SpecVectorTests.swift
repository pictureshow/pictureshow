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
    func json(_ value:Any)throws->String{String(data:try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys,.fragmentsAllowed]),encoding:.utf8)!}
    func errorName(_ error:Error)->String{switch error as? ClientError{case .serverRefused:return "SERVER_REFUSED";case .sourceNotFound:return "SOURCE_NOT_FOUND";case .noCompatibleSource:return "NO_COMPATIBLE_SOURCE";case .unsafeStreamURL:return "UNSAFE_STREAM_URL";default:return "UNEXPECTED_\(error)"}}
    func decisionJSON(_ d:PlayDecision,urlParts:Bool=false)->[String:Any]{["method":d.method.rawValue,"mediaSourceId":d.source.id as Any? ?? NSNull(),"url":urlParts ? parts(d.url):d.url.absoluteString as Any,"headers":d.headers,"playSessionId":d.playSessionId as Any? ?? NSNull(),"transcodeReasons":d.transcodeReasons]}
    func parts(_ url:URL)->[String:Any]{let c=URLComponents(url:url,resolvingAgainstBaseURL:false)!;var q:[String:[String]]=[:];for x in c.queryItems ?? []{q[x.name,default:[]].append(x.value ?? "")};return ["scheme":c.scheme!,"host":c.host!,"port":c.port ?? (c.scheme == "https" ? 443:80),"path":c.percentEncodedPath,"query":q]}
    func testAllSharedVectors()async throws{
        let root:URL
        if let override=ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"]{root=URL(fileURLWithPath:override)}else{var p=URL(fileURLWithPath:#filePath);for _ in 0..<5{p.deleteLastPathComponent()};root=p.appendingPathComponent("spec")}
        let vectors=root.appendingPathComponent("vectors");let files=try XCTUnwrap(FileManager.default.enumerator(at:vectors,includingPropertiesForKeys:nil)).compactMap{$0 as? URL}.filter{$0.pathExtension == "json"}.sorted{$0.path<$1.path}
        XCTAssertFalse(files.isEmpty,"No shared vectors at \(vectors.path)")
        // Validate every envelope and global case identity before any operation can perform mock HTTP work.
        var ids=Set<String>()
        let documents=try files.map { file -> ([String:Any],[[String:Any]]) in
            let document=try XCTUnwrap(JSONSerialization.jsonObject(with:Data(contentsOf:file)) as? [String:Any])
            guard try required(Int.self,document,"schemaVersion") == 1 else { throw NSError(domain:"Unknown vector schema in \(file.lastPathComponent)",code:1) }
            let suite=try required(String.self,document,"suite")
            guard ["reference","application","recovery"].contains(suite) else { throw NSError(domain:"Unknown vector suite \(suite)",code:1) }
            let cases=try XCTUnwrap(document["cases"] as? [[String:Any]])
            guard !cases.isEmpty else { throw NSError(domain:"Empty vector suite \(file.lastPathComponent)",code:1) }
            for vector in cases {
                let id=try required(String.self,vector,"id"),operation=try required(String.self,vector,"operation")
                guard !id.isEmpty,!operation.isEmpty,ids.insert(id).inserted,vector["input"] is [String:Any],vector["expected"] != nil else { throw NSError(domain:"Malformed or duplicate vector \(id)",code:1) }
            }
            return (document,cases)
        }
        var count=0;var counts:[String:Int]=[:];var failures:[String]=[]
        for (document,cases) in documents{
            let suite=text(document,"suite")
            for vector in cases{let id=text(vector,"id");do{let result=try await execute(text(vector,"operation"),input:vector["input"] as! [String:Any],context:document["context"] as? [String:Any] ?? [:]);let actual=try json(result),expected=try json(vector["expected"]!);if actual != expected{failures.append("\(id)\n expected: \(expected)\n actual: \(actual)")}}catch{failures.append("\(id): \(error)")};count+=1;counts[suite,default:0]+=1}
        }
        print("Shared vectors: \(count) executed \(counts), \(failures.count) failures; no skipped cases.")
        XCTAssertTrue(failures.isEmpty,failures.joined(separator:"\n"))
    }
    func execute(_ operation:String,input i:[String:Any],context:[String:Any])async throws->Any{
        let serverJSON=context["server"] as? [String:Any] ?? [:];let identity=context["identity"] as? [String:Any] ?? [:];let credentials=context["account"] as? [String:Any] ?? [:]
        let server=Server(url:URL(string:text(serverJSON,"url","https://media.example.test/jellyfin"))!);let account=Account(server:server,userId:text(credentials,"userId","viewer"),username:"Viewer",accessToken:text(credentials,"token","fixture-token"),deviceID:text(credentials,"deviceId",text(identity,"deviceId","install")))
        let client=JellyfinClient(deviceID:text(identity,"deviceId","install"),deviceName:text(identity,"deviceName","Fixture TV"),version:text(identity,"version","0.1.0"))
        switch operation{
        case "external-subtitle-loading":return ["retryDelayMs":ExternalSubtitleLoading.retryDelayMs(errorCount:try required(Int.self,i,"errorCount")),"sourceOffsetMs":ExternalSubtitleLoading.sourceOffsetMs(fullTimeline:try required(Bool.self,i,"fullTimeline"),serverStartMs:try required(Int64.self,i,"serverStartMs"))]
        case "detail-empty":return DetailAvailability.showEmptyState(itemType:try required(String?.self,i,"itemType"),loading:try required(Bool.self,i,"loading"),readSucceeded:try required(Bool.self,i,"readSucceeded"),readFailed:try required(Bool.self,i,"readFailed"),episodeCount:try required(Int.self,i,"episodeCount"),seasonCount:try required(Int.self,i,"seasonCount"))
        case "detail-action-state":return DetailFeedback.actionDescription(currentState:try required(String?.self,i,"currentState"),pending:try required(Bool.self,i,"pending"),available:try required(Bool.self,i,"available"),ready:try required(Bool.self,i,"ready"),requested:try required(Bool.self,i,"requested")) as Any? ?? NSNull()
        case "detail-failure-key":return DetailFeedback.failureKey(readRecovery:try required(Bool.self,i,"readRecovery"),retrying:try required(Bool.self,i,"retrying"),writeAttempt:try required(Int64?.self,i,"writeAttempt")) as Any? ?? NSNull()
        case "detail-failure-presentation":
            struct Step:Decodable { let key:String?;let message:String? }
            var previous:DetailFailurePresentation?
            return try required([Step].self,i,"steps").map { step -> Any in
                previous=DetailFeedback.presentFailure(previous:previous,key:step.key,message:step.message)
                return previous?.message as Any? ?? NSNull()
            }
        case "detail-request-priority":return DetailFeedback.requestIsUrgent(newlyAccepted:try required(Bool.self,i,"newlyAccepted"),requested:try required(Bool.self,i,"requested"),available:try required(Bool.self,i,"available"))
        case "catalog-arrival-animation":return DetailFeedback.catalogArrivalUsesAnimation(accessibilityEnabled:try required(Bool.self,i,"accessibilityEnabled"),sourceIsPlayback:try required(Bool.self,i,"sourceIsPlayback"),destinationIsCatalog:try required(Bool.self,i,"destinationIsCatalog"))
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
