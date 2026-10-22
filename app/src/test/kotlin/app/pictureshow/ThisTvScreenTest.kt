/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ThisTvScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun viewJsonImmediatelyShowsProfileAndDpadScrollsToItsEnd() {
        val json = "{\n  \"DirectPlayProfiles\": [\n" +
            (1..60).joinToString(",\n") { "    {\"Codec\": \"fixture-codec-$it\"}" } + "\n  ]\n}"
        val report = ThisTvReport((1..20).joinToString("\n\n") { "Decoder $it\nReported capability" }, json)
        val preferences = PlaybackPreferencesStore(RuntimeEnvironment.getApplication().preferences)
        compose.setContent { MaterialTheme { ThisTvScreen(preferences, capture = { _, _ -> report }, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("View device profile JSON").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("View device profile JSON").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        val chunks = json.lines().chunked(12).map { it.joinToString("\n") }
        compose.onNodeWithText(chunks.first()).assertIsDisplayed()
        chunks.forEach { chunk ->
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithText(chunk).assertIsFocused().assertIsDisplayed()
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Back").assertIsFocused()
    }
}
