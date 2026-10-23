/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.Format
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream

/** One playback's offset; positive values display captions later than their authored time. */
internal class SubtitleDelay {
    @Volatile
    var milliseconds: Int = 0
        private set

    fun set(milliseconds: Int): Boolean {
        val next = milliseconds.coerceIn(-10_000, 10_000) / 100 * 100
        if (next == this.milliseconds) return false
        this.milliseconds = next
        return true
    }

    /** Full-file external captions keep authored timestamps when video restarts at local zero. */
    @Volatile
    var sourceStartMs: Long = 0

    fun rendererPositionUs(positionUs: Long): Long = positionUs + sourceStartMs * 1_000 - milliseconds.toLong() * 1_000
}

/** Shifts the real text renderer's media clock, covering extraction-time and legacy subtitle decoding. */
internal class SubtitleDelayRenderer(renderer: Renderer, private val delay: SubtitleDelay) : ForwardingRenderer(renderer) {
    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        super.enable(
            configuration, formats, stream, delay.rendererPositionUs(positionUs), joining,
            mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId
        )
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(delay.rendererPositionUs(positionUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        super.resetPosition(delay.rendererPositionUs(positionUs), sampleStreamIsResetToKeyFrame)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(delay.rendererPositionUs(positionUs), elapsedRealtimeUs)

    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean = false
}
