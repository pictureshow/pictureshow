/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.SystemClock
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** Player-thread callbacks, with one summary per ten seconds even when a loader never calls back.
 * Only numeric metadata and an allowlist of exception classes cross the logging boundary.
 */
internal class PlaybackLoadDiagnostics(
    private val write: (String) -> Unit = PlaybackDiagnostics::write,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : AnalyticsListener,
    androidx.media3.datasource.TransferListener {
    private data class Load(val dataType: Int, val trackType: Int, val began: Long, var retry: Int, var bytes: Long = 0)
    private val loads = mutableMapOf<Long, Load>()
    private val wireBytes = java.util.concurrent.atomic.AtomicLong()
    private val lastByte = java.util.concurrent.atomic.AtomicLong(-1)
    private var window = clock()
    private var completed = 0
    private var errors = 0
    private var retries = 0
    private var cancellations = 0
    private var bytes = 0L
    private var emitted = 0
    private var suppressed = 0

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int
    ) {
        val load = loads.getOrPut(loadEventInfo.loadTaskId) {
            Load(mediaLoadData.dataType, mediaLoadData.trackType, clock(), retryCount)
        }
        load.retry = retryCount
        if (retryCount > 0) {
            retries++
            event("retry", loadEventInfo, load, "none", false)
        }
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val load = take(loadEventInfo, mediaLoadData)
        account(loadEventInfo, load)
        completed++
        if (loadEventInfo.loadDurationMs >= 5_000) event("slow", loadEventInfo, load, "none", false)
        loads.remove(loadEventInfo.loadTaskId)
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val load = take(loadEventInfo, mediaLoadData)
        account(loadEventInfo, load)
        cancellations++
        event("cancel", loadEventInfo, load, "none", true)
        loads.remove(loadEventInfo.loadTaskId)
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        val load = take(loadEventInfo, mediaLoadData)
        account(loadEventInfo, load)
        errors++
        event("error", loadEventInfo, load, errorClass(error), wasCanceled, httpStatus(error))
        if (wasCanceled) loads.remove(loadEventInfo.loadTaskId)
    }

    /** Called by the owner's bounded timer, including while playback is buffering. */
    fun summary(final: Boolean = false) {
        val now = clock()
        val duration = (now - window).coerceAtLeast(0)
        if (!final && duration < 10_000) return
        val oldest = loads.values.minByOrNull { it.began }
        write(
            "load-summary dataType=${oldest?.dataType ?: -1} trackType=${oldest?.trackType ?: -1} " +
                "bytes=$bytes durationMs=$duration throughputKbps=${throughput(bytes, duration)} " +
                "completed=$completed errors=$errors retries=$retries cancellations=$cancellations " +
                "wireBytes=${wireBytes.getAndSet(0)} idleMs=${lastByte.get().let { if (it < 0) -1 else (now - it).coerceAtLeast(0) }} " +
                "inFlight=${loads.size} oldestMs=${oldest?.let { (now - it.began).coerceAtLeast(0) } ?: 0} " +
                "suppressed=$suppressed final=$final"
        )
        window = now
        completed = 0
        errors = 0
        retries = 0
        cancellations = 0
        bytes = 0
        emitted = 0
        suppressed = 0
    }

    override fun onTransferInitializing(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = Unit
    override fun onTransferStart(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = Unit
    override fun onTransferEnd(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = Unit
    override fun onBytesTransferred(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int
    ) {
        if (isNetwork) {
            wireBytes.addAndGet(bytesTransferred.toLong())
            lastByte.set(clock())
        }
    }

    private fun take(info: LoadEventInfo, data: MediaLoadData): Load = loads.getOrPut(info.loadTaskId) {
        Load(data.dataType, data.trackType, clock() - info.loadDurationMs, 0)
    }

    private fun account(info: LoadEventInfo, load: Load) {
        bytes += (info.bytesLoaded - load.bytes).coerceAtLeast(0)
        load.bytes = info.bytesLoaded
    }

    private fun event(kind: String, info: LoadEventInfo, load: Load, error: String, cancelled: Boolean, httpStatus: Int = -1) {
        // A failing origin may return errors immediately; keep those storms out of logd too.
        if (emitted >= 10) {
            suppressed++
            return
        }
        emitted++
        write(
            "load event=$kind dataType=${load.dataType} trackType=${load.trackType} " +
                "bytes=${info.bytesLoaded} durationMs=${info.loadDurationMs} " +
                "throughputKbps=${throughput(info.bytesLoaded, info.loadDurationMs)} " +
                "errorClass=$error httpStatus=$httpStatus retryCount=${load.retry} cancelled=$cancelled"
        )
    }

    private fun httpStatus(error: IOException): Int = generateSequence<Throwable>(error) { it.cause }.take(8)
        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode ?: -1

    private fun errorClass(error: IOException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is SocketTimeoutException } -> "SocketTimeoutException"
            causes.any { it is InterruptedIOException } -> "InterruptedIOException"
            causes.any { it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException } -> "InvalidResponseCodeException"
            causes.any { it is java.net.ConnectException } -> "ConnectException"
            causes.any { it is java.net.SocketException } -> "SocketException"
            causes.any { it is java.io.EOFException } -> "EOFException"
            else -> "IOException"
        }
    }

    private fun throughput(bytes: Long, duration: Long): Long = if (duration > 0) bytes * 8 / duration else 0
}
