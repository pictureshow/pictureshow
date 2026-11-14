/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LivePage
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveTvService
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi", shadows = [LiveClockPatternShadow::class])
class LiveGuideNowScreenTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("u", "Viewer"), "fake")

    @Test fun nowKeepsItsFocusDuringFetchThenSelectsOnAirInTheChosenRowAndRefreshDoesNotDropFocus() {
        // The app uses real wall time. At a half-hour boundary, wait at most two seconds for a visible ended slot.
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.currentTimeMillis() - liveGuideWindowStart(System.currentTimeMillis()) < 2_000 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        val now = System.currentTimeMillis()
        val start = liveGuideWindowStart(now)
        assertTrue(now - start >= 2_000, "Fixture needs one genuinely ended programme inside the current window")
        val fake = LiveFake().apply { channelRows = listOf(LiveChannel("c1", "First", "1"), LiveChannel("c2", "Chosen", "2")) }
        val programs = fake.channelRows.flatMap { channel ->
            listOf(
                LiveProgram(
                    "${channel.id}-past",
                    "Ended",
                    channel.id,
                    startDate = Instant.ofEpochMilli(start).toString(),
                    endDate = Instant.ofEpochMilli(
                        now - 1_000
                    ).toString()
                ),
                LiveProgram(
                    "${channel.id}-current",
                    "On air",
                    channel.id,
                    startDate = Instant.ofEpochMilli(now - 500).toString(),
                    endDate = Instant.ofEpochMilli(
                        now + 1_800_000
                    ).toString()
                ),
                LiveProgram(
                    "${channel.id}-future",
                    "Later",
                    channel.id,
                    startDate = Instant.ofEpochMilli(now + 1_800_000).toString(),
                    endDate = Instant.ofEpochMilli(
                        now + 3_600_000
                    ).toString()
                )
            )
        }
        var gate: CompletableDeferred<Unit>? = null
        var reads = 0
        val api = object : LiveTvService by fake {
            override suspend fun programs(
                account: Account,
                channelIds: List<String>,
                startUtc: String,
                endUtc: String,
                startIndex: Int,
                limit: Int
            ): ClientResult<LivePage<LiveProgram>> {
                reads++
                gate?.await()
                return ClientResult.Success(LivePage(programs, programs.size))
            }
        }
        val vm = LiveTvViewModel(account, api)
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, { error("Focus cannot tune") }, {}, {}) } }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("live:p:c2-future").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("live:p:c2-future").assertIsFocused()
        compose.runOnIdle { gate = CompletableDeferred() }
        val beforeNow = reads
        compose.onNodeWithTag("live:guide-now").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("live:guide-now").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertTrue(vm.state.value.loading)
            assertEquals(beforeNow + 1, reads)
        }
        compose.onNodeWithTag("live:guide-now").assertIsEnabled().assertIsFocused()
        listOf("earlier", "day", "later").forEach { compose.onNodeWithTag("live:guide-$it").assertIsEnabled() }
        compose.onNodeWithTag("live:guide-now").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(beforeNow + 1, reads, "Repeated Now during the held fetch must not restart its owner")
            gate!!.complete(Unit)
        }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("live:p:c2-current").assertIsFocused()
        compose.runOnIdle {
            assertEquals(start, vm.state.value.windowStart)
            gate = CompletableDeferred()
        }
        val beforeRefresh = reads
        compose.onNodeWithContentDescription("Refresh").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Refresh").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertTrue(vm.state.value.loading)
            assertEquals(beforeRefresh + 1, reads)
        }
        compose.onNodeWithContentDescription("Refresh").assertIsEnabled().assertIsFocused()
        compose.onNodeWithContentDescription("Refresh").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(beforeRefresh + 1, reads)
            gate!!.complete(Unit)
        }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithContentDescription("Refresh").assertIsFocused()
    }

    @Test fun disabledQuietDateControlsKeepTheirTransparentSilhouette() {
        val background = Color(0xFF102030)
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.fillMaxSize().background(background)) { LiveGuideDateBar(0, 1_000, true, false) {} }
            }
        }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        listOf("earlier", "day", "later", "now").forEach { kind ->
            val bounds = compose.onNodeWithTag("live:guide-$kind").getUnclippedBoundsInRoot()
            assertEquals(
                background,
                pixels[((bounds.left.value + bounds.right.value) / 2).toInt(), bounds.top.value.toInt() + 5],
                "$kind must not gain a filled disabled container"
            )
        }
    }
}
