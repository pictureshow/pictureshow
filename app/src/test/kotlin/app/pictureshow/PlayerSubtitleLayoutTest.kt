/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

    @Test
    fun emptyActionPaddingDoesNotReserveSubtitleSpaceAndRemovingTheCardRestoresIt() {
        val shown = mutableStateOf(false)
        val overlayTop = mutableStateOf<Int?>(null)
        var fullHeight = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().onGloballyPositioned { fullHeight = it.size.height }) {
                PlayerSubtitles(null, SubtitleAppearance(), overlayTop.value)
                // The production actions column uses outer padding before its measured content.
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 36.dp)
                        .onGloballyPositioned { overlayTop.value = it.positionInRoot().y.toInt().takeIf { _ -> it.size.height > 0 } }
                ) {
                    if (shown.value) Box(Modifier.size(160.dp, 80.dp))
                }
            }
        }
        compose.waitForIdle()
        val view = subtitleView(compose.activity.findViewById(android.R.id.content))!!
        compose.runOnIdle { assertEquals(fullHeight, view.height, "Empty action padding must not count as a visible overlay") }
        compose.runOnIdle { shown.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(view.height < fullHeight - 80) }
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(fullHeight, view.height) }
    }

    private fun subtitleView(view: View): SubtitleView? {
        if (view is SubtitleView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) subtitleView(view.getChildAt(index))?.let { return it }
        return null
    }
}
