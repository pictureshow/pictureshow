/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.playback.ObservedLiveWindow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchLivePositionReadTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var host: PlaybackHostState
    private lateinit var view: View

    @After fun cleanup() {
        compose.runOnIdle {
            if (::host.isInitialized) host.reportScope.cancel()
            if (::output.isInitialized) output.release()
        }
    }

    @Test fun pausedWindowGrowthIsFreshOnNativeQueryWhileComposeAnnouncementsStayFrozen() {
        launch(ObservedLiveWindow(100_000, 214_000, 204_000, true))
        val slider = compose.onNodeWithTag("touch:scrubber")
        val status = compose.onNodeWithTag("touch:live-status")
        val sliderId = slider.fetchSemanticsNode().id
        val statusId = status.fetchSemanticsNode().id
        val initial = "Paused · 3 sec behind live"
        assertEquals(initial, slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(initial, status.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.runOnIdle {
            host.liveWindow = requireNotNull(host.liveWindow).copy(durationMs = 262_000, defaultPositionMs = 252_000)
        }
        compose.mainClock.advanceTimeBy(32)
        assertEquals(initial, slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(initial, status.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(listOf("Live playback status"), status.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        compose.runOnIdle {
            val provider = requireNotNull(view.accessibilityNodeProvider)
            val position = requireNotNull(provider.createAccessibilityNodeInfo(sliderId))
            val description = requireNotNull(provider.createAccessibilityNodeInfo(statusId))
            assertEquals("touch:scrubber", position.viewIdResourceName)
            assertEquals("touch:live-status", description.viewIdResourceName)
            assertEquals("Paused · 51 sec behind live", position.stateDescription?.toString())
            assertEquals("Paused · 51 sec behind live", description.stateDescription?.toString())
            assertEquals("Live playback status", description.contentDescription?.toString())
            val range = requireNotNull(position.rangeInfo)
            assertEquals(0f, range.min)
            assertEquals(262_000f, range.max)
            assertEquals(201_000f, range.current)
            assertTrue(
                provider.performAction(
                    sliderId,
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 230_000f) }
                ),
                "The decorator forwards the native slider action"
            )
            assertEquals(230_000L, host.position)
            assertEquals(230_000L, host.seekPreview)
        }
    }

    @Test fun statusStillHasAReaderNodeWithoutAnAvailableSlider() {
        launch(null)
        val status = compose.onNodeWithTag("touch:live-status")
        val id = status.fetchSemanticsNode().id
        compose.runOnIdle {
            val node = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
            assertEquals("Live playback status", node.contentDescription?.toString())
            assertEquals("Live · Time shift unavailable", node.stateDescription?.toString())
            host.liveBufferExpired = true
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            val node = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(id))
            assertEquals("Paused · Buffer expired. Go live to resume.", node.stateDescription?.toString())
        }
    }

    @Test fun phoneTrackKeepsItsMeasuredBoundsAcrossLivePauseAndBehindStates() {
        val paused = mutableStateOf(false)
        launch(ObservedLiveWindow(100_000, 214_000, 201_000, true), paused)
        val slider = compose.onNodeWithTag("touch:scrubber")
        val live = compose.onNodeWithTag("touch:go-live")
        val trackBounds = slider.getBoundsInRoot()
        val slot = compose.onNodeWithTag("touch:live-slot")
        val slotBounds = slot.getBoundsInRoot()
        val static = live.fetchSemanticsNode().config
        assertEquals(listOf("Live"), static[SemanticsProperties.ContentDescription])
        assertFalse(static.contains(SemanticsActions.OnClick), "A playing live edge is status, not a disabled action")
        assertFalse(static.contains(SemanticsActions.RequestFocus), "Static Live is not a remote focus stop")
        compose.runOnIdle { paused.value = true }
        compose.mainClock.advanceTimeBy(32)
        live.assertHasClickAction()
        assertEquals(trackBounds, slider.getBoundsInRoot(), "Pausing does not move the live slider")
        assertEquals(slotBounds, slot.getBoundsInRoot(), "Go live occupies the static status slot")
        compose.runOnIdle { host.position = 190_000 }
        compose.mainClock.advanceTimeBy(32)
        live.assertHasClickAction()
        assertEquals(trackBounds, slider.getBoundsInRoot(), "Behind-live playback does not shorten the slider")
        assertEquals(slotBounds, slot.getBoundsInRoot())
    }

    private fun launch(window: ObservedLiveWindow?, paused: MutableState<Boolean> = mutableStateOf(true)) {
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, Server("server", "Server", "https://example.test", "12"))
            host = PlaybackHostState(MediaItem("channel", "Channel", type = "TvChannel"), 201_000, output, {}, {})
            host.liveWindow = window
        }
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                TouchLivePlayerTimeline(host, androidx.compose.runtime.remember { TouchControlsState() }, paused = paused.value)
            }
        }
    }
}
