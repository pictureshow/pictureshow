/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30], qualifiers = "w960dp-h540dp-mdpi", shadows = [LiveClockPatternShadow::class])
class LiveProgramEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Server", "https://example.test", "12"), User("u", "Viewer"), "token")

    @Test fun futureProgrammeWithExistingTimerFocusesItsPrimaryActionWithoutWriting() {
        render(current = false)
        compose.onNodeWithText("Cancel recording…").assertIsFocused()
    }

    @Test fun currentProgrammeWithExistingTimerKeepsWatchAsTheEntryAction() {
        render(current = true)
        compose.onNodeWithText("Watch now").assertIsFocused()
    }

    @Test fun tvProgrammeArtworkStartsOnThe48dpPageKeyline() {
        render(current = false)
        assertEquals(48.dp, compose.onNodeWithTag("live:program-artwork", useUnmergedTree = true).getUnclippedBoundsInRoot().left)
    }

    private fun render(current: Boolean) {
        val program = LiveProgram(
            "p",
            "Programme",
            "c",
            startDate = if (current) "2026-11-13T00:00:00Z" else "2026-11-13T02:00:00Z",
            endDate = "2026-11-13T03:00:00Z",
            timerId = "t"
        )
        val timer = LiveSchedule(
            buildJsonObject {
                put("Id", "t")
                put("Name", "Scheduled programme")
                put("ProgramId", "p")
                put("ChannelId", "c")
                put("Status", "New")
            }
        )
        val state = LiveTvState(
            loading = false,
            channels = listOf(LiveChannel("c", "Channel", "1")),
            programs = listOf(program),
            timers = listOf(timer),
            policy = LivePolicy(true, true, true, true)
        )
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                    LiveProgramDetail(
                        program, state, account, true, true, java.time.Instant.parse("2026-11-13T01:00:00Z").toEpochMilli(),
                        { error("Focus must not play") }, { error("Focus must not record") }, { error("Focus must not record series") },
                        { _, _ -> error("Focus must not cancel or edit a recording") }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
}
