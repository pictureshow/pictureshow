/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.LiveTvService
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
class LiveScheduleRefreshUiTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")

    @Test fun shortChannelAndUnbrokenRangeStayAdjacentOnPhone() {
        val timer = LiveSchedule(
            buildJsonObject {
                put("Id", "t")
                put("Name", "Generated programme")
                put("ChannelName", "Test B")
                put("StartDate", "2026-11-10T20:00:00Z")
                put("EndDate", "2026-11-10T21:00:00Z")
            }
        )
        compose.setContent { TouchTheme { LiveScheduleRow(timer, false, Modifier) {} } }
        val channel = compose.onNodeWithText("Test B", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val range = compose.onNodeWithTag("live:schedule:range:t", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(range.left >= channel.right && range.left - channel.right <= 1.dp, "Range follows the channel without a weighted void")
        assertEquals(channel.top, range.top)
        assertTrue(range.bottom - range.top <= 24.dp, "The complete range stays on one line")
    }

    @Test fun phoneCancelledStatusUsesAnActualRaisedNativeSurface() {
        var expected = Color.Unspecified
        compose.setContent {
            TouchTheme {
                expected = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                Box(Modifier.testTag("badge")) { LiveStatus("Cancelled", false, "Cancelled") }
            }
        }
        val pixels = compose.onNodeWithTag("badge").captureToImage().toPixelMap()
        assertEquals(expected, pixels[3, pixels.height / 2], "The inset label has a visible native container")
    }

    @Test fun tvCancelledStatusUsesSurfaceVariant() {
        var expected = Color.Unspecified
        compose.setContent {
            PictureshowTheme {
                expected = androidx.tv.material3.MaterialTheme.colorScheme.surfaceVariant
                Box(Modifier.testTag("badge")) { LiveStatus("Cancelled", true, "Cancelled") }
            }
        }
        val pixels = compose.onNodeWithTag("badge").captureToImage().toPixelMap()
        assertEquals(expected, pixels[3, pixels.height / 2])
    }

    @Test fun initialPhoneLoadUsesHeaderInsteadOfPretendingTheViewerPulled() {
        val gate = CompletableDeferred<Unit>()
        val fake = LiveFake()
        val service = object : LiveTvService by fake {
            override suspend fun policy(account: Account) = fake.policy(account).also { gate.await() }
        }
        compose.setContent {
            TouchTheme { LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, service) }, account, false, {}, {}, {}) }
        }
        compose.onNodeWithTag("live:header-loading").assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(indeterminate, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.runOnIdle { gate.complete(Unit) }
        compose.onNodeWithTag("live:header-loading").assertDoesNotExist()
    }

    @Test fun actualPullOwnsTheFloatingIndicatorAndStopsOwningItAfterCompletion() {
        var gate: CompletableDeferred<Unit>? = null
        var requests = 0
        val fake = LiveFake()
        val service = object : LiveTvService by fake {
            override suspend fun policy(account: Account) = fake.policy(account).also {
                requests++
                gate?.await()
            }
        }
        compose.setContent {
            TouchTheme { LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, service) }, account, false, {}, {}, {}) }
        }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        val initialRequests = requests
        compose.runOnIdle { gate = CompletableDeferred() }
        compose.onNodeWithTag("live:pull-refresh").performTouchInput { swipeDown() }
        compose.onNodeWithTag("live:header-loading").assertDoesNotExist()
        assertEquals(1, compose.onAllNodes(indeterminate, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.runOnIdle {
            assertTrue(requests > initialRequests, "The actual gesture invokes refresh")
            assertTrue(gate?.isCompleted == false)
            gate?.complete(Unit)
        }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.onNodeWithTag("live:header-loading").assertDoesNotExist()
        assertEquals(0, compose.onAllNodes(indeterminate, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun serverReturnedEndedCancelledRowsRemainVisibleInPast() {
        val now = Instant.now()
        val fake = LiveFake().apply {
            timerRows = listOf(
                LiveSchedule(
                    buildJsonObject {
                        put("Id", "ended")
                        put("Name", "Ended generated airing")
                        put("Status", "Cancelled")
                        put("StartDate", now.minusSeconds(120).toString())
                        put("EndDate", now.minusSeconds(60).toString())
                    }
                )
            )
        }
        compose.setContent {
            TouchTheme { LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, false, {}, {}, {}) }
        }
        compose.onNodeWithTag("live:tab:Schedules").performClick()
        compose.onNodeWithText("Past").assertIsDisplayed()
        compose.onNodeWithTag("live:s:timer:ended").assertIsDisplayed()
        compose.onNodeWithText("Upcoming").assertDoesNotExist()
    }

    @Test fun immediatePullDoesNotMislabelTheNextToolbarRefresh() {
        var gate: CompletableDeferred<Unit>? = null
        var requests = 0
        val fake = LiveFake()
        val service = object : LiveTvService by fake {
            override suspend fun policy(account: Account) = fake.policy(account).also {
                requests++
                gate?.await()
            }
        }
        compose.setContent {
            TouchTheme { LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, service) }, account, false, {}, {}, {}) }
        }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        val initialRequests = requests
        compose.onNodeWithTag("live:pull-refresh").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertTrue(requests > initialRequests)
            gate = CompletableDeferred()
        }
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.onNodeWithTag("live:header-loading").assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(indeterminate, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.runOnIdle { gate?.complete(Unit) }
    }
    private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
}
