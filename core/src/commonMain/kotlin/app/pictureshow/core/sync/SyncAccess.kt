/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

/** Policy lookup state is distinct from a successfully confirmed server denial. */
enum class SyncAccessNotice { UNKNOWN, CHECKING, UNAVAILABLE, DENIED, ALLOWED }

data class SyncAccessPresentation(val notice: SyncAccessNotice, val canJoin: Boolean, val canCreate: Boolean, val canRefresh: Boolean)

/** Unknown or failed access never inherits the last viewer's permission; denied viewers can recheck while idle. */
fun syncAccessPresentation(policy: String, known: Boolean, busy: Boolean, failed: Boolean): SyncAccessPresentation {
    val confirmed = known && !failed
    val canJoin = confirmed && (policy == "CreateAndJoinGroups" || policy == "JoinGroups")
    val notice = when {
        failed -> SyncAccessNotice.UNAVAILABLE
        !known && busy -> SyncAccessNotice.CHECKING
        !known -> SyncAccessNotice.UNKNOWN
        policy == "None" -> SyncAccessNotice.DENIED
        canJoin -> SyncAccessNotice.ALLOWED
        else -> SyncAccessNotice.UNAVAILABLE
    }
    return SyncAccessPresentation(notice, canJoin, confirmed && policy == "CreateAndJoinGroups", !busy)
}

enum class SyncGroupListState { LOADING, FAILED, EMPTY, GROUPS, HIDDEN }

/**
 * SYN-01 / phone review P-2: a group list that failed to load never says "no groups", and nobody can start a group
 * until a refresh has actually reached the server. While a list loads the create form stays in place (its button
 * waits on the load), so focus and a half-typed name don't jump. Vector `sync-group-list`.
 */
data class SyncGroupList(val state: SyncGroupListState, val canCreate: Boolean)

fun syncGroupList(accessAllowed: Boolean, policy: String, busy: Boolean, failed: Boolean, count: Int): SyncGroupList {
    val state = when {
        !accessAllowed -> SyncGroupListState.HIDDEN
        busy -> SyncGroupListState.LOADING
        failed -> SyncGroupListState.FAILED
        count == 0 -> SyncGroupListState.EMPTY
        else -> SyncGroupListState.GROUPS
    }
    return SyncGroupList(
        state,
        policy == "CreateAndJoinGroups" && state != SyncGroupListState.FAILED && state != SyncGroupListState.HIDDEN
    )
}
