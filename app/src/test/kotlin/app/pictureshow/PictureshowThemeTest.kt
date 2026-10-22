/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.tv.material3.Text
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PictureshowThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun plainTextIsReadableOnTheWindowBackground() {
        compose.setContent { PictureshowTheme { Text("Unstyled label") } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Unstyled label").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val text = layouts.single().layoutInput.style.color
        val window = Color(0xFF10131C)
        val contrast = (maxOf(text.luminance(), window.luminance()) + 0.05f) /
            (minOf(text.luminance(), window.luminance()) + 0.05f)
        assertTrue(contrast >= 7f, "text $text on the window background has contrast $contrast")
    }
}
