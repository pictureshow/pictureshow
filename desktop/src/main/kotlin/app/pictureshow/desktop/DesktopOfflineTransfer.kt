/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.offline.Fmp4Fragment
import app.pictureshow.core.offline.Fmp4Track
import app.pictureshow.core.offline.OFFLINE_INDEX_PLACEHOLDER_BYTES
import app.pictureshow.core.offline.OFFLINE_SYNC_BYTES
import app.pictureshow.core.offline.OfflineResponse
import app.pictureshow.core.offline.OfflineValidator
import app.pictureshow.core.offline.fmp4ExplicitDataStarts
import app.pictureshow.core.offline.fmp4IndexBoxes
import app.pictureshow.core.offline.fmp4ParseMoof
import app.pictureshow.core.offline.fmp4ParseMoov
import app.pictureshow.core.offline.fmp4ShiftOffsets
import app.pictureshow.core.offline.offlineBodyEnd
import app.pictureshow.core.offline.offlineRangeHeader
import app.pictureshow.core.offline.offlineResumeDecision
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
internal fun interface DesktopOfflineHttp {
    fun open(url: String, headers: Map<String, String>, range: String?, onCall: (cancel: () -> Unit) -> Unit): DesktopOfflineHttpResponse
}

/** The byte ledger the transfer must clear with before every write. */
internal interface OfflineLedger {
    /** False: the reason is stored and the transfer stops before writing. */
    suspend fun reserve(bytes: Long): Boolean
    suspend fun commit(bytes: Long)
    suspend fun discard(bytes: Long)

    /** Hands back what a finished or dropped body didn't use, so a retry in the same attempt doesn't count it twice. */
    suspend fun release(bytes: Long)
    suspend fun progress(received: Long, total: Long?)
}

internal sealed interface OfflineTransferResult {
    data class Complete(val validator: OfflineValidator?, val bytes: Long) : OfflineTransferResult

    /** The ledger said no. The partial stays. */
    data object NoBudget : OfflineTransferResult

    data class Failed(val reason: String) : OfflineTransferResult

    /** Worth trying again: the connection dropped or the server restarted the body. */
    data class Interrupted(val reason: String) : OfflineTransferResult
}

internal const val OFFLINE_CHUNK_BYTES = 256 * 1024
internal const val OFFLINE_UNKNOWN_RESERVE_BYTES = 8L * 1024 * 1024

