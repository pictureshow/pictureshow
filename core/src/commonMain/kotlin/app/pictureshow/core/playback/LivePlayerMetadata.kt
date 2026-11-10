/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.canTune

/** A channel response cannot label another channel's playback, or keep a program after its half-open airing interval. */
fun livePlayerProgram(channel: LiveChannel?, nowMillis: Long): LiveProgram? = channel?.currentProgram?.takeIf {
    (it.channelId.isBlank() || it.channelId == channel.id) && canTune(it, nowMillis)
}
