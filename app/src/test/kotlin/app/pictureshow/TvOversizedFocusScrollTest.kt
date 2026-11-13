/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TvOversizedFocusScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun oversizedFocusTargetStaysStableAcrossEitherSingleEdge() {
        lateinit var policy: BringIntoViewSpec
        compose.setContent {
            PictureshowTheme {
                TvSettingsList(minimalFocusScroll = true) {
                    val current = LocalBringIntoViewSpec.current
                    SideEffect { policy = current }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0f, policy.calculateScrollDistance(10f, 120f, 100f), "Oversized target crossing only the bottom edge must remain stable")
            assertEquals(0f, policy.calculateScrollDistance(-30f, 120f, 100f), "Oversized target crossing only the top edge must remain stable")
            assertEquals(15f, policy.calculateScrollDistance(85f, 30f, 100f), "Ordinary bottom overflow must still scroll minimally")
            assertEquals(-10f, policy.calculateScrollDistance(-10f, 30f, 100f), "Ordinary top overflow must still scroll minimally")
            assertEquals(0f, policy.calculateScrollDistance(20f, 30f, 100f), "A fully visible target must remain stable")
        }
    }
}
