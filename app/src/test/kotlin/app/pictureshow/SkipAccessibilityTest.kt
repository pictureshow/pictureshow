/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentType
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SkipAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test
    fun skipAppearsPolitelyWithoutTakingFocusAndUpReachesItFromPinnedControls() {
        val play = FocusRequester()
        var position by mutableStateOf(9_000L)
        var skips = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.BottomCenter).padding(36.dp)) {
                        Text("Film")
                        PlayerTimeline(position, position, 60_000) {}
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {}, modifier = Modifier.focusRequester(play)) { Text("Pause") }
                            Button(onClick = {}) { Text("Audio") }
                            Button(onClick = {}) { Text("Subtitles") }
                            Button(onClick = {}) { Text("Chapters") }
                            Button(onClick = {}) { Text("Stop") }
                        }
                    }
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 220.dp)) {
                        SegmentControl(
                            listOf(MediaSegment("intro", SegmentType.Intro, 100_000_000, 200_000_000)),
                            position,
                            emptyMap(),
                            onSeek = {
                                skips++
                                position = it
                            },
                            onFocusRemoved = { play.requestFocus() }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            play.requestFocus()
            position = 12_000
        }
        compose.onNodeWithText("Pause").assertIsFocused()
        compose.onNodeWithTag("skip:Intro").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("skip:Intro").assertIsFocused()
        compose.runOnIdle { assertEquals(0, skips) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, skips) }
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertIsFocused()
    }
}
