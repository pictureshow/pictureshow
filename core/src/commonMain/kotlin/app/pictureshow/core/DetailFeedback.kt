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

/** A successful full read started after the completion check can replace its older snapshot. */
fun detailReadCanReplaceCompletion(completionItem: String, completionRead: Long, item: String, read: Long, succeeded: Boolean): Boolean =
    succeeded && completionItem == item && read > completionRead

/** A cold destination offers the same guarded requests as a returning destination. */
fun detailNeedsPendingControls(
    loading: Boolean,
    firstArrival: Boolean,
    canQueue: Boolean,
    returningFromPlayback: Boolean = false
): Boolean = loading && firstArrival && canQueue && !returningFromPlayback

/** Request acceptance is a cue; rediscovery describes an outstanding request instead. */
fun detailRequestedActionLabel(target: String, newlyAccepted: Boolean): String = if (newlyAccepted) {
    when (target) {
        "primary" -> "Play requested"
        "from-start" -> "Start requested"
        else -> "${detailHeldActionLabel(target)} change requested"
    }
} else {
    "${detailHeldActionLabel(target)} already requested"
}

/** Session bookkeeping without a data revision cannot invalidate retained catalog reads. */
fun catalogRevisionNeedsRefresh(previous: Long?, current: Long): Boolean = previous == null || previous != current

/** A pending attempt reports current work, never the previous attempt's reason. */
fun detailReadBandText(retrying: Boolean, reason: String): String = if (retrying) "Loading item. Please wait." else reason

/** A matching full leaf read already contains the acknowledged completion's current values. */
fun detailSessionNeedsRead(newRevision: Boolean, refreshed: Boolean, container: Boolean, alreadyConfirmed: Boolean): Boolean =
    newRevision && (refreshed || container) && !(refreshed && alreadyConfirmed)

/** A failure flag alone does not supply a reason for a superseding pending attempt. */
fun detailReadHasFailure(readFailed: Boolean, reason: String?, mutationReady: Boolean): Boolean =
    reason != null && (readFailed || !mutationReady)

/** Reader navigation to another node ends acceptance even when Compose input focus stays. */
fun detailRequestCueRemainsActive(active: Boolean, readerLeftAction: Boolean, requested: Boolean = true): Boolean =
    active && requested && !readerLeftAction

/** Only an applied full leaf result can already confirm a matching report refresh. */
fun detailHasConfirmedLeafRead(readSucceeded: Boolean, sameUserData: Boolean, container: Boolean): Boolean =
    readSucceeded && sameUserData && !container

/** Series and Season readiness includes their dependent episode reads. */
fun detailReadRequiresDependents(type: String): Boolean = type in setOf("Series", "Season")
