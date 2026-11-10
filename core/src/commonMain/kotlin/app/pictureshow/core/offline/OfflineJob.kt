/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.offline

import kotlinx.serialization.Serializable

@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
@Serializable
enum class OfflinePhase {
    queued,
    waitingNetwork,
    waitingStorage,
    preparing,
    transferring,
    verifying,
    ready,
    paused,
    failed,
    missing,
    deleting
}

@Serializable
data class OfflineJob(
    val phase: OfflinePhase = OfflinePhase.queued,
    val attempt: Long = 0,
    val cellularAllowed: Boolean = false,
    val failure: String? = null,
    val pausedByCap: Boolean = false
)

data class OfflineJobEvent(val op: String, val attempt: Long? = null, val reason: String? = null, val cellularAllowed: Boolean? = null)

/** job == null means the record is gone (only after `deleted`). */
data class OfflineJobResult(val job: OfflineJob?, val effects: List<String> = emptyList(), val ignored: String? = null)

/** Maximum transfers running at once per account (OFF-06c). */
const val OFFLINE_MAX_TRANSFERS = 2

private val RUNNING = setOf(OfflinePhase.preparing, OfflinePhase.transferring, OfflinePhase.verifying)
private val ATTEMPT_BOUND = setOf("prepared", "transfer-complete", "verified", "committed", "verify-failed", "restart-transfer", "fail")

/**
 * The durable state of one download. Attempt-bound callbacks from an old attempt are ignored; Ready is reachable only through
 * `committed` (OFF-03a). A repeated `verified` re-emits `commit-ready`: the commit itself is guarded, and a second `committed` is
 * `wrong-phase`.
 */
fun reduceOfflineJob(job: OfflineJob, event: OfflineJobEvent): OfflineJobResult {
    val wrongPhase = OfflineJobResult(job, ignored = "wrong-phase")
    val ran = job.phase in RUNNING
    val stop = if (ran) listOf("stop-transfer") else emptyList()
    if (event.op in ATTEMPT_BOUND && event.attempt != job.attempt) return OfflineJobResult(job, ignored = "stale-attempt")
    return when (event.op) {
        "admit" -> if (job.phase == OfflinePhase.queued) {
            OfflineJobResult(job.copy(phase = OfflinePhase.preparing, attempt = job.attempt + 1), listOf("start-attempt"))
        } else {
            wrongPhase
        }
        "prepared" -> if (job.phase == OfflinePhase.preparing) OfflineJobResult(job.copy(phase = OfflinePhase.transferring)) else wrongPhase
        "transfer-complete" -> if (job.phase == OfflinePhase.transferring) {
            OfflineJobResult(job.copy(phase = OfflinePhase.verifying), listOf("verify"))
        } else {
            wrongPhase
        }
        "verified" -> if (job.phase == OfflinePhase.verifying) OfflineJobResult(job, listOf("commit-ready")) else wrongPhase
        "committed" -> if (job.phase == OfflinePhase.verifying) {
            OfflineJobResult(job.copy(phase = OfflinePhase.ready, failure = null), listOf("publish"))
        } else {
            wrongPhase
        }
        "verify-failed" -> if (job.phase == OfflinePhase.verifying) {
            OfflineJobResult(job.copy(phase = OfflinePhase.queued, failure = event.reason), listOf("discard-partial"))
        } else {
            wrongPhase
        }
        "restart-transfer" -> if (job.phase == OfflinePhase.transferring || job.phase == OfflinePhase.preparing) {
            OfflineJobResult(job, listOf("discard-partial"))
        } else {
            wrongPhase
        }
        "network-lost" -> if (ran || job.phase == OfflinePhase.queued) {
            OfflineJobResult(job.copy(phase = OfflinePhase.waitingNetwork), stop)
        } else {
            wrongPhase
        }
        "network-back" -> if (job.phase ==
            OfflinePhase.waitingNetwork
        ) {
            OfflineJobResult(job.copy(phase = OfflinePhase.queued))
        } else {
            wrongPhase
        }
        "storage-denied" -> if (job.phase == OfflinePhase.preparing ||
            job.phase == OfflinePhase.transferring ||
            job.phase == OfflinePhase.queued
        ) {
            OfflineJobResult(job.copy(phase = OfflinePhase.waitingStorage), stop)
        } else {
            wrongPhase
        }
        "storage-available" -> if (job.phase ==
            OfflinePhase.waitingStorage
        ) {
            OfflineJobResult(job.copy(phase = OfflinePhase.queued))
        } else {
            wrongPhase
        }
        "pause" -> if (ran || job.phase in PAUSABLE_IDLE) {
            OfflineJobResult(job.copy(phase = OfflinePhase.paused, attempt = job.attempt + 1, pausedByCap = event.reason == "cap"), stop)
        } else {
            wrongPhase
        }
        "resume" -> if (job.phase ==
            OfflinePhase.paused
        ) {
            OfflineJobResult(job.copy(phase = OfflinePhase.queued, pausedByCap = false))
        } else {
            wrongPhase
        }
        "delete" -> if (job.phase == OfflinePhase.deleting) {
            OfflineJobResult(job, listOf("delete-owned"))
        } else {
            OfflineJobResult(job.copy(phase = OfflinePhase.deleting, attempt = job.attempt + 1), stop + "delete-owned")
        }
        "deleted" -> if (job.phase == OfflinePhase.deleting) OfflineJobResult(null) else wrongPhase
        "fail" -> if (ran) OfflineJobResult(job.copy(phase = OfflinePhase.failed, failure = event.reason), stop) else wrongPhase
        "retry" -> if (job.phase ==
            OfflinePhase.failed
        ) {
            OfflineJobResult(job.copy(phase = OfflinePhase.queued, failure = null))
        } else {
            wrongPhase
        }
        "process-restart" -> when {
            ran -> OfflineJobResult(job.copy(phase = OfflinePhase.queued, attempt = job.attempt + 1))
            job.phase == OfflinePhase.deleting -> OfflineJobResult(job, listOf("delete-owned"))
            else -> OfflineJobResult(job)
        }
        "missing" -> if (job.phase == OfflinePhase.ready) OfflineJobResult(job.copy(phase = OfflinePhase.missing)) else wrongPhase
        "redownload" -> if (job.phase == OfflinePhase.missing || job.phase == OfflinePhase.failed) {
            OfflineJobResult(job.copy(phase = OfflinePhase.queued, failure = null), listOf("discard-partial"))
        } else {
            wrongPhase
        }
        "set-cellular" -> if (job.phase != OfflinePhase.ready && job.phase != OfflinePhase.deleting) {
            OfflineJobResult(job.copy(cellularAllowed = requireNotNull(event.cellularAllowed) { "set-cellular needs cellularAllowed" }))
        } else {
            wrongPhase
        }
        else -> error("Unknown offline job operation: ${event.op}")
    }
}

private val PAUSABLE_IDLE = setOf(OfflinePhase.queued, OfflinePhase.waitingNetwork, OfflinePhase.waitingStorage, OfflinePhase.failed)

/** ids to start now: at most OFFLINE_MAX_TRANSFERS running per account, FIFO, active = preparing/transferring/verifying. */
fun offlineAdmit(activeCount: Int, queuedInOrder: List<String>): List<String> =
    queuedInOrder.take((OFFLINE_MAX_TRANSFERS - activeCount).coerceAtLeast(0))
