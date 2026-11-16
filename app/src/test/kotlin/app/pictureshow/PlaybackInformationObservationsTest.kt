/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackInformationObservationsTest {
    @Test fun actualPlayerMimeDoesNotInventVp9ConversionAndInfoUsesSameReductionLine() {
        val source = MediaSourceInfo(
            mediaStreams = listOf(
                MediaStream(
                    type = MediaStreamType.Video,
                    codec = "vp9",
                    width = 3840,
                    height = 2160
                )
            )
        )
        val decision = PlayDecision("film", source, PlayMethod.DirectPlay, "https://example.test/film", emptyMap(), null)
        val format = Format.Builder().setSampleMimeType("video/x-vnd.on2.vp9").setWidth(960).setHeight(540).build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        val notice = "Lowered to 4 Mbps because the connection slowed. Choose Quality → Auto to try again."
        val sections = playbackInformationSections(decision, -1, -1, null, tracks, adaptiveNotice = notice)
        assertEquals(listOf(notice), sections.single { it.title == "Quality" }.lines)
        val lines = sections.flatMap { it.lines }
        assertFalse(lines.any { it.startsWith("Video changed") })
        assertTrue(lines.any { it.startsWith("Scaled from 3840×2160 to 960×540") })
        assertTrue("Decoded by this device." in lines)
    }
}
