/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.ObservedLiveWindow
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LivePlayerControlGeometryTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveSeekPanelDisablesOnlyForwardAtTheEdgeAndOrdinaryDefaultStaysEnabled() {
        val live = mutableStateOf(true)
        compose.setContent {
            PictureshowTheme {
                if (live.value) {
                    SeekPanel(27_000, 30_000, {}, forwardEnabled = false) {}
                } else {
                    SeekPanel(27_000, 30_000, {}, {})
                }
            }
        }
        compose.onNodeWithTag("seek:back").assertIsEnabled()
        compose.onNodeWithTag("seek:forward").assertIsNotEnabled()
        compose.runOnIdle { live.value = false }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("seek:back").assertIsEnabled()
        compose.onNodeWithTag("seek:forward").assertIsEnabled()
    }

    @OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
    @Test
    fun nativeTvActionAndStaticStatusKeepOneSlotAtBothFontScales() {
        val position = mutableLongStateOf(27_000)
        val paused = mutableStateOf(false)
        val fontScale = mutableFloatStateOf(1f)
        var pixelDensity = 1f
        compose.setContent {
            val density = LocalDensity.current
            pixelDensity = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
                PictureshowTheme {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerLiveEdgeChip(ObservedLiveWindow(100_000, 30_000, 27_000, true), position.longValue, false, paused.value) {}
                        androidx.tv.material3.Button(onClick = {
                        }, modifier = Modifier.testTag("audio")) { androidx.tv.material3.Text("Audio") }
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle {
                fontScale.floatValue = scale
                position.longValue = 27_000
                paused.value = false
            }
            compose.mainClock.advanceTimeBy(32)
            val live = compose.onNodeWithTag("player:go-live")
            val slot = compose.onNodeWithTag("player:live-slot")
            val bounds = slot.getBoundsInRoot()
            val audio = compose.onNodeWithTag("audio").getBoundsInRoot()
            val expectedWidth = (96f * scale * pixelDensity).roundToInt() / pixelDensity
            assertEquals(expectedWidth, (bounds.right - bounds.left).value, .01f)
            assertEquals(((bounds.top + bounds.bottom) / 2).value, ((audio.top + audio.bottom) / 2).value, .01f)
            assertEquals(listOf("Live"), live.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
            assertFalse(live.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
            assertFalse(live.fetchSemanticsNode().config.contains(SemanticsActions.RequestFocus))
            compose.runOnIdle { paused.value = true }
            compose.mainClock.advanceTimeBy(32)
            live.assertHasClickAction()
            assertEquals(bounds, slot.getBoundsInRoot())
            assertEquals(audio, compose.onNodeWithTag("audio").getBoundsInRoot(), "Native audio stays anchored when Live becomes Go live")
            compose.runOnIdle { position.longValue = 10_000 }
            compose.mainClock.advanceTimeBy(32)
            assertEquals(bounds, slot.getBoundsInRoot())
            assertEquals(audio, compose.onNodeWithTag("audio").getBoundsInRoot())
        }
    }
}
