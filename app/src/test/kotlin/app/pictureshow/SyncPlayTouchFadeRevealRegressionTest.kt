/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** PRIVATE uncompiled draft. Requires ONLY the proposed visibility-only baseline patch.
 * This composes the existing production body and reads its descendant policy; no IME is mocked.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPlayTouchFadeRevealRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var policy: BringIntoViewSpec? = null
    private var density = 0f

    private fun showActualBody() {
        compose.setContent {
            app.pictureshow.ui.PictureshowTheme {
                SyncTouchBody(Modifier.width(320.dp).height(300.dp), compactHeight = false) {
                    val current = LocalBringIntoViewSpec.current
                    val currentDensity = LocalDensity.current.density
                    SideEffect { policy = current; density = currentDensity }
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("reveal-probe:start"))
                    Box(Modifier.height(1600.dp))
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("reveal-probe:end"))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun distanceInDp(offsetDp: Float, sizeDp: Float): Float = assertNotNull(policy)
        .calculateScrollDistance(offsetDp * density, sizeDp * density, 300f * density) / density

    private fun scrollToActualEnd() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("reveal-probe:end"))
        compose.waitForIdle()
    }

    @Test fun actualTouchPolicyRevealsAboveActiveBottomFadePlusEightDp() {
        showActualBody()
        val fade = compose.onNodeWithTag("sync:fade-bottom").getUnclippedBoundsInRoot()
        assertEquals(20.dp, fade.bottom - fade.top, "Keep the real20dp bottom scroll affordance")
        // A50dp control at240..290 is inside the physical300dp viewport, but crosses its
        // effective272dp boundary (20dp actual fade +8dp breathing room).
        assertEquals(18f, distanceInDp(240f, 50f), .01f,
            "Actual touch descendant policy must reveal a focused control above fade+8dp")
    }

    @Test fun actualTouchPolicyRevealsBelowActiveTopFadePlusEightDp() {
        showActualBody()
        scrollToActualEnd()
        val fade = compose.onNodeWithTag("sync:fade-top").getUnclippedBoundsInRoot()
        assertEquals(16.dp, fade.bottom - fade.top, "Keep the real16dp top scroll affordance")
        // A40dp control starting4dp down crosses the24dp effective top edge.
        assertEquals(-20f, distanceInDp(4f, 40f), .01f,
            "Actual touch descendant policy must reveal below the active top fade+8dp")
    }

    @Test fun visibleControlsStayStableAndAbsentFadesReserveNoInvisibleInset() {
        showActualBody()
        compose.onNodeWithTag("sync:fade-top").assertDoesNotExist()
        assertEquals(0f, distanceInDp(4f, 40f), .01f, "No top fade at start means no invented top inset")
        assertEquals(0f, distanceInDp(100f, 40f), .01f, "A fully visible middle control must not move")
        scrollToActualEnd()
        compose.onNodeWithTag("sync:fade-bottom").assertDoesNotExist()
        assertEquals(0f, distanceInDp(260f, 40f), .01f, "No bottom fade at end means no invented bottom inset")
        assertEquals(0f, distanceInDp(100f, 40f), .01f, "A visible control remains stable after scrolling")
    }
}
