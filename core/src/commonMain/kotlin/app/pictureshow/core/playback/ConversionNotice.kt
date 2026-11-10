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
 * [title], [reason] and [fix] are short on purpose: the notice is on screen for 10 s. [reason] says why, [fix] is the one
 * true thing the viewer (or whoever keeps the server's files) can do about it. Each platform shows them as one line and
 * appends its own path to Playback info (for example "Press Down, then choose Playback info.").
 */
data class ConversionNotice(val title: String, val reason: String, val fix: String) {
    companion object {
        /** The default device noun; each platform passes its own ("this TV", "this phone", "this browser"). */
        const val DEVICE = "this device"

        /**
         * Ordered by how useful fixing it is: the first category is what lets the video play directly. Nothing on the
         * device fixes a video limit, so its fix says playback goes on and names a copy that would play as it is: every
         * platform this app runs on decodes 8-bit SDR H.264 at 1080p.
         */
        private val categories: List<Triple<Set<String>, String, String>> = listOf(
            Triple(
                setOf("videocodecnotsupported"),
                "This video format cannot play on %s.",
                copy("a 1080p H.264 copy")
            ),
            Triple(
                setOf("videorangetypenotsupported"),
                "This HDR format cannot play on %s.",
                copy("an SDR copy")
            ),
            Triple(
                setOf("videoprofilenotsupported", "videolevelnotsupported", "videobitdepthnotsupported", "refframesnotsupported"),
                "The video encoding is too demanding for %s.",
                copy("an 8-bit 1080p H.264 copy")
            ),
            Triple(
                setOf("videoresolutionnotsupported"),
                "The resolution is too high for %s.",
                copy("a 1080p copy")
            ),
            Triple(
                setOf("videoframeratenotsupported"),
                "The frame rate is too high for %s.",
                copy("a 30 fps copy")
            ),
            Triple(
                setOf("interlacedvideonotsupported"),
                "Interlaced video cannot play on %s.",
                copy("a deinterlaced copy")
            ),
            Triple(
                setOf("anamorphicvideonotsupported"),
                "The non-square pixels cannot display correctly on %s.",
                copy("a square-pixel copy")
            ),
            Triple(
                setOf("videorotationnotsupported"),
                "The rotation cannot display correctly on %s.",
                copy("an upright copy")
            )
        )
        private const val SUBTITLE = "The selected subtitles must be burned in for %s."

        /** Text subtitles are sent alongside the picture, so choosing them, or none, stops the burn-in. */
        const val SUBTITLE_FIX = "Choose text subtitles, such as SRT, or turn them off to avoid it."

        private fun copy(what: String) = "${what.replaceFirstChar { it.uppercase() }} on the server would play without conversion."

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
            val subtitle = (SUBTITLE to SUBTITLE_FIX).takeIf { burnedIn }
            val (why, fix) = when {
                copied -> subtitle
                else -> categories.firstOrNull { (codes) -> codes.any(reasons::contains) }?.let { it.second to it.third } ?: subtitle
            } ?: return null
            return ConversionNotice(title(device), why.replace("%s", device), fix)
        }
    }
}
