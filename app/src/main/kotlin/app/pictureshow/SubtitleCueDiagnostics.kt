/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import java.util.WeakHashMap

/** Main-thread view receipts, with provenance captured by the renderer before posting its output.
 * CueGroup uses object identity in Media3 1.11.1; weak keys retain no cue history after the view drops it.
 * Never derive a receipt's stream from the currently requested selection.
 */
internal class SubtitleCueDiagnostics(
    private val decision: PlayDecision,
    private val write: (String) -> Unit = PlaybackDiagnostics::write
) {
    @Volatile private var formats: Map<Format, Int> = emptyMap()
    private val provenance = WeakHashMap<CueGroup, Int?>()
    private val reported = mutableSetOf<Int?>()
    private var lastDisplayed: CueGroup? = null
    private var selection = 0L

    fun tracks(streams: List<MediaStream>, groups: List<Tracks.Group>) {
        formats = streams.filter { it.type == MediaStreamType.Subtitle }.mapNotNull { stream ->
            PlaybackTracks.find(streams, stream, groups)?.let { (group, track) -> group.getTrackFormat(track) to stream.index }
        }.toMap()
    }

    fun stream(format: Format?): Int? = format?.id?.substringAfterLast("server-subtitle:", "")?.toIntOrNull()
        ?: formats[format]

    fun selected(index: Int, paused: Boolean, positionMs: Long) {
        selection++
        reported.clear()
        write(
            PlaybackDiagnostics.line(
                decision,
                "subtitle-select",
                "stream=${if (index < 0) "off" else index} " +
                    "paused=$paused positionMs=$positionMs selection=$selection"
            )
        )
    }

    fun delivered(group: CueGroup, stream: Int?) {
        provenance[group] = stream
    }

    /** Call only after applying this exact group to SubtitleView, not merely from Player.onCues. */
    fun displayed(group: CueGroup, positionMs: Long) {
        if (group === lastDisplayed) return
        lastDisplayed = group
        if (group.cues.isEmpty()) return
        val stream = provenance[group]
        if (!reported.add(stream)) return
        write(
            PlaybackDiagnostics.line(
                decision,
                "subtitle-cues",
                "stream=${stream ?: "unknown"} " +
                    "firstCueAtMs=$positionMs selection=$selection"
            )
        )
    }

    fun loaded(stream: Int, attempts: Int) {
        write(PlaybackDiagnostics.line(decision, "subtitle-loaded", "stream=$stream attempts=$attempts"))
    }
}

/** The receipt follows the actual view update, including the frame-adjusted cues used by Compose. */
internal fun showSubtitleCues(
    view: androidx.media3.ui.SubtitleView,
    group: CueGroup,
    cues: List<androidx.media3.common.text.Cue>,
    positionMs: Long,
    diagnostics: SubtitleCueDiagnostics?
) {
    view.setCues(cues)
    diagnostics?.displayed(group, positionMs)
}
