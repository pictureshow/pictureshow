/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityReadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        report: ThisTvReport,
        preferences: PlaybackPreferencesStore = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
    ) {
        compose.setContent {
            PictureshowTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    ThisTvScreen(preferences, capture = { _, _ -> report }, onBack = {})
                }
            }
        }
        compose.waitForIdle()
        // DataStore's first IO emission is outside Compose idling; an idle loading screen is not a report.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("View device profile JSON").fetchSemanticsNodes().size == 1
        }
    }

    private fun down() = compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }

    private fun readToBack(): String {
        val text = StringBuilder()
        repeat(40) {
            down()
            val focused = compose.onNode(isFocused())
            val value = focused.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            if (value == "Back") return text.toString()
            val bounds = focused.getUnclippedBoundsInRoot()
            assertTrue(bounds.top.value >= 0f && bounds.bottom.value <= 540f, "Focused reading block is outside viewport: $bounds")
            assertTrue(bounds.bottom.value - bounds.top.value <= 488f, "Focused reading block is too tall: $bounds")
            focused.assertIsDisplayed()
            text.append(value)
        }
        error("D-pad did not reach Back after reading diagnostics")
    }

    @Test
    fun compactJsonIsReadableWithoutAnOversizedFocusedNode() {
        val json = "{\"DirectPlayProfiles\":[" + (1..120).joinToString(",") { "{\"Codec\":\"format-$it\"}" } + "]}"
        show(ThisTvReport("Reported capabilities", json))
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        val read = readToBack().replace("\n", "")
        assertEquals(json, read, "Every part of the compact JSON must remain readable")
    }

    @Test
    fun delayedPreferencesStillAllowEveryJsonBlockToBeRead() {
        // DataStore's first disk read runs outside Compose's idling clock. Keep that boundary real.
        val preferences = PlaybackPreferencesStore(object : DataStore<Preferences> {
            override val data = flow {
                val value = withContext(Dispatchers.IO) {
                    delay(1_000)
                    emptyPreferences()
                }
                emit(value)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Read-only fixture")
        })
        val json = "{\"DirectPlayProfiles\":[" + (1..120).joinToString(",") { "{\"Codec\":\"format-$it\"}" } + "]}"
        show(ThisTvReport("Reported capabilities", json), preferences)
        compose.onNodeWithText("Refresh capabilities").assertIsFocused()
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(json, readToBack().replace("\n", ""), "Every part must remain readable after a slow preferences read")
    }

    @Test
    fun compactJsonKeepsEveryBlockInOrderWhileScrollingIsStillInFlight() {
        val json = "{\"DirectPlayProfiles\":[" + (1..120).joinToString(",") { "{\"Codec\":\"format-$it\"}" } + "]}"
        show(ThisTvReport("Reported capabilities", json))
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val chunks = diagnosticTextChunks(json, 1.3f)
            (chunks + "Back").forEachIndexed { index, expected ->
                down()
                val actual = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
                assertEquals(expected, actual, "D-pad step $index at clock ${compose.mainClock.currentTime} ms")
                compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun longDiagnosticParagraphCanBeReadToItsEndAtLargeText() {
        val summary = (1..100).joinToString(" ") { "Wide format $it WWWWWWWWWWWWWWWWWWWW" }
        show(ThisTvReport(summary, "{}"))
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val read = readToBack().replace("\n", "")
        assertEquals(summary, read, "Every part of the long summary must remain readable")
    }
}
