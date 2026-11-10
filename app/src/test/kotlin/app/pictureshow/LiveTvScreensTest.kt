/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LivePolicy
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h1080dp-xhdpi")
class LiveTvScreensTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")

    @Test fun focusingAChannelDoesNotTuneAndActivationTunesExactlyOnce() {
        var tuned = 0
        val vm = LiveTvViewModel(account, LiveFake())
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                LiveTvScreen(vm, account, true, { tuned++ }, {}, {})
            }
        }
        compose.onNodeWithTag(
            "live:c:c"
        ).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) {
            it()
        }
        compose.runOnIdle { assertEquals(0, tuned) }
        // The screen samples its action clock once a second; loading can finish after the first sample.
        compose.mainClock.advanceTimeBy(1_100)
        // TV Material buttons activate through remote keys; a synthetic touch click is not a D-pad press.
        compose.onNodeWithTag("live:c:c").assertIsEnabled().assertIsFocused()
        compose.onNodeWithTag("live:c:c").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1, tuned) }
    }

    @Test fun readOnlyViewerHasRecordingExplanationAndNoScheduleWrites() {
        val fake = LiveFake().apply { rights = LivePolicy(access = true) }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, {}, {}, {}) } }
        compose.onNodeWithText("Schedules").performClick()
        compose.onNodeWithText(
            "Recording schedules require Live TV management permission. Ask your server administrator."
        ).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fake.created) }
    }

    @Test fun openingGuideDetailsDoesNotTuneAndExpiredBroadcastCannotTune() {
        var tuned = 0
        val vm = LiveTvViewModel(account, LiveFake())
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, { tuned++ }, {}, {}) } }
        compose.onNodeWithText("Guide").performClick()
        // The fake's broadcast is in 1970: a new guide window has no current broadcast.
        compose.onNodeWithText(
            "No guide data for this time. Channels can still be tuned. An administrator can add an XMLTV guide in Jellyfin Dashboard → Live TV."
        ).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, tuned) }
    }

    @Test fun emptyRecordingDaysShowsValidationWithoutSubmittingOrCrashing() {
        var writes = 0
        val original = app.pictureshow.core.livetv.LiveSchedule(
            kotlinx.serialization.json.buildJsonObject {
                put("Name", kotlinx.serialization.json.JsonPrimitive("Generated series"))
                put("Days", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("Monday"))))
            }
        )
        compose.setContent {
            TouchTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    item { LiveScheduleEditor(original, true, false, false, false) { writes++ } }
                }
            }
        }
        compose.onNodeWithText("Monday: On").performScrollTo().performClick()
        compose.onNodeWithText("Review recording").performScrollTo().performClick()
        compose.onNodeWithText("Confirm recording").performScrollTo().performClick()
        compose.onNodeWithText("Select at least one valid day without duplicates.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithText("Review settings").performScrollTo().performClick()
        compose.onNodeWithText("Monday: Off").performScrollTo().performClick()
        compose.onNodeWithText("Review recording").performScrollTo().performClick()
        compose.onNodeWithText("Confirm recording").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }
}
