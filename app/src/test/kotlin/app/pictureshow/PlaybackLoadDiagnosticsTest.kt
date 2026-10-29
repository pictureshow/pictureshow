/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackLoadDiagnosticsTest {
    private val time = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
    private val spec = DataSpec(Uri.parse("https://private.invalid/private-item.ts?api_key=secret"))
    private val media = MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, null, 0, null, 0, 3_000)
    private fun info(id: Long, duration: Long = 0, bytes: Long = 0) = LoadEventInfo.Builder(id, spec, 0)
        .setLoadDurationMs(duration).setBytesLoaded(bytes).build()

    @Test
    fun formatPinsErrorsRetriesSlowLoadsCancellationAndPrivateFieldsStayOut() {
        val lines = mutableListOf<String>()
        var now = 0L
        val log = PlaybackLoadDiagnostics(lines::add) { now }
        log.onLoadStarted(time, info(1), media, 0)
        log.onLoadError(time, info(1, 8_000, 188), media, IOException("private URL", SocketTimeoutException("secret")), false)
        log.onLoadStarted(time, info(1, 8_000, 188), media, 1)
        log.onLoadCompleted(time, info(1, 8_500, 4_000), media)
        log.onLoadStarted(time, info(2), media, 0)
        log.onLoadCanceled(time, info(2, 500, 100), media)
        now = 10_000
        log.summary()
        assertEquals(
            listOf(
                "load event=error dataType=1 trackType=2 bytes=188 durationMs=8000 throughputKbps=0 errorClass=SocketTimeoutException retryCount=0 cancelled=false",
                "load event=retry dataType=1 trackType=2 bytes=188 durationMs=8000 throughputKbps=0 errorClass=none retryCount=1 cancelled=false",
                "load event=slow dataType=1 trackType=2 bytes=4000 durationMs=8500 throughputKbps=3 errorClass=none retryCount=1 cancelled=false",
                "load event=cancel dataType=1 trackType=2 bytes=100 durationMs=500 throughputKbps=1 errorClass=none retryCount=0 cancelled=true",
                "load-summary dataType=-1 trackType=-1 bytes=4100 durationMs=10000 throughputKbps=3 completed=1 errors=1 retries=1 cancellations=1 wireBytes=0 idleMs=-1 inFlight=0 oldestMs=0 suppressed=0 final=false"
            ),
            lines
        )
        assertTrue(lines.none { it.contains("private") || it.contains("secret") })
    }

    @Test
    fun ordinarySegmentsAreSilentAndStuckLoadsHavePeriodicEvidenceWithABoundedErrorStorm() {
        val lines = mutableListOf<String>()
        var now = 0L
        val log = PlaybackLoadDiagnostics(lines::add) { now }
        repeat(100) { n ->
            log.onLoadStarted(time, info(n.toLong()), media, 0)
            log.onLoadCompleted(time, info(n.toLong(), 50, 1_000), media)
        }
        assertTrue(lines.isEmpty())
        log.onLoadStarted(time, info(100), media, 0)
        now = 9_999
        log.summary()
        assertTrue(lines.isEmpty())
        now = 10_000
        log.summary()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("inFlight=1 oldestMs=10000"))
        repeat(100) { log.onLoadError(time, info(100, 11_000), media, IOException("secret"), false) }
        assertEquals(11, lines.size)
        now = 20_000
        log.summary()
        assertTrue(lines.last().contains("errors=100"))
        assertTrue(lines.last().contains("suppressed=90"))
        log.onLoadCanceled(time, info(100, 21_000), media)
        assertTrue(lines.last().startsWith("load event=cancel"))
    }
}
