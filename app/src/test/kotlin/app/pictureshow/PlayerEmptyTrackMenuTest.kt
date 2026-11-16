/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PLAY-08 (TV review T-10) and PLAY-03's last focused action (T-3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlayerEmptyTrackMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aVideoWithoutSubtitlesOffersNoBareOffAndSaysWhy() {
        val menus = preparePlayerTrackMenus(listOf(MediaStream(type = MediaStreamType.Audio, index = 1, language = "en", codec = "aac")))
        assertTrue(menus.subtitles.choices.isEmpty(), "no lone Off")
        assertEquals("This video has no subtitles.", menus.subtitles.emptyNotice)
        assertNull(menus.audio.emptyNotice)
    }

    @Test fun subtitlesStillOfferOffWhenThereIsATrack() {
        val menus = preparePlayerTrackMenus(listOf(MediaStream(type = MediaStreamType.Subtitle, index = 3, language = "en", codec = "srt")))
        assertEquals(-1 to "Off", menus.subtitles.choices.first())
        assertNull(menus.subtitles.emptyNotice)
        assertEquals("This video has no audio tracks.", menus.audio.emptyNotice)
    }

    @Test fun theEmptyPanelPutsFocusOnCloseAndCloses() {
        var closed = false
        compose.setContent { EmptyTrackPanel("This video has no subtitles.", onClose = { closed = true }) }
        compose.waitForIdle()
        compose.onNodeWithText("This video has no subtitles.").assertExists()
        compose.onNodeWithText("Close").assertIsFocused()
        compose.onNodeWithText("Close").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(closed)
    }

    @Test fun lastActionReturnsOnlyWithinThirtySecondsOfHiding() {
        val last = PlayerLastAction<String>()
        assertNull(last.restorable(1_000), "nothing focused yet")
        last.focused("info")
        assertNull(last.restorable(1_000), "never hidden")
        last.hidden(10_000)
        assertEquals("info", last.restorable(40_000))
        assertNull(last.restorable(40_001))
        last.focused("quality")
        assertEquals("quality", last.restorable(20_000))
        assertFalse(last.restorable(9_999) != null, "a clock that runs backwards never restores")
    }
}
