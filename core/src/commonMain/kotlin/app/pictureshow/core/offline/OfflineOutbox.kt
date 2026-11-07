/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import app.pictureshow.core.control.DecimalLongSerializer
import kotlinx.serialization.Serializable

@Serializable
data class OfflineUserData(
    @Serializable(with = DecimalLongSerializer::class) val positionTicks: Long = 0,
    val played: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedDate: String? = null
)

/** Only dirty, non-conflicting fields. */
@Serializable
data class OfflineUserDataWrite(
    @Serializable(with = DecimalLongSerializer::class) val positionTicks: Long? = null,
    val played: Boolean? = null
)

/** fields: "position", "played". */
@Serializable
data class OfflineConflict(
    @Serializable(with = DecimalLongSerializer::class) val serverPositionTicks: Long,
    val serverPlayed: Boolean,
    @Serializable(with = DecimalLongSerializer::class) val localPositionTicks: Long?,
    val localPlayed: Boolean?,
    val fields: List<String>
)

/**
 * positionTicks and played are dirty when non-null. phase: dirty|writing|conflict|synced. writing holds what the POST in flight carries, so a
 * viewing that lands during the write isn't mistaken for a write that didn't hold.
 */
@Serializable
data class OfflineOutboxEntry(
    val server: String,
    val viewer: String,
    val itemId: String,
    val base: OfflineUserData,
    @Serializable(with = DecimalLongSerializer::class) val sequence: Long = 0,
    @Serializable(with = DecimalLongSerializer::class) val positionTicks: Long? = null,
    val played: Boolean? = null,
    val phase: String = "dirty",
    val conflict: OfflineConflict? = null,
    val writing: OfflineUserDataWrite? = null
)

data class OfflineOutboxEvent(
    val op: String,
    val server: String? = null,
    val viewer: String? = null,
    val itemId: String? = null,
    val positionTicks: Long? = null,
    val played: Boolean? = null,
    val sequence: Long? = null,
    val current: OfflineUserData? = null,
    val base: OfflineUserData? = null,
    val status: Int? = null
)

/** effect: none|read|write|readback|ack|conflict. */
data class OfflineOutboxResult(
    val entry: OfflineOutboxEntry?,
    val effect: String = "none",
    val write: OfflineUserDataWrite? = null,
    val ignored: String? = null
)

private const val DIRTY = "dirty"
private const val WRITING = "writing"
private const val CONFLICT = "conflict"
private const val SYNCED = "synced"

private fun OfflineOutboxEntry.ignore(reason: String) = OfflineOutboxResult(this, ignored = reason)

private fun newEntry(event: OfflineOutboxEvent): OfflineOutboxEntry? = OfflineOutboxEntry(
    server = event.server ?: return null,
    viewer = event.viewer ?: return null,
    itemId = event.itemId ?: return null,
    base = event.base ?: return null,
    sequence = event.sequence ?: 0
)

/**
 * Applies a local change unless the record refuses it. A conflict holds until a strictly newer viewing or a dismissal; that viewing starts
 * again from its own base, because the conflict already told the viewer what the server holds. A synced record's server was current, so it
 * takes the event's base too. Any other record keeps its original base.
 */
private fun localChange(
    entry: OfflineOutboxEntry?,
    event: OfflineOutboxEvent,
    apply: (OfflineOutboxEntry) -> OfflineOutboxEntry
): OfflineOutboxResult {
    val record = entry ?: newEntry(event) ?: return OfflineOutboxResult(null, ignored = "no-entry")
    if (event.server != null && event.server != record.server || event.viewer != null && event.viewer != record.viewer) {
        return record.ignore("other-account")
    }
    if (event.itemId != null && event.itemId != record.itemId) return record.ignore("other-item")
    val sequence = event.sequence ?: record.sequence
    if (sequence < record.sequence) return record.ignore("old-sequence")
    val restarted = when (record.phase) {
        CONFLICT -> {
            if (event.op != "viewing" || sequence == record.sequence) return record.ignore(CONFLICT)
            record.copy(base = event.base ?: record.base, phase = DIRTY, conflict = null, positionTicks = null, played = null)
        }
        SYNCED -> record.copy(base = event.base ?: record.base, phase = DIRTY, positionTicks = null, played = null)
        else -> record
    }
    return OfflineOutboxResult(apply(restarted.copy(sequence = sequence)))
}

