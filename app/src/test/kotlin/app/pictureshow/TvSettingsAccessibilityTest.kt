/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.core.graphics.ColorUtils
import androidx.tv.material3.MaterialTheme
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class TvSettingsAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeAccessibilityExportsActualSwitchCheckedStateAndNoCheckableChoiceOrAction() {
        var checked by mutableStateOf(true)
        compose.runOnIdle {
            val manager = compose.activity.getSystemService(AccessibilityManager::class.java)
            shadowOf(manager).setEnabled(true)
            shadowOf(manager).setTouchExplorationEnabled(true)
        }
        compose.setContent {
            MaterialTheme {
                Column {
                    TvSettingsToggleRow("Auto-adjust quality", checked, Modifier.testTag("switch")) { checked = it }
                    TvSettingsChoiceRow("Home limit", 20, listOf(10, 20), { "$it Mbps" }, Modifier.testTag("choice")) {}
                    TvSettingsActionRow("Audio output", Modifier.testTag("action"), navigation = true) {}
                }
            }
        }
        val on = nativeInfo("switch")
        assertTrue(on.isCheckable)
        assertTrue(on.isChecked, "An on-switch must reach Android accessibility as checked")
        assertFalse(nativeInfo("choice").isCheckable)
        assertFalse(nativeInfo("action").isCheckable)
        compose.onNodeWithTag("switch").assertIsOn()
        for (tag in listOf("switch", "choice", "action")) {
            assertNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected), tag)
        }
        compose.onNodeWithTag("switch").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("switch").assertIsOff()
        val off = nativeInfo("switch")
        assertTrue(off.isCheckable)
        assertFalse(off.isChecked)
    }

    private fun nativeInfo(tag: String): AccessibilityNodeInfo {
        val id = compose.onNodeWithTag(tag).fetchSemanticsNode().id
        return compose.runOnIdle {
            fun descendants(view: View): Sequence<View> = sequence {
                yield(view)
                if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
            }
            descendants(compose.activity.window.decorView)
                .mapNotNull { it.accessibilityNodeProvider?.createAccessibilityNodeInfo(id) }
                .firstOrNull() ?: error("No exported accessibility node for $tag / $id")
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectedRadioHasThreeToOnePixelContrastBothFocusedAndAfterFocusMovesAway() {
        compose.setContent {
            MaterialTheme {
                TvSettingsChoicePicker("Quality", 20, listOf(10, 20, 40), { "$it Mbps" }, {}, {})
            }
        }
        fun contrast(): Double {
            val panel = compose.onNodeWithTag("tv-settings:panel").fetchSemanticsNode().boundsInRoot
            val radio = compose.onNodeWithTag("tv-settings:radio:1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bitmap = compose.onNodeWithTag("tv-settings:panel").captureToImage().asAndroidBitmap()
            val center = bitmap.getPixel((radio.center.x - panel.left).toInt(), (radio.center.y - panel.top).toInt())
            val background = bitmap.getPixel((radio.left - panel.left + 1).toInt(), (radio.top - panel.top + 1).toInt())
            return ColorUtils.calculateContrast(center, background)
        }
        val focusedContrast = contrast()
        assertTrue(focusedContrast >= 3.0, "Focused selected radio contrast: $focusedContrast")
        compose.onNodeWithTag("tv-settings:panel").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        val selectedContrast = contrast()
        assertTrue(selectedContrast >= 3.0, "Selected radio after focus moves away: $selectedContrast")
        println("TV radio pixel contrast: focused=$focusedContrast selected-unfocused=$selectedContrast")
    }
}
