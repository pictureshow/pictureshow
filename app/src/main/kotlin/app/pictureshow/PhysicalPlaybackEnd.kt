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
        fun afterRender() {
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
            var hasOutput = false
            for (renderer in renderers) {
                if (renderer.state == Renderer.STATE_DISABLED ||
                    (renderer.trackType != C.TRACK_TYPE_AUDIO && renderer.trackType != C.TRACK_TYPE_VIDEO)
                ) {
                    continue
                }
                hasOutput = true
                if (!renderer.isCurrentStreamFinal || !renderer.isEnded) return
            }
            if (hasOutput && signalled.getAndSet(token) != token) {
                handler.post {
                    if (generation.get() == token && bodyComplete) {
                        if (canEnd()) {
                            onEnd()
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
