/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LivePolicy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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
        ).assertIsDisplayed()
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

    @Test fun longGuideTitleStaysInBoundedCellWithFullReaderDescription() {
        val title = "A generated program with a long title that must remain fully available to the screen reader. ".repeat(4).trim()
        val now = java.time.Instant.now()
        val fake = LiveFake().apply {
            show = show.copy(name = title, startDate = now.minusSeconds(60).toString(), endDate = now.plusSeconds(1_800).toString())
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent {
            androidx.tv.material3.MaterialTheme { LiveTvScreen(vm, account, true, {}, {}, {}) }
        }
        compose.onNodeWithText("Guide").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        val cell = compose.onNodeWithTag("live:p:p").performScrollTo()
        val bounds = cell.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top <= 120.dp, "Long labels must not expand a guide lane")
        cell.assert(hasContentDescription(title, substring = true))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("live:cell-title:p", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(2, layouts.single().layoutInput.maxLines, "Compact guide rows reserve two lines for the title")
        assertTrue(layouts.single().lineCount <= 2, "Long guide labels must remain within the rendered text budget")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun compactTvHeaderShowsContentAndRequestsRealInitialFocus() {
        val now = java.time.Instant.now()
        val fake = LiveFake().apply {
            show = show.copy(startDate = now.minusSeconds(60).toString(), endDate = now.plusSeconds(1800).toString())
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("live:tab:Channels").assertIsSelected()
        val row = compose.onNodeWithTag("live:c:c").assertIsDisplayed().assertIsFocused().getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("live:tv").getUnclippedBoundsInRoot()
        assertTrue(row.top - viewport.top <= 160.dp, "Initial channel must be visible below compact chrome: $row")
        compose.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun tvGuideShowsSixCompleteChannelsWithFocusedPreview() {
        val now = java.time.Instant.now()
        val fake = LiveFake().apply {
            channelRows = (1..8).map { app.pictureshow.core.livetv.LiveChannel("c$it", "Generated $it", "$it") }
            show = show.copy(
                channelId = "c1",
                startDate = now.minusSeconds(30).toString(),
                endDate = now.plusSeconds(1800).toString(),
                overview = "An original generated programme description for the focused preview."
            )
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { androidx.tv.material3.MaterialTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:p:p").assertIsFocused()
        compose.onNodeWithTag("live:guide-preview").assertIsDisplayed()
        val first = compose.onNodeWithTag("live:guide-row:c1").getUnclippedBoundsInRoot()
        val sixth = compose.onNodeWithTag("live:guide-row:c6").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(first.top <= 170.dp, "Guide chrome must leave room for six channels: $first")
        assertTrue(sixth.bottom <= 524.dp, "The sixth channel must fit completely in the safe viewport: $sixth")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneTabsStayOnOneLineAndChannelIsVisibleWithoutScrolling() {
        val vm = LiveTvViewModel(account, LiveFake())
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, {}, {}, {}) } }
        val first = compose.onNodeWithTag("live:tab:Channels").getUnclippedBoundsInRoot()
        val last = compose.onNodeWithTag("live:tab:Schedules").getUnclippedBoundsInRoot()
        assertEquals(first.top, last.top)
        listOf("Channels", "Guide", "Recordings", "Schedules").forEach { label ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }, "$label must fit at 360dp")
        }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.onNodeWithTag("live:tab:Guide").performClick().assertIsSelected()
    }

    @Test
    fun narrowGuideCellRetainsReaderLabelAndFocusWithoutClippedGlyphs() {
        val now = java.time.Instant.now()
        val fake = LiveFake().apply {
            show =
                show.copy(
                    name = "Short generated program",
                    startDate = now.minusSeconds(10).toString(),
                    endDate = now.plusSeconds(120).toString()
                )
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { androidx.tv.material3.MaterialTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:p:p").assertIsDisplayed().assert(hasContentDescription("Short generated program", substring = true))
        compose.onNodeWithTag("live:cell-title:p", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("live:p:p").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("live:p:p").assertIsFocused()
        val left = compose.onNodeWithTag("live:tick:0").getUnclippedBoundsInRoot().left
        val next = compose.onNodeWithTag("live:tick:1").getUnclippedBoundsInRoot().left
        assertEquals(180.dp, next - left)
    }

    @Test
    fun guideBackRestoresTheExactProgramFocusWithoutTuning() {
        val now = java.time.Instant.now()
        var tuned = 0
        val fake = LiveFake().apply {
            show = show.copy(startDate = now.minusSeconds(60).toString(), endDate = now.plusSeconds(1800).toString())
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { androidx.tv.material3.MaterialTheme { LiveTvScreen(vm, account, true, { tuned++ }, {}, {}) } }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:p:p").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("live:p:p").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("live:program:watch").assertIsDisplayed()
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("live:p:p").assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { assertEquals(0, tuned) }
    }

    @Test
    fun staleChannelTuneIsSemanticallyDisabledWhileCachedGuideRemainsReadable() {
        val fake = LiveFake()
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { androidx.tv.material3.MaterialTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("live:c:c").assertIsEnabled()
        compose.runOnIdle {
            fake.failPrograms = true
            vm.refresh()
        }
        compose.onNodeWithTag("live:c:c").assertIsNotEnabled()
        compose.onNodeWithText("Saved guide is for reference. Reconnect before scheduling or tuning.").assertIsDisplayed()
    }

    @Test
    fun phoneFavoriteIsACheckedTrailingActionAndDoesNotTuneTheParentRow() {
        var tuned = 0
        val fake = LiveFake()
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, { tuned++ }, {}, {}) } }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithContentDescription("Add favorite").performClick()
        compose.waitUntil(5_000) {
            !vm.state.value.loading &&
                !vm.state.value.writing &&
                vm.state.value.channels.single().userData.isFavorite
        }
        compose.onNodeWithContentDescription("Remove favorite").assertIsOn()
        compose.runOnIdle {
            assertEquals(1, fake.favoriteWrites)
            assertEquals(0, tuned)
        }
    }

    @Test
    fun linkedSeriesOpensExistingRuleInsteadOfCreatingAnotherFromThisEpisode() {
        val now = java.time.Instant.now()
        val fake = LiveFake().apply {
            show =
                show.copy(
                    isSeries = true,
                    seriesTimerId = "rule",
                    startDate = now.minusSeconds(30).toString(),
                    endDate = now.plusSeconds(1800).toString()
                )
            seriesRows = listOf(
                app.pictureshow.core.livetv.LiveSchedule(
                    kotlinx.serialization.json.buildJsonObject {
                        put("Id", kotlinx.serialization.json.JsonPrimitive("rule"))
                        put("Name", kotlinx.serialization.json.JsonPrimitive("Existing series"))
                    }
                )
            )
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("live:tab:Guide").performClick()
        compose.onNodeWithTag("live:p:p").performClick()
        compose.onNodeWithText("Manage series…").performScrollTo().performClick()
        compose.onNodeWithText("Recording schedule").assertIsDisplayed()
        compose.onNodeWithText("Existing series").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fake.created) }
    }

    @Test
    fun linearGuideNamesScheduledOccurrencesAndKeepsSeriesOnlyProgramsUnmarked() {
        val occurrence = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            TouchTheme {
                LiveProgramRow(
                    LiveFake().show.copy(timerId = if (occurrence.value) "timer" else null, seriesTimerId = "rule"),
                    false,
                    androidx.compose.ui.Modifier
                ) {}
            }
        }
        compose.onNodeWithText("Recording scheduled", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Generated program").assertIsDisplayed()
        compose.runOnIdle { occurrence.value = false }
        compose.onNodeWithText("Recording scheduled", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-mdpi")
    fun fullWidthFocusedChannelRetainsTheTvSafeMargin() {
        val vm = LiveTvViewModel(account, LiveFake())
        compose.setContent { androidx.tv.material3.MaterialTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.mainClock.advanceTimeBy(1_100)
        val row = compose.onNodeWithTag("live:c:c").assertIsFocused().getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val center = ((row.top.value + row.bottom.value) / 2 - root.top.value).toInt()
        assertEquals(LiveBackground, pixels[20, center], "Full-width focus must not scale into the32dp safe margin")
    }

    @Test
    fun liveTvKeepsItsHomeNavigationParentSelected() {
        assertEquals("home", touchTabFor("live-tv"))
        assertEquals("search", touchTabFor("search?query={query}&keyboard={keyboard}"))
        assertEquals("settings", touchTabFor("settings"))
    }

    @Test
    fun initialGuideFocusSkipsAnElapsedSlotInTheRoundedWindow() {
        val start = 1_800_000L
        fun program(id: String, from: Long, until: Long) = app.pictureshow.core.livetv.LiveProgram(
            id,
            id,
            "c",
            startDate = java.time.Instant.ofEpochMilli(from).toString(),
            endDate = java.time.Instant.ofEpochMilli(until).toString()
        )
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                LiveGuide(
                    listOf(
                        app.pictureshow.core.livetv.LiveChannel("empty", "No guide data", "1"),
                        app.pictureshow.core.livetv.LiveChannel("c", "Generated", "2")
                    ),
                    listOf(program("elapsed", start, start + 300_000), program("current", start + 300_000, start + 1_800_000)),
                    account, true, start, start + 900_000,
                    androidx.compose.foundation.rememberScrollState(), androidx.compose.foundation.lazy.rememberLazyListState(),
                    null, false, {}, onOpen = {}
                )
            }
        }
        compose.onNodeWithTag("live:p:current").assertIsFocused()
    }
}
