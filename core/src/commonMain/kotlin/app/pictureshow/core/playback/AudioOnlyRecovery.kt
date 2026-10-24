/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import io.ktor.http.URLBuilder
import io.ktor.http.Url

/** AllowVideoStreamCopy permits copy, but Jellyfin may still choose an encoder for compatible video. */
object AudioOnlyRecovery {
    fun hasDirectVideo(decision: PlayDecision): Boolean = decision.method != PlayMethod.Transcode ||
        Url(decision.url).parameters.entries().any { (name, values) ->
            name.equals("VideoCodec", true) && values.singleOrNull().equals("copy", true)
        }

    fun preserveVideo(decision: PlayDecision, subtitleIndex: Int?): PlayOutcome {
        if (decision.method != PlayMethod.Transcode) return PlayOutcome.Play(decision)
        val selected = subtitleIndex ?: decision.mediaSource.defaultSubtitleStreamIndex ?: -1
        val burnsSubtitles = selected >= 0 &&
            (
                decision.mediaSource.mediaStreams.any {
                    it.type == MediaStreamType.Subtitle &&
                        it.index == selected &&
                        it.deliveryMethod == SubtitleDeliveryMethod.Encode
                } ||
                    Url(decision.url).parameters.entries().any { (name, values) ->
                        name.equals("SubtitleMethod", true) &&
                            values.any { it.equals("Encode", true) }
                    }
                )
        if (burnsSubtitles) {
            // Audio recovery cannot silently drop captions or turn a working video into an encode.
            return PlayOutcome.Unplayable(
                UnplayableReason.NO_COMPATIBLE_SOURCE,
                "Audio recovery cannot keep these subtitles. Choose text subtitles or turn them off."
            )
        }
        val url = URLBuilder(decision.url).apply {
            parameters.names().filter { it.equals("VideoCodec", true) || it.equals("AllowVideoStreamCopy", true) }
                .forEach(parameters::remove)
            parameters.append("VideoCodec", "copy")
            parameters.append("AllowVideoStreamCopy", "true")
        }.buildString()
        return PlayOutcome.Play(
            PlayDecision(
                decision.itemId,
                decision.mediaSource,
                decision.method,
                url,
                decision.headers,
                decision.playSessionId,
                decision.transcodeReasons
            )
        )
    }
}
