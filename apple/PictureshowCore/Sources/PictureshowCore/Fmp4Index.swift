// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public let OFFLINE_INDEX_PLACEHOLDER_BYTES = 512 * 1024
private struct Fmp4Reader {
    let bytes: [UInt8]
    func byte(_ at: Int) throws -> UInt8 {
        guard at >= 0, at < bytes.count else { throw OfflineError.invalidArgument("Incomplete MP4 box") }
        return bytes[at]
    }
    func u32(_ at: Int) throws -> Int64 {
        guard at >= 0, at <= bytes.count - 4 else { throw OfflineError.invalidArgument("Incomplete MP4 integer") }
        return (0..<4).reduce(0) { ($0 << 8) | Int64(bytes[at + $1]) }
    }
    func u64(_ at: Int) throws -> Int64 { try (u32(at) << 32) | u32(at + 4) }
    func type(_ at: Int) throws -> String {
        guard at >= 0, at <= bytes.count - 4 else { throw OfflineError.invalidArgument("Incomplete MP4 type") }
        return String(String.UnicodeScalarView(bytes[at..<at + 4].map { UnicodeScalar($0) }))
    }
    struct Header { let type: String; let size: Int64; let headerBytes: Int }
    struct Child { let type: String; let start: Int; let end: Int }
    func header(_ at: Int, _ limit: Int) throws -> Header? {
        guard limit - at >= 8 else { return nil }
        let small = try u32(at), type = try type(at + 4)
        if small == 0 { return Header(type: type, size: -1, headerBytes: 8) }
        if small == 1 {
            guard limit - at >= 16 else { return nil }
            let large = try u64(at + 8)
            return large < 16 ? nil : Header(type: type, size: large, headerBytes: 16)
        }
        if small < 8 { return nil }
        return Header(type: type, size: small, headerBytes: 8)
    }
    func children(_ start: Int, _ end: Int) throws -> [Child] {
        var result: [Child] = [], at = start
        while at < end {
            guard let header = try header(at, end) else { break }
            let size = header.size == -1 ? Int64(end - at) : header.size
            guard size <= end - at else { break }
            result.append(Child(type: header.type, start: at + header.headerBytes, end: at + Int(size)))
            at += Int(size)
        }
        return result
    }
    func payload(_ type: String) throws -> (Int, Int) {
        let h = try header(0, bytes.count)
        return (h?.type == type ? h!.headerBytes : 0, bytes.count)
    }
    func tfhdDefaultDuration(_ start: Int) throws -> Int64? {
        let flags = try u32(start) & 0xffffff
        if flags & 8 == 0 { return nil }
        let at = start + 8 + (flags & 1 != 0 ? 8 : 0) + (flags & 2 != 0 ? 4 : 0)
        return try u32(at)
    }
    func trunDuration(_ trun: Child, _ defaultDuration: Int64) throws -> Int64 {
        let flags = try u32(trun.start) & 0xffffff, count = try u32(trun.start + 4)
        if flags & 0x100 == 0 { return count &* defaultDuration }
        var at = trun.start + 8 + (flags & 1 != 0 ? 4 : 0) + (flags & 4 != 0 ? 4 : 0)
        let stride = 4 + (flags & 0x200 != 0 ? 4 : 0) + (flags & 0x400 != 0 ? 4 : 0) + (flags & 0x800 != 0 ? 4 : 0)
        guard Int64(at) + count * Int64(stride) <= trun.end else { throw OfflineError.invalidArgument("Incomplete trun samples") }
        var sum: Int64 = 0
        for _ in 0..<Int(count) { sum &+= try u32(at); at += stride }
        return sum
    }
    func trak(_ trak: Child) throws -> (Fmp4Track, String)? {
        let boxes = try children(trak.start, trak.end)
        guard let tkhd = boxes.first(where: { $0.type == "tkhd" }), let mdiaBox = boxes.first(where: { $0.type == "mdia" }) else { return nil }
        let id = try u32(tkhd.start + (byte(tkhd.start) == 1 ? 20 : 12))
        let mdia = try children(mdiaBox.start, mdiaBox.end)
        guard let mdhd = mdia.first(where: { $0.type == "mdhd" }), let hdlr = mdia.first(where: { $0.type == "hdlr" }) else { return nil }
        let timescale = try u32(mdhd.start + (byte(mdhd.start) == 1 ? 20 : 12))
        return (Fmp4Track(id: Int(Int32(truncatingIfNeeded: id)), timescale: timescale, trexDefaultDuration: 0), try type(hdlr.start + 8))
    }
}

/// Incomplete or impossible headers return nil. A size of zero (to EOF) is represented as -1.
public func fmp4BoxHeader(bytes: [UInt8], length: Int) -> (type: String, size: Int64)? {
    guard let h = try? Fmp4Reader(bytes: bytes).header(0, min(length, bytes.count)) else { return nil }
    return (h.type, h.size)
}

