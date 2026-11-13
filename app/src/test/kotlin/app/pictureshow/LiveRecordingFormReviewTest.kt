/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import app.pictureshow.core.livetv.LiveSchedule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
class LiveRecordingFormReviewTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val original = LiveSchedule(
        buildJsonObject {
            put("Name", "Generated recording")
            put("PrePaddingSeconds", 60)
            put("PostPaddingSeconds", 120)
            put("Priority", 1)
            put("Days", JsonArray(listOf(JsonPrimitive("Monday"), JsonPrimitive("Friday"))))
        }
    )

    @Test fun disclosureHasButtonRoleAndExpansionStateInsteadOfFilterState() {
        compose.setContent { TouchTheme { LazyColumn { item { LiveScheduleEditor(original, true, false, false, false) {} } } } }
        val more = compose.onNodeWithTag("live:editor:more").performScrollTo()
        more.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        compose.onNodeWithText("Collapsed", useUnmergedTree = true).assertDoesNotExist()
        more.performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Expanded", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("High").performScrollTo().assertIsDisplayed()
    }

    @Test fun reviewIsReadableNoninteractiveSummaryWithOneCommitAndNoHiddenOptions() {
        var writes = 0
        var expectedColor = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            TouchTheme {
                expectedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                LazyColumn { item { LiveScheduleEditor(original, true, false, false, false) { writes++ } } }
            }
        }
        compose.onNodeWithText("Review recording").performScrollTo().performClick()
        compose.onNodeWithTag("live:editor:more").assertDoesNotExist()
        listOf("Start early", "End late", "Days", "Priority").forEach { title ->
            val row = compose.onNodeWithTag("live:summary:$title").performScrollTo()
            row.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(expectedColor, layouts.single().layoutInput.style.color, title)
        }
        compose.onNodeWithText("Monday, Friday").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("High").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, writes) }
        val confirm = compose.onNodeWithText("Confirm recording").performScrollTo().getUnclippedBoundsInRoot()
        val edit = compose.onNodeWithText("Edit options").getUnclippedBoundsInRoot()
        assertEquals(confirm.top, edit.top, "The primary and secondary actions share one row at360dp")
        compose.onNodeWithText("Confirm recording").performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvSecondaryActionRemainsOutlinedWhenAnotherControlHasFocus() {
        compose.setContent {
            PictureshowTheme {
                Column(Modifier.fillMaxSize().background(LiveBackground)) {
                    LiveAction("Confirm recording", true, Modifier.testTag("primary")) {}
                    LiveAction("Edit options", true, Modifier.testTag("secondary"), primary = false) {}
                    LiveAction("Elsewhere", true, Modifier.testTag("elsewhere")) {}
                }
            }
        }
        compose.onNodeWithTag("elsewhere").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(500)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        fun fill(tag: String): androidx.compose.ui.graphics.Color {
            val rect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            return pixels[(rect.left - root.left).value.toInt() + 12, ((rect.top + rect.bottom) / 2 - root.top).value.toInt()]
        }
        assertEquals(LiveBackground, fill("secondary"), "Secondary remains transparent inside its outline")
        assertNotEquals(LiveBackground, fill("primary"), "The commit retains its filled container away from focus")
        assertFalse(
            compose.onNodeWithTag("secondary").fetchSemanticsNode().config.contains(SemanticsProperties.Focused) &&
                compose.onNodeWithTag("secondary").fetchSemanticsNode().config[SemanticsProperties.Focused]
        )
    }
}
