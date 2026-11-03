/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/**
 * PLAY-27: a brief notice when the server must convert the *video* because this device cannot play it.
 *
 * It fires only for a device limit that forces video encoding. Viewer quality caps, audio-only
 * conversion, remux (container or codec-tag changes) and direct play never produce a notice;
 * Playback info (PLAY-26) explains those.
 */
data class ConversionNotice(val message: String) {
    val title: String get() = TITLE

    companion object {
        const val TITLE = "The server is converting this video"
        const val DETAILS = "Playback info in the player controls has the details."

        /** Ordered by how useful the remedy is: fixing the first category is what lets the video play directly. */
        private val categories: List<Pair<Set<String>, String>> = listOf(
            setOf("videocodecnotsupported") to
                "This device can't decode the video's format, so the server is converting it.",
            setOf("videorangetypenotsupported") to
                "This device can't show the video's HDR format, so the server is converting it.",
            setOf("videoprofilenotsupported", "videolevelnotsupported", "videobitdepthnotsupported", "refframesnotsupported") to
                "The video's encoding goes beyond what this device's decoder supports, so the server is converting it.",
            setOf("videoresolutionnotsupported") to
                "The video's resolution is higher than this device supports, so the server is reducing it.",
            setOf("videoframeratenotsupported") to
                "The video's frame rate is higher than this device supports, so the server is reducing it.",
            setOf("interlacedvideonotsupported") to
                "This device can't show interlaced video, so the server is deinterlacing it.",
            setOf("anamorphicvideonotsupported") to
                "This device can't show the video's non-square pixels, so the server is converting it.",
            setOf("videorotationnotsupported") to
                "This device can't rotate the video, so the server is converting it."
        )

        private const val SUBTITLE_WHY = "This device can't show the selected subtitles, so the server is burning them into the video."
        private const val SUBTITLE_FIX = "Choose a text subtitle track or turn subtitles off to watch without conversion."
        private const val HDR_FIX = "Play an SDR version if there is one, or watch on a device that supports this HDR format."
        private const val VIDEO_FIX = "Play another version of this title if there is one, or watch on a device that supports this format."

        /**
         * [subtitleIndex] is the selected subtitle stream (-1 for Off). Reason codes compare case-insensitively.
         * Returns null when no notice applies.
         */
        fun of(decision: PlayDecision, subtitleIndex: Int): ConversionNotice? {
            // Remux, including Jellyfin's all-copy transcode URL, does not re-encode video.
            if (PlaybackInformation.method(decision) != "Transcode") return null
            if (PlaybackInformation.videoCopied(decision)) return null
            val reasons = decision.transcodeReasons.map { it.lowercase() }.toSet()
            categories.firstOrNull { (codes, _) -> codes.any(reasons::contains) }?.let { (codes, why) ->
                val fix = if ("videorangetypenotsupported" in codes) HDR_FIX else VIDEO_FIX
                return ConversionNotice("$why $fix")
            }
            val burnedIn = PlaybackInformation.subtitle(decision.mediaSource, subtitleIndex) == "Burned in"
            if ("subtitlecodecnotsupported" in reasons && burnedIn) return ConversionNotice("$SUBTITLE_WHY $SUBTITLE_FIX")
            return null
        }
    }
}
