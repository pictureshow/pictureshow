/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

/** Size of the `free` box the transfer writes right before the first moof, later rewritten in place as sidx + a smaller free. */
const val OFFLINE_INDEX_PLACEHOLDER_BYTES = 512 * 1024

/** size includes the header. */
data class Fmp4Box(val type: String, val offset: Long, val size: Long)

/** moofOffset and totalSize describe the file; [fmp4ParseMoof] only sees the moof, so the caller fills them in. */
data class Fmp4Fragment(val moofOffset: Long, val totalSize: Long, val durationInTimescale: Long, val earliestTime: Long)

data class Fmp4Track(val id: Int, val timescale: Long, val trexDefaultDuration: Long)

private const val SIDX_HEADER_BYTES = 40
private const val SIDX_REFERENCE_BYTES = 12
private const val FREE_HEADER_BYTES = 8
private const val MAX_REFERENCES = 65535
private const val MAX_REFERENCED_SIZE = 0x7fffffffL
private const val MAX_U32 = 0xffffffffL

private fun u32(bytes: ByteArray, at: Int): Long {
    if (at < 0 || at + 4 > bytes.size) throw IndexOutOfBoundsException()
    return (bytes[at].toLong() and 0xff shl 24) or (bytes[at + 1].toLong() and 0xff shl 16) or
        (bytes[at + 2].toLong() and 0xff shl 8) or (bytes[at + 3].toLong() and 0xff)
}

private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)

private fun typeAt(bytes: ByteArray, at: Int): String = (0 until 4).map { bytes[at + it].toInt().and(0xff).toChar() }.joinToString("")

/**
 * Reads one top-level box header at bytes[0..]; null if incomplete or if the size can't be a box (a declared size smaller than its own
 * header would never advance a reader). size 0 (to EOF) -> -1. 64-bit sizes handled.
 */
fun fmp4BoxHeader(bytes: ByteArray, length: Int): Pair<String, Long>? = headerAt(bytes, 0, length.coerceAtMost(bytes.size))?.let {
    it.type to
        it.size
}

private class Header(val type: String, val size: Long, val headerBytes: Int)

private fun headerAt(bytes: ByteArray, at: Int, limit: Int): Header? {
    if (limit - at < 8) return null
    val small = u32(bytes, at)
    val type = typeAt(bytes, at + 4)
    return when (small) {
        0L -> Header(type, -1, 8)
        1L -> {
            if (limit - at < 16) return null
            val large = u64(bytes, at + 8)
            if (large < 16) null else Header(type, large, 16)
        }
        in 2L..7L -> null
        else -> Header(type, small, 8)
    }
}

private class Child(val type: String, val start: Int, val end: Int)

/** Children between start and end; a box running past its parent ends the walk. */
private fun children(bytes: ByteArray, start: Int, end: Int): List<Child> = buildList {
    var at = start
    while (at < end) {
        val header = headerAt(bytes, at, end) ?: break
        val size = if (header.size == -1L) (end - at).toLong() else header.size
        if (size > end - at) break
        add(Child(header.type, at + header.headerBytes, at + size.toInt()))
        at += size.toInt()
    }
}

/** The box's own payload range, whether the caller passed the whole box or only what's inside it. */
private fun payload(bytes: ByteArray, type: String): Pair<Int, Int> {
    val header = headerAt(bytes, 0, bytes.size)
    return if (header?.type == type) header.headerBytes to bytes.size else 0 to bytes.size
}

/**
 * Parses a moof (with or without its header) for track `trackId`: tfdt base time, and the sum of trun sample durations (per-sample or tfhd
 * default or trex default). Null if the track isn't there or the box is malformed. moofOffset is 0 and totalSize the size of `moof`: the
 * caller sets both from the file.
 */
fun fmp4ParseMoof(moof: ByteArray, trackId: Int, trexDefaultDuration: Long): Fmp4Fragment? = try {
    val (start, end) = payload(moof, "moof")
    var earliest: Long? = null
    var duration = 0L
    var found = false
    for (traf in children(moof, start, end).filter { it.type == "traf" }) {
        val boxes = children(moof, traf.start, traf.end)
        val tfhd = boxes.firstOrNull { it.type == "tfhd" } ?: continue
        if (u32(moof, tfhd.start + 4).toInt() != trackId) continue
        found = true
        val defaultDuration = tfhdDefaultDuration(moof, tfhd.start) ?: trexDefaultDuration
        val tfdt = boxes.firstOrNull { it.type == "tfdt" }
        if (tfdt != null && earliest == null) {
            earliest = if (moof[tfdt.start].toInt() == 1) u64(moof, tfdt.start + 4) else u32(moof, tfdt.start + 4)
        }
        for (trun in boxes.filter { it.type == "trun" }) duration += trunDuration(moof, trun, defaultDuration)
    }
    val time = earliest
    if (!found || time == null) null else Fmp4Fragment(0, moof.size.toLong(), duration, time)
} catch (_: IndexOutOfBoundsException) {
    null
}

private fun tfhdDefaultDuration(bytes: ByteArray, start: Int): Long? {
    val flags = u32(bytes, start) and 0xffffff
    if (flags and 0x8 == 0L) return null
    var at = start + 8
    if (flags and 0x1 != 0L) at += 8
    if (flags and 0x2 != 0L) at += 4
    return u32(bytes, at)
}

