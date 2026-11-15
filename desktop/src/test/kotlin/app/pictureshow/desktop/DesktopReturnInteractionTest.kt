/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import app.pictureshow.core.*
import kotlin.test.*

class DesktopReturnInteractionTest {
    @Test fun escapeClosesEachMenuWithoutBackAndRestoresItsOpener() = runComposeUiTest {
        val owner = DesktopMenuOwner()
        var back = 0
        var label by mutableStateOf("Account")
        var open by mutableStateOf(false)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopMenus provides owner) {
                    val opener = remember { FocusRequester() }
                    Box(Modifier.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.Escape && owner.escape() }
                        .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { back++; true } else false }) {
                        Button({ open = true }, Modifier.focusRequester(opener).testTag("opener")) { Text(label) }
                        DesktopDropdownMenu(open, { open = false }, opener) { DropdownMenuItem(text = { Text("Option") }, onClick = {}) }
                    }
                }
            }
        }
        for (name in listOf("Account", "Video quality", "Sort", "Guide day", "Guide time", "Retention", "Audio", "Subtitles")) {
            runOnIdle { label = name }
            onNodeWithTag("opener").performClick()
            onNodeWithText("Option").assertExists().performKeyInput { pressKey(Key.Escape) }
            waitUntil { !open }
            waitForIdle()
            assertEquals(0, back, name)
            onNodeWithTag("opener").assertIsFocused()
        }
    }

    @Test fun escapeInDialogDropdownPreservesTheUnsavedDialog() = runComposeUiTest {
        var dialog by mutableStateOf(true)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopMenus provides remember { DesktopMenuOwner() }) {
                    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("Unsaved recording") },
                        text = { Choice("Retention", listOf(1 to "Keep", 2 to "Delete later"), 1, "retention") {} },
                        confirmButton = { TextButton({ dialog = false }) { Text("Cancel") } })
                }
            }
        }
        onNodeWithTag("retention").performClick()
        onNodeWithTag("retention:1").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertTrue(dialog, "Escape must close only the dropdown, preserving the edit")
        onNodeWithText("Unsaved recording").assertExists()
        onNodeWithTag("retention").assertIsFocused()
    }

    @Test fun topmostMenuWinsBeforeTheDialogOrBack() {
        val owner = DesktopMenuOwner(); val closed = mutableListOf<String>()
        val parent = Any(); val child = Any()
        owner.register(parent) { closed += "parent" }; owner.register(child) { closed += "child" }
        assertTrue(owner.escape()); assertEquals(listOf("child"), closed)
        owner.remove(child)
        assertTrue(owner.escape()); assertEquals(listOf("child", "parent"), closed)
        assertFalse(owner.escape())
    }

    @Test fun mouseEntryStartsInsideEverySidebarDestination() = runComposeUiTest {
        val account = Account(Server("s", "Demo", "https://example.invalid", "12"), User("u", "Viewer"), "token")
        var state by mutableStateOf(DesktopState())
        val destinations = listOf(DesktopRoute.Settings to "sign-out", DesktopRoute.Library to "grid:film", DesktopRoute.LiveTv to "live:tab:Channels",
            DesktopRoute.Detail to "play", DesktopRoute.Search to "search-field", DesktopRoute.Home to "card:latest:film")
        setContent {
            MaterialTheme {
                val page = state.route.name
                Column {
                    Button({ state = DesktopState() }, Modifier.testTag("header")) { Text("Back") }
                    Row {
                        Column { destinations.forEach { (route, _) -> Button({ state = DesktopState(route = route, account = account,
                            items = listOf(MediaItem("film")), homeRows = listOf(HomeRow("latest", "Latest", listOf(MediaItem("film")))) ) }, Modifier.testTag("nav:$route")) { Text(route.name) } } }
                        key(page) {
                            CompositionLocalProvider(LocalDesktopPage provides page) {
                                val target = destinations.firstOrNull { it.first == state.route }?.second
                                if (target != null) Button({}, Modifier.probe(target)) { Text("Page control") }
                                DesktopPageEntryFocus(state, page)

                            }
                        }
                    }
                }
            }
        }
        for ((route, target) in destinations) {
            onNodeWithTag("nav:$route").performClick()
            waitUntil(timeoutMillis = 3000) { DesktopUiProbe.focus[target] == true }
            onNodeWithTag(target).assertIsFocused()
            onNodeWithTag("header").assertIsNotFocused()
        }
    }

    @Test fun delayedSearchEntryPreservesFocusAlreadyInsideResults() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopPage provides "Search") {
                    Column {
                        Button({}, Modifier.probe("search-field")) { Text("Search field") }
                        Button({}, Modifier.probe("search:MOVIES:entry")) { Text("First result") }
                        DesktopPageEntryFocus(DesktopState(route = DesktopRoute.Search), "Search")
                    }
                }
            }
        }
        runOnIdle { DesktopUiProbe.requestFocus.getValue("search:MOVIES:entry").requestFocus() }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        onNodeWithTag("search:MOVIES:entry").assertIsFocused()
    }

    @Test fun delayedPageEntryDoesNotTakeFocusFromAModal() = runComposeUiTest {
        var blocked by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                Column {
                    CompositionLocalProvider(LocalDesktopPage provides "Settings") {
                        Button({}, Modifier.probe("sign-out")) { Text("Page action") }
                        DesktopPageEntryFocus(DesktopState(route = DesktopRoute.Settings), "Settings", blocked = blocked)
                    }
                    Button({}, Modifier.probe("modal-control")) { Text("Modal action") }
                }
            }
        }
        runOnIdle { blocked = true }
        mainClock.advanceTimeByFrame()
        runOnIdle { DesktopUiProbe.requestFocus.getValue("modal-control").requestFocus() }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        onNodeWithTag("modal-control").assertIsFocused()
        onNodeWithTag("sign-out").assertIsNotFocused()
    }

}
