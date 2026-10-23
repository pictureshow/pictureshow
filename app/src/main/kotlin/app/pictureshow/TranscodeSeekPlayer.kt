/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A fresh progressive encode starts at local zero; controllers and reports keep the original media timeline. */
internal class TranscodeSeekPlayer(
    player: Player,
    private val startMs: Long,
    private val durationMs: Long,
    private val onSeek: (Long) -> Boolean
) : ForwardingSimpleBasePlayer(player) {
    private var pendingTarget: Long? = null

    override fun getState(): State {
        val state = super.getState()
        val pending = pendingTarget
        val builder = state.buildUpon()
            .setAvailableCommands(
                state.availableCommands.buildUpon().addAll(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION
                ).build()
            )
            .setPlaylist(
                state.playlist.map {
                    it.buildUpon().setDurationUs(durationMs * 1_000).setPeriods(emptyList())
                        .setPositionInFirstPeriodUs(0).setDefaultPositionUs(0)
                        .setIsSeekable(true).setIsDynamic(false).setLiveConfiguration(null).build()
                }
            )
            .setContentPositionMs(
                PositionSupplier {
                    pending ?: (startMs + state.contentPositionMsSupplier.get()).coerceAtMost(durationMs)
                }
            )
            .setContentBufferedPositionMs(
                PositionSupplier { pending ?: (startMs + state.contentBufferedPositionMsSupplier.get()).coerceAtMost(durationMs) }
            )
        if (pending != null && state.playerError == null) {
            builder.setPlaybackState(Player.STATE_BUFFERING).setTotalBufferedDurationMs(PositionSupplier.ZERO)
        }
        if (state.hasPositionDiscontinuity) {
            builder.setPositionDiscontinuity(state.positionDiscontinuityReason, startMs + state.discontinuityPositionMs)
        }
        return builder.build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != 0) return Futures.immediateVoidFuture()
        val target = (if (positionMs == C.TIME_UNSET) 0 else positionMs).coerceIn(0, durationMs)
        if (target == currentPosition) return Futures.immediateVoidFuture()
        // Stopping the old source can synchronously invalidate forwarding state. Let that happen
        // before publishing the target; SimpleBasePlayer publishes the seek and its discontinuity once.
        if (playerError == null && onSeek(target)) pendingTarget = target
        return Futures.immediateVoidFuture()
    }
}
