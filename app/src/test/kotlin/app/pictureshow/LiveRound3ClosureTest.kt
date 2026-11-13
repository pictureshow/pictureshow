/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
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
class LiveRound3ClosureTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")

    @Test fun emptyFavoritesGuideActionKeepsTabAndTimeWindow() {
        val vm = LiveTvViewModel(account, LiveFake(), now = { 0L })
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithContentDescription("Later").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Favorites").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val before = vm.state.value.windowStart
        compose.onNodeWithTag("live:empty-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:tab:Guide").assertIsSelected()
        compose.runOnIdle { assertEquals(before, vm.state.value.windowStart) }
        compose.onNodeWithText("No guide data").assertIsDisplayed()
    }

    @Test fun guideUsesAvailableWidthAndKeepsVisibleLabelsAfterResize() {
        val width = mutableStateOf(360.dp)
        val fake = LiveFake()
        val shows = (0..3).map { n ->
            fake.show.copy(
                id = "p$n",
                name = "Visible program $n",
                startDate = Instant.ofEpochMilli(n * 1_800_000L).toString(),
                endDate = Instant.ofEpochMilli(n * 1_800_000L + 1_680_000).toString()
            )
        }
        compose.setContent {
            TouchTheme {
                Column(Modifier.width(width.value).height(360.dp)) {
                    LiveGuide(
                        fake.channelRows, shows, account, false, 0, 0,
                        rememberScrollState(), rememberLazyListState(), null, false, {}, onOpen = {}
                    )
                }
            }
        }
        fun checkWidth() {
            val box = compose.onNodeWithTag("live:guide-channel:c", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(80.dp, box.right - box.left, "The guide breakpoint uses its available content, not the whole window")
        }
        checkWidth()
        compose.runOnIdle { width.value = 712.dp }
        checkWidth()
        compose.onNodeWithText("Visible program 2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("live:tick-label:2", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w853dp-h384dp-mdpi")
    fun shortGuideChromeLeavesThreeCompleteChannelRows() {
        val fake = LiveFake().apply {
            channelRows = (0..7).map { LiveChannel("c$it", "Channel $it", "$it") }
            show =
                show.copy(channelId = "c0")
        }
        val vm = LiveTvViewModel(account, fake, now = { 0L })
        var toolbarInset = 0.dp
        compose.setContent {
            TouchTheme {
                val density = LocalDensity.current
                toolbarInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
                Column(Modifier.width(712.dp).height(384.dp)) { LiveTvScreen(vm, account, false, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        val toolbar = compose.onNodeWithTag("live:touch-app-bar").getBoundsInRoot()
        assertEquals(48.dp, toolbar.bottom - toolbar.top - toolbarInset)
        val controls = compose.onNodeWithTag("live:compact-guide-controls").getBoundsInRoot()
        assertEquals(48.dp, controls.bottom - controls.top)
        val ruler = compose.onNodeWithTag("live:guide-ruler", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(24.dp, ruler.bottom - ruler.top)
        assertTrue(ruler.bottom <= 152.dp, "Guide chrome leaves three rows on a short screen: ruler bottom ${ruler.bottom}")
        val third = compose.onNodeWithTag("live:guide-row:c2", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(third.bottom <= 384.dp, "Third channel row must be fully visible: $third")
        val visible = compose.onNodeWithTag("live:guide-row:c2", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
        assertEquals(third, visible, "The third row must not be clipped by a scrolling ancestor")
        assertEquals(60.dp, visible.bottom - visible.top)
    }

    @Test fun tvGoToChannelsNavigatesFromNoGuideAndManagementDenied() = recoveryNavigates(true)

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneGoToChannelsNavigatesFromNoGuideAndManagementDenied() = recoveryNavigates(false)

    private fun recoveryNavigates(tv: Boolean) {
        val fake = LiveFake()
        val vm = LiveTvViewModel(account, fake)
        compose.setContent {
            if (tv) {
                PictureshowTheme { LiveTvScreen(vm, account, tv, {}, {}, {}) }
            } else {
                TouchTheme { LiveTvScreen(vm, account, tv, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("No guide data").assertIsDisplayed()
        compose.onNodeWithText("Go to channels").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:tab:Channels").assertIsSelected()
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.runOnIdle {
            fake.rights = fake.rights.copy(manage = false)
            vm.refresh()
        }
        compose.onNodeWithTag("live:tab:Schedules").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Recording schedules aren't available").assertIsDisplayed()
        compose.onNodeWithText("Go to channels").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:tab:Channels").assertIsSelected()
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, fake.created)
            assertEquals(0, fake.deleted)
        }
    }

    @Test fun tvNoGuideKeepsTheDateBarAtTheGridChannelWidth() {
        val vm = LiveTvViewModel(account, LiveFake())
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("No guide data").assertIsDisplayed()
        val date = compose.onNodeWithTag("live:guide-time").getUnclippedBoundsInRoot()
        assertEquals(200.dp, date.right - date.left)
    }

    @Test fun tvRecordingMenuUsesEqualRowsAndPlayFocusWithoutWriting() {
        var plays = 0
        val fake = LiveFake().apply {
            recordingRows = listOf(app.pictureshow.core.MediaItem("rec", "Blue Hour", "Episode", canDelete = true))
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, { plays++ }, {}, {}) } }
        compose.onNodeWithTag("live:tab:Recordings").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:r:rec").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        val rows = listOf("Play", "Delete recorded file…", "Close").map {
            compose.onNodeWithText(it).getUnclippedBoundsInRoot()
        }
        rows.forEach { assertEquals(48.dp, it.bottom - it.top) }
        assertEquals(1, rows.map { it.left }.distinct().size)
        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("Close").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(0, fake.deleted)
        }
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneOnceReviewDoesNotRepeatTheChannelTimeOrSubmit() {
        val schedule = app.pictureshow.core.livetv.LiveSchedule(
            kotlinx.serialization.json.buildJsonObject {
                put("Name", kotlinx.serialization.json.JsonPrimitive("Blue Hour"))
                put("ChannelName", kotlinx.serialization.json.JsonPrimitive("Aurora"))
                put("StartDate", kotlinx.serialization.json.JsonPrimitive("2026-11-13T12:00:00Z"))
                put("EndDate", kotlinx.serialization.json.JsonPrimitive("2026-11-13T12:28:00Z"))
            }
        )
        var writes = 0
        compose.setContent {
            TouchTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    item { LiveScheduleEditor(schedule, false, false, false, false) { writes++ } }
                }
            }
        }
        compose.onNodeWithText("Review recording").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Records this airing once.").assertIsDisplayed()
        compose.onNodeWithText("Confirm recording").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, writes) }
    }
}