private fun trunDuration(bytes: ByteArray, trun: Child, defaultDuration: Long): Long {
    val flags = u32(bytes, trun.start) and 0xffffff
    val count = u32(bytes, trun.start + 4)
    if (flags and 0x100 == 0L) return count * defaultDuration
    var at = trun.start + 8
    if (flags and 0x1 != 0L) at += 4
    if (flags and 0x4 != 0L) at += 4
    val sampleBytes =
        4 + (if (flags and 0x200 != 0L) 4 else 0) + (if (flags and 0x400 != 0L) 4 else 0) + (if (flags and 0x800 != 0L) 4 else 0)
    if (at + count * sampleBytes > trun.end) throw IndexOutOfBoundsException()
    var sum = 0L
    repeat(count.toInt()) {
        sum += u32(bytes, at)
        at += sampleBytes
    }
    return sum
}

/** From moov (with or without its header): the first video trak's id + timescale + trex default duration; audio if no video. */
fun fmp4ParseMoov(moov: ByteArray): Fmp4Track? = try {
    val (start, end) = payload(moov, "moov")
    val boxes = children(moov, start, end)
    val trex = boxes.filter { it.type == "mvex" }.flatMap { children(moov, it.start, it.end) }.filter { it.type == "trex" }
        .associate { u32(moov, it.start + 4).toInt() to u32(moov, it.start + 12) }
    val tracks = boxes.filter { it.type == "trak" }.mapNotNull { parseTrak(moov, it) }
    val chosen = tracks.firstOrNull { it.second == "vide" } ?: tracks.firstOrNull { it.second == "soun" }
    chosen?.first?.let { it.copy(trexDefaultDuration = trex[it.id] ?: 0) }
} catch (_: IndexOutOfBoundsException) {
    null
}

private fun parseTrak(bytes: ByteArray, trak: Child): Pair<Fmp4Track, String>? {
    val boxes = children(bytes, trak.start, trak.end)
    val tkhd = boxes.firstOrNull { it.type == "tkhd" } ?: return null
    val id = u32(bytes, tkhd.start + if (bytes[tkhd.start].toInt() == 1) 20 else 12).toInt()
    val mdia = children(bytes, (boxes.firstOrNull { it.type == "mdia" } ?: return null).start, boxes.first { it.type == "mdia" }.end)
    val mdhd = mdia.firstOrNull { it.type == "mdhd" } ?: return null
    val timescale = u32(bytes, mdhd.start + if (bytes[mdhd.start].toInt() == 1) 20 else 12)
    val hdlr = mdia.firstOrNull { it.type == "hdlr" } ?: return null
    return Fmp4Track(id, timescale, 0) to typeAt(bytes, hdlr.start + 8)
}

private fun ByteArray.putU32(at: Int, value: Long) {
    for (i in 0 until 4) this[at + i] = (value ushr (24 - 8 * i)).toByte()
}

private fun ByteArray.putU64(at: Int, value: Long) {
    putU32(at, value ushr 32)
    putU32(at + 4, value and MAX_U32)
}

private fun ByteArray.putBoxHeader(at: Int, size: Int, type: String) {
    putU32(at, size.toLong())
    type.forEachIndexed { i, c -> this[at + 4 + i] = c.code.toByte() }
}

/**
 * Coalesces adjacent fragments until they fit maxBytes, then returns a `free` filler followed by the exact `sidx` (version 1,
 * reference_type 0, starts_with_SAP 1, SAP_type 1), together exactly maxBytes. first_offset counts from the first byte after the sidx
 * (ISO 14496-12), and that byte is the first moof, so it is 0. The free box is at least 8 bytes or absent: a remainder of 1..7 bytes gives
 * up one reference, which leaves at least 13. Never exceeds 65535 references. With no fragments the whole placeholder stays a free box.
 * Fragments must be contiguous in the file.
 */
fun fmp4IndexBoxes(track: Fmp4Track, fragments: List<Fmp4Fragment>, maxBytes: Int): ByteArray {
    require(maxBytes >= FREE_HEADER_BYTES) { "Placeholder too small" }
    val out = ByteArray(maxBytes)
    if (fragments.isEmpty()) {
        out.putBoxHeader(0, maxBytes, "free")
        return out
    }
    fragments.zipWithNext { a, b ->
        require(a.moofOffset + a.totalSize == b.moofOffset) { "Fragments are not contiguous at ${b.moofOffset}" }
    }
    val fit = (maxBytes - SIDX_HEADER_BYTES) / SIDX_REFERENCE_BYTES
    val remainder = (maxBytes - SIDX_HEADER_BYTES) % SIDX_REFERENCE_BYTES
    val capacity = minOf(MAX_REFERENCES, if (remainder in 1 until FREE_HEADER_BYTES) fit - 1 else fit)
    require(capacity >= 1) { "Placeholder too small for one reference" }
    val groups = fragments.chunked((fragments.size + capacity - 1) / capacity)
    val sidxBytes = SIDX_HEADER_BYTES + groups.size * SIDX_REFERENCE_BYTES
    val sidx = maxBytes - sidxBytes
    if (sidx > 0) out.putBoxHeader(0, sidx, "free")
    out.putBoxHeader(sidx, sidxBytes, "sidx")
    out[sidx + 8] = 1 // version 1, flags 0
    out.putU32(sidx + 12, track.id.toLong())
    out.putU32(sidx + 16, track.timescale)
    out.putU64(sidx + 20, fragments.first().earliestTime)
    out.putU64(sidx + 28, 0)
    out.putU32(sidx + 36, groups.size.toLong()) // reserved 16 bits, then reference_count
    groups.forEachIndexed { index, group ->
        val size = group.sumOf { it.totalSize }
        val duration = group.sumOf { it.durationInTimescale }
        require(size <= MAX_REFERENCED_SIZE && duration <= MAX_U32) { "A reference holds at most 31 bits of size and 32 of duration" }
        val at = sidx + SIDX_HEADER_BYTES + index * SIDX_REFERENCE_BYTES
        out.putU32(at, size)
        out.putU32(at + 4, duration)
        out.putU32(at + 8, 0x90000000L)
    }
    return out
}
