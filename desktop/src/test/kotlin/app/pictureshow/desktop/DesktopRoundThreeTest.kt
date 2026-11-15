/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import kotlin.test.*

class DesktopRoundThreeTest {
    // Popup scenes retain the opener's focus path in their parent scene. Assert active item focus
    // and navigation here; opener-ring suppression is a rendering property checked in native captures.
    private val account = Account(Server("s", "Demo", "https://example.invalid", "12"), User("u", "Viewer"), "token")

    @Test fun accountMenuOpensOnSwitchAndOneDownGoesToSettings() = runComposeUiTest {
        var switched = 0; var settings = 0; var signedOut = 0
        setContent { MaterialTheme { DesktopAccountMenu(account, { settings++ }, { switched++ }, { signedOut++ }) } }
        onNodeWithTag("nav:account").requestFocus().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("account-menu:switch").assertIsFocused()
        onNodeWithTag("account-menu:switch").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("account-menu:settings").assertIsFocused()
        onNodeWithTag("account-menu:settings").performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.Enter) }
        assertEquals(1, switched); assertEquals(0, settings); assertEquals(0, signedOut)
    }

    @Test fun choiceMenusStartOnSelectedItemAndMoveToTheNextItem() = runComposeUiTest {
        var tag by mutableStateOf("quality")
        setContent { MaterialTheme { Choice("Choice", listOf(0 to "Original", 4 to "4 Mbps", 10 to "10 Mbps"), 4, tag) {} } }
        for (name in listOf("quality", "sort")) {
            runOnIdle { tag = name }
            onNodeWithTag(name).requestFocus().performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("$name:4").assertIsFocused()
            onNodeWithTag("$name:4").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag("$name:10").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(name).assertIsFocused()
        }
    }

    @Test fun trackMenusStartOnCurrentTrackRatherThanContainer() = runComposeUiTest {
        var open by mutableStateOf(false)
        var tag by mutableStateOf("audio")
        setContent { MaterialTheme { TrackMenu("Tracks", tag, DesktopGlyph.Audio, listOf(1 to "One", 2 to "Two", 3 to "Three"), 2,
            "Two", null, open, { open = true }, { open = false }, 500) {} } }
        for (name in listOf("audio", "subtitles")) {
            runOnIdle { tag = name }
            onNodeWithTag(name).requestFocus().performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("$name:2").assertIsFocused()
            onNodeWithTag("$name:2").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag("$name:3").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag(name).assertIsFocused()
        }
    }

    @Test fun emptyAudioChoiceIsDisabledRatherThanOpeningWithoutAFocusTarget() = runComposeUiTest {
        setContent { MaterialTheme { TrackMenu("Audio", "audio", DesktopGlyph.Audio, emptyList(), -1, "No audio", null, false, {}, {}, 500) {} } }
        onNodeWithTag("audio").assertIsNotEnabled()
    }

    @Test fun retentionMenuStartsOnSelectedValueAndDownSelectsNextItem() = runComposeUiTest {
        val original = app.pictureshow.core.livetv.LiveSchedule(kotlinx.serialization.json.buildJsonObject {
            put("Name", kotlinx.serialization.json.JsonPrimitive("Morning show"))
            put("KeepUntil", kotlinx.serialization.json.JsonPrimitive("UntilDeleted"))
        })
        setContent { MaterialTheme { DesktopLiveScheduleEditor(original, true, false, false, true, {}, {}) } }
        onNodeWithTag("live:retention").performScrollTo().requestFocus().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("live:retention:UntilDeleted").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("live:retention:UntilSpaceNeeded").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("live:retention").assertIsFocused()
    }

    @Test fun guideDayAndTimeMenusStartOnSelectedValues() = runComposeUiTest {
        val directory = java.nio.file.Files.createTempDirectory("desktop-guide-focus-").toFile()
        val controller = DesktopController(directory)
        try {
            val zone = java.time.ZoneId.systemDefault()
            val day = java.time.LocalDate.now()
            val start = day.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
            val state = DesktopLiveState(start = start)
            setContent { MaterialTheme { Column { DesktopGuide(state, emptyList(), account, start, controller.liveTv) } } }
            onNodeWithTag("live:guide-date").performClick()
            onNodeWithTag("live:guide-day").requestFocus().performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("live:guide-day:$day").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag("live:guide-day:${day.plusDays(1)}").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("live:guide-day").assertIsFocused()
            onNodeWithTag("live:guide-time").requestFocus().performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("live:guide-time:$start").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag("live:guide-time:${start + 1_800_000}").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("live:guide-time").assertIsFocused()
        } finally { controller.close(); directory.deleteRecursively() }
    }

    @Test fun signOutConfirmationDefaultsToCancelAndExplainsPassword() = runComposeUiTest {
        var open by mutableStateOf(true); var signedOut = 0
        setContent { MaterialTheme { if (open) SignOutConfirmation("Viewer", { open = false }, { signedOut++; open = false }) } }
        onNodeWithText("Sign out of Viewer?").assertExists()
        onNodeWithText(SIGN_OUT_CONSEQUENCE_COPY).assertExists()
        onNodeWithTag("sign-out-cancel").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertEquals(0, signedOut); assertFalse(open)
    }

    @Test fun noRouteEntryOrRememberedDestructiveControlCanBeDefault() {
        for (route in DesktopRoute.entries) {
            val state = DesktopState(route=route, account=account, accounts=listOf(account), items=listOf(MediaItem("film")),
                homeRows=listOf(HomeRow("latest", "Latest", listOf(MediaItem("film")))))
            assertFalse(desktopEntryTarget(state)?.let(::destructiveEntryTarget) ?: false, route.name)
        }
        assertEquals("quality", desktopEntryTarget(DesktopState(route=DesktopRoute.Settings)))
        for (tag in listOf("sign-out", "accounts-sign-out", "remove:key", "remove-confirm", "account-menu:sign-out")) assertTrue(destructiveEntryTarget(tag), tag)
    }

    @Test fun cancelledEndCardLeadsWithFinishedAndOffersPlayNext() = runComposeUiTest {
        val directory = java.nio.file.Files.createTempDirectory("desktop-end-card-").toFile()
        val controller = DesktopController(directory)
        try {
            val next = MediaItem("next", "Second episode", "Episode")
            val item = MediaItem("first", "First episode", "Episode")
            val decision = PlayDecision("first", MediaSourceInfo(), PlayMethod.DirectPlay, "https://example.invalid/video", emptyMap(), null)
            var end by mutableStateOf(DesktopEndCard(next, 8))
            setContent { MaterialTheme { EndCard(DesktopPlaybackState(item, decision), end, controller) } }
            onNodeWithText("Up next · Playing in 8 s").assertExists()
            onNodeWithText("First episode finished").assertDoesNotExist()
            onNodeWithText("Play now").assertExists()
            runOnIdle { end = DesktopEndCard(next, null) }
            onNodeWithText("First episode finished").assertExists()
            onNodeWithText("Up next · Playing in 8 s").assertDoesNotExist()
            onNodeWithText("Play now").assertDoesNotExist()
            onNodeWithText("Play next").assertExists()
            onNodeWithText("Back to title").assertExists()
            assertTrue(onNodeWithText("First episode finished").fetchSemanticsNode().boundsInRoot.top <
                onNodeWithText("Next: Episode · Second episode").fetchSemanticsNode().boundsInRoot.top, "Finished title leads the secondary next suggestion")
        } finally { controller.close(); directory.deleteRecursively() }
    }

    @Test fun popupFocusOutlineHasAllFourSidesInsideTheClippedRow() = runComposeUiTest {
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color.Magenta)) {
            Choice("Quality", listOf(0 to "Original", 4 to "4 Mbps"), 0, "quality") {}
        } }
        onNodeWithTag("quality").requestFocus().performKeyInput { pressKey(Key.Enter) }
        val row = onNodeWithTag("quality:0").assertIsFocused()
        val pixels = row.captureToImage().toPixelMap()
        fun ink(x: Int, y: Int): Boolean {
            val c = pixels[x,y]
            return c.red > .8f && c.green < .2f && c.blue > .8f
        }
        val midX = pixels.width / 2; val midY = pixels.height / 2
        val horizontalBand = (pixels.width / 8).coerceAtLeast(8)
        val verticalBand = (pixels.height / 4).coerceAtLeast(8)
        assertTrue((0 until horizontalBand).any { ink(it, midY) }, "left side must be inside the row clip")
        assertTrue((pixels.width-horizontalBand until pixels.width).any { ink(it, midY) }, "right side must be inside the row clip")
        assertTrue((0 until verticalBand).any { ink(midX, it) }, "top side must not be clipped")
        assertTrue((pixels.height-verticalBand until pixels.height).any { ink(midX, it) }, "bottom side must not be clipped")
    }

    @Test fun originalQualitySupportingLineFitsWithoutAnOrphan() = runComposeUiTest {
        setContent { MaterialTheme { Choice("Quality", listOf(0 to VIDEO_QUALITY_ORIGINAL, 4 to "4 Mbps"), 0, "quality", menuMinWidth=340.dp) {} } }
        onNodeWithTag("quality").performClick()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        onNodeWithText("Keep original quality when supported", useUnmergedTree=true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertTrue(layouts.single().lineCount <= 2)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test fun shortcutsFrameFitsInsideA600PixelWindowWithCloseVisible() = runDesktopComposeUiTest(800,600) {
        setContent { MaterialTheme { DesktopShortcutsDialog(true, true, 536.dp) {} } }
        assertTrue(onNodeWithTag("help-dialog").getUnclippedBoundsInRoot().height <= 536.dp)
        onNodeWithTag("help-close").assertIsDisplayed()
        val fs = desktopShortcutSheet(true,true).flatMap { it.rows }.filter { it.first in setOf("F", "⌃⌘F") }.map { it.second }.toSet()
        assertEquals(setOf("Fullscreen"), fs)
    }

    @Test fun countsOnlyDiagnosticsEmitRouteTransitionsAndActiveIntervals() {
        val lines = mutableListOf<String>(); val diagnostics = DesktopInputDiagnostics(lines::add)
        diagnostics.route(DesktopRoute.Home); diagnostics.route(DesktopRoute.Home); diagnostics.route(DesktopRoute.Settings)
        diagnostics.key(); diagnostics.key(); diagnostics.pointer(); diagnostics.heartbeat(); diagnostics.heartbeat()
        assertEquals(listOf("route Home→Settings", "input keys=2 pointers=1"), lines)
        diagnostics.pointer(); diagnostics.heartbeat()
        assertEquals("input keys=0 pointers=1", lines.last())
    }

    @Test fun qualityOptionsExplainEveryBandwidthCap() {
        for (cap in listOf(0, 4, 10, 20, 40, 80)) assertFalse(qualitySupport(cap).isNullOrBlank(), "$cap Mbps needs a tradeoff")
        assertTrue(desktopShortcutSheet(true, true).flatMap { it.rows }.any { it.second.contains("rows") })
    }
}
