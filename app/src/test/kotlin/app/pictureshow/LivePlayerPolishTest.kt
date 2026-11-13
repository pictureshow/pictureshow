/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.ObservedLiveWindow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePlayerPolishTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun largeFontTransportKeepsFocusAcrossPauseAndOneRightReachesGoLive() {
        var paused by mutableStateOf(false)
        val focus = FocusRequester()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                PictureshowTheme {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlaybackToggle(
                            paused,
                            paused,
                            Modifier.width(liveControlWidth()).focusRequester(focus).testTag("play"),
                            liveStyle = true
                        ) {}
                        PlayerLiveEdgeChip(ObservedLiveWindow(0, 30_000, 27_000, true), 10_000, false, paused) {}
                        PlayerActionButton(onClick = {}, liveStyle = true, modifier = Modifier.testTag("audio")) {
                            androidx.tv.material3.Text("Audio")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val before = compose.onNodeWithTag("play").getUnclippedBoundsInRoot()
        compose.runOnIdle { paused = true }
        compose.onNodeWithTag("play").assertIsFocused()
        assertEquals(before, compose.onNodeWithTag("play").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("play").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("player:go-live").assertIsFocused()
        val actions = listOf("play", "player:go-live", "audio").map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        actions.forEach { assertEquals(48.dp, it.bottom - it.top) }
        listOf("Play", "Go live", "Audio").zip(actions).forEach { (label, bounds) ->
            val text = compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(
                kotlin.math.abs((text.top + text.bottom).value / 2 - (bounds.top + bounds.bottom).value / 2) <= 2,
                "$label is vertically centered in its native button: $text / $bounds"
            )
        }
    }
}
