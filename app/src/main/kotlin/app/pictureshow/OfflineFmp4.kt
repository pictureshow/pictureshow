/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.Fmp4Fragment
import app.pictureshow.core.offline.Fmp4Track
import app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES
import app.pictureshow.core.offline.fmp4IndexBoxes
import app.pictureshow.core.offline.fmp4ParseMoof
import app.pictureshow.core.offline.fmp4ParseMoov
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The server's finite conversion is `ftyp moov (moof mdat)*` with no index, which Media3 treats as unseekable. Room is
 * left before the first moof while bytes arrive, so indexing afterwards needs no second copy of the file.
 */
internal class Fmp4Spacer(private val out: OutputStream) {
    private val header = ByteArray(16)
    private var headerBytes = 0
    private var boxRemaining = 0L
    private var done = false

    /** Bytes the placeholder added to the file. */
    var inserted = 0L
        private set

    fun write(bytes: ByteArray, length: Int) {
        var at = 0
        while (at < length) {
            if (done) {
                out.write(bytes, at, length - at)
                return
            }
            if (boxRemaining > 0) {
                val take = minOf(boxRemaining, (length - at).toLong()).toInt()
                out.write(bytes, at, take)
                at += take
                boxRemaining -= take
                continue
            }
            header[headerBytes++] = bytes[at++]
            if (headerBytes < 8) continue
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val size32 = header.be32(0)
            if (size32 == 1L && headerBytes < 16) continue
            if (type == "moof") insertPlaceholder()
            out.write(header, 0, headerBytes)
            val size = if (size32 == 1L) header.be64(8) else size32
            done = type == "moof" || size < headerBytes
            boxRemaining = if (done) 0 else size - headerBytes
            headerBytes = 0
        }
    }

    private fun insertPlaceholder() {
        val box = ByteArray(OFFLINE_INDEX_PLACEHOLDER_BYTES)
        box[0] = (OFFLINE_INDEX_PLACEHOLDER_BYTES ushr 24).toByte()
        box[1] = (OFFLINE_INDEX_PLACEHOLDER_BYTES ushr 16).toByte()
        box[2] = (OFFLINE_INDEX_PLACEHOLDER_BYTES ushr 8).toByte()
        box[3] = OFFLINE_INDEX_PLACEHOLDER_BYTES.toByte()
        "free".encodeToByteArray().copyInto(box, 4)
        out.write(box)
        inserted = box.size.toLong()
    }
}

private fun ByteArray.be32(at: Int): Long = ((this[at].toLong() and 0xff) shl 24) or ((this[at + 1].toLong() and 0xff) shl 16) or
    ((this[at + 2].toLong() and 0xff) shl 8) or (this[at + 3].toLong() and 0xff)

private fun ByteArray.be64(at: Int): Long = (be32(at) shl 32) or be32(at + 4)

private class Fmp4Walk(val track: Fmp4Track?, val placeholder: Long, val fragments: List<Fmp4Fragment>)

/** Walks a finished file and turns its placeholder into `sidx`. False when the file isn't what a conversion should be. */
internal fun writeFmp4Index(file: File): Boolean = RandomAccessFile(file, "rw").use { raf ->
    val walk = raf.walkFmp4() ?: return@use false
    val active = walk.track
    if (active == null || walk.placeholder < 0 || walk.fragments.isEmpty()) return@use false
    raf.seek(walk.placeholder)
    raf.write(fmp4IndexBoxes(active, walk.fragments, OFFLINE_INDEX_PLACEHOLDER_BYTES))
    raf.fd.sync()
    true
}

/** The running time a conversion's fragments add up to, in 100 ns ticks. Null when the file isn't fragmented. */
internal fun fmp4MeasureTicks(file: File): Long? = RandomAccessFile(file, "r").use { raf ->
    val walk = raf.walkFmp4() ?: return@use null
    val track = walk.track ?: return@use null
    if (track.timescale <= 0 || walk.fragments.isEmpty()) return@use null
    walk.fragments.sumOf { it.durationInTimescale } * 10_000_000L / track.timescale
}

private fun RandomAccessFile.walkFmp4(): Fmp4Walk? {
    val raf = this
    var offset = 0L
    var track: Fmp4Track? = null
    var placeholder = -1L
    val fragments = mutableListOf<Fmp4Fragment>()
    val head = ByteArray(16)
    while (offset + 8 <= raf.length()) {
        raf.seek(offset)
        raf.readFully(head, 0, 8)
        var size = head.be32(0)
        var headerSize = 8
        if (size == 1L) {
            raf.readFully(head, 8, 8)
            size = head.be64(8)
            headerSize = 16
        } else if (size == 0L) {
            size = raf.length() - offset
        }
        if (size < headerSize || offset + size > raf.length()) return null
        when (String(head, 4, 4, Charsets.ISO_8859_1)) {
            "moov" -> track = fmp4ParseMoov(raf.readBox(offset, size) ?: return null)
            "free" -> {
                if (placeholder < 0 && size == OFFLINE_INDEX_PLACEHOLDER_BYTES.toLong()) {
                    placeholder = offset
                } else if (fragments.isNotEmpty()) {
                    fragments[fragments.lastIndex] = fragments.last().let { it.copy(totalSize = it.totalSize + size) }
                }
            }
            "moof" -> {
                val active = track ?: return null
                val fragment = fmp4ParseMoof(raf.readBox(offset, size) ?: return null, active.id, active.trexDefaultDuration)
                if (fragment != null) {
                    fragments += fragment.copy(moofOffset = offset, totalSize = size)
                } else if (fragments.isNotEmpty()) {
                    fragments[fragments.lastIndex] = fragments.last().let { it.copy(totalSize = it.totalSize + size) }
                }
            }
            // The index needs contiguous references: whatever sits between fragments belongs to the one before it.
            "mfra" -> Unit
            else -> if (fragments.isNotEmpty()) {
                fragments[fragments.lastIndex] = fragments.last().let { it.copy(totalSize = it.totalSize + size) }
            }
        }
        offset += size
    }
    return Fmp4Walk(track, placeholder, fragments)
}

/** A moov or moof past this is not a conversion we produced. */
private const val FMP4_MAX_METADATA_BOX = 16L * 1024 * 1024

private fun RandomAccessFile.readBox(offset: Long, size: Long): ByteArray? {
    if (size > FMP4_MAX_METADATA_BOX) return null
    seek(offset)
    return ByteArray(size.toInt()).also(::readFully)
}
