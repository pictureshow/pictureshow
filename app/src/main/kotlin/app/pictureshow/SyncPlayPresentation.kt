/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.sync.SyncAccessNotice
import app.pictureshow.core.sync.syncAccessPresentation

internal enum class SyncLocalPlayback { UNAVAILABLE, PREPARING, BUFFERING, READY }
internal enum class SyncNoticeKind {
    ACCESS_FAILED,
    GROUPS_FAILED,
    REJOIN,
    PREPARING,
    LIBRARY_DENIED,
    CONNECTION,
    SOURCE_UNAVAILABLE,
    CHECK_ACCESS,
    ACCESS_DENIED,
    REQUEST_FAILED,
    SNAPSHOT,
    LEFT,
    DEPARTURE,
    LEAVE_UNCONFIRMED,
    SPEED,
    CLOCK,
    QUEUE_PENDING,
    GROUP_UNAVAILABLE,
    PLAYBACK_FAILED
}
internal enum class SyncNoticeTone { INFO, WARNING, ERROR }
internal enum class SyncNoticeAction { REJOIN, LEAVE, REFRESH }
internal data class SyncNotice(val kind: SyncNoticeKind, val tone: SyncNoticeTone, val action: SyncNoticeAction? = null)

/** Names and membership counts are facts supplied by the signed-in server, never inferred locally. */
internal data class SyncPlayGroupRow(val id: String, val name: String, val members: Int)

internal data class SyncPlayQueueRow(
    val id: String,
    val title: String?,
    val current: Boolean,
    val itemId: String = id,
    val titleUnavailable: Boolean = false
)

internal data class SyncPlayPresentation(
    val server: String = "",
    val viewer: String = "",
    val phase: String = "idle",
    /** Retained diagnostic compatibility; viewer copy uses [notice] and the typed [departure]. */
    val status: String = "",
    val notice: SyncNotice? = null,
    val quality: app.pictureshow.core.sync.SyncQuality = app.pictureshow.core.sync.SyncQuality.UNKNOWN,
    val localPlayback: SyncLocalPlayback = SyncLocalPlayback.UNAVAILABLE,
    val waitReason: String? = null,
    val policy: String = "None",
    val policyKnown: Boolean = false,
    val policyFailed: Boolean = false,
    val groupId: String? = null,
    val groupName: String? = null,
    val groups: List<SyncPlayGroupRow> = emptyList(),
    val participants: List<String> = emptyList(),
    val queue: List<SyncPlayQueueRow> = emptyList(),
    val ignoreWait: Boolean = false,
    val repeat: String = "RepeatNone",
    val shuffle: String = "Sorted",
    val busy: Boolean = false,
    val departure: app.pictureshow.core.sync.SyncDeparture? = null
) {
    val access get() = syncAccessPresentation(policy, policyKnown, busy, policyFailed)
    val accessAllowed: Boolean get() = access.canJoin
    val canRefresh: Boolean get() = access.canRefresh

    /** Legacy diagnostic accessor used by protocol regression checks, never rendered by the panel. */
    val accessNotice: String? get() = when (access.notice) {
        SyncAccessNotice.CHECKING -> "Checking watch-together access…"
        SyncAccessNotice.UNKNOWN -> "Refresh to check watch-together access."
        SyncAccessNotice.UNAVAILABLE -> "Watch-together access could not be confirmed. Refresh to try again."
        SyncAccessNotice.DENIED ->
            "This viewer is not allowed to join watch-together groups. Ask the server administrator to enable SyncPlay access."
        SyncAccessNotice.ALLOWED -> null
    }
}

/** One banner owns recovery; a retained departure is shown only in a non-recovery chooser. */
internal fun effectiveSyncNotice(state: SyncPlayPresentation): SyncNotice? = when {
    state.notice?.tone != null && (state.notice.tone != SyncNoticeTone.INFO || state.notice.action != null) -> state.notice
    state.phase == "Reconnecting" -> SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.ERROR,
        if (state.groupId == null) SyncNoticeAction.LEAVE else SyncNoticeAction.REJOIN)
    state.phase == "Error" -> SyncNotice(SyncNoticeKind.PLAYBACK_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
    state.groupId != null && state.quality == app.pictureshow.core.sync.SyncQuality.OUT_OF_SYNC ->
        SyncNotice(SyncNoticeKind.REJOIN, SyncNoticeTone.WARNING, SyncNoticeAction.REJOIN)
    state.groupId == null && state.departure != null -> SyncNotice(SyncNoticeKind.DEPARTURE, SyncNoticeTone.INFO)
    else -> state.notice
}
