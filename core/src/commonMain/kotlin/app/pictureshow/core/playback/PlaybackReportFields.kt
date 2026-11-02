/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Fixed report metadata. Stop has no track fields, but retains the viewing's independent origins. */
object PlaybackReportFields {
    fun describe(kind: String, positionTicks: Long, audio: Int?, subtitle: Int?, audioViewer: Boolean, subtitleViewer: Boolean): String {
        require(kind in listOf("start", "progress", "stop"))
        val reportedAudio = audio.takeUnless { kind == "stop" }
        val reportedSubtitle = subtitle.takeUnless { kind == "stop" }
        return "kind=$kind positionMs=${positionTicks / Ticks.PER_MILLISECOND} " +
            "audio=${reportedAudio ?: "omitted"} subtitle=${reportedSubtitle ?: "omitted"} " +
            "audioOrigin=${if (audioViewer) "viewer" else "automatic"} " +
            "subtitleOrigin=${if (subtitleViewer) "viewer" else "automatic"}"
    }
}
