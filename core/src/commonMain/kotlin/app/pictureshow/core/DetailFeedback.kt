/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/**
 * Complement the action label without repeating it. The label describes pending/requested intent;
 * its accepted live-region cue supplies delivery priority. State describes actual value or readiness.
 */
fun detailActionDescription(currentState: String?, pending: Boolean, available: Boolean, ready: Boolean, requested: Boolean): String? =
    when {
        !available -> "Unavailable while saving progress."
        requested || pending -> currentState
        ready -> listOfNotNull("Ready", currentState).joinToString(". ")
        else -> currentState
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
