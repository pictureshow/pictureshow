/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveProgram
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi", shadows = [LiveClockPatternShadow::class])
class LiveLayoutReviewTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")

    @Test
    fun tvEditorWithNoPaddingFieldsHasARealInitialReviewFocus() {
        val schedule = app.pictureshow.core.livetv.LiveSchedule(
            kotlinx.serialization.json.buildJsonObject {
                put("Name", kotlinx.serialization.json.JsonPrimitive("Generated series"))
                put("RecordNewOnly", kotlinx.serialization.json.JsonPrimitive(true))
            }
        )
        compose.setContent { androidx.tv.material3.MaterialTheme { LiveScheduleEditor(schedule, true, true, false, false) {} } }
        compose.onNodeWithTag("live:editor:review").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun errorStatusOwnsItsTextColorInsideAnActuallyFocusedTvRow() {
        var expected = androidx.compose.ui.graphics.Color.Unspecified
        val schedule = app.pictureshow.core.livetv.LiveSchedule(
            kotlinx.serialization.json.buildJsonObject {
                put("Id", kotlinx.serialization.json.JsonPrimitive("failed"))
                put("Name", kotlinx.serialization.json.JsonPrimitive("Generated recording"))
                put("Status", kotlinx.serialization.json.JsonPrimitive("Error"))
            }
        )
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                expected = androidx.tv.material3.MaterialTheme.colorScheme.onErrorContainer
                LiveScheduleRow(schedule, true, Modifier.liveFocus("test-error", true)) { }
            }
        }
        compose.onNodeWithTag("live:test-error").assertIsFocused()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Failed", useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { it(layouts) }
        assertEquals(expected, layouts.single().layoutInput.style.color, "Focused rows must not replace status on-container color")
    }

    @Test
    fun channelWithoutGuideDoesNotPreviewAnotherChannelsProgramAndTabsStayOnTheirKeyline() {
        val now = Instant.now()
        val fake = LiveFake().apply {
            channelRows = listOf(LiveChannel("c", "With guide", "1"), LiveChannel("empty", "No programme channel", "2"))
            show = show.copy(startDate = now.minusSeconds(60).toString(), endDate = now.plusSeconds(1800).toString())
        }
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, true, {}, {}, {})
            }
        }
        // The UI's sampled action clock advances independently of asynchronous loading.
        compose.mainClock.advanceTimeBy(1_100)
        val channels = compose.onNodeWithTag("live:tab:Channels").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("live:c:empty").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        listOf("No programme channel", "No guide data").forEach { label ->
            compose.onNode(
                androidx.compose.ui.test.hasText(label) and
                    androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("live:guide-preview"))
            ).assertIsDisplayed()
        }
        val inactive = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Guide", useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { it(inactive) }
        assertEquals(
            LiveMuted,
            inactive.single().layoutInput.style.color,
            "Inactive TV tabs retain readable foreground while a channel owns focus"
        )
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertEquals(channels.top, compose.onNodeWithTag("live:tab:Guide").getUnclippedBoundsInRoot().top)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun bothTimeDirectionsAndDayPickerAreVisibleAt360dp() {
        val now = System.currentTimeMillis()
        var selected = 0L
        compose.setContent {
            TouchTheme { LiveGuideDateBar(liveGuideWindowStart(now) + LiveTvViewModel.WINDOW, now, false, true) { selected = it } }
        }
        val before = compose.onNodeWithContentDescription("Earlier").assertIsDisplayed().getUnclippedBoundsInRoot()
        val day = compose.onNodeWithTag("live:guide-day").assertIsDisplayed().getUnclippedBoundsInRoot()
        val after = compose.onNodeWithContentDescription("Later").assertIsDisplayed().getUnclippedBoundsInRoot()
        val reset = compose.onNodeWithTag("live:guide-now").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(before.right <= day.left && day.right <= after.left && after.right <= reset.left)
        assertTrue(reset.right <= 360.dp, "Time navigation must fit without horizontal scrolling")
        compose.onNodeWithContentDescription("Later").performClick()
        assertEquals(liveGuideWindowStart(now) + LiveTvViewModel.WINDOW * 2, selected)
        compose.onNodeWithTag("live:guide-day").performClick()
        compose.onNodeWithText("Guide day").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneProgramHasOneFullWidthWatchAndTwoEqualRecordingActions() {
        val now = System.currentTimeMillis()
        val show = LiveProgram(
            "p",
            "Generated series",
            "c",
            startDate = Instant.ofEpochMilli(now - 60_000).toString(),
            endDate = Instant.ofEpochMilli(now + 1_800_000).toString(),
            isSeries = true
        )
        var watched = 0
        var once = 0
        var series = 0
        compose.setContent {
            TouchTheme {
                Box(Modifier.padding(16.dp)) {
                    LiveProgramDetail(
                        show,
                        LiveTvState(
                            policy = LivePolicy(access = true, manage = true),
                            channels = listOf(LiveChannel("c", "Generated channel", "1"))
                        ),
                        account, false, true, now,
                        { watched++ }, { once++ }, { series++ }, { _, _ -> }
                    )
                }
            }
        }
        val watch = compose.onNodeWithTag("live:program:watch").assertIsDisplayed().getUnclippedBoundsInRoot()
        val record = compose.onNodeWithTag("live:program:record").assertIsDisplayed().getUnclippedBoundsInRoot()
        val rule = compose.onNodeWithTag("live:program:series").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(328.dp, watch.right - watch.left)
        assertEquals(record.top, rule.top, "Secondary recording actions share a row")
        assertEquals(record.right - record.left, rule.right - rule.left)
        assertTrue(record.top >= watch.bottom)
        compose.onNodeWithTag("live:program:watch").performClick()
        compose.onNodeWithTag("live:program:record").performClick()
        compose.onNodeWithTag("live:program:series").performClick()
        assertEquals(listOf(1, 1, 1), listOf(watched, once, series))
    }

    @Test
    fun focusedGuideCellPaintStaysInsideBothTheChannelBoundaryAndItsLane() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                Box(Modifier.fillMaxSize().background(LiveBackground)) {
                    LiveGuide(
                        listOf(LiveChannel("c", "Generated channel", "1")),
                        listOf(
                            LiveProgram(
                                "p",
                                "Generated show",
                                "c",
                                startDate = Instant.EPOCH.toString(),
                                endDate = Instant.ofEpochMilli(1_800_000).toString()
                            )
                        ),
                        account, true, 0, 900_000,
                        androidx.compose.foundation.rememberScrollState(), androidx.compose.foundation.lazy.rememberLazyListState(),
                        null, false, {}, onOpen = {}
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("live:p:p").assertIsFocused()
        val row = compose.onNodeWithTag("live:guide-row:c").getUnclippedBoundsInRoot()
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val y = ((row.top.value + row.bottom.value) / 2 - viewport.top.value).toInt()
        assertEquals(LiveBackground, pixels[202, y], "Scaled focus must leave a visible inset after the channel boundary")
        assertTrue(pixels[211, y].red > .5f, "The complete leading focus edge must remain visible")
        assertEquals(LiveBackground, pixels[250, (row.bottom - viewport.top).value.toInt()], "Focus may not spill into the next lane")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun twentyCharacterChannelNamesUseTwoLinesAndRetainTheirFullReaderNameAtLargeText() {
        val name = "Twenty character NBC"
        compose.setContent {
            TouchTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    LiveGuide(
                        listOf(LiveChannel("c", name, "321")), emptyList(), account, false, 0, 0,
                        androidx.compose.foundation.rememberScrollState(), androidx.compose.foundation.lazy.rememberLazyListState(),
                        null, false, {}, onOpen = {}
                    )
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { it(layouts) }
        assertEquals(2, layouts.single().layoutInput.maxLines)
        assertTrue(layouts.single().lineCount == 2, "A long channel name must use the available second line")
        compose.onNodeWithContentDescription("Channel 321, $name").assertIsDisplayed()
    }
}
