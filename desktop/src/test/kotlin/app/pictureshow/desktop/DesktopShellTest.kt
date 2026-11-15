/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.pictureshow.desktop

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import app.pictureshow.ui.PictureshowTheme
import kotlin.test.*

class DesktopShellTest {
    private val account = Account(Server("s1", "Living Room Server", "https://example.invalid", "10.11.0"), User("u1", "Mei"), "token")
    private fun libraries(n: Int) = (1..n).map { MediaItem(id = "lib$it", name = if (it == 4) "Recorded Shows and Specials from Last Year" else "Library $it", type = "CollectionFolder", isFolder = true) }

    /** The shell around the sidebar: a 52 dp title bar above it, as in [DesktopApp]. */
    private fun ComposeUiTest.shell(views: Int, livingRoom: Boolean, root: String? = DESKTOP_ROOT_HOME) = setContent {
        val base = LocalDensity.current
        PictureshowTheme {
            CompositionLocalProvider(LocalDensity provides Density(base.density * if (livingRoom) 1.25f else 1f, base.fontScale)) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(52.dp))
                    Row(Modifier.weight(1f)) { DesktopSidebar(account, libraries(views), root, {}, {}, {}, {}, {}) }
                }
            }
        }
    }

    /** Unclipped bounds in root pixels: a clipped node must not pass for a visible one. */
    private fun ComposeUiTest.unclipped(tag: String): androidx.compose.ui.geometry.Rect {
        val n = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return androidx.compose.ui.geometry.Rect(n.positionInRoot, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat()))
    }

    private fun ComposeUiTest.assertInsideWindow(tag: String) {
        val size = onRoot().fetchSemanticsNode().size
        val b = unclipped(tag)
        assertTrue(b.top >= 0f && b.bottom <= size.height && b.left >= 0f && b.right <= size.width && b.height > 0f,
            "$tag at $b lies outside the ${size.width}x${size.height} window")
    }

    @Test fun everyDestinationStaysInsideTheMinimumWindowWithLivingRoomSize() = runDesktopComposeUiTest(800, 600) {
        shell(views = 12, livingRoom = true)
        // Fixed destinations are pinned: the account menu (Switch account, Settings, Sign out) and Settings.
        assertInsideWindow("nav:account")
        assertInsideWindow("nav:settings")
        assertInsideWindow("nav:home")
    }

    @Test fun pinnedDestinationsAtTheDefaultWindow() = runDesktopComposeUiTest(1280, 800) {
        shell(views = 12, livingRoom = false)
        assertInsideWindow("nav:account")
        assertInsideWindow("nav:settings")
    }

    @Test fun focusScrollsAHiddenLibraryIntoView() = runDesktopComposeUiTest(800, 600) {
        shell(views = 12, livingRoom = true)
        val list = unclipped("sidebar-list")
        val last = onNodeWithTag("nav:library:lib12", useUnmergedTree = true)
        assertTrue(unclipped("nav:library:lib12").top >= list.bottom, "the 12th library should start below the visible list")
        last.requestFocus()
        waitForIdle()
        val b = unclipped("nav:library:lib12")
        assertTrue(b.top >= list.top && b.bottom <= list.bottom, "focused library at $b is outside the list $list")
        assertInsideWindow("nav:settings")
    }

    @Test fun litRootIsTheStoredOwner() = runDesktopComposeUiTest(1280, 800) {
        shell(views = 3, livingRoom = false, root = desktopLibraryRoot("lib2"))
        onNodeWithTag("nav:library:lib2", useUnmergedTree = true).assertIsSelected()
        onNodeWithTag("nav:home", useUnmergedTree = true).assertIsNotSelected()
        onNodeWithTag("nav:settings", useUnmergedTree = true).assertIsNotSelected()
    }

    @Test fun rootOwnership() {
        assertEquals(DESKTOP_ROOT_HOME, desktopRootFor(DesktopRoute.Home, desktopLibraryRoot("x")))
        assertEquals(DESKTOP_ROOT_SETTINGS, desktopRootFor(DesktopRoute.Settings, DESKTOP_ROOT_HOME))
        // Detail and nested folders keep the root they were opened from (a title opened from Home stays under Home).
        assertEquals(DESKTOP_ROOT_HOME, desktopRootFor(DesktopRoute.Detail, DESKTOP_ROOT_HOME))
        assertEquals(desktopLibraryRoot("x"), desktopRootFor(DesktopRoute.Library, desktopLibraryRoot("x")))
        assertEquals(desktopLibraryRoot("x"), desktopRootFor(DesktopRoute.Player, desktopLibraryRoot("x")))
    }

    @Test fun longLabelsWrapToTwoLinesAndOpenTheMenu() = runDesktopComposeUiTest(1280, 800) {
        shell(views = 4, livingRoom = false)
        onNodeWithText("Recorded Shows and Specials from Last Year", useUnmergedTree = true).assertExists()
        onNodeWithTag("nav:account").performClick()
        onNodeWithText("Switch account").assertExists()
        onNodeWithText("Sign out").assertExists()
        onAllNodesWithText("Settings", useUnmergedTree = true).assertCountEquals(2) // the menu item and the pinned item
    }

    @Test fun arrowsDoNotWalkOutOfTheBodyIntoTheTitleBar() = runDesktopComposeUiTest(800, 400) {
        lateinit var focus: FocusManager
        setContent {
            focus = LocalFocusManager.current
            Column {
                Row { Spacer(Modifier.weight(1f)); Button({}, Modifier.testTag("fullscreen")) { Text("Fullscreen") } }
                Row(Modifier.directionalFence()) {
                    Button({}, Modifier.testTag("a")) { Text("A") }
                    Button({}, Modifier.testTag("b")) { Text("B") }
                }
            }
        }
        onNodeWithTag("b").requestFocus()
        runOnIdle { focus.moveFocus(FocusDirection.Right) }
        onNodeWithTag("b").assertIsFocused()
        runOnIdle { focus.moveFocus(FocusDirection.Up) }
        onNodeWithTag("fullscreen").assertIsNotFocused()
        onNodeWithTag("b").assertIsFocused()
        runOnIdle { focus.moveFocus(FocusDirection.Left) }
        onNodeWithTag("a").assertIsFocused()
        // Tab still leaves the body.
        runOnIdle { focus.moveFocus(FocusDirection.Previous) }
        onNodeWithTag("fullscreen").assertIsFocused()
    }

    private fun act(key: Key, route: DesktopRoute, focused: Boolean = false, ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false, char: Char? = null, mac: Boolean = false, fullscreen: Boolean = false) =
        shellFallbackAction(ShellKeyPress(key, ctrl = ctrl, meta = meta, shift = shift, char = char), route, focused, fullscreen, mac, signedIn = true)

    @Test fun spaceAndArrowsActOnPlaybackOnlyWhenNoControlHasFocus() {
        assertEquals(ShellAction.TogglePlay, act(Key.Spacebar, DesktopRoute.Player))
        assertNull(act(Key.Spacebar, DesktopRoute.Player, focused = true), "a focused button keeps Space")
        assertEquals(ShellAction.SeekBack, act(Key.DirectionLeft, DesktopRoute.Player))
        assertEquals(ShellAction.Left, act(Key.DirectionLeft, DesktopRoute.Player, focused = true))
        assertEquals(ShellAction.SeekForward, act(Key.DirectionRight, DesktopRoute.Player))
        // Letters work with a control focused, never with a modifier.
        assertEquals(ShellAction.TogglePlay, act(Key.K, DesktopRoute.Player, focused = true))
        assertEquals(ShellAction.SeekBack, act(Key.J, DesktopRoute.Player, focused = true))
        assertEquals(ShellAction.SeekForward, act(Key.L, DesktopRoute.Player))
        assertEquals(ShellAction.Fullscreen, act(Key.F, DesktopRoute.Player))
        assertNull(act(Key.K, DesktopRoute.Player, ctrl = true))
        assertNull(act(Key.Spacebar, DesktopRoute.Home), "Space outside the player is the focused control's")
    }

    @Test fun helpSettingsAndEscape() {
        assertEquals(ShellAction.Help, act(Key.Slash, DesktopRoute.Home, shift = true))
        assertEquals(ShellAction.Help, act(Key.Unknown, DesktopRoute.Player, char = '?'))
        assertNull(act(Key.Slash, DesktopRoute.SignIn, shift = true), "? is typed text on sign-in")
        assertEquals(ShellAction.Settings, act(Key.Comma, DesktopRoute.Home, ctrl = true))
        assertEquals(ShellAction.Settings, act(Key.Comma, DesktopRoute.Detail, meta = true, mac = true))
        assertNull(act(Key.Comma, DesktopRoute.Home, meta = true), "Cmd+, is not the Windows/Linux shortcut")
        assertNull(act(Key.Comma, DesktopRoute.Player, ctrl = true), "Settings never interrupts playback")
        assertEquals(ShellAction.Back, act(Key.Escape, DesktopRoute.Library))
        assertEquals(ShellAction.ExitFullscreen, act(Key.Escape, DesktopRoute.Player, fullscreen = true))
        assertEquals(ShellAction.Down, act(Key.DirectionDown, DesktopRoute.Settings))
        assertNull(act(Key.DirectionDown, DesktopRoute.SignIn))
    }
}
