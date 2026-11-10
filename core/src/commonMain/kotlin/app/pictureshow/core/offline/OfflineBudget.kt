/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlinx.serialization.Serializable

const val OFFLINE_DEFAULT_CAP_BYTES = 10L * 1024 * 1024 * 1024
const val OFFLINE_FREE_RESERVE_BYTES = 512L * 1024 * 1024

/** min(10 GiB, 20% of quota, integer floor); null when the quota is unknown: the viewer must pick a cap. */
fun offlineDefaultCap(quotaBytes: Long?): Long? {
    if (quotaBytes == null || quotaBytes < 0) return null
    return minOf(OFFLINE_DEFAULT_CAP_BYTES, quotaBytes / 5)
}

@Serializable
data class OfflineBudget(val capBytes: Long? = null, val usedBytes: Long = 0, val reservations: Map<String, Long> = emptyMap())

data class OfflineBudgetResult(
    val budget: OfflineBudget,
    val granted: Boolean = true,
    val reason: String? = null,
    val pauseJobs: List<String> = emptyList()
)

private fun OfflineBudget.reserved(): Long = reservations.values.sum()

/** Adds to the job's reservation. freeBytes = measured free space now, null when unknown. Denied reasons: "cap-required", "cap", "disk". */
fun OfflineBudget.reserve(job: String, bytes: Long, freeBytes: Long?): OfflineBudgetResult {
    require(bytes >= 0) { "Negative reservation" }
    if (bytes == 0L) return OfflineBudgetResult(this)
    val cap = capBytes ?: return OfflineBudgetResult(this, granted = false, reason = "cap-required")
    val reserved = reserved()
    if (bytes > cap - usedBytes - reserved) return OfflineBudgetResult(this, granted = false, reason = "cap")
    if (freeBytes != null && freeBytes - reserved - bytes < OFFLINE_FREE_RESERVE_BYTES) {
        return OfflineBudgetResult(this, granted = false, reason = "disk")
    }
    return OfflineBudgetResult(copy(reservations = reservations + (job to (reservations[job] ?: 0) + bytes)))
}

/** Moves bytes from reservation to used; more than the remaining reservation is refused unchanged ("unreserved"). */
fun OfflineBudget.commit(job: String, bytes: Long): OfflineBudgetResult {
    require(bytes >= 0) { "Negative commit" }
    if (bytes == 0L) return OfflineBudgetResult(this)
    val remaining = reservations[job] ?: 0
    if (bytes > remaining) return OfflineBudgetResult(this, granted = false, reason = "unreserved")
    val left = remaining - bytes
    return OfflineBudgetResult(
        copy(usedBytes = usedBytes + bytes, reservations = if (left == 0L) reservations - job else reservations + (job to left))
    )
}

/** Drops the remaining reservation only. */
fun OfflineBudget.release(job: String): OfflineBudgetResult = OfflineBudgetResult(copy(reservations = reservations - job))

/** Deleting partials: used -= committedBytes (floor 0), and the remaining reservation goes too. */
fun OfflineBudget.discard(job: String, committedBytes: Long): OfflineBudgetResult {
    require(committedBytes >= 0) { "Negative discard" }
    return OfflineBudgetResult(copy(usedBytes = (usedBytes - committedBytes).coerceAtLeast(0), reservations = reservations - job))
}

/**
 * If used + reservations > cap, releases reservations newest first until it fits; those jobs go in pauseJobs. Jobs not named in
 * jobNewestFirst release last, in key order. Committed bytes are never evicted (OFF-06e), so a cap below usedBytes keeps the bytes, releases
 * every reservation and answers reason "used-over-cap"; every later reserve is then refused with "cap".
 */
fun OfflineBudget.setCap(capBytes: Long?, jobNewestFirst: List<String>): OfflineBudgetResult {
    if (capBytes == null) return OfflineBudgetResult(copy(capBytes = null))
    var left = reservations
    val paused = mutableListOf<String>()
    for (job in jobNewestFirst.distinct() + reservations.keys.sorted()) {
        if (usedBytes + left.values.sum() <= capBytes) break
        if (job in left) {
            left = left - job
            paused += job
        }
    }
    return OfflineBudgetResult(
        copy(capBytes = capBytes, reservations = left),
        reason = if (usedBytes > capBytes) "used-over-cap" else null,
        pauseJobs = paused
    )
}
