/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import com.google.common.util.concurrent.ListenableFuture

/** Retiring a decoder is buffering within a viewing, with the same timeline and play/pause intent. */
internal class ReopeningPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private var reopening: State? = null

    override fun getState(): State = reopening ?: super.getState()

    fun beginReopen() {
        if (reopening != null) return
        val state = super.getState()
        if (state.playlist.isEmpty() || state.playerError != null) return
        val position = state.contentPositionMsSupplier.get()
        reopening = state.buildUpon()
            .clearPositionDiscontinuity()
            .setNewlyRenderedFirstFrame(false)
            .setCurrentCues(CueGroup.EMPTY_TIME_ZERO)
            .setAvailableCommands(state.availableCommands.buildUpon().remove(Player.COMMAND_PREPARE).build())
            .setPlaybackState(Player.STATE_BUFFERING)
            .setContentPositionMs(position)
            .setContentBufferedPositionMs(PositionSupplier { position })
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        reopening = reopening?.buildUpon()
            ?.setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)?.build()
        return super.handleSetPlayWhenReady(playWhenReady)
    }
}
