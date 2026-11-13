/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
class LiveDirectorRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "http://example.test", "12"), User("u", "Viewer"), "token")

    @Test fun tvDeleteHasSafeInitialFocusAndNoRefreshOrWriteBeforeConfirmation() = deletion(true)

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneDeleteHasExplicitCancelAndNoRefreshOrWriteBeforeConfirmation() = deletion(false)

    private fun deletion(tv: Boolean) {
        var writes = 0
        var dismissals = 0
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            LiveRecordingDeleteDialog("Own episode", tv, false, null, { dismissals++ }, { writes++ })
        }
        compose.setContent { if (tv) PictureshowTheme { content() } else TouchTheme { content() } }
        compose.onNodeWithText("Delete this recording?").assertIsDisplayed()
        compose.onNodeWithText("“Own episode” will be permanently deleted. Recording schedules stay in place.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        val cancel = compose.onNodeWithTag("live:delete:cancel").assertIsDisplayed()
        val delete = compose.onNodeWithTag("live:delete:confirm").assertIsDisplayed()
        if (tv) cancel.assertIsFocused()
        assertEquals(0, writes)
        cancel.performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, dismissals)
        assertEquals(0, writes)
        delete.performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, writes)
    }

    @Test fun scrolledTvCellKeepsItsTitleInsideTheVisibleTimeViewport() = stickyTitle(true, 120, true)

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneSliverOmitsTheTitleButRetainsItsProgrammeName() = stickyTitle(false, 660, false)

    private fun stickyTitle(tv: Boolean, offset: Int, visible: Boolean) {
        lateinit var scroll: ScrollState
        lateinit var scope: CoroutineScope
        val show =
            LiveProgram(
                "p",
                "A long programme title",
                "c",
                startDate = Instant.EPOCH.toString(),
                endDate = Instant.ofEpochMilli(7_200_000).toString()
            )
        compose.setContent {
            scroll = rememberScrollState()
            scope = rememberCoroutineScope()
            PictureshowTheme {
                Column(Modifier.height(400.dp)) {
                    LiveGuide(
                        listOf(LiveChannel("c", "Channel", "1")), listOf(show), account, tv, 0, 1_800_000,
                        scroll, rememberLazyListState(), "no-initial-focus", false, {}, onOpen = {}
                    )
                }
            }
        }
        compose.runOnIdle { scope.launch { scroll.scrollTo(offset) } }
        compose.waitForIdle()
        assertEquals(offset, compose.runOnIdle { scroll.value })
        if (visible) {
            val row = compose.onNodeWithTag("live:guide-row:c").getUnclippedBoundsInRoot()
            val title = compose.onNodeWithTag("live:cell-title:p", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue(
                title.left >= row.left + 208.dp,
                "A left-clipped cell starts its text at the visible viewport, not underneath the channel column: $title / $row"
            )
            assertTrue(title.right <= row.right)
        } else {
            compose.onNodeWithTag("live:cell-title:p", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("A long programme title", substring = true).assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun landscapeChannelsCollapseTheirAppBarAndScrollTheFilterWithContent() {
        val fake = LiveFake().apply { channelRows = (1..12).map { LiveChannel("c$it", "Channel $it", "$it") } }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, {}, {}, {}) } }
        val initialBar = compose.onNodeWithTag("live:touch-app-bar").getUnclippedBoundsInRoot()
        val filters = compose.onNodeWithTag("live:filters").getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag("live:content-list").getUnclippedBoundsInRoot()
        assertTrue(filters.top >= content.top, "The filter belongs to the scrollable channel content in compact height")
        compose.onNodeWithTag("live:content-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("live:touch-app-bar").getUnclippedBoundsInRoot()
        assertTrue(after.bottom < initialBar.bottom - 16.dp, "Native nested scrolling collapses landscape chrome: $initialBar -> $after")
    }

    @Test fun resumedRecordingActionsStaySingleLineAndInPlaybackFirstOrderAtLargeFont() {
        val item = MediaItem(
            "r",
            "Recorded episode",
            "Episode",
            runTimeTicks = 1_200_000_000,
            canDownload = true,
            recordingStatus = "Completed",
            userData = ItemUserData(playbackPositionTicks = 450_000_000)
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                PictureshowTheme {
                    CatalogDetail(
                        CatalogDetailState(account, item, loading = false),
                        {},
                        {},
                        {},
                        {},
                        {},
                        recordingContext = true,
                        onDownload = { _, _, _ -> }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("detail:play-label", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertEquals(1, layouts.single().lineCount, "Resume time stays on its primary button's single line")
        val bounds = listOf("play", "from-start", "download", "played", "favorite").map {
            compose.onNodeWithTag("detail:$it").assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue(a.right <= b.left, "Playback choices precede download and library toggles without wrapping: $bounds")
        }
        assertTrue(bounds.last().right <= 912.dp)
        bounds.forEach { assertTrue(kotlin.math.abs(it.top.value - bounds.first().top.value) <= 3) }
    }
}
