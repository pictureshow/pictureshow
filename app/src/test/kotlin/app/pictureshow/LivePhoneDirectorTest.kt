/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveSchedule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
@Config(sdk = [30], application = Application::class, qualifiers = "w384dp-h853dp-mdpi")
class LivePhoneDirectorTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = LiveSchedule(
        buildJsonObject {
            put("Name", "Generated series")
            put("PrePaddingSeconds", 60)
            put("PostPaddingSeconds", 120)
        }
    )

    @Test fun unknownActionIconsNeverBecomeFavoritesAndWarningUsesItsOwnGlyph() {
        assertTrue((touchActionIcon("heart").root[0] as VectorPath).pathData.isNotEmpty())
        assertTrue((touchActionIcon("heart-outline").root[0] as VectorPath).pathData.isNotEmpty())
        assertTrue((touchActionIcon("missing-action").root[0] as VectorPath).pathData.isEmpty())
        assertEquals("warning", liveGuideWarningIcon().name)
    }

    @Test fun unchangedScheduleCanBeInspectedButCannotWriteThenChangedReviewSavesOnce() {
        var writes = 0
        compose.setContent { TouchTheme { LazyColumn { item { LiveScheduleEditor(original, true, false, false, true) { writes++ } } } } }
        compose.onNodeWithText("Review schedule").performClick()
        compose.onNodeWithText("Save changes").assertIsNotEnabled()
        compose.onNodeWithText("Save changes to this series rule. Completed recordings are kept.").assertIsDisplayed()
        compose.onNodeWithTag("live:review-receipt").assertIsDisplayed()
        val title = compose.onNodeWithText("Start early", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val value = compose.onNodeWithText("1 min", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(title.right <= value.left, "Receipt labels and values use separate horizontal columns")
        compose.onNodeWithText("Edit options").performClick()
        compose.onNodeWithText("Start early").performClick()
        compose.onNodeWithTag("live:menu-choice:1").assertIsSelected()
        compose.onNodeWithTag("live:menu-choice:3").performClick()
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("Save changes").assertIsEnabled()
        assertEquals(0, writes)
        compose.onNodeWithText("Save changes").performClick()
        assertEquals(1, writes)
    }

    @Test fun recordingOverflowIsAnchoredAndOnlyDeleteSelectionOpensConfirmation() {
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "token")
        val fake = LiveFake().apply { recordingRows = listOf(MediaItem("r", "Own recording", "Episode", canDelete = true)) }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Recordings").performClick()
        compose.onNodeWithContentDescription("Recording options for Own recording").performClick()
        compose.onNodeWithTag("live:recording:menu:r").assertIsDisplayed()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Delete this recording?").assertDoesNotExist()
        compose.onNodeWithTag("live:recording:delete:r").performClick()
        compose.onNodeWithText("Delete this recording?").assertIsDisplayed()
        compose.onNodeWithTag("live:delete:cancel").performClick()
        compose.onNodeWithText("Delete this recording?").assertDoesNotExist()
    }

    @Test fun touchShelfHeadingsGroupWithTheirOwnCardsWithoutTvFocusPadding() {
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "token")
        val state = LiveTvState(loading = false, recordings = listOf(MediaItem("r", "Own recording", "Episode", seriesName = "Series")))
        compose.setContent { TouchTheme { LazyColumn { liveRecordingShelves(state, account, false, false, null, 0, {}, {}, {}) } } }
        val heading = compose.onNodeWithTag("live:recording-heading:Series", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("live:r:r").getUnclippedBoundsInRoot()
        assertTrue(card.top - heading.bottom <= 12.dp, "TV focus space must not separate a touch shelf heading from its cards")
    }

    @Test fun togglingOriginalWeekdaysOffAndOnDoesNotEnableSave() {
        val schedule = LiveSchedule(
            kotlinx.serialization.json.JsonObject(
                original.fields + (
                    "Days" to
                        kotlinx.serialization.json.JsonArray(
                            listOf(kotlinx.serialization.json.JsonPrimitive("Monday"), kotlinx.serialization.json.JsonPrimitive("Friday"))
                        )
                    )
            )
        )
        compose.setContent { TouchTheme { LazyColumn { item { LiveScheduleEditor(schedule, true, false, false, true) {} } } } }
        compose.onNodeWithText("Monday: On").performScrollTo().performClick()
        compose.onNodeWithText("Monday: Off").performClick()
        compose.onNodeWithText("Review schedule").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").assertIsNotEnabled()
        compose.onNodeWithText("Monday, Friday").assertIsDisplayed()
    }

    @Test fun disabledChannelRowsLookDisabledAndRetainTheirGuard() {
        val enabled = mutableStateOf(true)
        val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "token")
        compose.setContent {
            TouchTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LiveChannelRow(
                        LiveChannel("c", "Channel", "1"), emptyList(), account, false, 0, Modifier,
                        canFavorite = true, favoriteEnabled = enabled.value, tuneEnabled = enabled.value, onFavorite = {}, onTune = {}
                    )
                }
            }
        }
        fun maximum(): Float {
            val pixels = compose.onNodeWithText("Channel", useUnmergedTree = true).captureToImage().toPixelMap()
            return (0 until pixels.width).maxOf { x -> (0 until pixels.height).maxOf { y -> pixels[x, y].red } }
        }
        val bright = maximum()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("Channel").assertIsNotEnabled()
        assertTrue(maximum() < bright * .65f, "An inert row must visibly differ from an enabled channel")
    }
}
