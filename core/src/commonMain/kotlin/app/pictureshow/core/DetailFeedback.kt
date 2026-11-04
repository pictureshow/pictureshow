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

/** Brief authoritative reads keep ordinary arrival speech; Select still queues behind the read. */
const val DETAIL_PENDING_LABEL_GRACE_MILLIS = 1_500L

/** A hidden navigation transition cannot spend the reader's arrival grace. */
fun detailPendingPresentationVisible(waiting: Boolean, resumed: Boolean, published: Boolean): Boolean = waiting && resumed && published

fun detailPendingPresentationLabel(held: Boolean, requested: Boolean, available: Boolean, plain: String, pending: String): String =
    if (held || requested || !available) pending else plain

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

/**
 * A reader can replace a waiting pending cue with this one, so it may be the only cue heard:
 * it names the action rather than relying on "when ready" having been spoken.
 */
fun detailReadyCue(actionLabel: String): String = "Ready. $actionLabel"

/**
 * TalkBack gives every label change on the selected control one speech group, and a newer one
 * discards the older one still waiting. The first arrival speech has taken up to about 1.8 s on
 * a slow speech engine, so hold Ready this long after a pending cue. Readers hear Ready late, and
 * the action works throughout.
 */
const val DETAIL_READY_AFTER_PENDING_CUE_MILLIS = 1_500L

/** Readiness belongs only to a held control already presented to the reader. */
class DetailReadyFeedback {
    private var presentedHold = false
    private var previousFocus: String? = null

    /** The platform supplied this held label while the same control was selected. */
    fun presented(target: String) {
        presentedHold = true
        previousFocus = target
    }
    fun submitted() {
        presentedHold = false
    }
    fun cancel() {
        presentedHold = false
    }

