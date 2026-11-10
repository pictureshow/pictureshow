/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class CastLiveLoadTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    private fun item(live: Boolean) = CastLoadItem(
        "https://example.test/live.m3u8",
        "application/x-mpegURL",
        "Channel",
        null,
        null,
        live,
        emptyList(),
        emptyMap()
    )

    @Test fun liveLoadLetsReceiverChooseLiveEdgeAndNeverSeeksToGuidePosition() {
        val data = castLoadData(CastLoadRequest(listOf(item(true)), 0, 50_000, true))
        assertEquals(MediaInfo.STREAM_TYPE_LIVE, data.mediaInfo!!.streamType)
        assertEquals(MediaLoadRequestData.PLAY_POSITION_UNASSIGNED, data.currentTime)
        assertNull(data.queueData)
    }

    @Test fun liveQueueAlsoLeavesStartTimeToReceiver() {
        val data = castLoadData(CastLoadRequest(listOf(item(false), item(true)), 1, 50_000, true))
        assertEquals(1, data.queueData!!.startIndex)
        assertNotEquals(50_000L, data.queueData!!.startTime)
        assertEquals(MediaInfo.STREAM_TYPE_LIVE, data.queueData!!.items!![1].media.streamType)
    }

    @Test fun onDemandStillLoadsRequestedPosition() {
        val data = castLoadData(CastLoadRequest(listOf(item(false)), 0, 50_000, true))
        assertEquals(50_000L, data.currentTime)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, data.mediaInfo!!.streamType)
    }
}
