/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

/** Facts about this viewer's departure; never infer kick/dissolution/everyone-left. */
enum class SyncDepartureCause {
    MANUAL_LEAVE,
    PLAYBACK_STOPPED,
    PLAYBACK_ENDED,
    PLAYBACK_CLOSED,
    EXTERNAL_ROUTE,
    MEMBERSHIP_LOST
}

data class SyncDeparture(val id: Long, val groupName: String?, val cause: SyncDepartureCause)

/** scope is a distinct account/token epoch, NOT request sequence or membership generation. */
data class SyncDepartureState(
    val scope: Long = 0,
    val revision: Long = 0,
    val pending: SyncDeparture? = null,
    val presentedIn: Long? = null
)

sealed interface SyncDepartureEvent {
    data class ScopeChanged(val scope: Long) : SyncDepartureEvent
    data class MembershipStarted(val scope: Long) : SyncDepartureEvent
    data class Recorded(val scope: Long, val name: String?, val cause: SyncDepartureCause) : SyncDepartureEvent
    data class Presented(val scope: Long, val id: Long, val chooserSession: Long) : SyncDepartureEvent
    data class Dismissed(val scope: Long, val id: Long, val chooserSession: Long) : SyncDepartureEvent
}

/** Pending facts survive hidden time and refresh; only a matching visible chooser dismissal consumes. */
fun reduceSyncDeparture(state: SyncDepartureState, event: SyncDepartureEvent): SyncDepartureState = when (event) {
    is SyncDepartureEvent.ScopeChanged -> if (event.scope == state.scope) {
        state
    } else {
        state.copy(
            scope = event.scope,
            pending = null,
            presentedIn = null
        )
    }
    is SyncDepartureEvent.MembershipStarted -> if (event.scope != state.scope) state else state.copy(pending = null, presentedIn = null)
    is SyncDepartureEvent.Recorded -> if (event.scope != state.scope) {
        state
    } else {
        val revision = state.revision + 1
        state.copy(
            revision = revision,
            pending = SyncDeparture(
                revision,
                event.name?.takeUnless {
                    it.isBlank()
                },
                event.cause
            ),
            presentedIn = null
        )
    }
    is SyncDepartureEvent.Presented -> if (event.scope != state.scope ||
        event.id != state.pending?.id ||
        event.chooserSession < (state.presentedIn ?: Long.MIN_VALUE)
    ) {
        state
    } else {
        state.copy(presentedIn = event.chooserSession)
    }
    is SyncDepartureEvent.Dismissed -> if (event.scope != state.scope ||
        event.id != state.pending?.id ||
        event.chooserSession != state.presentedIn
    ) {
        state
    } else {
        state.copy(pending = null, presentedIn = null)
    }
}
