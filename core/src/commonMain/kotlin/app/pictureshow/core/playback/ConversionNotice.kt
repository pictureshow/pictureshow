/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/**
 * PLAY-27: a brief notice when the server must convert the *video* because this device cannot play it.
 *
 * It fires only for a device limit that forces video encoding. Viewer quality caps, audio-only
 * conversion, remux (container or codec-tag changes) and direct play never produce a notice;
 * Playback info (PLAY-26) explains those and carries the full remedy.
 *
 * [title] and [reason] are short on purpose: the notice is on screen for 10 s. Each platform
 * appends its own one-line path to Playback info (for example "Press Down, then choose Playback info.").
 */
data class ConversionNotice(val title: String, val reason: String) {
    companion object {
        /** The default device noun; each platform passes its own ("this TV", "this phone", "this browser"). */
        const val DEVICE = "this device"

        /** Ordered by how useful fixing it is: the first category is what lets the video play directly. */
        private val categories: List<Pair<Set<String>, String>> = listOf(
            setOf("videocodecnotsupported") to "Its video format won't play on %s.",
            setOf("videorangetypenotsupported") to "Its HDR format won't play on %s.",
            setOf("videoprofilenotsupported", "videolevelnotsupported", "videobitdepthnotsupported", "refframesnotsupported") to
                "Its video encoding is too demanding for %s.",
            setOf("videoresolutionnotsupported") to "Its resolution is too high for %s.",
            setOf("videoframeratenotsupported") to "Its frame rate is too high for %s.",
            setOf("interlacedvideonotsupported") to "Its interlaced video won't play on %s.",
            setOf("anamorphicvideonotsupported") to "Its non-square pixels won't display on %s.",
            setOf("videorotationnotsupported") to "Its rotation won't display on %s."
        )
        private const val SUBTITLE = "The selected subtitles must be burned in for %s."

        fun title(device: String = DEVICE): String = "Converting this video for $device"

        /**
         * [subtitleIndex] is the selected subtitle stream (-1 for Off). Reason codes compare case-insensitively.
         * Returns null when no notice applies.
         */
        fun of(decision: PlayDecision, subtitleIndex: Int, device: String = DEVICE): ConversionNotice? {
            if (decision.method != PlayMethod.Transcode) return null
            val reasons = decision.transcodeReasons.map { it.lowercase() }.toSet()
            val burnedIn = "subtitlecodecnotsupported" in reasons &&
                PlaybackInformation.subtitle(decision.mediaSource, subtitleIndex) == "Burned in"
            // A copied video stream is a remux, so its video reasons did not force encoding. Burning subtitles
            // into the picture always re-encodes it, so a burn-in report wins over a `VideoCodec=copy` URL.
            val copied = PlaybackInformation.method(decision) != "Transcode" || PlaybackInformation.videoCopied(decision)
            val why = when {
                copied -> SUBTITLE.takeIf { burnedIn }
                else -> categories.firstOrNull { (codes, _) -> codes.any(reasons::contains) }?.second
                    ?: SUBTITLE.takeIf { burnedIn }
            } ?: return null
            return ConversionNotice(title(device), why.replace("%s", device))
        }
    }
}
