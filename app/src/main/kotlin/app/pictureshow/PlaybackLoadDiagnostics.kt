/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException

/** Player-thread analytics and synchronized loader-thread traces; summaries continue when a loader never calls back.
 * Only numeric metadata and an allowlist of exception classes cross the logging boundary.
 */
internal class PlaybackLoadDiagnostics(
    private val write: (String) -> Unit = PlaybackDiagnostics::write,
    private val trace: (String) -> Unit = {},
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
    private val traceLock = Any()
    private var traceWindow = clock()
    private var traceEmitted = 0
    private var traceDropped = 0L
    private var reportedDropped = 0L
    private val sourceQueue = ReferenceQueue<DataSource>()
    private val sources = mutableMapOf<SourceIdentity, SourceTrace>()
    private var sourceOrdinal = 0L
    private var collectedActiveTransfers = 0L

    /** Weak identity keys retain neither old segment sources nor any request metadata. */
    private class SourceIdentity(source: DataSource, queue: ReferenceQueue<DataSource>? = null) : WeakReference<DataSource>(source, queue) {
        private val identity = System.identityHashCode(source)
        override fun hashCode() = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is SourceIdentity && get() != null && get() === other.get())
    }
    private data class SourceTrace(val id: Long, var attempt: Long = 0, var transfer: TransferTrace? = null)
    private data class TransferTrace(
        val position: Long,
        val length: Long,
        val network: Boolean,
        val began: Long,
        val initialized: Boolean,
        var started: Boolean = false,
        var active: Boolean = true,
        var bytes: Long = 0,
        var firstByte: Long = -1,
        var lastByte: Long = -1
    )

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
        traceLoad(if (retryCount > 0) "retry" else "start", eventTime, loadEventInfo, load)
        if (retryCount > 0) {
            retries++
            event("retry", loadEventInfo, load, "none", false)
        }
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val load = take(loadEventInfo, mediaLoadData)
        account(loadEventInfo, load)
        completed++
        traceLoad("complete", eventTime, loadEventInfo, load)
        if (loadEventInfo.loadDurationMs >= 5_000) event("slow", loadEventInfo, load, "none", false)
        loads.remove(loadEventInfo.loadTaskId)
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val load = take(loadEventInfo, mediaLoadData)
        account(loadEventInfo, load)
        cancellations++
        traceLoad("cancel", eventTime, loadEventInfo, load, cancelled = true)
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
        traceLoad("error", eventTime, loadEventInfo, load, errorClass(error), wasCanceled, httpStatus(error))
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
        synchronized(traceLock) {
            cleanSources()
            val active = sources.values.filter { it.transfer?.active == true }.sortedBy { it.id }
            val loadIds = loads.entries.sortedBy { it.key }.take(20).joinToString(",") { "${it.key}:${it.value.retry + 1}" }
            val transfers = active.take(20).joinToString(",") { source ->
                val transfer = requireNotNull(source.transfer)
                "${source.id}:${source.attempt}:${transfer.bytes}:${age(now, transfer.lastByte)}:${age(now, transfer.began)}"
            }
            // Summary bypasses the event cap so suppressed evidence is always accounted for.
            trace(
                "summary callbackMs=$now loadTaskIdsAttempts=[$loadIds] loadsOmitted=${(loads.size - 20).coerceAtLeast(0)} " +
                    "activeTransfers=${active.size} transferSourceAttemptBytesIdleAge=[$transfers] " +
                    "transfersOmitted=${(active.size - 20).coerceAtLeast(0)} association=unknown " +
                    "collectedActiveTransfers=$collectedActiveTransfers " +
                    "droppedTraceCount=$traceDropped droppedSinceSummary=${traceDropped - reportedDropped} final=$final"
            )
            reportedDropped = traceDropped
        }
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
    ) = synchronized(traceLock) {
        val now = clock()
        val sourceState = sourceTrace(source)
        val replacedActive = sourceState.transfer?.active == true
        sourceState.attempt++
        sourceState.transfer = TransferTrace(dataSpec.position, dataSpec.length, isNetwork, now, initialized = true)
        traceTransfer("initializing", sourceState, now, "replacedActive=$replacedActive")
    }
    override fun onTransferStart(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = synchronized(traceLock) {
        val now = clock()
        val sourceState = sourceTrace(source)
        val transfer = currentTransfer(sourceState, dataSpec, isNetwork, now, starting = true)
        transfer.started = true
        traceTransfer("start", sourceState, now)
    }
    override fun onTransferEnd(
        source: androidx.media3.datasource.DataSource,
        dataSpec: androidx.media3.datasource.DataSpec,
        isNetwork: Boolean
    ) = synchronized(traceLock) {
        val now = clock()
        val sourceState = sourceTrace(source)
        val transfer = currentTransfer(sourceState, dataSpec, isNetwork, now)
        traceTransfer("end", sourceState, now)
        transfer.active = false
    }
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
        synchronized(traceLock) {
            val now = clock()
            val sourceState = sourceTrace(source)
            val transfer = currentTransfer(sourceState, dataSpec, isNetwork, now)
            if (bytesTransferred > 0) {
                transfer.bytes += bytesTransferred
                transfer.lastByte = now
                if (transfer.firstByte < 0) {
                    transfer.firstByte = now
                    traceTransfer("first-byte", sourceState, now)
                }
            }
        }
    }

    private fun sourceTrace(source: DataSource): SourceTrace {
        cleanSources()
        val key = SourceIdentity(source, sourceQueue)
        return sources.getOrPut(key) { SourceTrace(++sourceOrdinal) }
    }

    private fun cleanSources() {
        while (true) {
            val collected = sources.remove((sourceQueue.poll() as? SourceIdentity) ?: return)
            if (collected?.transfer?.active == true) collectedActiveTransfers++
        }
    }

    private fun currentTransfer(
        source: SourceTrace,
        spec: DataSpec,
        network: Boolean,
        now: Long,
        starting: Boolean = false
    ): TransferTrace {
        val previous = source.transfer
        if (previous != null &&
            previous.active &&
            (!starting || !previous.started) &&
            previous.position == spec.position &&
            previous.length == spec.length &&
            previous.network == network
        ) {
            return previous
        }
        source.attempt++
        return TransferTrace(spec.position, spec.length, network, now, initialized = false).also { source.transfer = it }
    }

    private fun traceLoad(
        kind: String,
        event: AnalyticsListener.EventTime,
        info: LoadEventInfo,
        load: Load,
        error: String = "none",
        cancelled: Boolean = false,
        status: Int = -1
    ) = synchronized(traceLock) {
        val now = clock()
        boundedTrace(
            "load event=$kind loadTaskId=${info.loadTaskId} attempt=${load.retry + 1} retryCount=${load.retry} " +
                "dataType=${load.dataType} trackType=${load.trackType} eventMs=${event.realtimeMs} " +
                "loadElapsedMs=${info.elapsedRealtimeMs} callbackMs=$now " +
                "position=${info.dataSpec.position} length=${info.dataSpec.length} " +
                "bytes=${info.bytesLoaded} durationMs=${info.loadDurationMs} errorClass=$error httpStatus=$status cancelled=$cancelled",
            now
        )
    }

    private fun traceTransfer(kind: String, source: SourceTrace, now: Long, extra: String = "") {
        val transfer = requireNotNull(source.transfer)
        boundedTrace(
            "transfer event=$kind sourceId=${source.id} openAttempt=${source.attempt} loadTaskId=unknown " +
                "callbackMs=$now position=${transfer.position} length=${transfer.length} network=${transfer.network} " +
                "initialized=${transfer.initialized} started=${transfer.started} bytes=${transfer.bytes} " +
                "firstByteMs=${transfer.firstByte} lastByteMs=${transfer.lastByte} idleMs=${age(now, transfer.lastByte)} " +
                "ageMs=${age(now, transfer.began)} $extra".trimEnd(),
            now
        )
    }

    private fun boundedTrace(line: String, now: Long) {
        if (now - traceWindow >= 10_000) {
            traceWindow = now
            traceEmitted = 0
        }
        if (traceEmitted >= 50) {
            traceDropped++
        } else {
            traceEmitted++
            trace(line)
        }
    }

    private fun age(now: Long, at: Long): Long = if (at < 0) -1 else (now - at).coerceAtLeast(0)

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
