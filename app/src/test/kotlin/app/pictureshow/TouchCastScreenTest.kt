/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.pictureshow.core.control.CastState
import app.pictureshow.core.control.OwnerState
import app.pictureshow.core.control.ViewingIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class TouchCastScreenTest {
    @get:Rule val compose = createComposeRule()

    private val viewing = CastState(
        OwnerState(ViewingIdentity("s", "v", "route", "c", 2, "cast"), ViewingIdentity("s", "v", "phone", "p", 1), phase = "disconnected")
    )

    /** Stop, Close and Forget all end with no viewing. "Nothing is casting" was a dead end the viewer had to back out of. */
    @Test fun endingTheViewingGoesBackToWhereItBegan() {
        var backs = 0
        val ui = androidx.compose.runtime.mutableStateOf(CastUi(cast = viewing, deviceName = "Den"))
        val host = inertCastSender()
        compose.setContent { MaterialTheme { TouchCastScreen(ui.value, host, onBack = { backs += 1 }) } }
        compose.waitForIdle()
        assertEquals(0, backs)
        ui.value = CastUi()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    // SET-12: off stops this device reaching for Cast devices, so there's nothing the button could do.
    @Test fun reconnectIsOfferedOnlyWhileGoogleCastIsOn() {
        val host = inertCastSender()
        compose.setContent { MaterialTheme { TouchCastScreen(CastUi(cast = viewing, deviceName = "Den"), host, onBack = {}) } }
        compose.onNodeWithTag("cast:reconnect").assertExists()
        host.setEnabled(false)
        compose.waitForIdle()
        compose.onNodeWithTag("cast:reconnect").assertDoesNotExist()
        compose.onNodeWithText("Turn Google Cast on in Settings to reconnect.", substring = true).assertExists()
        compose.onNodeWithText("Forget it").assertExists()
    }

    @Test fun aDroppedPickIsToldOnce() {
        val host = detachedCastSender()
        var ui by androidx.compose.runtime.mutableStateOf(CastUi(droppedFrom = "Den"))
        compose.setContent { MaterialTheme { TouchCastDroppedNotice(ui, host) } }
        compose.waitForIdle()
        assertEquals("Didn't start on Den, because this title finished first.", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
        ui = CastUi()
        compose.waitForIdle()
        assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount())
    }

    @Test fun aRefusedPickSaysWhyInTheChooser() {
        val ui = CastUi(
            chooserOpen = true,
            deviceName = "Den",
            pickRefusal = "detached-elsewhere",
            routes = listOf(CastRoute("r", "Kitchen"))
        )
        compose.setContent {
            MaterialTheme {
                TouchCastChooser(RemoteControllerUi(), ui, showCast = true, onThisDevice = null, onPickRemote = {
                }, onPickCast = { }, onSetCastEnabled = { }, onDismiss = { })
            }
        }
        compose.onNodeWithTag("cast:pick-refused").assertIsDisplayed()
        compose.onNodeWithText("Still playing on Den.", substring = true).assertIsDisplayed()
    }
}
