// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public struct TextSubtitleCue: Equatable, Sendable {
    public var startMs: Int64
    public var endMs: Int64
    public var text: String
    public init(startMs: Int64, endMs: Int64, text: String) { self.startMs=startMs;self.endMs=endMs;self.text=text }
}

public enum TextSubtitleError: Error, Equatable { case tooLarge, invalidEncoding, unsupportedFormat, malformed }

/// Parses supported external plain text into authored source time. No renderer or network dependency.
public enum TextSubtitles {
    public static let maximumBytes=16*1024*1024

    public static func parse(data: Data, format: String) throws -> [TextSubtitleCue] {
        guard data.count<=maximumBytes else { throw TextSubtitleError.tooLarge }
        guard ["srt","subrip","vtt","webvtt"].contains(format.lowercased()) else { throw TextSubtitleError.unsupportedFormat }
        guard var value=String(data:data,encoding:.utf8) else { throw TextSubtitleError.invalidEncoding }
        if value.first == "\u{feff}" { value.removeFirst() }
        value=value.replacingOccurrences(of:"\r\n",with:"\n").replacingOccurrences(of:"\r",with:"\n")
        let webvtt=["vtt","webvtt"].contains(format.lowercased())
        var blocks:[[String]]=[];var block:[String]=[]
        for line in value.components(separatedBy:"\n") {
            if line.trimmingCharacters(in:.whitespaces).isEmpty {
                if !block.isEmpty { blocks.append(block);block=[] }
            } else { block.append(line) }
        }
        if !block.isEmpty { blocks.append(block) }
        if webvtt {
            guard let header=blocks.first?.first,header == "WEBVTT" || header.hasPrefix("WEBVTT ") || header.hasPrefix("WEBVTT\t") else { throw TextSubtitleError.malformed }
            // The first block contains header metadata, not cue content.
            guard !(blocks[0].contains { $0.contains("-->") }) else { throw TextSubtitleError.malformed }
            blocks.removeFirst()
        }
        var cues:[TextSubtitleCue]=[]
        for block in blocks {
            let first=block[0]
            if webvtt && (first == "NOTE" || first.hasPrefix("NOTE ") || first.hasPrefix("NOTE\t") || first == "STYLE" || first == "REGION") { continue }
            let timingIndex=first.contains("-->") ? 0:1
            guard timingIndex<block.count else { throw TextSubtitleError.malformed }
            let fields=block[timingIndex].components(separatedBy:"-->")
            guard fields.count == 2 else { throw TextSubtitleError.malformed }
            let start=fields[0].trimmingCharacters(in:.whitespaces)
            let endFields=fields[1].split(whereSeparator:{$0.isWhitespace})
            guard let end=endFields.first else { throw TextSubtitleError.malformed }
            let startMs=try timestamp(start,webvtt:webvtt),endMs=try timestamp(String(end),webvtt:webvtt)
            guard endMs>startMs else { throw TextSubtitleError.malformed }
            let text=plainText(block.dropFirst(timingIndex+1).joined(separator:"\n"))
            if !text.isEmpty { cues.append(TextSubtitleCue(startMs:startMs,endMs:endMs,text:text)) }
        }
        return cues
    }

    /// Start-inclusive/end-exclusive authored time, preserving simultaneous dialogue in source order.
    public static func text(cues: [TextSubtitleCue], positionMs: Int64) -> String? {
        let active=cues.filter { positionMs >= $0.startMs && positionMs < $0.endMs }.map(\.text)
        return active.isEmpty ? nil : active.joined(separator:"\n")
    }

    private static func timestamp(_ value:String,webvtt:Bool)throws->Int64 {
        let components=value.components(separatedBy:":")
        guard components.count == 3 || (webvtt && components.count == 2) else { throw TextSubtitleError.malformed }
        let last=components.last!.components(separatedBy:webvtt ? ".":",")
        guard last.count == 2,last[0].count == 2,last[1].count == 3,
              components[components.count-2].count == 2,
              let seconds=decimal(last[0]),seconds<60,let millis=decimal(last[1]),
              let minutes=decimal(components[components.count-2]),minutes<60 else { throw TextSubtitleError.malformed }
        let hours:Int64
        if components.count == 3 {
            guard components[0].count>=2,let parsed=decimal(components[0]) else { throw TextSubtitleError.malformed }
            hours=parsed
        } else { hours=0 }
        let (hourMs,overflow)=hours.multipliedReportingOverflow(by:3_600_000)
        let (result,additionOverflow)=hourMs.addingReportingOverflow(minutes*60_000+seconds*1_000+millis)
        guard !overflow,!additionOverflow else { throw TextSubtitleError.malformed }
        return result
    }

    private static func decimal(_ value:String)->Int64? {
        guard !value.isEmpty,value.utf8.allSatisfy({$0>=48 && $0<=57}) else{return nil}
        return Int64(value)
    }

    private static func plainText(_ value:String)->String {
        // Strip authored markup before decoding entities so an escaped literal tag stays visible.
        let stripped=value.replacingOccurrences(of:"<[^<>]*>",with:"",options:.regularExpression)
        let entities=["amp":"&","lt":"<","gt":">","quot":"\"","apos":"'","nbsp":" ","lrm":"\u{200e}","rlm":"\u{200f}"]
        guard let regex=try? NSRegularExpression(pattern:"&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);") else { return stripped }
        var text=stripped
        for match in regex.matches(in:stripped,range:NSRange(stripped.startIndex...,in:stripped)).reversed() {
            guard let range=Range(match.range,in:text),let nameRange=Range(match.range(at:1),in:stripped) else {continue}
            let name=String(stripped[nameRange]);let replacement:String?
            if name.hasPrefix("#") {
                let hex=name.hasPrefix("#x");let raw=name.dropFirst(hex ? 2:1)
                replacement=UInt32(raw,radix:hex ? 16:10).flatMap(UnicodeScalar.init).map(String.init)
            } else { replacement=entities[name] }
            if let replacement { text.replaceSubrange(range,with:replacement) }
        }
        return text
    }
}

/// PLAY-11: one viewing's subtitle timing. Positive shows captions later than their authored time; 100 ms steps, bounded
/// to ±10 s. Wording follows web's panel (`player-subtitles.ts`).
public enum SubtitleTiming {
    public static let stepMs = 100
    public static let limitMs = 10_000
    public static let help = "Use Later if captions appear too early."
    public static let burnedIn = "These subtitles are encoded into the video. Timing and appearance cannot be changed."

    /// The delay after one Earlier (-1) or Later (+1) press, clamped and kept on the 100 ms grid.
    public static func step(_ ms: Int, by direction: Int) -> Int {
        let next = ms + direction.signum() * stepMs
        return min(limitMs, max(-limitMs, next / stepMs * stepMs))
    }

    public static func canStep(_ ms: Int, by direction: Int) -> Bool { step(ms, by: direction) != ms }

    /// "Timing: in sync", or the signed delay and which way it moves the captions ("Timing: +1,200 ms (later)").
    public static func label(_ ms: Int) -> String {
        if ms == 0 { return "Timing: in sync" }
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.numberStyle = .decimal
        let amount = formatter.string(from: NSNumber(value: abs(ms))) ?? String(abs(ms))
        return ms > 0 ? "Timing: +\(amount) ms (later)" : "Timing: \u{2212}\(amount) ms (earlier)"
    }

    /// The authored cue time to show at `positionMs` with this delay: a later delay looks further back.
    public static func cueTime(positionMs: Int64, delayMs: Int) -> Int64 { positionMs - Int64(delayMs) }
}
