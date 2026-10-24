/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** An unprepared replacement remains controllable BUFFERING while the old decoder retires. */
internal class ReleaseGatedPlayer(private val output: Player) : ForwardingSimpleBasePlayer(output) {
    private var held = false

    override fun getState(): State {
        val state = super.getState()
        if (!held || state.playlist.isEmpty() || state.playerError != null) return state
        return state.buildUpon()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setAvailableCommands(state.availableCommands.buildUpon().remove(Player.COMMAND_PREPARE).build())
            .build()
    }

    fun hold() {
        held = true
        invalidateState()
    }

    fun prepareAfterRelease() {
        // prepare masks BUFFERING synchronously. Drop our mask afterwards, without an IDLE gap.
        output.prepare()
        held = false
        invalidateState()
    }

    override fun handlePrepare(): ListenableFuture<*> = if (held) Futures.immediateVoidFuture() else super.handlePrepare()
}
