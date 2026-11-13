/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.livetv.LiveSchedule
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
class LiveTvReviewNavigationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = LiveSchedule(
        buildJsonObject {
            put("Name", "Generated series rule")
            put("PrePaddingSeconds", 60)
            put("PostPaddingSeconds", 120)
            put("RecordNewOnly", true)
            put("RecordAnyChannel", false)
            put("RecordAnyTime", true)
            put("SkipEpisodesInLibrary", true)
            put("KeepUpTo", 0)
            put("KeepUntil", "UntilDeleted")
            put("Priority", 0)
            put("Days", JsonArray(liveRecordingWeekdays.map(::JsonPrimitive)))
        }
    )

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun remoteReadsTheCompleteLargeFontReviewAndReturnsWithoutWriting() {
        // Foundation chooses the real 30% TV pivot from this feature, not from our tv Boolean.
        org.robolectric.Shadows.shadowOf(compose.activity.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        var platformSpec: BringIntoViewSpec? = null
        var writes = 0
        compose.setContent {
            platformSpec = LocalBringIntoViewSpec.current
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                PictureshowTheme {
                    Box(Modifier.fillMaxSize().background(LiveBackground).padding(top = 104.dp)) {
                        LazyColumn(Modifier.fillMaxSize().testTag("review:viewport"), contentPadding = PaddingValues(32.dp)) {
                            item { LiveScheduleEditor(original, true, true, false, false) { writes++ } }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(69.2f, platformSpec!!.calculateScrollDistance(200f, 48f, 436f), .01f, "Exercise the actual TV 30% pivot")
        }
        compose.onNodeWithText("Review recording").performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("live:editor:confirm").assertIsFocused()
        val reviewRows = listOf(
            "Start early", "End late", "Record", "Channel", "Time", "Episodes in library", "Keep", "Delete", "Days", "Priority"
        )
        reviewRows.forEach { title ->
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("live:summary:$title").assertIsDisplayed().assertIsFocused()
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("live:review:footer").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("live:summary:Priority").assertIsDisplayed()
        compose.onNodeWithTag("live:review:footer").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(0, writes, "Reading and activating a read-only stop never submits") }
        reviewRows.asReversed().forEach { title ->
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("live:summary:$title").assertIsDisplayed().assertIsFocused()
        }
        // Native spatial navigation chooses the nearer action; Left returns to Confirm.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Edit options").assertIsFocused()
        val actionViewport = compose.onNodeWithTag("review:viewport").getBoundsInRoot()
        val editHeading = compose.onNodeWithText(original.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            editHeading.top >= actionViewport.top,
            "Up naturally returns to Edit options; the complete heading must already be visible without an extra Left: $editHeading / $actionViewport"
        )
        val vectorFile = listOf(File("../spec/vectors/live-presentation.json"), File("spec/vectors/live-presentation.json")).first {
            it.isFile
        }
        val transitions = Json.parseToJsonElement(vectorFile.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.filter { it.getValue("operation").jsonPrimitive.content == "review-header-navigation" }
        assertEquals(2, transitions.size)
        fun action(key: String) = if (key ==
            "edit"
        ) {
            compose.onNodeWithText("Edit options")
        } else {
            compose.onNodeWithTag("live:editor:confirm")
        }
        transitions.forEach { vector ->
            val input = vector.getValue("input").jsonObject
            val expected = vector.getValue("expected").jsonObject
            action(input.getValue("from").jsonPrimitive.content).assertIsFocused()
            val key = when (input.getValue("direction").jsonPrimitive.content) {
                "left" -> Key.DirectionLeft
                "right" -> Key.DirectionRight
                else -> error("Unsupported native header navigation")
            }
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            action(expected.getValue("focusedAction").jsonPrimitive.content).assertIsFocused()
            val bounds = compose.onNodeWithText(original.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(
                expected.getValue("fullHeadingVisible").jsonPrimitive.boolean,
                bounds.top >= actionViewport.top,
                "Every first-row focus recipient must reveal the full heading under the real TV pivot: ${vector.getValue("id")} / $bounds"
            )
            compose.runOnIdle { assertEquals(expected.getValue("writes").jsonPrimitive.int, writes) }
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithTag("live:editor:confirm").assertIsDisplayed().assertIsFocused()
        val viewport = compose.onNodeWithTag("review:viewport").getBoundsInRoot()
        val heading = compose.onNodeWithText(original.name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            heading.top >= viewport.top,
            "Returning to the first action must reveal the complete heading below the pinned header: $heading / $viewport"
        )
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun staleTouchReviewRemainsReadableButCannotConfirm() {
        var writes = 0
        compose.setContent {
            TouchTheme {
                LazyColumn { item { LiveScheduleEditor(original, true, false, false, false, canSubmit = false) { writes++ } } }
            }
        }
        compose.onNodeWithText("Review recording").performScrollTo().performClick()
        compose.onNodeWithTag("live:editor:confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("live:summary:Days").performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.runOnIdle { assertEquals(0, writes) }
    }
}
