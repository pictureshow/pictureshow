/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

/** A UI estimate from current local facts, never a qualification of another renderer or physical output. */
enum class SyncQuality { UNKNOWN, IN_SYNC, CATCHING_UP, OUT_OF_SYNC }

fun syncQuality(usableClock: Boolean, ready: Boolean, applied: Boolean, errorTicks: Long?, uncertaintyTicks: Long?): SyncQuality = when {
    !usableClock ||
        !ready ||
        !applied ||
        errorTicks == null ||
        uncertaintyTicks == null ||
        uncertaintyTicks !in 0..500_000 -> SyncQuality.UNKNOWN
    errorTicks in -1_000_000..1_000_000 -> SyncQuality.IN_SYNC
    errorTicks in -10_000_000..10_000_000 -> SyncQuality.CATCHING_UP
    else -> SyncQuality.OUT_OF_SYNC
}

/** Queue lookup responses belong to the exact account, membership and queue revision that requested them. */
data class SyncTitleScope(val account: String, val membership: Long, val revision: Long)
fun syncAcceptTitle(request: SyncTitleScope, current: SyncTitleScope, entryIds: Set<String>, requestedEntries: Set<String>): Boolean =
    request == current && requestedEntries.isNotEmpty() && entryIds.containsAll(requestedEntries)

/** Group state never identifies a buffering person. The names-only roster cannot identify this device. */
enum class SyncPhase { EMPTY, JOINING, WAITING, WAITING_BUFFER, WAITING_SEEK, PLAYING, PAUSED, RECONNECTING, LEAVING, ERROR }
fun syncPhase(phase: String, reason: String?): SyncPhase = when (phase.lowercase()) {
    "joining" -> SyncPhase.JOINING
    "waiting" -> when (reason) {
        "Buffer" -> SyncPhase.WAITING_BUFFER
        "Seek" -> SyncPhase.WAITING_SEEK
        else -> SyncPhase.WAITING
    }
    "playing" -> SyncPhase.PLAYING
    "paused" -> SyncPhase.PAUSED
    "reconnecting" -> SyncPhase.RECONNECTING
    "leaving" -> SyncPhase.LEAVING
    "error" -> SyncPhase.ERROR
    else -> SyncPhase.EMPTY
}

data class SyncPeople(val names: List<String>, val remaining: Int)
fun syncPeople(names: List<String>): SyncPeople = SyncPeople(names.take(4), (names.size - 4).coerceAtLeast(0))

/** An empty authoritative group queue has no transport target, even while settings remain usable. */
fun syncTransportAvailable(usable: Boolean, queueEntries: Int): Boolean = usable && queueEntries > 0

/** A recovery banner owns its estimate/action; the panel phase must not duplicate its chip hint. */
fun syncPanelQuality(quality: SyncQuality, recoveryOffered: Boolean): SyncQuality = if (recoveryOffered) SyncQuality.UNKNOWN else quality

/** A glanceable chip omits an unknown estimate rather than reporting a non-fact. */
fun syncChipShowsQuality(quality: SyncQuality): Boolean = quality != SyncQuality.UNKNOWN

/** An empty authoritative queue has no local playback fact to display. */
fun syncShowsLocalPlayback(queueEntries: Int): Boolean = queueEntries > 0

/** Native segmentation and measurement supply complete graphemes and actual available width. */
fun syncChipName(name: String, graphemeEnds: List<Int>, fits: (String) -> Boolean): String? {
    if (fits(name)) return name
    if (graphemeEnds.size < 6) return null
    return graphemeEnds.indices.reversed().firstNotNullOfOrNull { index ->
        if (index < 5 || graphemeEnds[index] == name.length) null else {
            (name.substring(0, graphemeEnds[index]) + "…").takeIf(fits)
        }
    }
}
