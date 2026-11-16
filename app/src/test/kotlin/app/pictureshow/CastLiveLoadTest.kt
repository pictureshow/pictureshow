/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        val data = castLoadData(CastLoadRequest(listOf(item(false), item(true)), 1, 0, true))
        assertEquals(1, data.queueData!!.startIndex)
        assertEquals(MediaQueueData.Builder().build().startTime, data.queueData!!.startTime)
        assertEquals(MediaInfo.STREAM_TYPE_LIVE, data.queueData!!.items!![1].media?.streamType)
    }

    @Test fun musicQueuePreservesInitialPauseButEveryRepeatedItemAutoplays() {
        fun song(entry: String) = CastLoadItem(
            "https://example.test/song.mp3", "audio/mpeg", "Song", "https://example.test/art.jpg",
            60_000, false, emptyList(), mapOf("entry" to entry), music = true, artist = "Artist", album = "Album", activeTracks = listOf(4)
        )
        val data = castLoadData(CastLoadRequest(listOf(song("first"), song("duplicate")), 1, 45_000, false, "all"))
        assertFalse(data.autoplay!!)
        assertEquals(45_000L, data.currentTime)
        val queue = data.queueData!!
        assertEquals(1, queue.startIndex)
        assertEquals(MediaStatus.REPEAT_MODE_REPEAT_ALL, queue.repeatMode)
        assertTrue(queue.items!!.all { it.autoplay })
        val loaded = queue.items!![1]
        assertEquals("duplicate", loaded.media!!.customData!!.getString("entry"))
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK, loaded.media!!.metadata!!.mediaType)
        assertEquals("Artist", loaded.media!!.metadata!!.getString(MediaMetadata.KEY_ARTIST))
        assertEquals("Album", loaded.media!!.metadata!!.getString(MediaMetadata.KEY_ALBUM_TITLE))
        assertEquals(1, loaded.media!!.metadata!!.images.size)
        assertEquals(listOf(4L), loaded.activeTrackIds!!.toList())
    }

    @Test fun onDemandStillLoadsRequestedPosition() {
        val data = castLoadData(CastLoadRequest(listOf(item(false)), 0, 50_000, true))
        assertEquals(50_000L, data.currentTime)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, data.mediaInfo!!.streamType)
    }
}