private fun serverState(entry: OfflineOutboxEntry, current: OfflineUserData): OfflineOutboxResult {
    if (entry.phase == CONFLICT) return entry.ignore(CONFLICT)
    val conflicts = mutableListOf<String>()
    var writePosition: Long? = null
    var writePlayed: Boolean? = null
    entry.positionTicks?.let { desired ->
        when {
            current.positionTicks == desired -> Unit
            // A write whose answer was lost may have landed: the server then holds this device's own value, not someone else's.
            current.positionTicks == entry.base.positionTicks -> writePosition = desired
            current.positionTicks == entry.writing?.positionTicks -> writePosition = desired
            else -> conflicts += "position"
        }
    }
    entry.played?.let { desired ->
        when {
            current.played == desired -> Unit
            current.played == entry.base.played -> writePlayed = desired
            current.played == entry.writing?.played -> writePlayed = desired
            else -> conflicts += "played"
        }
    }
    val conflict = if (conflicts.isEmpty()) null else entry.conflictRecord(current, conflicts)
    return when {
        writePosition != null || writePlayed != null -> {
            val write = OfflineUserDataWrite(writePosition, writePlayed)
            OfflineOutboxResult(entry.copy(phase = WRITING, conflict = conflict, writing = write), "write", write)
        }
        conflict != null -> OfflineOutboxResult(entry.copy(phase = CONFLICT, conflict = conflict, writing = null), CONFLICT)
        else -> OfflineOutboxResult(null, "ack")
    }
}

private fun OfflineOutboxEntry.conflictRecord(current: OfflineUserData, fields: List<String>) =
    OfflineConflict(current.positionTicks, current.played, positionTicks, played, fields)

private fun readback(entry: OfflineOutboxEntry, current: OfflineUserData): OfflineOutboxResult {
    val held = entry.conflict?.fields.orEmpty()
    val written = entry.writing ?: OfflineUserDataWrite(
        entry.positionTicks.takeUnless { "position" in held },
        entry.played.takeUnless { "played" in held }
    )
    val failed = listOfNotNull(
        "position".takeIf { written.positionTicks != null && written.positionTicks != current.positionTicks },
        "played".takeIf { written.played != null && written.played != current.played }
    )
    return when {
        failed.isNotEmpty() -> OfflineOutboxResult(
            entry.copy(phase = CONFLICT, writing = null, conflict = entry.conflictRecord(current, held + failed)),
            CONFLICT
        )
        entry.conflict != null -> OfflineOutboxResult(entry.copy(phase = CONFLICT, writing = null), "ack")
        written.positionTicks == entry.positionTicks && written.played == entry.played -> OfflineOutboxResult(null, "ack")
        // A viewing landed during the write: the written part holds, the newer values go through a fresh read.
        else -> OfflineOutboxResult(entry.copy(phase = DIRTY, base = current, writing = null), "read")
    }
}

/**
 * The durable offline progress record for one server, viewer and item (OFF-07, OFF-08). It writes only fields that still equal their base,
 * never replays Start/Stop, reads back every accepted write and reads before retrying an ambiguous one.
 */
