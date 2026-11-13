/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.Handler
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.pictureshow.core.playback.ActiveReadBandwidth

/** Factory used only by primary progressive media; subtitles and HLS retain their own sources. */
internal fun progressiveBandwidthSources(
    upstream: DataSource.Factory,
    handler: Handler,
    isCurrent: () -> Boolean,
    sample: (Long, Long) -> Unit,
    clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos
): DataSource.Factory = DataSource.Factory {
    ActiveReadBandwidthDataSource(upstream.createDataSource(), clockNanos) { bitrate, bytes ->
        handler.post {
            if (isCurrent()) sample(bitrate, bytes)
        }
    }
}

/** Timing surrounds the actual read call, never the gap until the loader asks for another read. */
internal class ActiveReadBandwidthDataSource(
    private val source: DataSource,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val sample: (Long, Long) -> Unit
) : DataSource by source {
    private val meter = ActiveReadBandwidth()
    private var network = false
    private var fullSpeed = false

    init {
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                network = isNetwork
                fullSpeed = !dataSpec.isFlagSet(DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                network = false
            }
        })
    }

    override fun open(dataSpec: DataSpec): Long {
        meter.reset()
        network = false
        fullSpeed = false
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val began = clockNanos()
        val count = source.read(buffer, offset, length)
        val elapsed = (clockNanos() - began).coerceAtLeast(0)
        meter.record(count, elapsed, network, fullSpeed)?.let { sample(it.bitrate, it.bytes) }
        return count
    }

    override fun close() {
        try {
            source.close()
        } finally {
            meter.reset()
            network = false
            fullSpeed = false
        }
    }
}
