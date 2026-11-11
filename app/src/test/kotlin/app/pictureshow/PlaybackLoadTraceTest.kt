/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackLoadTraceTest {
    private val time = AnalyticsListener.EventTime(111, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
    private val spec = DataSpec.Builder().setUri("https://private.invalid/private-name?api_key=secret")
        .setHttpRequestHeaders(mapOf("Private-Header" to "secret")).setPosition(128).setLength(4096).build()
    private val media = MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, null, 0, null, 0, 3_000)
    private fun info(id: Long, duration: Long = 0, bytes: Long = 0) = LoadEventInfo.Builder(id, spec, 222)
        .setLoadDurationMs(duration).setBytesLoaded(bytes).build()

    /** Equal values must still receive separate source-instance identities. No source text may be logged. */
    private class Source : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec) = C.LENGTH_UNSET.toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri = Uri.parse("https://private.invalid/secret")
        override fun close() = Unit
        override fun equals(other: Any?) = other is Source
        override fun hashCode() = 0
        override fun toString(): String = error("Source metadata must not cross the trace boundary")
    }

    @Test fun initialAndFastCompletedLoadsCarryAllThreeClocksAndNumericRangesWithoutChangingLegacySilence() {
        val old = mutableListOf<String>()
        val trace = mutableListOf<String>()
        var now = 333L
        val log = PlaybackLoadDiagnostics(old::add, trace::add) { now }
        log.onLoadStarted(time, info(42), media, 0)
        now = 444
        log.onLoadCompleted(time, info(42, 50, 1000), media)
        assertTrue(old.isEmpty(), "Ordinary segments must remain silent in the existing write sink")
        assertEquals(2, trace.size)
        assertEquals(
            "load event=start loadTaskId=42 attempt=1 retryCount=0 dataType=1 trackType=2 eventMs=111 " +
                "loadElapsedMs=222 callbackMs=333 position=128 length=4096 bytes=0 durationMs=0 errorClass=none httpStatus=-1 cancelled=false",
            trace.first()
        )
        assertTrue(trace.last().contains("event=complete loadTaskId=42 attempt=1 retryCount=0"))
        assertTrue(trace.last().contains("callbackMs=444 position=128 length=4096 bytes=1000 durationMs=50"))
        log.summary(final = true)
        assertTrue(trace.last().contains("loadTaskIdsAttempts=[]"))
    }

    @Test fun retriesErrorsAndCancellationRetainTheLoadTaskIdentityAndOnlyAllowlistedErrorMetadata() {
        val trace = mutableListOf<String>()
        val log = PlaybackLoadDiagnostics(write = {}, trace = trace::add) { 0 }
        val refusal = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
            401,
            "private response",
            null,
            mapOf("Private-Header" to listOf("secret")),
            spec,
            "secret".toByteArray()
        )
        log.onLoadStarted(time, info(7), media, 0)
        log.onLoadError(time, info(7, 250, 12), media, IOException("private URI secret", refusal), false)
        log.onLoadStarted(time, info(7, 250, 12), media, 1)
        log.onLoadCanceled(time, info(7, 300, 20), media)
        assertEquals(listOf("start", "error", "retry", "cancel"), trace.map { it.substringAfter("event=").substringBefore(' ') })
        assertTrue(trace.all { it.contains("loadTaskId=7") })
        assertTrue(trace[1].contains("errorClass=InvalidResponseCodeException httpStatus=401 cancelled=false"))
        assertTrue(trace[2].contains("attempt=2 retryCount=1"))
        assertTrue(trace[3].contains("attempt=2 retryCount=1"))
        assertTrue(trace[3].endsWith("cancelled=true"))
        log.summary(final = true)
        assertTrue(trace.last().contains("loadTaskIdsAttempts=[]"))
        assertTrue(trace.none { it.contains("private", true) || it.contains("secret", true) || it.contains("https:") })
    }

    @Test fun firstByteIsEmittedOnceAndAnOutstandingTransferReportsItsBytesAndAgeWithoutClaimingALoadAssociation() {
        val trace = mutableListOf<String>()
        var now = 0L
        val log = PlaybackLoadDiagnostics(write = {}, trace = trace::add) { now }
        val source = Source()
        log.onLoadStarted(time, info(42), media, 0)
        log.onTransferInitializing(source, spec, true)
        now = 10
        log.onTransferStart(source, spec, true)
        now = 20
        log.onBytesTransferred(source, spec, true, 4)
        now = 70
        log.onBytesTransferred(source, spec, true, 8)
        now = 100
        log.summary(final = true)
        val transfers = trace.filter { it.startsWith("transfer ") }
        assertEquals(listOf("initializing", "start", "first-byte"), transfers.map { it.substringAfter("event=").substringBefore(' ') })
        assertTrue(transfers.all { it.contains("sourceId=1 openAttempt=1 loadTaskId=unknown") })
        assertTrue(transfers.last().contains("callbackMs=20 position=128 length=4096"))
        assertTrue(transfers.last().contains("bytes=4 firstByteMs=20 lastByteMs=20 idleMs=0 ageMs=20"))
        assertTrue(trace.last().contains("loadTaskIdsAttempts=[42:1]"))
        assertTrue(trace.last().contains("activeTransfers=1 transferSourceAttemptBytesIdleAge=[1:1:12:30:100]"))
        assertTrue(trace.last().contains("association=unknown collectedActiveTransfers=0 droppedTraceCount=0"))
        now = 110
        log.onTransferEnd(source, spec, true)
        assertTrue(trace.last().contains("bytes=12 firstByteMs=20 lastByteMs=70 idleMs=40 ageMs=110"))
        log.summary(final = true)
        assertTrue(trace.last().contains("activeTransfers=0 transferSourceAttemptBytesIdleAge=[]"))
    }

    @Test fun reopenedSourcesIncrementAttemptsAndEqualDistinctSourcesKeepIndependentIdentities() {
        val trace = mutableListOf<String>()
        val log = PlaybackLoadDiagnostics(write = {}, trace = trace::add) { 0 }
        val a = Source()
        val b = Source()
        assertEquals(a, b)
        log.onTransferInitializing(a, spec, false)
        log.onTransferStart(a, spec, false)
        log.onBytesTransferred(a, spec, false, 5)
        log.onTransferEnd(a, spec, false)
        log.onTransferInitializing(a, spec, false)
        log.onTransferStart(a, spec, false)
        log.onBytesTransferred(a, spec, false, 3)
        log.onTransferInitializing(b, spec, true)
        assertTrue(trace.any { it.contains("event=first-byte sourceId=1 openAttempt=2") && it.contains("bytes=3") })
        assertTrue(trace.last().contains("sourceId=2 openAttempt=1"))
        log.summary(final = true)
        assertTrue(trace.last().contains("activeTransfers=2"))
        assertTrue(trace.last().contains("[1:2:3:0:0,2:1:0:-1:0]"))
        assertTrue(trace.none { it.contains("private", true) || it.contains("secret", true) })
    }

    @Test fun loaderThreadsKeepIndependentTransferBytesWhileAnalyticsCallbacksArrive() {
        val trace = mutableListOf<String>()
        val log = PlaybackLoadDiagnostics(write = {}, trace = trace::add) { 0 }
        val sources = List(4) { Source() }
        val go = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = sources.map { source ->
            Thread {
                try {
                    go.await()
                    log.onTransferInitializing(source, spec, true)
                    log.onTransferStart(source, spec, true)
                    repeat(100) { log.onBytesTransferred(source, spec, true, 1) }
                } catch (failure: Throwable) {
                    failures += failure
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        go.countDown()
        repeat(20) { log.onLoadStarted(time, info(it.toLong()), media, 0) }
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive }, "Transfer callbacks must not deadlock analytics callbacks")
        assertTrue(failures.isEmpty(), "Transfer callback failures: ${failures.map { it.javaClass.simpleName }}")
        log.summary(final = true)
        assertTrue(trace.last().contains("activeTransfers=4"))
        assertEquals(4, Regex("[1-4]:1:100:0:0").findAll(trace.last()).count())
        assertEquals(4, trace.count { it.startsWith("transfer event=first-byte") })
        java.lang.ref.Reference.reachabilityFence(sources)
    }

    @Test fun traceStormsAreCappedButSummaryReportsEveryDroppedEventAndTheNextWindowCanReportAgain() {
        val trace = mutableListOf<String>()
        var now = 0L
        val log = PlaybackLoadDiagnostics(write = {}, trace = trace::add) { now }
        repeat(60) { log.onLoadStarted(time, info(it.toLong()), media, 0) }
        assertEquals(50, trace.size)
        now = 10_000
        log.summary()
        assertEquals(51, trace.size)
        assertTrue(trace.last().contains("loadsOmitted=40"))
        assertTrue(trace.last().contains("droppedTraceCount=10 droppedSinceSummary=10"))
        log.onLoadStarted(time, info(100), media, 0)
        assertTrue(trace.last().contains("event=start loadTaskId=100"))
        now = 20_000
        log.summary()
        assertTrue(trace.last().contains("droppedTraceCount=10 droppedSinceSummary=0"))
        assertFalse(trace.any { it.contains("private", true) || it.contains("secret", true) })
    }
}