fun reduceOfflineOutbox(entry: OfflineOutboxEntry?, event: OfflineOutboxEvent): OfflineOutboxResult = when (event.op) {
    "viewing" -> localChange(entry, event) { it.copy(positionTicks = event.positionTicks ?: it.positionTicks) }
    "completed" -> localChange(entry, event) { it.copy(played = true, positionTicks = 0) }
    "played-intent" -> localChange(entry, event) {
        val played = requireNotNull(event.played) { "played-intent needs played" }
        it.copy(played = played, positionTicks = if (played) it.positionTicks else 0)
    }
    "reconnect" -> when {
        entry == null -> OfflineOutboxResult(null, ignored = "no-entry")
        event.server != entry.server || event.viewer != entry.viewer -> entry.ignore("other-account")
        else -> OfflineOutboxResult(entry, "read")
    }
    "server-state" -> if (entry == null) {
        OfflineOutboxResult(null, ignored = "no-entry")
    } else {
        serverState(entry, requireNotNull(event.current) { "server-state needs current" })
    }
    "write-result" -> when {
        entry == null -> OfflineOutboxResult(null, ignored = "no-entry")
        entry.phase != WRITING -> entry.ignore("wrong-phase")
        event.status != null && event.status in 200..299 -> OfflineOutboxResult(entry, "readback")
        else -> OfflineOutboxResult(entry, "read")
    }
    "readback" -> when {
        entry == null -> OfflineOutboxResult(null, ignored = "no-entry")
        entry.phase != WRITING -> entry.ignore("wrong-phase")
        else -> readback(entry, requireNotNull(event.current) { "readback needs current" })
    }
    "process-restart" -> if (entry?.phase == WRITING) OfflineOutboxResult(entry, "read") else OfflineOutboxResult(entry)
    // A viewing online on this device is newer than anything held here. A server that didn't move means it reported nothing, so an unsent
    // place stays. A conflict goes either way: its notice is about a place the online session has replaced.
    "online-viewing" -> {
        val current = requireNotNull(event.current) { "online-viewing needs current" }
        when {
            entry == null -> OfflineOutboxResult(null, ignored = "no-entry")
            entry.phase != CONFLICT && current.positionTicks == entry.base.positionTicks && current.played == entry.base.played ->
                OfflineOutboxResult(entry)
            else -> OfflineOutboxResult(null)
        }
    }
    "dismiss-conflict" -> when {
        entry == null -> OfflineOutboxResult(null, ignored = "no-entry")
        entry.phase != CONFLICT -> entry.ignore("wrong-phase")
        else -> OfflineOutboxResult(null)
    }
    else -> error("Unknown offline outbox operation: ${event.op}")
}

/**
 * Where a local viewing starts. A conflict's place isn't one: the notice says the server's was kept, and the base of the next viewing
 * is the server's, so resuming from the older place would write it over the newer one.
 */
fun offlineResumeTicks(entry: OfflineOutboxEntry?, lastKnown: OfflineUserData?): Long = when {
    entry != null && entry.phase != CONFLICT && entry.played == true -> 0
    entry != null && entry.phase != CONFLICT && entry.positionTicks != null -> entry.positionTicks
    else -> lastKnown?.takeIf { !it.played }?.positionTicks ?: 0
}

/** Stable per server+viewer+installation, with no credential in it: "pictureshow-dl-" + the first 16 bytes of SHA-256 in hex. */
fun offlineDownloadSetDevice(installationDeviceId: String, serverId: String, userId: String): String =
    "pictureshow-dl-" + offlineSha256Hex("$installationDeviceId\u0000$serverId\u0000$userId".encodeToByteArray()).take(32)

private val SHA256_K = intArrayOf(
    0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
)

/** SHA-256 in pure common Kotlin, lowercase hex. */
fun offlineSha256Hex(bytes: ByteArray): String {
    val h = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)
    val padded = ByteArray(((bytes.size + 9 + 63) / 64) * 64)
    bytes.copyInto(padded)
    padded[bytes.size] = 0x80.toByte()
    val bitLength = bytes.size.toLong() * 8
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    val w = IntArray(64)
    for (block in padded.indices step 64) {
        for (t in 0 until 16) {
            val at = block + t * 4
            w[t] = (padded[at].toInt() and 0xff shl 24) or (padded[at + 1].toInt() and 0xff shl 16) or
                (padded[at + 2].toInt() and 0xff shl 8) or (padded[at + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        val v = h.copyOf()
        for (t in 0 until 64) {
            val s1 = v[4].rotateRight(6) xor v[4].rotateRight(11) xor v[4].rotateRight(25)
            val choose = (v[4] and v[5]) xor (v[4].inv() and v[6])
            val t1 = v[7] + s1 + choose + SHA256_K[t] + w[t]
            val s0 = v[0].rotateRight(2) xor v[0].rotateRight(13) xor v[0].rotateRight(22)
            val majority = (v[0] and v[1]) xor (v[0] and v[2]) xor (v[1] and v[2])
            for (i in 7 downTo 1) v[i] = v[i - 1]
            v[4] += t1
            v[0] = t1 + s0 + majority
        }
        for (i in 0 until 8) h[i] += v[i]
    }
    return h.joinToString("") { word ->
        (0 until 4).joinToString("") { i -> (word ushr (24 - 8 * i) and 0xff).toString(16).padStart(2, '0') }
    }
}
