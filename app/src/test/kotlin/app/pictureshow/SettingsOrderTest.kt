/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The viewer's own settings come before the technical decoder report, and Back lands on the entry that was opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsOrderTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val viewerSettings = settingsViewerTags

    private fun open() {
        val report = ThisTvReport("Decoder\nReported capability", "{}")
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "settings") {
                    composable("settings") {
                        PlaybackSettingsScreen(
                            PlaybackSettingsState(),
                            "PCM only",
                            {},
                            { _, _ -> },
                            {},
                            onThisTv = { nav.navigate("this-tv") },
                            onAbout = { nav.navigate("about") },
                            onBack = {}
                        )
                    }
                    composable("this-tv") { ThisTvScreen(preferences, capture = { _, _ -> report }) { nav.popBackStack() } }
                    aboutScreens(nav)
                }
            }
        }
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsReadAsHeadedGroupsInOrderWithEachSettingOnce() {
        open()
        compose.onNodeWithTag("settings:advanced:Network quality").assertIsFocused()
        val contract = listOf(
            File("../spec/vectors/sync-tv-structure-discovery.json"),
            File("spec/vectors/sync-tv-structure-discovery.json")
        )
            .first { it.isFile }.readText().let { Json.parseToJsonElement(it).jsonObject.getValue("cases").jsonArray }
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "settings-secondary-watch-together-order" }
            .getValue("expected").jsonObject
        val section = compose.onNodeWithText(contract.getValue("sectionHeading").jsonPrimitive.content).getUnclippedBoundsInRoot()
        val together = compose.onNodeWithTag(contract.getValue("rowTag").jsonPrimitive.content).getUnclippedBoundsInRoot()
        val supporter = compose.onNodeWithTag(contract.getValue("nextTag").jsonPrimitive.content).getUnclippedBoundsInRoot()
        assertTrue(section.bottom <= together.top, "Watch together belongs below its section heading")
        assertTrue(together.bottom <= supporter.top, "Watch together precedes Support Pictureshow")
        contract.getValue("followingTags").jsonArray.map { it.jsonPrimitive.content }.fold(supporter) { above, tag ->
            compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().also {
                assertTrue(above.bottom <= it.top, "$tag follows the row above it")
            }
        }
        // Signed out of nothing here, so there is no Account group; every other heading appears once, in order.
        val headings = tvSettingsGroups.drop(1).map { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
        headings.zipWithNext().forEach { (above, below) -> assertTrue(above.bottom <= below.top, "headings keep their order") }
        // Each group's rows sit under its own heading and above the next one.
        val groups = mapOf(
            "Playback & quality" to listOf(
                "settings:advanced:Network quality",
                "settings:advanced:Playback",
                "settings:conversion-notice",
                "settings:music-quality",
                "settings:phone-remote"
            ),
            "Subtitles" to listOf("settings:advanced:Subtitle appearance", "settings:advanced:Subtitle conversion"),
            "Audio & video output" to listOf("settings:advanced:Audio output", "settings:advanced:Video output", "settings:this-tv"),
            "Your server preferences" to listOf("settings:audio-language", "settings:subtitle-language", "settings:subtitle-mode")
        )
        groups.forEach { (heading, tags) ->
            val index = tvSettingsGroups.drop(1).indexOf(heading)
            val top = headings[index]
            val next = headings.getOrNull(index + 1)
            tags.forEach { tag ->
                val row = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                assertTrue(top.bottom <= row.top && (next == null || row.bottom <= next.top), "$tag belongs under $heading")
            }
        }
        // The shortcuts that repeated grouped settings are gone, with their "Also in" copy.
        listOf("settings:bitrate", "settings:passthrough", "settings:size", "settings:background").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Also in", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Troubleshooting").assertDoesNotExist()
    }

    @Test
    fun backFromThisTvLandsOnThisTvAfterWalkingDownTheList() {
        open()
        // Walk the list with the remote, as a viewer would, to the report at the end of Audio & video output.
        compose.walkSettingsFromHomeTo("settings:this-tv")
        compose.onNodeWithTag("settings:this-tv").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Refresh capabilities").assertExists() }.isSuccess }
        back()
        compose.onNodeWithTag("settings:this-tv").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag(settingsViewerTags[settingsViewerTags.indexOf("settings:this-tv") + 1]).assertIsFocused()
    }

    @Test
    fun theWholeListWalksInPageOrderToBack() {
        open()
        compose.walkSettingsFromHomeTo("settings:back")
        compose.onNodeWithTag("settings:back").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun backFromAboutLandsOnAboutNotTheFirstSetting() {
        open()
        compose.walkSettingsFromHomeTo("settings:syncplay")
        compose.onNodeWithTag("settings:syncplay").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("settings:supporter").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("settings:about").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("about:licenses").assertIsFocused()
        back()
        compose.onNodeWithTag("settings:about").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun theAccountGroupLeadsSettingsAndReachesSwitchUserAndSignOut() {
        // T-1: viewers looked through every Settings page for Sign out. Signed in, the page starts with who and where.
        var switches = 0
        var signOuts = 0
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    PlaybackSettingsState(),
                    "PCM only",
                    {},
                    { _, _ -> },
                    {},
                    account = SettingsAccount("Jamie", "Screen room"),
                    onSwitchUser = { switches++ },
                    onSignOut = { signOuts++ },
                    onBack = {}
                )
            }
        }
        compose.onNodeWithTag("settings:switch-user").assertIsFocused().assertIsDisplayed()
        val heading = compose.onNodeWithText("Account").getUnclippedBoundsInRoot()
        val switchUser = compose.onNodeWithTag("settings:switch-user").getUnclippedBoundsInRoot()
        val playback = compose.onNodeWithText("Playback & quality").getUnclippedBoundsInRoot()
        assertTrue(heading.bottom <= switchUser.top && switchUser.bottom <= playback.top, "Account comes first, above Playback & quality")
        compose.onNodeWithText("Jamie on Screen room", substring = true).assertExists()
        val path = settingsAccountTags + settingsViewerTags + settingsTrailingTags
        path.drop(1).forEach { tag ->
            key(Key.DirectionDown)
            compose.onNodeWithTag(tag).assertIsFocused()
        }
        compose.onNodeWithTag("settings:sign-out").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertEquals(0, signOuts, "Sign out asks first (AUTH-10)")
        compose.onNodeWithTag("sign-out:confirm-button").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertEquals(1, signOuts)
        compose.onNodeWithTag("settings:switch-user").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertEquals(1, switches)
    }

    @Test
    fun signingOutHoldsBothAccountRows() {
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    PlaybackSettingsState(),
                    "PCM only",
                    {},
                    { _, _ -> },
                    {},
                    account = SettingsAccount("Jamie", "Screen room"),
                    signingOut = true,
                    onBack = {}
                )
            }
        }
        compose.onNodeWithTag("settings:switch-user").assertIsNotEnabled()
        compose.onNodeWithTag("settings:sign-out").assertIsNotEnabled().assert(hasText("Signing out…"))
    }
}
