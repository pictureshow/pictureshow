/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/** Intercepts intent before the decoder changes; scheduled server output uses the explicit bypass. */
internal class SyncTransportPlayer(private val output: Player, private val request: (String, Long?) -> Boolean) : ForwardingPlayer(output) {
    override fun play() {
        if (!request("play", null)) output.play()
    }
    override fun pause() {
        if (!request("pause", null)) output.pause()
    }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!request(if (playWhenReady) "play" else "pause", null)) output.playWhenReady = playWhenReady
    }
    override fun seekTo(positionMs: Long) {
        if (!request("seek", positionMs)) output.seekTo(positionMs)
    }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (!request("seek", positionMs)) output.seekTo(mediaItemIndex, positionMs)
    }
    override fun seekToDefaultPosition() {
        if (!request("seek", 0L)) output.seekToDefaultPosition()
    }
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (!request("seek", 0L)) output.seekToDefaultPosition(mediaItemIndex)
    }
    override fun seekBack() {
        if (!request("seek", (currentPosition - seekBackIncrement).coerceAtLeast(0))) output.seekBack()
    }
    override fun seekForward() {
        if (!request("seek", currentPosition + seekForwardIncrement)) output.seekForward()
    }
    override fun seekToNext() {
        if (!request("next", null)) output.seekToNext()
    }
    override fun seekToNextMediaItem() {
        if (!request("next", null)) output.seekToNextMediaItem()
    }
    override fun seekToPrevious() {
        if (!request("previous", null)) output.seekToPrevious()
    }
    override fun seekToPreviousMediaItem() {
        if (!request("previous", null)) output.seekToPreviousMediaItem()
    }
    override fun stop() {
        if (!request("stop", null)) output.stop()
    }
    override fun setPlaybackSpeed(speed: Float) {
        if (!request("speed", null)) output.setPlaybackSpeed(speed)
    }
    override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) {
        if (!request("speed", null)) output.playbackParameters = playbackParameters
    }
}
