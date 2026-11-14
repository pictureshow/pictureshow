/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.pictureshow.core.playback.ObservedLiveWindow
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A live window's offset changes with manifest refreshes; its duration is the observed buffer. */
internal fun Player.observedLiveWindow(): ObservedLiveWindow? {
    if (currentTimeline.isEmpty) return null
    val window = currentTimeline.getWindow(currentMediaItemIndex, Timeline.Window())
    val duration = window.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
    val offset = window.windowStartTimeMs.takeIf { it != C.TIME_UNSET }
        ?: window.positionInFirstPeriodMs
    return ObservedLiveWindow(offset, duration, window.defaultPositionMs, window.isSeekable)
}

/** Every controller, media key and screen uses the same measured-range guards. */
internal class LivePlaybackPlayer(private val underlying: Player) : ForwardingSimpleBasePlayer(underlying) {
    private var pausedAt: Long? = null
    var bufferExpired: Boolean = false
        private set
    val window: ObservedLiveWindow? get() = underlying.observedLiveWindow()
    val canTimeShift: Boolean get() = window?.canTimeShift == true

    fun observe() {
        if (window?.pausedPositionExpired(pausedAt) == true) bufferExpired = true
        if (bufferExpired && underlying.playWhenReady) underlying.pause()
    }

    fun expired() {
        bufferExpired = true
        underlying.pause()
    }

    fun goLive() {
        val actual = window ?: return
        if (actual.goLivePosition() == null) return
        bufferExpired = false
        pausedAt = null
        underlying.seekToDefaultPosition()
        if (underlying.playerError != null || underlying.playbackState == Player.STATE_IDLE) underlying.prepare()
        underlying.play()
    }

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
        if (window?.goLivePosition() == null) commands.remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        if (!canTimeShift) {
            commands.removeAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD
            )
        }
        return state.buildUpon().setAvailableCommands(commands.build()).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        observe()
        if (playWhenReady && bufferExpired) {
            goLive()
            return Futures.immediateVoidFuture()
        }
        if (!playWhenReady) {
            val actual = window
            if (actual?.canTimeShift != true) return Futures.immediateVoidFuture()
            pausedAt = actual.offsetMs + underlying.currentPosition
        } else {
            pausedAt = null
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != underlying.currentMediaItemIndex) return Futures.immediateVoidFuture()
        if (positionMs == C.TIME_UNSET) {
            goLive()
            return Futures.immediateVoidFuture()
        }
        if (bufferExpired || window?.contains(positionMs) != true) return Futures.immediateVoidFuture()
        pausedAt = if (!underlying.playWhenReady) requireNotNull(window).offsetMs + positionMs else null
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
