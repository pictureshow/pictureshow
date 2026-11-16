/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.geometry.Rect
import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TOUCH-15: the window grows out of the picture itself, not the black bars around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TouchPictureInPictureTest {
    @Test fun aWideFilmOnAPortraitPhoneGrowsFromItsLetterbox() {
        val source = touchPictureInPictureSource(Rect(0f, 100f, 1080f, 2100f), VideoSize(1920, 800))
        assertEquals(android.graphics.Rect(0, 875, 1080, 1325), source)
    }

    @Test fun aNarrowVideoInLandscapeGrowsFromItsPillarbox() {
        val source = touchPictureInPictureSource(Rect(0f, 0f, 2400f, 1080f), VideoSize(1440, 1080))
        assertEquals(android.graphics.Rect(480, 0, 1920, 1080), source)
    }

    @Test fun anamorphicPixelsWidenThePicture() {
        // 720x480 with 32:27 pixels is 16:9 on screen.
        val source = touchPictureInPictureSource(Rect(0f, 0f, 1600f, 900f), VideoSize(720, 480, 32f / 27f))
        assertEquals(android.graphics.Rect(0, 0, 1600, 900), source)
    }

    @Test fun anUnknownSizeAssumesWidescreen() {
        val source = touchPictureInPictureSource(Rect(0f, 0f, 1600f, 1600f), VideoSize.UNKNOWN)
        assertEquals(android.graphics.Rect(0, 350, 1600, 1250), source)
    }
}
