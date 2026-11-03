/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ClientResult
import app.pictureshow.core.control.CommandEvent
import app.pictureshow.core.control.CommandState
import app.pictureshow.core.control.ControlIntent
import app.pictureshow.core.control.OwnerEvent
import app.pictureshow.core.control.OwnerState
import app.pictureshow.core.control.ViewingIdentity
import app.pictureshow.core.control.reduceCommand
import app.pictureshow.core.control.reduceOwner
import app.pictureshow.core.playback.PlaybackHttpOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo

/**
 * A viewing survives decoder/encode replacement. Calls run on the existing player thread.
 * PlaybackSession retains preparation, seeks, tracks, reporting order and scoped final cleanup.
 * Legacy inputs retain that path; future controllers submit serialized, identity-bound intents.
 */
internal class LocalVideoOwnerAdapter(
    val identity: ViewingIdentity,
    gateway: PlaybackGateway,
    val playbackOutput: PlaybackOutput,
    itemId: String
) {
    var ownership = OwnerState(identity, intent = ControlIntent(entry = itemId, queue = listOf(itemId)))
        private set
    var commands = CommandState(identity)
        private set
    private var reportSequence = 0L
    private var retired = false

    val playbackGateway: PlaybackGateway = object : PlaybackGateway by gateway {
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> =
            if (claimReport()) gateway.start(info) else ClientResult.Success(Unit)
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> =
            if (claimReport()) gateway.progress(info) else ClientResult.Success(Unit)
        override suspend fun start(info: PlaybackProgressInfo, observe: (PlaybackHttpOutcome) -> Unit): ClientResult<Unit> =
            if (claimReport()) gateway.start(info, observe) else ClientResult.Success(Unit)
        override suspend fun progress(info: PlaybackProgressInfo, observe: (PlaybackHttpOutcome) -> Unit): ClientResult<Unit> =
            if (claimReport()) gateway.progress(info, observe) else ClientResult.Success(Unit)
    }

    /** Final Stop/encoding cleanup remains owned by the retiring viewing and is never suppressed. */
    fun reportAllowed(source: ViewingIdentity, sequence: Long): Boolean {
        if (retired) return false
        ownership = reduceOwner(ownership, OwnerEvent("report", identity = source, sequence = sequence))
        return ownership.effect == "report"
    }

    private fun claimReport(): Boolean = reportAllowed(identity, reportSequence++)

    /** A coordinator can commit a handoff without altering the retiring viewing's cleanup scope. */
    fun ownerEvent(event: OwnerEvent) {
        if (!retired) ownership = reduceOwner(ownership, event)
    }

    /** Returns one desired effect; the caller applies it to this viewing, never blindly retries it. */
    fun submit(event: CommandEvent): CommandState {
        if (retired || ownership.owner != identity) return commands.copy(effect = "none", rejection = "retired-owner")
        commands = reduceCommand(commands, event)
        return commands
    }

    /** Only after final network cleanup, never on a decoder replacement. */
    fun cleanupDone() {
        retired = true
    }
}
