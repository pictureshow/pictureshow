/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

/** Decode/output synchronously on the playback thread, then post the group and its origin together.
 * Capture the immutable Format before queuing; embedded metadata may arrive after enable.
 * Reading renderer state in a delayed UI callback would relabel old queued cues after a switch.
 */
internal class SubtitleCueRenderer private constructor(private val bridge: Bridge) : ForwardingRenderer(bridge.renderer) {
    constructor(output: TextOutput, handler: Handler, diagnostics: SubtitleCueDiagnostics) : this(Bridge(output, handler, diagnostics))

    internal val cueStreamIndex: Int? get() = bridge.diagnostics.stream(bridge.stream)

    private class Bridge(output: TextOutput, handler: Handler, val diagnostics: SubtitleCueDiagnostics) {
        var stream: Format? = null
        var incomingStream: Format? = null
        val renderer = TextRenderer({ group ->
            val origin = stream
            handler.post {
                diagnostics.delivered(group, diagnostics.stream(origin))
                @Suppress("DEPRECATION")
                output.onCues(group.cues)
                output.onCues(group)
            }
        }, null)
    }

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        bridge.incomingStream = formats.firstOrNull()
        bridge.stream = bridge.incomingStream
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
        bridge.stream = bridge.incomingStream
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        bridge.incomingStream = formats.firstOrNull()
        // Legacy TextRenderer drains its previous decoder after onStreamChanged. Until a reset
        // flushes/replaces it, neither the old nor incoming origin is safe to claim.
        bridge.stream = if (formats.firstOrNull()?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) bridge.incomingStream else null
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }
}
