// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Portable capability inputs. The supplied demuxer contract is independent of native Apple capability probing.
public struct DeviceCapabilities: Decodable, Sendable {
    public var name: String
    public var videoDecoders: [VideoDecoder]
    public var display: DisplayCapabilities
    public var audioDecoders: [AudioDecoder]
    public var audioOutput: AudioOutput
    public var settings: DeviceSettings?
}
public struct CodecLevel: Codable, Equatable, Comparable, Sendable { public var major: Int; public var minor: Int = 0
    enum CodingKeys: CodingKey { case major, minor }
    public init(major: Int, minor: Int = 0) { self.major = major; self.minor = minor }
    public init(from decoder: Decoder) throws { let c = try decoder.container(keyedBy: CodingKeys.self); major = try c.decode(Int.self,forKey:.major); minor = try c.decodeIfPresent(Int.self,forKey:.minor) ?? 0 }
    public static func < (a: Self,b:Self)->Bool { a.major == b.major ? a.minor < b.minor : a.major < b.major }
}
public struct VideoPerformance: Decodable, Sendable { public var width:Int; public var height:Int; public var frameRate:Double }
public struct VideoTuple: Decodable, Sendable { public var profile:String; public var maxLevel:CodecLevel?; public var maxBitDepth:Int; public var hdrFormats:[String]? }
public struct VideoDecoder: Decodable, Sendable {
    public var codec:String; public var profiles:[String]; public var maxLevel:CodecLevel?; public var maxWidth:Int; public var maxHeight:Int; public var maxFrameRate:Double
    public var maxFrameRateAtMaxSize:Double?; public var maxBitDepth:Int?; public var hdrFormats:[String]?; public var dolbyVisionProfiles:[Int]?; public var hardware:Bool?; public var sustainablePerformance:VideoPerformance?; public var profileCapabilities:[VideoTuple]?
    var rateAtSize:Double { maxFrameRateAtMaxSize ?? maxFrameRate }; var depth:Int { maxBitDepth ?? 8 }; var isHardware:Bool { hardware ?? true }; var hdr:[String] { hdrFormats ?? [] }; var dv:[Int] { dolbyVisionProfiles ?? [] }
}
public struct DisplayCapabilities: Decodable, Sendable { public var maxWidth:Int; public var maxHeight:Int; public var maxRefreshRate:Double; public var hdrFormats:[String]? }
public struct AudioDecoder: Decodable, Sendable { public var codec:String; public var maxChannels:Int; public var hardware:Bool? }
public struct AudioOutput: Decodable, Sendable { public var pcmChannels:Int; public var passthrough:[String]? }
public struct DeviceSettings: Decodable, Sendable {
    public var maxStreamingBitrate:Int?; public var passthroughEnabled:Bool?
    enum CodingKeys: String,CodingKey { case maxStreamingBitrate,passthroughEnabled }
    public static func validateBitrate(_ value:Int)throws->Int { guard value>0 else { throw ClientError.invalidResponse };return value }
    public init(from decoder:Decoder)throws { let c=try decoder.container(keyedBy:CodingKeys.self);maxStreamingBitrate=try c.decodeIfPresent(Int.self,forKey:.maxStreamingBitrate).map(Self.validateBitrate);passthroughEnabled=try c.decodeIfPresent(Bool.self,forKey:.passthroughEnabled) }
}

