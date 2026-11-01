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

/** An absent optional file must not keep natural end waiting after all enabled A/V has drained.
 * Continue polling the real text renderer throughout playback so late cues need no reselection.
 * Embedded text retains the renderer's original end semantics.
 */
internal class OptionalSubtitleRenderer(renderer: Renderer, private val avEnded: () -> Boolean) : ForwardingRenderer(renderer) {
    private var external = false

    private fun update(formats: Array<out Format>) {
        external = formats.isNotEmpty() && formats.all { it.id?.contains("server-subtitle:") == true }
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
        update(formats)
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        update(formats)
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun isEnded(): Boolean = super.isEnded() || (external && avEnded())
}