    fun update(
        held: Boolean,
        focused: String?,
        resumed: Boolean,
        usable: Boolean = true,
        presented: Boolean = false,
        cancelled: Boolean = false
    ): String? {
        val sameFocus = focused != null && focused == previousFocus
        if (!sameFocus || !resumed || cancelled) presentedHold = false
        if (held && presented && resumed && !cancelled && focused != null) presentedHold = true
        val cue = "Ready".takeIf { presentedHold && !held && usable && sameFocus && resumed && !cancelled }
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
fun catalogArrivalUsesAnimation(
    accessibilityEnabled: Boolean,
    sourceIsPlayback: Boolean,
    destinationIsCatalog: Boolean,
    returning: Boolean = false
): Boolean = if (returning) {
    !(sourceIsPlayback && destinationIsCatalog)
} else {
    !accessibilityEnabled || sourceIsPlayback || !destinationIsCatalog
}

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

/** After the reader reaches a request, leaving it ends acceptance even if input focus stays. */
fun detailRequestCueRemainsActive(
    active: Boolean,
    readerLeftAction: Boolean,
    requested: Boolean = true,
    presented: Boolean = true
): Boolean = active && requested && !(presented && readerLeftAction)

/** Only an applied full leaf result can already confirm a matching report refresh. */
fun detailHasConfirmedLeafRead(readSucceeded: Boolean, sameUserData: Boolean, container: Boolean): Boolean =
    readSucceeded && sameUserData && !container

/** Series and Season readiness includes their dependent episode reads. */
fun detailReadRequiresDependents(type: String): Boolean = type in setOf("Series", "Season")

/** TalkBack supplies punctuation between the state description and the role/label. */
fun detailRetryDescription(reason: String): String = "Retry. Loading failed. ${reason.trimEnd().trimEnd('.')}"

/** During the established Retry handoff, its focus event replaces old action-label chatter. */
fun detailQuietsDepartingActionChange(handoff: Boolean, action: Boolean, onlyLabelChanged: Boolean): Boolean =
    handoff && action && onlyLabelChanged

/** Rediscovery is supplied by focus; acceptance has its own one-shot event. */
fun detailQuietsRequestedRediscoveryChange(action: Boolean, onlyLabelChanged: Boolean, rediscovery: Boolean): Boolean =
    action && onlyLabelChanged && rediscovery

/** Submission belongs to an accepted request, not an observer or layout lifetime. */
class DetailRequestFeedback {
    private var lastSubmitted: Long? = null
    fun shouldSend(request: Long?, eligible: Boolean): Boolean = request != null && eligible && request != lastSubmitted
    fun submitted(request: Long) {
        lastSubmitted = request
    }
}

/** Framework prefetch cannot turn an unselected placeholder into a readiness cue. */
fun detailHeldLabelIsPresentation(selectedWhileHeld: Boolean, heldLabelRequested: Boolean): Boolean =
    selectedWhileHeld && heldLabelRequested

/** A logical action name is insufficient if its announcing page has departed. */
fun detailFeedbackCanSubmit(currentPage: Boolean, selectedAction: Boolean): Boolean = currentPage && selectedAction

/** Completion intent changes presentation only; the authoritative read/write guard stays separate. */
fun detailCompletionPresentedPlayed(played: Boolean, completionIntent: Boolean): Boolean = played || completionIntent

/** A desired-state request may already match a newer read, but must not disappear silently. */
fun detailPlayedRequestNotice(wanted: Boolean, current: Boolean): String? = if (wanted == current) {
    "Nothing changed. This item is already ${if (wanted) "watched" else "unwatched"}."
} else {
    null
}

/** Release only this page's exact retained reader node; input focus remains independent. */
fun detailReaderFocusCanClear(currentPage: Boolean, ownedHeader: Boolean, sameNativeNode: Boolean): Boolean =
    currentPage && ownedHeader && sameNativeNode

/** Only an accepted, newer Retry attempt owns a failure cue away from the Retry control. */
fun detailRetryFailureNeedsCue(baseline: Long?, attempt: Long, loading: Boolean, failed: Boolean, resumed: Boolean): Boolean =
    baseline != null && attempt > baseline && !loading && failed && resumed

/** A successful Retry ends its episode before an unrelated dependent read can fail later. */
fun detailRetryAttemptSucceeded(baseline: Long?, attempt: Long, loading: Boolean, failed: Boolean): Boolean =
    baseline != null && attempt > baseline && !loading && !failed

/** Only the raw successful read can confirm an ACK; projected completion state cannot. */
fun detailReadMatchesAcknowledgement(raw: ItemUserData?, acknowledged: ItemUserData?): Boolean = raw != null && raw == acknowledged

/** A failed report ends its claim; a successful raw read ends it once reporting is over. */
fun detailCompletionIntentRetained(reportFailed: Boolean, reportPending: Boolean, rawReadSucceeded: Boolean): Boolean =
    !reportFailed && (reportPending || !rawReadSucceeded)

/** No reader can receive a cue while accessibility is disabled; pending delivery is bounded. */
fun detailRetryCueCanWait(accessibilityEnabled: Boolean, elapsedMillis: Long): Boolean =
    accessibilityEnabled && elapsedMillis < ApiTimeouts.REQUEST_MILLIS

/** An accepted later played-state choice outranks automatic completion from that report. */
fun detailCompletionMayApply(reportChoiceRevision: Long?, currentChoiceRevision: Long): Boolean =
    reportChoiceRevision != null && reportChoiceRevision == currentChoiceRevision

/** Only a control selected during the quiet grace needs an explicit pending transition. */
class DetailPendingFeedback {
    private var graceTarget: String? = null
    private var submitted = false

    fun update(waiting: Boolean, graceExpired: Boolean, focused: String?, resumed: Boolean, cancelled: Boolean): String? {
        if (!waiting || !resumed || cancelled) {
            graceTarget = null
            submitted = false
            return null
        }
        if (!graceExpired) graceTarget = focused
        return focused?.takeIf { graceExpired && !submitted && it == graceTarget }
    }

    fun submitted() {
        submitted = true
    }
}

/** Retained catalog user data can name a pending toggle before its authoritative read. */
fun detailPendingPlainLabel(target: String, known: Boolean, played: Boolean, favorite: Boolean): String = when {
    !known -> detailHeldActionLabel(target)
    target == "played" -> if (played) "Mark unwatched" else "Mark watched"
    target == "favorite" -> if (favorite) "Unfavorite" else "Favorite"
    else -> detailHeldActionLabel(target)
}
