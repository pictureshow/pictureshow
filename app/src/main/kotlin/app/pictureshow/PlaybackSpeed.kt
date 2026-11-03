/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import app.pictureshow.core.playback.PlaybackSpeeds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PlaybackSpeedState(val speed: Float = 1f, val encodedAudio: Boolean = false, val message: String? = null)

/** Owned by a viewing's output, so a decoder replacement cannot reset its speed. */
internal class ViewingPlaybackSpeed {
    private val mutable = MutableStateFlow(PlaybackSpeedState())
    val state = mutable.asStateFlow()

    fun select(speed: Float, player: Player?) {
        if (!PlaybackSpeeds.allowed(speed, mutable.value.encodedAudio)) return
        mutable.value = mutable.value.copy(speed = speed, message = if (mutable.value.encodedAudio) PlaybackSpeeds.ENCODED_AUDIO else null)
        apply(player)
    }

    fun audioOutput(encoded: Boolean, player: Player?) {
        mutable.value =
            if (encoded) {
                PlaybackSpeedState(
                    1f,
                    true,
                    PlaybackSpeeds.ENCODED_AUDIO
                )
            } else {
                mutable.value.copy(encodedAudio = false, message = null)
            }
        apply(player)
    }

    fun apply(player: Player?) {
        if (player == null) return
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            mutable.value = mutable.value.copy(speed = 1f, message = "This player cannot change playback speed.")
            return
        }
        player.playbackParameters = PlaybackParameters(mutable.value.speed, 1f)
    }

    fun observed(parameters: PlaybackParameters) {
        if (parameters.speed != mutable.value.speed) {
            mutable.value =
                mutable.value.copy(
                    speed = parameters.speed,
                    message = "The current audio output adjusted playback speed to ${PlaybackSpeeds.label(parameters.speed)}."
                )
        }
    }
}
