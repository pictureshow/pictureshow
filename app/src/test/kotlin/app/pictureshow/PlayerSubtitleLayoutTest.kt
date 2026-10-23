/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.ui.SubtitleView
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
class PlayerSubtitleLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun subtitleViewportTracksOverlayGeometryAndRestoresWithoutRecreatingTheNativeView() {
        val overlayTop = mutableStateOf<Int?>(null)
        compose.setContent { PlayerSubtitles(null, SubtitleAppearance(), overlayTop.value) }
        compose.waitForIdle()
        val view = subtitleView(compose.activity.findViewById(android.R.id.content))!!
        val fullHeight = view.height
        assertTrue(fullHeight > 0)
        for (top in listOf(fullHeight * 3 / 4, fullHeight / 2)) {
            compose.runOnIdle { overlayTop.value = top }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame(view, subtitleView(compose.activity.findViewById(android.R.id.content)))
                assertTrue(view.height in 1 until top, "Subtitle viewport must end above the overlay")
            }
        }
        compose.runOnIdle { overlayTop.value = null }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(view, subtitleView(compose.activity.findViewById(android.R.id.content)))
            assertEquals(fullHeight, view.height)
        }
    }

    private fun subtitleView(view: View): SubtitleView? {
        if (view is SubtitleView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) subtitleView(view.getChildAt(index))?.let { return it }
        return null
    }
}
