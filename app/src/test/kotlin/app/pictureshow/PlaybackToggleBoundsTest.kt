/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackToggleBoundsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pillFitsTheAccessibilityBounds(fontScale: Float, seekBack: Boolean = false) {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabled(true)
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PictureshowTheme {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(64.dp)) {
                        if (seekBack) {
                            Column { SeekPanel(30_000, 60_000, {}, {}) }
                        } else {
                            PlaybackToggle(true, true, Modifier.testTag("toggle")) {}
                        }
                    }
                }
            }
        }
        val node = compose.onNodeWithTag(if (seekBack) "seek:back" else "toggle")
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        node.assertIsFocused()
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val bounds = Rect()
        val nodeId = node.fetchSemanticsNode().id
        compose.runOnIdle {
            val info = checkNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(nodeId))
            info.getBoundsInScreen(bounds)
            val origin = IntArray(2)
            compose.activity.window.decorView.getLocationOnScreen(origin)
            assertEquals(0, origin[0], "Canvas and accessibility screen coordinates must share their origin")
            assertEquals(0, origin[1], "Canvas and accessibility screen coordinates must share their origin")
            @Suppress("DEPRECATION")
            info.recycle()
            compose.activity.window.decorView.draw(Canvas(bitmap))
        }
        try {
            // The green system outline follows these native bounds, not a child's visual scale.
            // At the cap's centre any light pixel outside them is a protruding focused pill.
            val y = bounds.centerY()
            val inside = (bounds.left until bounds.right).count { x ->
                val pixel = bitmap.getPixel(x, y)
                Color.red(pixel) > 120 && Color.green(pixel) > 120 && Color.blue(pixel) > 120
            }
            assertTrue(inside > bounds.width() / 2, "The focused light pill must actually be painted inside the measured bounds")
            val outside = ((bounds.left - 20) until bounds.left).count { x ->
                val pixel = bitmap.getPixel(x, y)
                Color.red(pixel) > 120 && Color.green(pixel) > 120 && Color.blue(pixel) > 120
            } + (bounds.right until (bounds.right + 20)).count { x ->
                val pixel = bitmap.getPixel(x, y)
                Color.red(pixel) > 120 && Color.green(pixel) > 120 && Color.blue(pixel) > 120
            }
            assertEquals(0, outside, "fontScale=$fontScale: pill paint extends outside native focus bounds $bounds")
        } finally {
            bitmap.recycle()
            shadowOf(manager).setEnabled(false)
        }
    }

    @Test fun playFitsTheNativeFocusBoundsAtDefaultTextSize() = pillFitsTheAccessibilityBounds(1f)

    @Test fun playFitsTheNativeFocusBoundsAtLargeTextSize() = pillFitsTheAccessibilityBounds(1.3f)

    @Test fun seekBackFitsTheNativeFocusBoundsAtDefaultTextSize() = pillFitsTheAccessibilityBounds(1f, true)

    @Test fun seekBackFitsTheNativeFocusBoundsAtLargeTextSize() = pillFitsTheAccessibilityBounds(1.3f, true)
}