/// Exact reference demuxer policy used by the shared capability vectors. The Apple runtime uses AppleProfile,
/// since AVFoundation must never inherit Media3's Matroska/bitmap-subtitle/FFmpeg claims.
public enum ReferenceDeviceProfile {
    static let videoOrder = ["H264","HEVC","VP8","VP9","AV1","MPEG2","MPEG4","VC1"]
    static let audioOrder = ["AAC","AC3","EAC3","TRUEHD","DTS","FLAC","ALAC","OPUS","VORBIS","MP3","MP2","PCM"]
    static let videoNames = ["H264":"h264","HEVC":"hevc","VP8":"vp8","VP9":"vp9","AV1":"av1","MPEG2":"mpeg2video","MPEG4":"mpeg4","VC1":"vc1"]
    static let pcm = "pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_u8"
    static func audioName(_ v:String)->String { v == "PCM" ? pcm : v.lowercased() }
    static let containers: [(String,[String],[String],[String])] = [
        ("mkv",["H264","HEVC","VP8","VP9","AV1","MPEG2","MPEG4"],audioOrder,["subrip","srt","ass","ssa","webvtt","pgssub","dvdsub","dvbsub"]),
        ("webm",["VP8","VP9","AV1"],["OPUS","VORBIS"],["webvtt"]),
        ("mp4,m4v,mov",["H264","HEVC","VP9","AV1","MPEG2","MPEG4"],["AAC","AC3","EAC3","TRUEHD","DTS","FLAC","ALAC","OPUS","MP3","PCM"],["mov_text","webvtt","ttml","dvdsub"]),
        ("ts,mpegts",["H264","HEVC","MPEG2"],["AAC","AC3","EAC3","DTS","MP3","MP2"],["dvbsub"]),
        ("avi",["H264","MPEG4"],["AAC","AC3","DTS","MP3","PCM"],[]),
        ("mpeg,mpg",["MPEG2","H264"],["AC3","MP2","MP3","DTS"],[]),
        ("flv",["H264"],["AAC","MP3"],[]),("ogg,ogv",[],["OPUS","VORBIS","FLAC"],[])
    ]
    static func orderedUnion<T:Hashable>(_ a:[T],_ b:[T])->[T] { var seen = Set<T>(); return (a+b).filter { seen.insert($0).inserted } }
    static func condition(_ property:String,_ value:String,_ kind:String = "LessThanEqual",required:Bool = false)->[String:Any] { ["Condition":kind,"Property":property,"Value":value,"IsRequired":required] }
    static func codecProfile(_ codec:String,type:String = "Video",conditions:[[String:Any]],apply:[[String:Any]] = [],container:String? = nil)->[String:Any] { var d:[String:Any] = ["Type":type,"Codec":codec,"Conditions":conditions,"ApplyConditions":apply]; if let container { d["Container"] = container }; return d }
    static func number(_ n:Double)->String { n.rounded() == n ? String(Int64(n)) : String(n) }
    static func level(_ d:VideoDecoder)->Int? { guard let l=d.maxLevel else{return nil}; switch d.codec { case "H264":return l.major*10+l.minor;case "HEVC":return (l.major*10+l.minor)*3;case "AV1":return (l.major-2)*4+l.minor;default:return nil } }
    static func conservative(_ value:VideoDecoder)->VideoDecoder {
        var d=value; guard d.profiles.isEmpty else{return d}
        if let tuples=d.profileCapabilities,!tuples.isEmpty { d.profiles=orderedUnion([],tuples.map(\.profile));return d }
        d.profiles = ["H264":["Baseline","Constrained Baseline"],"HEVC":["Main"],"AV1":["Main"],"VP8":["0"],"VP9":["Profile 0"],"MPEG2":["Simple"],"VC1":["Simple"],"MPEG4":["Simple Profile"]][d.codec] ?? []
        let cap = ["H264":1,"HEVC":1,"AV1":2][d.codec].map { CodecLevel(major:$0) }
        d.maxLevel = cap.map { min($0,d.maxLevel ?? $0) }; d.maxBitDepth=min(d.depth,8);d.hdrFormats=[];d.dolbyVisionProfiles=[];return d
    }
    static func performance(_ value:VideoDecoder)->VideoDecoder { var d=value; if d.codec == "AV1",!d.isHardware,let p=d.sustainablePerformance { d.maxWidth=min(d.maxWidth,p.width);d.maxHeight=min(d.maxHeight,p.height);d.maxFrameRate=min(d.maxFrameRate,p.frameRate);d.maxFrameRateAtMaxSize=min(d.rateAtSize,p.frameRate) };return d }
    static func select(_ decoders:[VideoDecoder])->[String:VideoDecoder] {
        var result:[String:VideoDecoder]=[:]
        for codec in videoOrder {
            let all=decoders.filter { $0.codec == codec && (codec != "AV1" || $0.isHardware || $0.sustainablePerformance != nil) }.map(performance)
            let hardware=all.filter(\.isHardware);let values=hardware.isEmpty ? all:hardware
            guard var merged=values.first else{continue}
            if codec == "AV1",hardware.isEmpty { result[codec]=values.max { score($0)<score($1) };continue }
            for b in values.dropFirst() { merged.profiles=orderedUnion(merged.profiles,b.profiles);merged.maxLevel=[merged.maxLevel,b.maxLevel].compactMap{$0}.max();merged.maxWidth=max(merged.maxWidth,b.maxWidth);merged.maxHeight=max(merged.maxHeight,b.maxHeight);merged.maxFrameRate=max(merged.maxFrameRate,b.maxFrameRate);merged.maxFrameRateAtMaxSize=max(merged.rateAtSize,b.rateAtSize);merged.maxBitDepth=max(merged.depth,b.depth);merged.hdrFormats=orderedUnion(merged.hdr,b.hdr);merged.dolbyVisionProfiles=orderedUnion(merged.dv,b.dv) };result[codec]=merged
        };return result
    }
    static func score(_ d:VideoDecoder)->Double { Double(d.maxWidth)*Double(d.maxHeight)*d.maxFrameRate }
    /// Evaluate a single reported hardware Main decoder, preserving its own size, speed and level tuple.
    public static func prefersHevcTranscode(_ capabilities:DeviceCapabilities)->Bool {
        let display=capabilities.display
        return capabilities.videoDecoders.contains { decoder in
            let tuples=decoder.profileCapabilities ?? []
            let mainTuples=tuples.filter { $0.profile.caseInsensitiveCompare("Main") == .orderedSame && $0.maxBitDepth >= 8 }
            let main=tuples.isEmpty ? decoder.profiles.contains { $0.caseInsensitiveCompare("Main") == .orderedSame } : !mainTuples.isEmpty
            let mainLevel=tuples.isEmpty ? decoder.maxLevel : mainTuples.compactMap(\.maxLevel).min()
            let rate=display.maxWidth>1920 || display.maxHeight>1080 ? min(decoder.maxFrameRate,decoder.rateAtSize) : decoder.maxFrameRate
            return decoder.codec == "HEVC" && decoder.isHardware && main && decoder.maxWidth>=display.maxWidth && decoder.maxHeight>=display.maxHeight && rate>=min(30,display.maxRefreshRate) && hevcLevelCovers(mainLevel,width:display.maxWidth,height:display.maxHeight,rate:min(rate,display.maxRefreshRate))
        }
    }
    static func hevcLevelCovers(_ level:CodecLevel?,width:Int,height:Int,rate:Double)->Bool {
        guard let level else{return false}
        let reported=(level.major*10+level.minor)*3
        // H.265 Annex A level_idc, MaxLumaPs and MaxLumaSr; standard facts shared with the reference.
        let limits:[(Int,Double,Double)]=[(30,36_864,552_960),(60,122_880,3_686_400),(63,245_760,7_372_800),(90,552_960,16_588_800),(93,983_040,33_177_600),(120,2_228_224,66_846_720),(123,2_228_224,133_693_440),(150,8_912_896,267_386_880),(153,8_912_896,534_773_760),(156,8_912_896,1_069_547_520),(180,35_651_584,1_069_547_520),(183,35_651_584,2_139_095_040),(186,35_651_584,4_278_190_080)]
        guard let limit=limits.last(where:{$0.0<=reported}) else{return false}
        let w=Double(width),h=Double(height),pixels=w*h
        return pixels<=limit.1 && pixels*rate<=limit.2 && w*w<=8*limit.1 && h*h<=8*limit.1
    }
    static func dominates(_ a:VideoDecoder,_ b:VideoDecoder)->Bool { a.maxWidth>=b.maxWidth && a.maxHeight>=b.maxHeight && a.maxFrameRate>=b.maxFrameRate && a.rateAtSize>=b.rateAtSize && a.depth>=b.depth && Set(a.hdr).isSuperset(of:b.hdr) && Set(a.dv).isSuperset(of:b.dv) && (a.maxLevel == nil || (b.maxLevel != nil && a.maxLevel! >= b.maxLevel!)) }
    static func rangeTypes(_ d:VideoDecoder,_ display:DisplayCapabilities)->[String] {
        let shown=Set(d.hdr).intersection(display.hdrFormats ?? []);let dolbyCodec=["HEVC","AV1"].contains(d.codec);let dolby=dolbyCodec && shown.contains("DOLBY_VISION")
        var r:Set<String>=["SDR"];if shown.contains("HDR10"){r.insert("HDR10")};if shown.contains("HLG"){r.insert("HLG")};if shown.contains("HDR10_PLUS"){r.insert("HDR10Plus")}
        if dolbyCodec { r.insert("DOVIWithSDR");if dolby || shown.contains("HDR10"){r.formUnion(["DOVIWithHDR10","DOVIWithHDR10Plus"])};if dolby || shown.contains("HLG"){r.insert("DOVIWithHLG")} }
        if dolby { if d.dv.contains(d.codec == "HEVC" ? 5:10){r.insert("DOVI")};if d.codec == "HEVC",d.dv.contains(7){r.formUnion(["DOVIWithEL","DOVIWithELHDR10Plus"])} }
        return ["Unknown","SDR","HDR10","HLG","DOVI","DOVIWithHDR10","DOVIWithHLG","DOVIWithSDR","DOVIWithEL","DOVIWithHDR10Plus","DOVIWithELHDR10Plus","DOVIInvalid","HDR10Plus"].filter(r.contains)
    }
    static func videoProfiles(_ d:VideoDecoder,_ display:DisplayCapabilities)->[[String:Any]] {
        let codec=videoNames[d.codec]!;let required=d.codec == "AV1" && !d.isHardware
        let relax=d.codec == "HEVC" && d.isHardware && (level(d) ?? 0)>=153 && d.maxWidth>=3840 && d.maxHeight>=2160 && d.rateAtSize>=60
        var c:[[String:Any]]=[]
        if !d.profiles.isEmpty { c.append(condition("VideoProfile",d.profiles.map{$0.lowercased()}.joined(separator:"|"),"EqualsAny")) }
        if let l=level(d),!relax {c.append(condition("VideoLevel",String(l)))}
        c += [condition("VideoBitDepth",String(d.depth)),condition("Width",String(d.maxWidth),required:required),condition("Height",String(d.maxHeight),required:required),condition("VideoFramerate",number(d.maxFrameRate),required:required)]
        if required {c.append(condition("VideoFramerate","1","GreaterThanEqual",required:true))}
        c.append(condition("VideoRangeType",rangeTypes(d,display).joined(separator:"|"),"EqualsAny"))
        var result=[codecProfile(codec,conditions:c)]
        if d.maxWidth>1920,d.rateAtSize<d.maxFrameRate { result.append(codecProfile(codec,conditions:[condition("VideoFramerate",number(d.rateAtSize))],apply:[condition("Width","1921","GreaterThanEqual")])) }
        if relax {
            let scopes:[([[String:Any]],Int)]=[([condition("Width","1920"),condition("Height","1080"),condition("VideoFramerate","30")],186),([condition("Width","1921","GreaterThanEqual")],level(d)!),([condition("Height","1081","GreaterThanEqual")],level(d)!),([condition("VideoFramerate","30.000000000000004","GreaterThanEqual")],level(d)!),([condition("VideoFramerate","0")],level(d)!)]
            result += scopes.map { codecProfile(codec,conditions:[condition("VideoLevel",String($0.1))],apply:$0.0) }
        }
        if required {result.append(codecProfile(codec,conditions:c,apply:[condition("Width",String(d.maxWidth)),condition("Height",String(d.maxHeight)),condition("VideoFramerate",number(d.maxFrameRate))]))};return result
    }
    static func guarded(_ reported:[VideoDecoder],_ selected:[String:VideoDecoder],_ display:DisplayCapabilities)->[[String:Any]] {
        var result:[[String:Any]]=[]
        for codec in videoOrder {guard let summary=selected[codec] else{continue}
            let all=reported.filter{$0.codec == codec && ($0.isHardware || codec != "AV1" || $0.sustainablePerformance != nil)};let hw=all.filter(\.isHardware);let preferred=hw.isEmpty ? all:hw
            let heterogeneous=preferred.count>1 || preferred.contains { d in (d.profileCapabilities ?? []).contains { $0.maxLevel != d.maxLevel || $0.maxBitDepth != d.depth || Set($0.hdrFormats ?? []) != Set(d.hdr) } }
            guard heterogeneous else{result += videoProfiles(summary,display);continue}
            var tuples:[VideoDecoder]=[]
            for d in preferred {
                if let caps=d.profileCapabilities,!caps.isEmpty {
                    for tuple in caps {var v=d;v.profiles=[tuple.profile];v.maxLevel=caps.filter{$0.profile.lowercased() == tuple.profile.lowercased() && $0.maxBitDepth == tuple.maxBitDepth}.compactMap(\.maxLevel).min();v.maxBitDepth=tuple.maxBitDepth;v.hdrFormats=orderedUnion(tuple.hdrFormats ?? [],d.hdr.contains("DOLBY_VISION") && tuple.maxBitDepth>=10 ? ["DOLBY_VISION"]:[]);v.profileCapabilities=[];tuples.append(performance(v))}
                } else {for p in d.profiles {var v=d;v.profiles=[p];tuples.append(performance(v))}}
            }
            if !tuples.allSatisfy({$0.profiles.isEmpty}){result += videoProfiles(summary,display)}
            for profile in orderedUnion([],tuples.compactMap{$0.profiles.first}) {
                let alternatives=tuples.filter{$0.profiles.first == profile};let actual=alternatives.first { a in alternatives.allSatisfy{dominates(a,$0)} } ?? alternatives.max{score($0)<score($1)}!
                result += videoProfiles(actual,display).map { value in var v=value;v["ApplyConditions"]=[condition("VideoProfile",profile.lowercased(),"EqualsAny")]+(v["ApplyConditions"] as! [[String:Any]]);return v }
            }
        };return result
    }
    public static func build(_ capabilities:DeviceCapabilities)->[String:Any] {
        let reported=capabilities.videoDecoders.map(conservative);let video=select(reported);let settings=capabilities.settings;let passthrough=(settings?.passthroughEnabled ?? false) ? (capabilities.audioOutput.passthrough ?? []):[]
        var audio:[String:Int]=[:];for d in capabilities.audioDecoders {audio[d.codec]=max(audio[d.codec] ?? 0,d.maxChannels)};if audio["PCM"] == nil{audio["PCM"]=8}
        for p in passthrough {let codec = ["EAC3_JOC":"EAC3","DTS_HD":"DTS"][p] ?? p;audio[codec]=max(audio[codec] ?? 0,["AC3","DTS"].contains(p) ? 6:8)}
        let direct:[[String:Any]]=containers.compactMap { name,vc,ac,_ in let v=videoOrder.filter{vc.contains($0) && video[$0] != nil};let a=audioOrder.filter{ac.contains($0) && audio[$0] != nil};guard !v.isEmpty && !a.isEmpty else{return nil};return ["Container":name,"Type":"Video","VideoCodec":v.map{videoNames[$0]!}.joined(separator:","),"AudioCodec":a.map(audioName).joined(separator:",")] }
        let preferred=prefersHevcTranscode(capabilities) ? ["HEVC","H264","AV1","VP9"] : ["H264","HEVC","AV1","VP9"]
        let vo=preferred.filter{video[$0] != nil && ["H264","HEVC"].contains($0)}
        let encodeTargets = passthrough.contains(where:{$0.hasPrefix("EAC3")}) ? ["EAC3","AC3","AAC"] : (passthrough.contains("AC3") ? ["AC3","AAC","EAC3"]:["AAC","AC3","EAC3"])
        let ao=(encodeTargets+["MP3","OPUS","FLAC","ALAC","DTS","TRUEHD"]).filter{audio[$0] != nil && ["AAC","AC3","EAC3","MP3"].contains($0)}
        var conditions=[condition("Width",String(capabilities.display.maxWidth)),condition("Height",String(capabilities.display.maxHeight))]
        if vo.first == "H264",let d=select(capabilities.videoDecoders)["H264"],!d.isHardware,d.maxFrameRate<30,let p=d.sustainablePerformance,p.frameRate>=30,capabilities.display.maxWidth<=p.width,capabilities.display.maxHeight<=p.height {conditions.append(condition("VideoFramerate","30","GreaterThanEqual"))}
        var transcode:[[String:Any]]=[]
        if !vo.isEmpty && !ao.isEmpty {transcode=[["Container":"ts","Type":"Video","VideoCodec":vo.map{videoNames[$0]!}.joined(separator:","),"AudioCodec":ao.map(audioName).joined(separator:","),"Protocol":"hls","EstimateContentLength":false,"EnableMpegtsM2TsMode":false,"TranscodeSeekInfo":"Auto","CopyTimestamps":false,"Context":"Streaming","EnableSubtitlesInManifest":false,"MaxAudioChannels":String(max(capabilities.audioOutput.pcmChannels,passthrough.map{["AC3","DTS"].contains($0) ? 6:8}.max() ?? 0)),"MinSegments":1,"SegmentLength":0,"BreakOnNonKeyFrames":false,"Conditions":conditions,"EnableAudioVbrEncoding":true]]}
        var codecs=guarded(reported,video,capabilities.display)
        codecs += audioOrder.compactMap { codec in audio[codec].map{codecProfile(audioName(codec),type:"VideoAudio",conditions:[condition("AudioChannels",String($0))])} }
        if (audio["EAC3"] ?? 0)>6 {codecs.append(codecProfile("eac3",type:"VideoAudio",conditions:[condition("AudioChannels","6")],container:"ts,mpegts"))}
        var subtitles:[[String:Any]]=[];for c in containers {subtitles += c.3.map{["Format":$0,"Method":"Embed","Container":c.0]}}
        subtitles += ["srt","subrip","ass","ssa","vtt","webvtt","ttml"].map{["Format":$0,"Method":"External"]};subtitles += ["pgssub","dvdsub","dvbsub"].map{["Format":$0,"Method":"Encode"]}
        let bitrate=settings?.maxStreamingBitrate ?? 200_000_000
        return ["Name":capabilities.name,"MaxStreamingBitrate":bitrate,"MaxStaticBitrate":bitrate,"DirectPlayProfiles":direct,"TranscodingProfiles":transcode,"ContainerProfiles":[],"CodecProfiles":codecs,"SubtitleProfiles":subtitles]
    }
}
