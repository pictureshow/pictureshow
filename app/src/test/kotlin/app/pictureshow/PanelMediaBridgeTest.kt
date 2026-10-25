/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    application = Application::class,
    qualifiers = "w960dp-h540dp-land-xhdpi",
    shadows = [PlaybackMediaSessionTest.RecordingMediaController::class]
)
@LooperMode(LooperMode.Mode.PAUSED)
class PanelMediaBridgeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun focusWindow(host: View, focused: Boolean = true) {
        val root = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(host, "getViewRootImpl")
        Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(root).callWindowFocusChanged(focused)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun selectionCommitsOnceEvenIfBackRemovesThePanelBeforeFocusReturns() {
        var selections = 0
        val shown = mutableStateOf(true)
        lateinit var activityHost: View
        compose.setContent {
            activityHost = LocalView.current
            PictureshowTheme {
                if (shown.value) {
                    PlayerPanel("Subtitles", { shown.value = false }) {
                        Button(onClick = { select({ selections++ }) { shown.value = false } }) { Text("Choose subtitle") }
                    }
                }
            }
        }
        compose.runOnIdle { focusWindow(activityHost, false) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Choose subtitle").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithText("Choose subtitle").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            assertEquals(1, selections, "Selecting commits before the window handoff and ignores another activation")
            shown.value = false // Activity Back while the old window is unfocusable.
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun aSystemWindowTakingFocusCannotLeaveThePanelCloseWaitingForever() = runTest {
        val host = object : View(compose.activity) {
            override fun hasWindowFocus() = false
        }
        assertFalse(awaitPlayerActivityFocus(host))
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun selectingAPanelActionWaitsForTheActivityToOwnWindowFocus() {
        lateinit var window: Window
        lateinit var activityHost: View
        var completed = false
        val shown = mutableStateOf(true)
        compose.setContent {
            activityHost = LocalView.current
            PictureshowTheme {
                if (shown.value) {
                    PlayerPanel("Chapters", { shown.value = false }) {
                        window = (LocalView.current.parent as DialogWindowProvider).window
                        Button(onClick = {
                            close {
                                completed = true
                                shown.value = false
                            }
                        }) { Text("Choose chapter") }
                    }
                }
            }
        }
        compose.runOnIdle { focusWindow(activityHost, false) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Choose chapter").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.waitForIdle()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle {
            assertEquals(false, completed, "The old window must remain alive during focus handoff")
            assertTrue(shown.value)
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            focusWindow(activityHost)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue(completed) }
    }

    @Test fun openAndClosingDialogSendEveryMediaKeyThroughTheActivityBridge() {
        lateinit var window: Window
        lateinit var activityHost: View
        val shown = mutableStateOf(true)
        val player = ExoPlayer.Builder(compose.activity).build()
        val handle = PlaybackMediaSession.open(compose.activity, player)
        try {
            compose.setContent {
                activityHost = LocalView.current
                PictureshowTheme {
                    if (shown.value) {
                        PlayerPanel("Audio", { shown.value = false }) {
                            window = (LocalView.current.parent as DialogWindowProvider).window
                            Button(onClick = { close { shown.value = false } }) { Text("Choose track") }
                        }
                    }
                }
            }
            compose.runOnIdle {
                assertTrue(window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
                assertTrue(window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
            }
            val delivered = Shadow.extract<PlaybackMediaSessionTest.RecordingMediaController>(handle.controller)
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), delivered.actions)
            compose.runOnIdle { focusWindow(activityHost, false) }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Choose track").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
            compose.waitForIdle()
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnIdle {
                assertTrue(shown.value, "Do not remove a focused window before the activity owns input focus")
                assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                assertEquals(4, delivered.keys.size)
                focusWindow(activityHost)
            }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle { assertEquals(false, shown.value) }
        } finally {
            handle.close()
            player.release()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
