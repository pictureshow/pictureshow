/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi", shadows = [LiveClockPatternShadow::class])
class LiveGuideEdgeRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Server", "https://demo.example.test", "12"), User("u", "Viewer"), "token")

    @Test fun movingTheViewportAwayFocusesARealProgrammeAtItsOwnTimeWithoutOpeningIt() {
        lateinit var scroll: ScrollState
        lateinit var scope: CoroutineScope
        var selected by mutableStateOf("p:p0")
        val shows = (0..3).map { index ->
            LiveProgram(
                "p$index",
                "Programme $index",
                "c",
                startDate = Instant.ofEpochMilli(index * 1_800_000L).toString(),
                endDate = Instant.ofEpochMilli((index + 1) * 1_800_000L).toString()
            )
        }
        compose.setContent {
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            PictureshowTheme {
                Column(Modifier.height(400.dp)) {
                    LiveGuide(
                        listOf(LiveChannel("c", "Channel", "1")), shows, account, true, 0, 900_000,
                        scroll, rememberLazyListState(), selected, true, {
                            selected = it
                        }, onOpen = { error("Focus must not open or tune a programme") }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("live:p:p0").assertIsFocused()
        compose.runOnIdle { scope.launch { scroll.scrollTo(300) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("live:p:p1").assertIsFocused()
        assertTrue(
            kotlin.math.abs(liveGuideFocusShift(8f, 178f, 300f, 760f)) <= 178f * .05f + 8f,
            "Off-screen focus paint cannot be dragged across the time scale"
        )
    }

    @Test fun nowKeepsItsSlotAndTvCanReturnToOnAirEvenInTheCurrentWindow() {
        var atNow by mutableStateOf(true)
        var resets = 0
        compose.setContent {
            PictureshowTheme {
                Column(Modifier.height(100.dp)) {
                    LiveGuideDateBar(0, 1_000, true, true, viewportAtNow = atNow, onNow = { resets++ }) {}
                }
            }
        }
        val before = listOf("earlier", "day", "later", "now").map {
            compose.onNodeWithTag("live:guide-$it").getUnclippedBoundsInRoot()
        }
        compose.onNodeWithTag("live:guide-now").assertIsEnabled()
        compose.onNodeWithTag("live:guide-now").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("live:guide-now").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, resets, "Now remains useful for returning focus to the on-air programme")
        compose.runOnIdle { atNow = false }
        val after = listOf("earlier", "day", "later", "now").map {
            compose.onNodeWithTag("live:guide-$it").getUnclippedBoundsInRoot()
        }
        assertEquals(before, after, "Clock boundaries, Back and horizontal movement cannot shift the date controls")
        compose.onNodeWithTag("live:guide-now").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("live:guide-now").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(2, resets)
    }
}
