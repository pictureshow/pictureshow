/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.pictureshow.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-5, D-7, D-10 and D-15 on the desktop catalog pages, driven by keys as a keyboard viewer drives them. */
class DesktopCatalogPagesTest {
    private val account = Account(Server("server", "Demo cinema", "http://127.0.0.1:9", "12.4.0"), User("viewer", "Viewer"), "token")
    private fun controller() = DesktopController(Files.createTempDirectory("pictureshow-catalog-test-").toFile())
    private fun movie(id: String) = MediaItem(id, "Film $id", "Movie", productionYear = 2026)
    private val rows = listOf(
        HomeRow("resume", "Continue watching", (1..4).map { movie("a$it") }),
        HomeRow("next", "Next up", (1..4).map { movie("b$it") }),
        HomeRow("latest", "Latest in Films", (1..4).map { movie("c$it") })
    )

    /** The real window's geometry: header with Fullscreen at the top right, a sidebar, then the page. */
    private fun ComposeUiTest.window(state: DesktopState, c: DesktopController, page: @androidx.compose.runtime.Composable () -> Unit = { HomePage(state, c) }): FocusManager {
        lateinit var focus: FocusManager
        setContent {
            MaterialTheme {
                focus = LocalFocusManager.current
                Column(Modifier.fillMaxSize()) {
                    Row { Spacer(Modifier.weight(1f)); Button({}, Modifier.testTag("fullscreen")) { Text("Fullscreen") } }
                    Row(Modifier.weight(1f)) {
                        Column { Button({}, Modifier.testTag("sidebar")) { Text("Home") } }
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { page() }
                    }
                }
            }
        }
        return focus
    }
    private fun ComposeUiTest.focused(tag: String) = onNodeWithTag(tag).assertIsFocused()
    private fun ComposeUiTest.move(focus: FocusManager, direction: FocusDirection) { runOnIdle { focus.moveFocus(direction) }; waitForIdle() }
    private fun ComposeUiTest.key(key: Key, shift: Boolean = false) {
        onRoot().performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
        waitForIdle()
    }
    private fun ComposeUiTest.focusedTag(): String? = onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.TestTag)

    @Test fun titleMetadataIdentifiesLibraryAndSourceHdr() = runComposeUiTest {
        controller().use { c ->
            val item = movie("hdr").copy(mediaStreams = listOf(app.pictureshow.core.playback.MediaStream(
                type = app.pictureshow.core.playback.MediaStreamType.Video, videoRangeType = app.pictureshow.core.playback.VideoRangeType.HDR10)))
            val state = DesktopState(route = DesktopRoute.Detail, account = account, detail = item, detailLibrary = "Open Movies")
            window(state, c) { DetailPage(state, c) }
            onNodeWithText("Open Movies · HDR10 · 2026").assertExists()
        }
    }

    @Test fun rightAndLeftStopAtTheRowEndsInsteadOfLeavingForTheHeader() = runComposeUiTest {
        controller().use { c ->
            val focus = window(DesktopState(route = DesktopRoute.Home, account = account, homeRows = rows), c)
            onNodeWithTag("card:resume:a4").requestFocus()
            focused("card:resume:a4")
            move(focus, FocusDirection.Right)
            focused("card:resume:a4")
            onNodeWithTag("fullscreen").assertIsNotFocused()
            onNodeWithTag("card:next:b1").requestFocus()
            move(focus, FocusDirection.Left)
            focused("card:next:b1")
            move(focus, FocusDirection.Right)
            focused("card:next:b2")
        }
    }

    @Test fun upFromTheFirstRowGoesToThePageHeadingAndNoFurther() = runComposeUiTest {
        controller().use { c ->
            val focus = window(DesktopState(route = DesktopRoute.Home, account = account, homeRows = rows), c)
            onNodeWithTag("card:resume:a4").requestFocus()
            move(focus, FocusDirection.Up)
            focused("home-heading")
            move(focus, FocusDirection.Up)
            focused("home-heading")
            onNodeWithTag("fullscreen").assertIsNotFocused()
            move(focus, FocusDirection.Down)
            assertTrue(focusedTag().orEmpty().startsWith("card:resume:"), "Down from the heading enters the first row, not ${focusedTag()}")
        }
    }

    @Test fun eachRowIsOneTabStopThatRestoresItsLastCard() = runComposeUiTest {
        controller().use { c ->
            window(DesktopState(route = DesktopRoute.Home, account = account, homeRows = rows), c)
            onNodeWithTag("card:resume:a3").requestFocus()
            key(Key.Tab)
            assertEquals("card:next:b1", focusedTag())
            key(Key.Tab)
            focused("card:latest:c1")
            key(Key.Tab, shift = true)
            assertEquals("card:next:b1", focusedTag())
            key(Key.Tab, shift = true)
            assertEquals("card:resume:a3", focusedTag())
            // From the last row, Shift+Tab reaches the sidebar within rows + 2 presses (D-7 acceptance).
            onNodeWithTag("home").performScrollToNode(hasTestTag("card:latest:c2"))
            onNodeWithTag("card:latest:c2").requestFocus()
            var presses = 0
            while (focusedTag() != "sidebar" && presses < rows.size + 2) { key(Key.Tab, shift = true); presses++ }
            focused("sidebar")
            assertTrue(presses <= rows.size + 2, "Shift+Tab took $presses presses")
        }
    }

    @Test fun liveTvOpensNativeTabsInsteadOfAnInterimPageOrEmptyLibrary() = runComposeUiTest {
        controller().use { c ->
            val live = MediaItem("live", "Live TV", "UserView", collectionType = "livetv", isFolder = true)
            window(DesktopState(route = DesktopRoute.Library, account = account, library = live), c) { LibraryPage(DesktopState(route = DesktopRoute.Library, account = account, library = live), c) }
            onNodeWithText("Channels").assertExists()
            onNodeWithText("Guide").assertExists()
            onNodeWithText("Recordings").assertExists()
            onNodeWithText("Schedules").assertExists()
            onNodeWithText("Live TV is coming to the desktop app").assertDoesNotExist()
            onNodeWithText("Loading Live TV…").assertExists()
            onNodeWithText("This library is empty.").assertDoesNotExist()
            onNodeWithText("0 items").assertDoesNotExist()
            onNodeWithTag("sort-direction").assertDoesNotExist()
        }
    }

    @Test fun episodeRowsShowAndAnnounceWatchedAndResumeState() = runComposeUiTest {
        controller().use { c ->
            val series = MediaItem("series", "A Show", "Series")
            val watched = MediaItem("e1", "Pilot", "Episode", seriesName = "A Show", parentIndexNumber = 1, indexNumber = 1, runTimeTicks = 310_000_000, userData = ItemUserData(played = true))
            val resumed = MediaItem("e2", "Second", "Episode", seriesName = "A Show", parentIndexNumber = 1, indexNumber = 2, runTimeTicks = 13_000_000_000, userData = ItemUserData(playbackPositionTicks = 6_000_000_000))
            val fresh = MediaItem("e3", "Third", "Episode", seriesName = "A Show", parentIndexNumber = 1, indexNumber = 3, runTimeTicks = 13_000_000_000)
            val state = DesktopState(route = DesktopRoute.Detail, account = account, detail = series, episodes = listOf(watched, resumed, fresh))
            window(state, c) { DetailPage(state, c) }
            onNodeWithTag("episode:e1").assertContentDescriptionContains("Watched", substring = true).assertContentDescriptionContains("31 s", substring = true)
            onNodeWithTag("episode:e2").assertContentDescriptionContains("Resume from 10:00", substring = true)
            val third = onNodeWithTag("episode:e3").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue("Watched" !in third && "Resume" !in third, third)
            assertEquals(1, onAllNodesWithTag("catalog:watched", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithTag("catalog:resume", useUnmergedTree = true).fetchSemanticsNodes().size)
            onNodeWithText("31 s", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun libraryTilesWithoutArtShowTheirTypeIconNotALetter() = runComposeUiTest {
        controller().use { c ->
            val libraries = HomeRow("libraries", "Libraries", listOf(
                MediaItem("films", "Films", "CollectionFolder", collectionType = "movies", isFolder = true),
                MediaItem("shows", "Shows", "CollectionFolder", collectionType = "tvshows", isFolder = true),
                MediaItem("live", "Live TV", "UserView", collectionType = "livetv", isFolder = true)
            ), library = true)
            window(DesktopState(route = DesktopRoute.Home, account = account, homeRows = listOf(libraries)), c)
            for (kind in listOf("movies", "shows", "livetv")) onNodeWithTag("catalog:type-icon:$kind", useUnmergedTree = true).assertExists()
            onNodeWithText("F", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("L", useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
