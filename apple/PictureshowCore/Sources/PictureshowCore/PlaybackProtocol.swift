// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum PlaybackRequests {
    public static func body(userID:String,profile:[String:Any],maxStreamingBitrate:Int = 200_000_000,startTicks:Int64 = 0,mediaSourceID:String? = nil,audioIndex:Int? = nil,subtitleIndex:Int? = nil,enableDirectPlay:Bool = true,enableDirectStream:Bool = true,allowVideoStreamCopy:Bool = true,allowAudioStreamCopy:Bool = true,alwaysBurnIn:Bool = false)->[String:Any] {
        var body:[String:Any] = ["UserId":userID,"DeviceProfile":profile,"MaxStreamingBitrate":maxStreamingBitrate,"StartTimeTicks":startTicks,"EnableDirectPlay":enableDirectPlay,"EnableDirectStream":enableDirectStream,"EnableTranscoding":true,"AllowVideoStreamCopy":allowVideoStreamCopy,"AllowAudioStreamCopy":allowAudioStreamCopy,"AutoOpenLiveStream":false,"AlwaysBurnInSubtitleWhenTranscoding":alwaysBurnIn]
        if let mediaSourceID{body["MediaSourceId"]=mediaSourceID};if let audioIndex{body["AudioStreamIndex"]=audioIndex};if let subtitleIndex{body["SubtitleStreamIndex"]=subtitleIndex};return body
    }
}
public enum PlaybackReports {
    public static func progress(decision:PlayDecision,positionTicks:Int64,paused:Bool,audioIndex:Int?,subtitleIndex:Int?,canSeek:Bool = true,isMuted:Bool = false)->[String:Any] {
        var body:[String:Any] = ["ItemId":decision.itemID,"PlayMethod":decision.method.rawValue,"PositionTicks":positionTicks,"IsPaused":paused,"IsMuted":isMuted,"CanSeek":canSeek,"RepeatMode":"RepeatNone","PlaybackOrder":"Default"]
        if let value=decision.source.id{body["MediaSourceId"]=value};if let value=decision.playSessionId{body["PlaySessionId"]=value};if let value=decision.source.liveStreamId{body["LiveStreamId"]=value};if let audioIndex{body["AudioStreamIndex"]=audioIndex};if let subtitleIndex{body["SubtitleStreamIndex"]=subtitleIndex};return body
    }
    public static func stopped(decision:PlayDecision,positionTicks:Int64,failed:Bool = false)->[String:Any] {
        var body:[String:Any] = ["ItemId":decision.itemID,"PositionTicks":positionTicks,"Failed":failed]
        if let value=decision.source.id{body["MediaSourceId"]=value};if let value=decision.playSessionId{body["PlaySessionId"]=value};if let value=decision.source.liveStreamId{body["LiveStreamId"]=value};return body
    }
}
public enum PlaybackRecovery {
    static func parameter(_ decision:PlayDecision,_ name:String)->String? {URLComponents(url:decision.url,resolvingAgainstBaseURL:false)?.queryItems?.first{$0.name.caseInsensitiveCompare(name) == .orderedSame}?.value}
    public static func serverSeekRequired(_ d:PlayDecision)->Bool {
        guard d.method == .transcode,!d.source.isInfiniteStream,d.source.liveStreamId == nil,(d.source.runTimeTicks ?? 0)>0,d.source.transcodingSubProtocol == "hls",d.source.transcodingContainer?.lowercased() == "ts",d.url.path.lowercased().hasSuffix("/master.m3u8"),parameter(d,"VideoCodec")?.lowercased() != "copy",let video=d.source.mediaStreams.first(where:{$0.type == "Video"}),let rate=video.realFrameRate ?? video.averageFrameRate,let cap=Double(parameter(d,"Framerate") ?? parameter(d,"MaxFramerate") ?? "") else{return false}
        return rate.isFinite && cap.isFinite && rate>30 && cap>0 && cap+0.001<rate
    }
    public static func audioAlreadyEncodes(_ d:PlayDecision)->Bool {
        let streams=d.source.mediaStreams.filter{$0.type == "Audio"};let index=parameter(d,"AudioStreamIndex").flatMap(Int.init) ?? d.source.defaultAudioStreamIndex
        if streams.isEmpty || index == -1{return true};guard let audio=index.flatMap({ i in streams.first{$0.index == i} }) ?? (index == nil ? streams.first:nil) else{return false}
        let targets=parameter(d,"AudioCodec")?.components(separatedBy:",").map{$0.trimmingCharacters(in:.whitespaces)}
        if targets?.contains(where:{$0.lowercased() == "copy"}) == true{return false}
        if parameter(d,"AllowAudioStreamCopy")?.lowercased() == "false" || parameter(d,"EnableAutoStreamCopy")?.lowercased() == "false"{return true}
        if d.transcodeReasons.contains("AudioChannelsNotSupported"){return true}
        guard let codec=audio.codec,!codec.isEmpty,let targets,!targets.isEmpty,!targets.contains(where:{$0.isEmpty}) else{return false}
        if !targets.contains(where:{$0.caseInsensitiveCompare(codec) == .orderedSame}){return true}
        guard let channels=audio.channels else{return false}
        let maxChannels=["\(codec)-audiochannels","MaxAudioChannels","AudioChannels","TranscodingMaxAudioChannels"].compactMap{parameter(d,$0).flatMap(Int.init)}.first
        return maxChannels.map{channels>$0} ?? false
    }
    public static func serverSeekStart(_ d:PlayDecision,targetMs:Int64)->Int64 {min(max(0,targetMs),max(0,(d.source.runTimeTicks ?? 0)/10_000-5_000))}
    public static func serverSeekURL(_ d:PlayDecision,startMs:Int64)->URL {
        var c=URLComponents(url:ServerURLs.strippingCredentials(d.url),resolvingAgainstBaseURL:false)!;c.path=(c.path as NSString).deletingLastPathComponent+"/stream.ts"
        let encodes=audioAlreadyEncodes(d);let replaced=["starttimeticks","static","copytimestamps","subtitlemethod"]+(encodes ? ["allowaudiostreamcopy"]:[])
        var query=(c.queryItems ?? []).filter{!replaced.contains($0.name.lowercased())};query += [URLQueryItem(name:"StartTimeTicks",value:String(max(0,startMs)*10_000)),URLQueryItem(name:"Static",value:"false"),URLQueryItem(name:"CopyTimestamps",value:"false")]
        if encodes{query.append(URLQueryItem(name:"AllowAudioStreamCopy",value:"false"))};query.append(URLQueryItem(name:"SubtitleMethod",value:parameter(d,"SubtitleMethod") ?? "External"));c.queryItems=query;return c.url!
    }
    public static func preserveVideo(_ d:PlayDecision,subtitleIndex:Int?)->PlayDecision? {
        guard d.method == .transcode else{return d};let selected=subtitleIndex ?? d.source.defaultSubtitleStreamIndex ?? -1
        let burned=selected>=0 && (d.source.mediaStreams.contains{$0.type == "Subtitle" && $0.index == selected && $0.deliveryMethod == "Encode"} || parameter(d,"SubtitleMethod")?.lowercased() == "encode")
        guard !burned else{return nil};var copy=d;var c=URLComponents(url:d.url,resolvingAgainstBaseURL:false)!;c.queryItems=(c.queryItems ?? []).filter{!["videocodec","allowvideostreamcopy"].contains($0.name.lowercased())}+[URLQueryItem(name:"VideoCodec",value:"copy"),URLQueryItem(name:"AllowVideoStreamCopy",value:"true")];copy.url=c.url!;return copy
    }
}
