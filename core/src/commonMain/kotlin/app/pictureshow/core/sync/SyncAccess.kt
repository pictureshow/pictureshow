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
