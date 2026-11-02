/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TrackPanelResponsivenessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstArrowMovesFromSelectedTrackBeforeWindowEffectAndNeverSnapsBack() {
        verifyEntry(chapters = false)
    }

    @Test fun chapterEntryAlsoCannotUndoAnArrowWhenWindowFocusArrivesLateOrReturns() {
        verifyEntry(chapters = true)
    }

    @Test fun elevenKeyPickAtOneHundredSixtyMillisecondsSurvivesLateWindowEntry() {
        var windowFocused by mutableStateOf(false)
        var picked: Int? = null
        compose.setContent {
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = windowFocused
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PictureshowTheme {
                    TrackChoices(listOf(-1 to "Off") + (2..45).map { it to "Subtitle $it" }, 23, { picked = it })
                }
            }
        }
        compose.onNodeWithTag("track:23").assertIsFocused()
        compose.mainClock.advanceTimeBy(160)
        compose.onRoot().performKeyInput {
            // Stay away from an end clamp, so losing the first arrow cannot be hidden by saturation.
            repeat(5) {
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionUp)
            }
            pressKey(Key.DirectionCenter)
        }
        assertEquals(23, picked)
        compose.runOnIdle { windowFocused = true }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("track:23").assertIsFocused()
    }

    private fun verifyEntry(chapters: Boolean) {
        var windowFocused by mutableStateOf(false)
        var position by mutableLongStateOf(30_000L)
        val entry = FocusRequester()
        lateinit var focus: FocusManager
        compose.setContent {
            focus = LocalFocusManager.current
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = windowFocused
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PictureshowTheme {
                    Column(Modifier.focusRequester(entry).focusGroup()) {
                        if (chapters) {
                            val account =
                                Account(Server("s", "Fixture", "https://media.example.test", "12"), User("u", "Viewer"), "fixture")
                            ChapterPanel(account, "film", (0..44).map { Chapter(it * 100_000_000L, "Chapter $it") }, position) {}
                        } else {
                            TrackChoices(listOf(-1 to "Off") + (0..43).map { it to "Subtitle $it" }, 3, {}, title = "Subtitles")
                        }
                    }
                }
            }
        }
        val prefix = if (chapters) "chapter" else "track"
        // No focused-window coroutine has run. Layout must already have claimed the selected row.
        compose.onNodeWithTag("$prefix:3").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("$prefix:4").assertIsFocused()
        compose.runOnIdle { windowFocused = true }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("$prefix:4").assertIsFocused()
        compose.runOnIdle { windowFocused = false }
        compose.runOnIdle { windowFocused = true }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("$prefix:4").assertIsFocused()
        // A chapter boundary can change the current row while the viewer is browsing another one.
        if (chapters) compose.runOnIdle { position = 50_000L }
        // Native view/window re-entry must restore the viewer's row, including before window effects.
        compose.runOnIdle {
            focus.clearFocus(force = true)
            assertTrue(entry.requestFocus())
        }
        compose.onNodeWithTag("$prefix:4").assertIsFocused()
    }

    @Test fun idleTicksSelectionAndTrackTypeReuseDescriptionsButChangedMetadataRebuildsThem() {
        val original = (0..43).map { MediaStream(type = MediaStreamType.Subtitle, index = it, language = "en", codec = "srt") } +
            MediaStream(type = MediaStreamType.Audio, index = 44, language = "en", codec = "aac")
        var streams by mutableStateOf(original)
        var type by mutableStateOf(MediaStreamType.Subtitle)
        var tick by mutableIntStateOf(0)
        var selected by mutableIntStateOf(3)
        var menu: PlayerTrackMenu? = null
        compose.setContent {
            // Model the player scope reading playback position and selection every half second.
            tick + selected
            menu = rememberPlayerTrackMenus("source", streams).menus?.get(type)
        }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            menu != null
        }
        val first = requireNotNull(menu)
        assertEquals(45, first.choices.size)
        repeat(8) {
            compose.runOnIdle {
                tick++
                selected++
            }
            compose.waitForIdle()
            assertSame(first, menu, "An unrelated player tick rebuilt all track labels")
        }
        compose.runOnIdle { streams = original.map { if (it.index == 0) it.copy(title = "Commentary") else it } }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            menu != null && menu !== first
        }
        assertNotSame(first, menu)
        assertEquals("English · SRT · Commentary", requireNotNull(menu).choices[1].second)
        compose.runOnIdle { type = MediaStreamType.Audio }
        compose.waitForIdle()
        assertEquals(listOf(-1 to "Off", 44 to "English · AAC"), requireNotNull(menu).choices)
    }
}
