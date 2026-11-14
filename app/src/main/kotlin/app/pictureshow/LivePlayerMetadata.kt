/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveTvService
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.playback.livePlayerProgram
import java.time.Instant

/** Some servers omit CurrentProgram on the single-channel response; the authorized guide is the fallback. */
internal suspend fun loadPlayerLiveChannel(service: LiveTvService, account: Account, channelId: String, nowMillis: Long): LiveChannel? {
    val channel = ((service.channel(account, channelId) as? ClientResult.Success)?.value)?.takeIf { it.id == channelId } ?: return null
    if (livePlayerProgram(channel, nowMillis) != null) return channel
    val programs = (
        service.programs(
            account,
            listOf(channelId),
            Instant.ofEpochMilli(nowMillis).toString(),
            Instant.ofEpochMilli(nowMillis + 60_000).toString()
        ) as? ClientResult.Success
        )?.value?.items.orEmpty()
    return channel.copy(currentProgram = programs.firstOrNull { it.channelId == channelId && canTune(it, nowMillis) })
}