internal class OfflineResourceTransfer(
    private val http: DesktopOfflineHttp,
    private val ledger: OfflineLedger,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() }
) {
    /**
     * @param spaceFmp4 this is a server conversion: leave room for the index that makes it seekable.
     * @param sourceETag the source version seen before this attempt, compared against the stored one.
     * @param trustedBytes how much of a partial of this length to keep. After a restart only what came before the last sync
     * boundary can be trusted: a power cut may have left a tail of the right length and the wrong bytes, and verify's hash
     * is taken from the same file.
     */
    suspend fun run(
        target: File,
        url: String,
        headers: Map<String, String>,
        stored: OfflineValidator?,
        sourceETag: String?,
        spaceFmp4: Boolean,
        trustedBytes: (Long) -> Long = { it },
        onValidator: suspend (OfflineValidator?) -> Unit = {}
    ): OfflineTransferResult = withContext(Dispatchers.IO) {
        desktopWithCallCancel { onCall -> attempt(target, url, headers, stored, sourceETag, spaceFmp4, trustedBytes, onValidator, onCall) }
    }

    private suspend fun attempt(
        target: File,
        url: String,
        headers: Map<String, String>,
        stored: OfflineValidator?,
        sourceETag: String?,
        spaceFmp4: Boolean,
        trustedBytes: (Long) -> Long,
        onValidator: suspend (OfflineValidator?) -> Unit,
        onCall: (cancel: () -> Unit) -> Unit
    ): OfflineTransferResult {
        target.parentFile?.mkdirs()
        // A conversion can't resume; its partial is only a leftover. Its bytes leave the ledger before it starts over.
        var partial = target.length()
        if (spaceFmp4 && partial > 0) {
            ledger.discard(partial)
            target.delete()
            partial = 0
        }
        if (!spaceFmp4 && partial > 0) {
            val keep = trustedBytes(partial)
            if (keep < partial) {
                RandomAccessFile(target, "rw").use { it.setLength(keep) }
                ledger.discard(partial - keep)
                partial = keep
            }
        }
        val range = if (spaceFmp4) null else offlineRangeHeader(partial, stored)
        if (range == null && partial > 0) {
            ledger.discard(partial)
            target.delete()
            partial = 0
        }
        val response = try {
            http.open(url, headers, range, onCall)
        } catch (e: IOException) {
            return OfflineTransferResult.Interrupted("network")
        }
        return response.use {
            if (response.status == 401 || response.status == 403) return OfflineTransferResult.Failed("unauthorized")
            // A Wi-Fi sign-in page or proxy, not media. Saving it would cost the partial and pass off a page as a file.
            if (response.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true)) {
                return OfflineTransferResult.Interrupted("network")
            }
            val decision = offlineResumeDecision(
                partial,
                stored,
                OfflineResponse(
                    response.status,
                    response.header("Content-Range"),
                    response.header("ETag"),
                    response.header("Last-Modified"),
                    response.contentLength,
                    response.header("Accept-Ranges"),
                    sourceETag
                )
            )
            when (decision.action) {
                "fail" -> OfflineTransferResult.Failed(decision.reason ?: "server")
                "complete" -> OfflineTransferResult.Complete(decision.validator ?: stored, partial)
                "restart" -> {
                    ledger.discard(partial)
                    target.delete()
                    // A 200 is a whole body we can take from zero. Anything else needs a fresh request.
                    if (response.status == 200 && response.body != null) {
                        copy(response, target, 0, decision.totalBytes ?: response.contentLength, decision.validator, spaceFmp4, onValidator)
                    } else {
                        OfflineTransferResult.Interrupted(decision.reason ?: "restart")
                    }
                }
                else -> copy(response, target, decision.offset, decision.totalBytes, decision.validator, spaceFmp4, onValidator)
            }
        }
    }

    private suspend fun copy(
        response: DesktopOfflineHttpResponse,
        target: File,
        offset: Long,
        total: Long?,
        validator: OfflineValidator?,
        spaceFmp4: Boolean,
        onValidator: suspend (OfflineValidator?) -> Unit
    ): OfflineTransferResult {
        val body = response.body ?: return OfflineTransferResult.Failed("no-body")
        onValidator(validator)
        var received = offset
        var reserved = 0L
        if (total != null && total > offset) {
            if (!ledger.reserve(total - offset)) return OfflineTransferResult.NoBudget
            reserved = total - offset
        }
        val buffer = ByteArray(OFFLINE_CHUNK_BYTES)
        var cleanEof = false
        try {
            FileOutputStream(target, offset > 0).use { file ->
                val spacer = if (spaceFmp4) Fmp4Spacer(file) else null
                // Everything before a boundary reaches the disk before anything is written past it (offlineDurableOffset).
                var position = offset
                var nextSync = (offset / OFFLINE_SYNC_BYTES + 1) * OFFLINE_SYNC_BYTES
                fun writeSynced(length: Int) {
                    var at = 0
                    while (at < length) {
                        val take = minOf((length - at).toLong(), nextSync - position).toInt()
                        file.write(buffer, at, take)
                        at += take
                        position += take
                        if (position == nextSync) {
                            sync(file)
                            nextSync += OFFLINE_SYNC_BYTES
                        }
                    }
                }
                suspend fun room(bytes: Long): Boolean {
                    if (reserved >= bytes) return true
                    val want = maxOf(bytes - reserved, OFFLINE_UNKNOWN_RESERVE_BYTES)
                    if (!ledger.reserve(want)) return false
                    reserved += want
                    return true
                }
                try {
                    if (spacer != null &&
                        !room(OFFLINE_CHUNK_BYTES.toLong() + OFFLINE_INDEX_PLACEHOLDER_BYTES)
                    ) {
                        return OfflineTransferResult.NoBudget
                    }
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = body.read(buffer)
                        if (read < 0) {
                            cleanEof = true
                            break
                        }
                        // Bytes already in memory are written only after the ledger clears them (OFF-03e).
                        val extra = if (spacer != null && spacer.inserted == 0L) OFFLINE_INDEX_PLACEHOLDER_BYTES.toLong() else 0L
                        if (!room(read + extra)) return OfflineTransferResult.NoBudget
                        val before = spacer?.inserted ?: 0L
                        if (spacer != null) spacer.write(buffer, read) else writeSynced(read)
                        val written = read + (spacer?.inserted ?: 0L) - before
                        ledger.commit(written)
                        reserved -= written
                        received += read
                        ledger.progress(received, total)
                    }
                    sync(file)
                } catch (e: IOException) {
                    runCatching { sync(file) }
                    if (isDiskFull(e)) return OfflineTransferResult.NoBudget
                    return OfflineTransferResult.Interrupted("network")
                }
            }
        } finally {
            // Commits moved the rest. A drop leaves this much promised to nothing; the next try asks again.
            if (reserved > 0) ledger.release(reserved)
        }
        return when (offlineBodyEnd(received, total, cleanEof).action) {
            "complete", "unknown-length-complete" -> OfflineTransferResult.Complete(validator, target.length())
            "overrun" -> {
                ledger.discard(target.length())
                target.delete()
                OfflineTransferResult.Interrupted("overrun")
            }
            else -> OfflineTransferResult.Interrupted("truncated")
        }
    }
}