/// Accepts a moof box or its payload. The engine fills in the fragment's file offset and total size.
public func fmp4ParseMoof(moof: [UInt8], trackId: Int, trexDefaultDuration: Int64) -> Fmp4Fragment? {
    do {
        let r = Fmp4Reader(bytes: moof), (start, end) = try r.payload("moof")
        var earliest: Int64?, duration: Int64 = 0, found = false
        for traf in try r.children(start, end).filter({ $0.type == "traf" }) {
            let boxes = try r.children(traf.start, traf.end)
            guard let tfhd = boxes.first(where: { $0.type == "tfhd" }), Int(Int32(truncatingIfNeeded: try r.u32(tfhd.start + 4))) == trackId else { continue }
            found = true
            let defaultDuration = try r.tfhdDefaultDuration(tfhd.start) ?? trexDefaultDuration
            if let tfdt = boxes.first(where: { $0.type == "tfdt" }), earliest == nil {
                earliest = try r.byte(tfdt.start) == 1 ? r.u64(tfdt.start + 4) : r.u32(tfdt.start + 4)
            }
            for trun in boxes where trun.type == "trun" { duration &+= try r.trunDuration(trun, defaultDuration) }
        }
        guard found, let earliest else { return nil }
        return Fmp4Fragment(moofOffset: 0, totalSize: Int64(moof.count), durationInTimescale: duration, earliestTime: earliest)
    } catch { return nil }
}
/// First video track, falling back to audio, with its trex default duration.
public func fmp4ParseMoov(moov: [UInt8]) -> Fmp4Track? {
    do {
        let r = Fmp4Reader(bytes: moov), (start, end) = try r.payload("moov"), boxes = try r.children(start, end)
        var trex: [Int: Int64] = [:]
        for mvex in boxes where mvex.type == "mvex" {
            for t in try r.children(mvex.start, mvex.end) where t.type == "trex" {
                trex[Int(Int32(truncatingIfNeeded: try r.u32(t.start + 4)))] = try r.u32(t.start + 12)
            }
        }
        let tracks = try boxes.filter { $0.type == "trak" }.compactMap { try r.trak($0) }
        guard var chosen = (tracks.first { $0.1 == "vide" } ?? tracks.first { $0.1 == "soun" })?.0 else { return nil }
        chosen.trexDefaultDuration = trex[chosen.id] ?? 0
        return chosen
    } catch { return nil }
}

/// Rewrites exactly maxBytes as free + version-1 sidx, coalescing adjacent fragments to fit and keeping at most 65535 references.
public func fmp4IndexBoxes(track: Fmp4Track, fragments: [Fmp4Fragment], maxBytes: Int) throws -> [UInt8] {
    guard maxBytes >= 8 else { throw OfflineError.invalidArgument("Placeholder too small") }
    var out = [UInt8](repeating: 0, count: maxBytes)
    func putU32(_ at: Int, _ value: Int64) {
        for i in 0..<4 { out[at + i] = UInt8(truncatingIfNeeded: UInt64(bitPattern: value) >> (24 - 8 * i)) }
    }
    func putU64(_ at: Int, _ value: Int64) { putU32(at, Int64(bitPattern: UInt64(bitPattern: value) >> 32)); putU32(at + 4, value & 0xffffffff) }
    func header(_ at: Int, _ size: Int, _ type: String) {
        putU32(at, Int64(size)); for (i, byte) in type.utf8.enumerated() { out[at + 4 + i] = byte }
    }
    if fragments.isEmpty { header(0, maxBytes, "free"); return out }
    for (a, b) in zip(fragments, fragments.dropFirst()) {
        guard a.moofOffset &+ a.totalSize == b.moofOffset else { throw OfflineError.invalidArgument("Fragments are not contiguous at \(b.moofOffset)") }
    }
    let fit = (maxBytes - 40) / 12, remainder = (maxBytes - 40) % 12
    let capacity = min(65535, (1..<8).contains(remainder) ? fit - 1 : fit)
    guard capacity >= 1 else { throw OfflineError.invalidArgument("Placeholder too small for one reference") }
    let groupSize = (fragments.count + capacity - 1) / capacity
    let groupCount = (fragments.count + groupSize - 1) / groupSize
    let sidxBytes = 40 + groupCount * 12, sidx = maxBytes - sidxBytes
    if sidx > 0 { header(0, sidx, "free") }
    header(sidx, sidxBytes, "sidx"); out[sidx + 8] = 1
    putU32(sidx + 12, Int64(track.id)); putU32(sidx + 16, track.timescale)
    putU64(sidx + 20, fragments[0].earliestTime); putU64(sidx + 28, 0); putU32(sidx + 36, Int64(groupCount))
    for index in 0..<groupCount {
        let group = fragments[(index * groupSize)..<min(fragments.count, (index + 1) * groupSize)]
        let size = group.reduce(Int64(0)) { $0 &+ $1.totalSize }, duration = group.reduce(Int64(0)) { $0 &+ $1.durationInTimescale }
        guard size <= 0x7fffffff, duration <= 0xffffffff else { throw OfflineError.invalidArgument("A reference holds at most 31 bits of size and 32 of duration") }
        let at = sidx + 40 + index * 12
        putU32(at, size); putU32(at + 4, duration); putU32(at + 8, 0x90000000)
    }
    return out
}
