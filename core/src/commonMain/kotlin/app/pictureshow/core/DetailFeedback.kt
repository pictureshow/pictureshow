/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** Actual toggle value only. Availability is supplied by the native disabled semantics. */
fun detailActionDescription(currentState: String?, available: Boolean): String? = currentState.takeIf { available }

/** A disabled placeholder names the action without claiming an unknown toggle direction. */
fun detailHeldActionLabel(target: String): String = when (target) {
    "primary" -> "Play"
    "from-start" -> "Play from start"
    "played" -> "Watched"
    else -> "Favorite"
}

/** Visible placeholder wording is independent of the single native disabled cue. */
fun detailUnavailableActionLabel(target: String): String =
    if (target == "from-start") "Start unavailable" else "${detailHeldActionLabel(target)} unavailable"

/** Construction owns the first read; only a later lifecycle return needs another one. */
class DetailReadLifecycle {
    private var hasResumed = false

    fun resumed(): Boolean {
        val refresh = hasResumed
        hasResumed = true
        return refresh
    }
}

/** A readiness transition belongs to the same selected control, never to later focus visits. */
class DetailReadyFeedback {
    private var wasHeld = false
    private var previousFocus: String? = null

    fun update(held: Boolean, focused: String?, resumed: Boolean, usable: Boolean = true): String? {
        val sameFocus = focused != null && focused == previousFocus
        val cue = "Ready".takeIf { wasHeld && !held && usable && sameFocus && resumed }
        // A fresh empty container cannot make its selected Play usable. Preserve only
        // that existing hold until it becomes usable or selection leaves; ordinary busy
        // controls never start a new held episode.
        wasHeld = held || (wasHeld && !usable && sameFocus)
        previousFocus = focused
        return cue
    }
}

/** Transport generations do not start a new visible error. Only Retry or settlement releases it. */
fun detailFailureKey(readRecovery: Boolean, retrying: Boolean, writeAttempt: Long?): String? = when {
    readRecovery && !retrying -> "read"
    readRecovery -> null
    writeAttempt != null -> "write:$writeAttempt"
    else -> null
}

data class DetailFailurePresentation(val key: String, val message: String)

/**
 * Publish one truthful reason for an error episode. A transport timeout can refine a budget
 * failure a moment later; replacing that already-spoken reason makes the screen contradict
 * the reader. Keep both presentations together until Retry/new attempt or error clearance.
 */
fun presentDetailFailure(previous: DetailFailurePresentation?, key: String?, message: String?): DetailFailurePresentation? = when {
    key == null || message == null -> null
    previous?.key == key -> previous
    else -> DetailFailurePresentation(key, message)
}

/** Prioritize acceptance once; rediscovery and passive pending/ready changes use normal focus feedback. */
fun detailRequestFeedbackIsUrgent(newlyAccepted: Boolean, requested: Boolean, available: Boolean): Boolean =
    newlyAccepted && requested && available

/** A reader's forward catalog arrival need not wait for decorative navigation motion. */
fun catalogArrivalUsesAnimation(accessibilityEnabled: Boolean, sourceIsPlayback: Boolean, destinationIsCatalog: Boolean): Boolean =
    !accessibilityEnabled || sourceIsPlayback || !destinationIsCatalog

/** Keep an initial read's static metadata from becoming the reader's replacement focus. */
fun detailArrivalAwaitsAction(loading: Boolean, contentRevealed: Boolean, returningFromPlayback: Boolean): Boolean =
    loading && !contentRevealed && !returningFromPlayback

/** Publish arrival actions before static metadata can become the reader's fallback target. */
fun detailMetadataAwaitsHandoff(hasActions: Boolean, handoffFinished: Boolean, returningFromPlayback: Boolean, failed: Boolean): Boolean =
    hasActions && !handoffFinished && !returningFromPlayback && !failed

/** The host's empty focus record cannot identify the ordinary page's selected virtual action. */
fun detailSuppressesEmptyHostFocus(
    ordinaryArrivalWithActions: Boolean,
    handoffFinished: Boolean,
    returningFromPlayback: Boolean,
    recoveryHandoff: Boolean,
    failed: Boolean
): Boolean = recoveryHandoff || (!handoffFinished && (returningFromPlayback || (ordinaryArrivalWithActions && !failed)))

/** Once the first handoff finishes, a later lifecycle pause is not another arrival. */
fun detailArrivalStillPending(wasPending: Boolean, handoffFinished: Boolean): Boolean = wasPending && !handoffFinished