private fun isDiskFull(e: IOException): Boolean = e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space left")


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

private class Fmp4At(val type: String, val offset: Long, val size: Long, val headerSize: Int)

private class Fmp4Walk(val track: Fmp4Track?, val placeholder: Long, val fragments: List<Fmp4Fragment>, val boxes: List<Fmp4At>)

/**
 * Walks a finished file and turns its placeholder into `sidx`. False when the file isn't what a conversion should be, including when its
 * fragments would read their samples from anywhere but the mdat that follows them.
 */
internal fun writeFmp4Index(file: File): Boolean = RandomAccessFile(file, "rw").use { raf ->
    val walk = raf.walkFmp4() ?: return@use false
    val active = walk.track
    if (active == null || walk.placeholder < 0 || walk.fragments.isEmpty()) return@use false
    // Everything after the placeholder sits that much later than the server wrote it. Positions the server wrote into the boxes must move
    // with it, or every sample is read from the wrong place (ffmpeg's default: tfhd base-data-offset is the moof's own position).
    val shift = OFFLINE_INDEX_PLACEHOLDER_BYTES.toLong()
    val moved = walk.boxes.filter { it.offset > walk.placeholder && (it.type == "moof" || it.type == "mfra") }.map { box ->
        box to (fmp4ShiftOffsets(raf.readBox(box.offset, box.size) ?: return@use false, shift) ?: return@use false)
    }
    for ((box, bytes) in moved) {
        if (box.type != "moof") continue
        val starts = fmp4ExplicitDataStarts(bytes) ?: return@use false
        if (starts.isEmpty()) continue
        val mdat = walk.boxes.firstOrNull { it.offset > box.offset && it.type == "mdat" } ?: return@use false
        if (starts.any { it < mdat.offset + mdat.headerSize || it >= mdat.offset + mdat.size }) return@use false
    }
    for ((box, bytes) in moved) {
        raf.seek(box.offset)
        raf.write(bytes)
    }
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
    val boxes = mutableListOf<Fmp4At>()
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
        boxes += Fmp4At(String(head, 4, 4, Charsets.ISO_8859_1), offset, size, headerSize)
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
    return Fmp4Walk(track, placeholder, fragments, boxes)
}

/** A moov or moof past this is not a conversion we produced. */
private const val FMP4_MAX_METADATA_BOX = 16L * 1024 * 1024

private fun RandomAccessFile.readBox(offset: Long, size: Long): ByteArray? {
    if (size > FMP4_MAX_METADATA_BOX) return null
    seek(offset)
    return ByteArray(size.toInt()).also(::readFully)
}
