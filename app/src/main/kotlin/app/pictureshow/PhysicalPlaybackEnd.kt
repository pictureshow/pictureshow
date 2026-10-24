/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * A container's duration can outlive its samples (notably damaged DVD subtitle durations). Media3
 * waits for that duration even after the A/V outputs drain. A complete progressive HTTP body plus
 * final, drained A/V renderers is also a natural end. Text timestamps cannot keep a film alive.
 * Unknown-length streams retain Media3's usual end handling; a short known-length read is an error.
 */
internal class PhysicalPlaybackEnd(
    private val mediaUri: Uri,
    private val canEnd: () -> Boolean = { true },
    private val diagnostic: (String) -> Unit = {},
    private val videoTail: VideoTail = VideoTail(),
    private val videoSnapshots: () -> List<VideoEndSnapshot> = { emptyList() },
    private val isPlaying: () -> Boolean = { false },
    private val onVideoStalled: () -> Unit = {},
    private val canEndVideoStalled: () -> Boolean = { true },
    private val onEnd: () -> Unit
) {
    @Volatile var bodyComplete = false
        private set
    private val generation = AtomicLong()
    private val signalled = AtomicLong(-1)

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun sourceFactory(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val source = factory.createDataSource()
        object : DataSource by source {
            private var tracked = false
            private var expected = C.LENGTH_UNSET.toLong()
            private var received = 0L
            override fun open(dataSpec: DataSpec): Long {
                tracked = dataSpec.uri == mediaUri
                received = 0
                if (tracked) {
                    bodyComplete = false
                    invalidate()
                }
                return source.open(dataSpec).also {
                    expected = it
                    if (tracked) diagnostic("event=open offset=${dataSpec.position} expectedBytes=$expected")
                }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
                source.read(buffer, offset, length).also { read ->
                    if (tracked) {
                        if (read == C.RESULT_END_OF_INPUT && expected >= 0 && received < expected) {
                            throw EOFException("Incomplete media response")
                        }
                        if (read > 0) received += read
                        if (expected > 0 && received == expected && !bodyComplete) {
                            bodyComplete = true
                            diagnostic("event=body-complete receivedBytes=$received")
                        }
                        if (read == C.RESULT_END_OF_INPUT && expected < 0) diagnostic("event=unknown-length-eof receivedBytes=$received")
                    }
                }
            } catch (error: IOException) {
                if (tracked) {
                    bodyComplete = false
                    invalidate()
                }
                throw error
            }
            override fun close() {
                if (tracked) diagnostic("event=close receivedBytes=$received expectedBytes=$expected complete=$bodyComplete")
                source.close()
            }
        }
    }

    fun wrap(renderers: Array<Renderer>, handler: Handler): Array<Renderer> {
        var lastStatus: String? = null
        var lastProgress = emptyList<VideoEndSnapshot>()
        var stalledSincePositionUs = C.TIME_UNSET
        var wasStalledCandidate = false
        var observedGeneration = generation.get()
        fun afterRender() {
            val currentGeneration = generation.get()
            if (observedGeneration != currentGeneration) {
                observedGeneration = currentGeneration
                stalledSincePositionUs = C.TIME_UNSET
                lastProgress = emptyList()
                wasStalledCandidate = false
            }
            val enabled = renderers.filter { it.state != Renderer.STATE_DISABLED }
            val audio = enabled.filter { it.trackType == C.TRACK_TYPE_AUDIO }
            val video = enabled.filter { it.trackType == C.TRACK_TYPE_VIDEO }
            if (bodyComplete) {
                val status = renderers.filter {
                    it.trackType == C.TRACK_TYPE_AUDIO || it.trackType == C.TRACK_TYPE_VIDEO
                }.joinToString(",") { "${it.trackType}:${it.state}:${it.isCurrentStreamFinal}:${it.isEnded}" }
                if (status != lastStatus) {
                    lastStatus = status
                    diagnostic("event=renderers complete=true av=$status")
                }
            }
            if (!bodyComplete) return
            val token = generation.get()
            if (signalled.get() == token) return
            val audioDrained = audio.isNotEmpty() && audio.all { it.isCurrentStreamFinal && it.isEnded }
            val allDrained = enabled.filter { it.trackType == C.TRACK_TYPE_AUDIO || it.trackType == C.TRACK_TYPE_VIDEO }
                .let { it.isNotEmpty() && it.all { output -> output.isCurrentStreamFinal && output.isEnded } }
            // Every sample has been extracted. Only obsolete video remains, and the codec has
            // accepted/produced nothing while the active media clock advances past that tail.
            // A known timeline end also protects the display duration of a final still frame.
            // The existing selected-A/V drain path does not consult that timeline. Any input, output, decoder restart or seek starts a fresh window.
            val snapshots = if (audioDrained && !allDrained) videoSnapshots() else emptyList()
            val progress = snapshots.map { it.progress() }
            val stalledCandidate = audioDrained &&
                video.isNotEmpty() &&
                video.all { it.state == Renderer.STATE_STARTED } &&
                isPlaying() &&
                videoTail.extracted &&
                videoTail.lastSampleUs != C.TIME_UNSET &&
                videoTail.durationUs > 0 &&
                snapshots.size == video.size &&
                snapshots.all {
                    it.lastRenderedUs != C.TIME_UNSET && it.positionUs > videoTail.lastSampleUs && it.lastQueuedUs <= it.positionUs
                }
            val videoPositionUs = snapshots.minOfOrNull { it.positionUs } ?: C.TIME_UNSET
            if (!stalledCandidate || !wasStalledCandidate || progress != lastProgress || videoPositionUs < stalledSincePositionUs) {
                stalledSincePositionUs = videoPositionUs
            }
            lastProgress = progress
            wasStalledCandidate = stalledCandidate
            val stalled =
                stalledCandidate &&
                    videoPositionUs - maxOf(stalledSincePositionUs, videoTail.lastSampleUs, videoTail.durationUs) >= 3_000_000
            if (!allDrained && !stalled) return
            if (signalled.getAndSet(token) != token) {
                handler.post {
                    if (generation.get() == token && bodyComplete) {
                        if (canEnd() &&
                            (!stalled || (canEndVideoStalled() && isPlaying() && videoSnapshots().map { it.progress() } == progress))
                        ) {
                            if (stalled && !allDrained) onVideoStalled() else onEnd()
                        } else {
                            // The app thread may still mask a completed seek as BUFFERING. A
                            // rejected delivery has not ended playback; retry once it can accept.
                            signalled.compareAndSet(token, -1)
                        }
                    }
                }
            }
        }
        return renderers.map { renderer ->
            if (renderer.trackType != C.TRACK_TYPE_AUDIO && renderer.trackType != C.TRACK_TYPE_VIDEO) {
                renderer
            } else {
                object : ForwardingRenderer(renderer) {
                    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                        super.render(positionUs, elapsedRealtimeUs)
                        afterRender()
                    }
                    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
                        invalidate()
                        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
                    }
                    override fun replaceStream(
                        formats: Array<Format>,
                        stream: SampleStream,
                        startPositionUs: Long,
                        offsetUs: Long,
                        mediaPeriodId: MediaPeriodId
                    ) {
                        invalidate()
                        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
                    }
                    override fun disable() {
                        invalidate()
                        super.disable()
                    }
                }
            }
        }.toTypedArray()
    }
}
