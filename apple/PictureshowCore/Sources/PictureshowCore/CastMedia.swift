// SPDX-License-Identifier: MPL-2.0
import Foundation

/// Receiver format table is deliberately independent of the sender's Apple decoders.
public enum CastMedia {
    public static func deviceProfile() -> [String:Any] {
        func condition(_ property:String,_ value:String,_ comparison:String = "LessThanEqual") -> [String:Any] { ["Condition":comparison,"Property":property,"Value":value,"IsRequired":true] }
        return ["Name":"Pictureshow Cast receiver","MaxStreamingBitrate":20_000_000,"MaxStaticBitrate":20_000_000,
                "DirectPlayProfiles":[["Container":"mp4,m4v","AudioCodec":"aac,mp3","VideoCodec":"h264","Type":"Video"]],
                "TranscodingProfiles":[["Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac","Protocol":"hls","Context":"Streaming","MaxAudioChannels":"2","MinSegments":1,"BreakOnNonKeyFrames":true,"EstimateContentLength":false,"EnableMpegtsM2TsMode":false,"TranscodeSeekInfo":"Auto","CopyTimestamps":false,"EnableSubtitlesInManifest":false,"SegmentLength":0,"Conditions":[Any](),"EnableAudioVbrEncoding":true]],
                "ContainerProfiles":[Any](),
                "CodecProfiles":[["Type":"Video","Codec":"h264","Conditions":[condition("Width","1920"),condition("Height","1080"),condition("VideoLevel","41"),condition("VideoBitDepth","8"),condition("VideoRangeType","SDR","Equals")],"ApplyConditions":[Any]()], ["Type":"VideoAudio","Conditions":[condition("AudioChannels","2")],"ApplyConditions":[Any]()]],
                "SubtitleProfiles":[["Format":"vtt","Method":"External"]]]
    }
    public static func mediaURL(_ url:String,token:String) -> String {
        let parts = url.components(separatedBy:"#"), body = parts[0], fragment = parts.count > 1 ? "#"+parts.dropFirst().joined(separator:"#") : ""
        let query = body.components(separatedBy:"?")
        var base = query[0]
        if query.count > 1 {
            let kept = query.dropFirst().joined(separator:"?").components(separatedBy:"&").filter { part in
                let name = String(part.prefix(while:{$0 != "="})).replacingOccurrences(of:"+",with:" ").removingPercentEncoding?.lowercased() ?? ""
                return !["apikey","api_key","x-emby-token","x-mediabrowser-token"].contains(name)
            }
            if !kept.isEmpty { base += "?"+kept.joined(separator:"&") }
        }
        return base + (base.contains("?") ? "&":"?") + "ApiKey=" + ServerURLs.encode(token) + fragment
    }
    public static func projection(_ input:[String:Any]) throws -> [String:Any] {
        let keys:Set<String> = ["serverUrl","itemId","token","method","url","source"]
        try CastJSON.fields(input,allowed:keys,required:keys)
        for k in keys.subtracting(["source"]) { _ = try CastJSON.string(input[k]) }
        let method = input["method"] as! String
        guard ["DirectPlay","DirectStream","Transcode"].contains(method) else { throw CastJSON.error("unknown play method") }
        let source = try CastJSON.object(input["source"])
        try CastJSON.fields(source,allowed:["Id","Container","Name","ETag","LiveStreamId","IsInfiniteStream","TranscodingSubProtocol","TranscodingContainer","RunTimeTicks","SupportsDirectPlay","SupportsDirectStream","SupportsTranscoding","TranscodingUrl","MediaStreams","RequiredHttpHeaders","DefaultAudioStreamIndex","DefaultSubtitleStreamIndex"])
        for raw in try CastJSON.objects(source["MediaStreams"] ?? []) {
            try CastJSON.fields(raw,allowed:["Type","Index","Codec","Language","DisplayTitle","Title","Channels","IsExternal","IsDefault","IsForced","IsHearingImpaired","DeliveryMethod","RealFrameRate","AverageFrameRate","DeliveryUrl","IsExternalUrl","IsTextSubtitleStream"])
        }
        let decoded = try JSONDecoder().decode(MediaSource.self,from:JSONSerialization.data(withJSONObject:source))
        let token = input["token"] as! String, item = input["itemId"] as! String
        let container = decoded.container?.components(separatedBy:",")[0].lowercased()
        let contentType = method == "Transcode" ? "application/x-mpegURL" : container == "webm" ? "video/webm" : container == "mp3" ? "audio/mpeg" : ["m4a","aac"].contains(container ?? "") ? "audio/mp4":"video/mp4"
        var subtitles:[[String:Any]] = []
        if let sourceID = decoded.id {
            for stream in decoded.mediaStreams where stream.type == "Subtitle" && stream.isTextSubtitleStream && !stream.isExternalURL {
                let path = "/Videos/"+ServerURLs.encode(item)+"/"+ServerURLs.encode(sourceID)+"/Subtitles/\(stream.index)/0/Stream.vtt"
                let base = (input["serverUrl"] as! String).replacingOccurrences(of:"/+$",with:"",options:.regularExpression)+path
                subtitles.append(["trackId":stream.index+1,"streamIndex":stream.index,"label":stream.displayTitle ?? stream.title ?? stream.language ?? "Subtitle \(stream.index)","language":stream.language as Any? ?? NSNull(),"url":mediaURL(base,token:token)])
            }
        }
        return ["url":mediaURL(input["url"] as! String,token:token),"contentType":contentType,"subtitles":subtitles]
    }
}
