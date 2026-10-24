/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Snapshot is published by the playback thread, never by the UI's extrapolated position. */
internal data class VideoEndSnapshot(
    val decoder: String = "unknown",
    val queued: Int = 0,
    val rendered: Int = 0,
    val skipped: Int = 0,
    val dropped: Int = 0,
    val lastQueuedUs: Long = C.TIME_UNSET,
    val lastRenderedUs: Long = C.TIME_UNSET,
    val positionUs: Long = C.TIME_UNSET,
    val initialized: Int = 0,
    val skippedInput: Int = 0,
    val droppedToKeyframe: Int = 0,
    val inputMisses: Long = 0,
    val outputMisses: Long = 0,
    val progressVersion: Long = 0,
    val droppedInput: Int = 0,
    val final: Boolean = false,
    val ended: Boolean = false
) {
    fun progress(): VideoEndSnapshot = copy(positionUs = C.TIME_UNSET, inputMisses = 0, outputMisses = 0)

    fun diagnostic(reason: String): String = "reason=$reason decoder=$decoder queued=$queued rendered=$rendered " +
        "skipped=$skipped dropped=$dropped lastQueuedUs=$lastQueuedUs lastRenderedUs=$lastRenderedUs " +
        "positionUs=$positionUs initialized=$initialized skippedInput=$skippedInput droppedToKeyframe=$droppedToKeyframe " +
        "inputMisses=$inputMisses outputMisses=$outputMisses droppedInput=$droppedInput final=$final ended=$ended"
}

internal class EndVideoRenderer(builder: MediaCodecVideoRenderer.Builder, private val probe: VideoCodecProbe) :
    MediaCodecVideoRenderer(builder) {
    @Volatile var endSnapshot = VideoEndSnapshot()
        private set
    private var lastQueuedUs = C.TIME_UNSET
    private var lastRenderedUs = C.TIME_UNSET

    private var progressVersion = 0L

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        lastQueuedUs = buffer.timeUs - streamOffsetUs
        progressVersion++
        publish()
        super.onQueueInputBuffer(buffer)
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        lastRenderedUs = presentationTimeUs
        progressVersion++
        publish()
        super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
    }

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        progressVersion++
        publish()
        super.onProcessedOutputBuffer(presentationTimeUs)
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        lastQueuedUs = C.TIME_UNSET
        lastRenderedUs = C.TIME_UNSET
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs, elapsedRealtimeUs)
        publish(positionUs - outputStreamOffsetUs)
    }

    private fun publish(positionUs: Long = endSnapshot.positionUs) {
        decoderCounters.ensureUpdated()
        endSnapshot = VideoEndSnapshot(
            codecInfo?.name ?: "unknown",
            decoderCounters.queuedInputBufferCount,
            decoderCounters.renderedOutputBufferCount,
            decoderCounters.skippedOutputBufferCount,
            decoderCounters.droppedBufferCount,
            lastQueuedUs,
            lastRenderedUs,
            positionUs,
            decoderCounters.decoderInitCount,
            decoderCounters.skippedInputBufferCount,
            decoderCounters.droppedToKeyframeCount,
            probe.inputMisses,
            probe.outputMisses,
            progressVersion,
            decoderCounters.droppedInputBufferCount,
            isCurrentStreamFinal,
            isEnded
        )
    }
}
