/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream

/** Diagnostics for every route, including HLS. Elapsed time here never decides completion. */
internal class VideoEndDiagnostics(private val pending: () -> Unit) {
    private var audioEndedAtUs = C.TIME_UNSET
    private var logged = false

    private fun resetObservation() {
        audioEndedAtUs = C.TIME_UNSET
        logged = false
    }

    fun wrap(renderers: Array<Renderer>): Array<Renderer> = renderers.map { renderer ->
        if (renderer.trackType != C.TRACK_TYPE_VIDEO) {
            renderer
        } else {
            object : ForwardingRenderer(renderer) {
                override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                    super.render(positionUs, elapsedRealtimeUs)
                    val audio = renderers.filter { it.trackType == C.TRACK_TYPE_AUDIO && it.state != Renderer.STATE_DISABLED }
                    if (state != Renderer.STATE_STARTED ||
                        isEnded ||
                        audio.isEmpty() ||
                        audio.any { !it.isCurrentStreamFinal || !it.isEnded }
                    ) {
                        resetObservation()
                        return
                    }
                    if (audioEndedAtUs == C.TIME_UNSET) audioEndedAtUs = elapsedRealtimeUs
                    if (!logged && elapsedRealtimeUs - audioEndedAtUs >= 3_000_000) {
                        logged = true
                        pending()
                    }
                }
                override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
                    resetObservation()
                    super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
                }
                override fun replaceStream(
                    formats: Array<Format>,
                    stream: SampleStream,
                    startPositionUs: Long,
                    offsetUs: Long,
                    mediaPeriodId: MediaPeriodId
                ) {
                    resetObservation()
                    super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
                }
                override fun disable() {
                    resetObservation()
                    super.disable()
                }
            }
        }
    }.toTypedArray()
}
