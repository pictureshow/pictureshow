/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30], qualifiers = "w384dp-h853dp-mdpi", shadows = [LiveClockPatternShadow::class])
class LiveProgramDetailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli()
    private val account = Account(Server("s", "Server", "https://example.test", "12"), User("u", "Viewer"), "token")
    private var managed: Pair<String, Boolean>? = null

    @Test fun endedTouchProgrammeExplainsTimeAndOffersNoDeadCreationButtons() {
        render()
        assertEndedWithoutCreation()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun endedTvProgrammeExplainsTimeAndOffersNoDeadCreationButtons() {
        render(tv = true)
        assertEndedWithoutCreation()
    }

    @Test fun theExactEndBoundaryIsAlreadyEnded() {
        render(end = "2026-11-13T01:00:00Z")
        assertEndedWithoutCreation()
    }

    @Test fun anEndedAiringRetainsItsActualTimerManagement() {
        render(timer = "t")
        compose.onNode(hasText("Ended · ", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Record once…").assertDoesNotExist()
        compose.onNodeWithText("Record series…").assertDoesNotExist()
        compose.onNodeWithText("Cancel recording…").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("t" to false, managed) }
    }

    @Test fun anEndedAiringRetainsItsActualSeriesRuleManagement() {
        render(series = "s1")
        compose.onNode(hasText("Ended · ", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Record once…").assertDoesNotExist()
        compose.onNodeWithText("Record series…").assertDoesNotExist()
        compose.onNodeWithText("Manage series…").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("s1" to true, managed) }
    }

    @Test fun missingProgrammeDatesNeverInventAnEndedTime() {
        render(start = null, end = null)
        compose.onNode(hasText("Ended · ", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Record once…").assertDoesNotExist()
        compose.onNodeWithText("Record series…").assertDoesNotExist()
    }

    @Test fun currentProgrammeKeepsWatchAndBothEnabledRecordingChoices() {
        render(end = "2026-11-13T02:00:00Z")
        compose.onNodeWithText("Watch now").assertIsEnabled()
        compose.onNodeWithText("Record once…").assertIsEnabled()
        compose.onNodeWithText("Record series…").assertIsEnabled()
        compose.onNode(hasText("Ended · ", substring = true)).assertDoesNotExist()
    }

    @Test fun futureProgrammeKeepsTheStartExplanationAndRecordingChoices() {
        render(start = "2026-11-13T02:00:00Z", end = "2026-11-13T03:00:00Z")
        compose.onNodeWithText("Watch now").assertDoesNotExist()
        compose.onNode(hasText("Starts in ", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Record once…").assertIsEnabled()
        compose.onNodeWithText("Record series…").assertIsEnabled()
        compose.onNode(hasText("Ended · ", substring = true)).assertDoesNotExist()
    }

    private fun assertEndedWithoutCreation() {
        compose.onNode(hasText("Ended · ", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Watch now").assertDoesNotExist()
        compose.onNodeWithText("Record once…").assertDoesNotExist()
        compose.onNodeWithText("Record series…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, managed) }
    }

    private fun render(
        tv: Boolean = false,
        start: String? = "2026-11-13T00:00:00Z",
        end: String? = "2026-11-13T00:59:00Z",
        timer: String? = null,
        series: String? = null
    ) {
        fun schedule(id: String) = LiveSchedule(
            buildJsonObject {
                put("Id", id)
                put("ProgramId", "p")
                put("Status", "New")
            }
        )
        val program =
            LiveProgram("p", "Programme", "c", startDate = start, endDate = end, isSeries = true, timerId = timer, seriesTimerId = series)
        val state = LiveTvState(
            loading = false,
            channels = listOf(LiveChannel("c", "Channel", "1")),
            programs = listOf(program),
            timers = listOfNotNull(timer?.let(::schedule)),
            series = listOfNotNull(series?.let(::schedule)),
            policy = LivePolicy(true, true, true, true)
        )
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveProgramDetail(
                        program, state, account, tv, true, now,
                        {
                            error("Inspection must not play")
                        }, { error("Inspection must not record") }, { error("Inspection must not record a series") },
                        { selected, rule -> managed = selected.id to rule }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
}
