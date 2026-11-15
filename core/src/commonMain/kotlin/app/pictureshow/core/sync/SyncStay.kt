/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

/**
 * SYN-08 (operator, 2026-11-15): closing the player keeps this viewer in its group.
 * Only an explicit Leave, an external route handoff, a sign-out or the server removes it.
 */
enum class SyncCloseReason {
    BACK,
    CLOSE,
    STOP_BUTTON,
    ERROR_BACK,
    NATURAL_END,
    BACKGROUND_IDLE,
    BACKGROUND_RENDERING,
    EXTERNAL_ROUTE,
    MANUAL_LEAVE,
    SIGN_OUT
}

enum class SyncCloseOutcome { NONE, STAY, LEAVE }

/**
 * [ignoreWait] asks the server not to hold the group's wait barrier for a device that renders nothing
 * (Jellyfin marks members buffering on joins and new queues; only Ready or IgnoreWait releases them).
 * [departure] is the retained cause for a Leave; a stay records none.
 */
data class SyncCloseDecision(val outcome: SyncCloseOutcome, val ignoreWait: Boolean = false, val departure: SyncDepartureCause? = null)

/** [following] is true while this device renders, or is opening, the group's current entry. */
fun syncCloseDecision(reason: SyncCloseReason, participating: Boolean, following: Boolean): SyncCloseDecision {
    if (!participating) return SyncCloseDecision(SyncCloseOutcome.NONE)
    return when (reason) {
        SyncCloseReason.BACK,
        SyncCloseReason.CLOSE,
        SyncCloseReason.STOP_BUTTON,
        SyncCloseReason.ERROR_BACK,
        SyncCloseReason.NATURAL_END -> SyncCloseDecision(SyncCloseOutcome.STAY, ignoreWait = following)
        // A home screen sent to the background has nothing to pause; membership is unchanged.
        SyncCloseReason.BACKGROUND_IDLE -> SyncCloseDecision(SyncCloseOutcome.STAY)
        // Unchanged SYN-08 background rule: a backgrounded renderer pauses and leaves once.
        SyncCloseReason.BACKGROUND_RENDERING -> SyncCloseDecision(SyncCloseOutcome.LEAVE, departure = SyncDepartureCause.MEMBERSHIP_LOST)
        SyncCloseReason.EXTERNAL_ROUTE -> SyncCloseDecision(SyncCloseOutcome.LEAVE, departure = SyncDepartureCause.EXTERNAL_ROUTE)
        SyncCloseReason.MANUAL_LEAVE -> SyncCloseDecision(SyncCloseOutcome.LEAVE, departure = SyncDepartureCause.MANUAL_LEAVE)
        // The account scope changes, so no departure fact survives for the next viewer.
        SyncCloseReason.SIGN_OUT -> SyncCloseDecision(SyncCloseOutcome.LEAVE)
    }
}

/** Facts the reminder may use; no participant readiness or identity is inferred. */
data class SyncStayFacts(
    val groupId: String?,
    val groupName: String?,
    val phase: String,
    val queueEntries: Int,
    val playerOpen: Boolean,
    val connected: Boolean,
    val leavePending: Boolean
)

/** "Still in <group> · Leave", with Watch (rejoin playback) only when there is something to rejoin. */
data class SyncStayReminder(val groupName: String?, val rejoin: Boolean, val leaveUnconfirmed: Boolean)

fun syncStayReminder(facts: SyncStayFacts): SyncStayReminder? {
    if (facts.playerOpen) return null
    val name = facts.groupName?.takeUnless { it.isBlank() }
    if (facts.leavePending) return SyncStayReminder(name, rejoin = false, leaveUnconfirmed = true)
    if (facts.groupId == null) return null
    val rejoin = facts.connected && facts.queueEntries > 0 && facts.phase !in setOf("Error", "Reconnecting")
    return SyncStayReminder(name, rejoin, leaveUnconfirmed = false)
}

/**
 * The server saying this session is in no group (NotInGroup, GroupDoesNotExist) ends a stayed membership
 * locally with no Leave request; an open player keeps its existing error and Leave recovery.
 */
fun syncStayEndsOnDenial(reason: String, playerOpen: Boolean): Boolean = !playerOpen && reason in setOf("NotInGroup", "GroupDoesNotExist")

/** A stayed device never opens a new group entry by itself; Watch on the reminder starts following again. */
fun syncOpensGroupEntry(following: Boolean): Boolean = following
