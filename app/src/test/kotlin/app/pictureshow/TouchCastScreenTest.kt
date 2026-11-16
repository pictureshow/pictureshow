/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import app.pictureshow.core.control.CastState
import app.pictureshow.core.control.OwnerState
import app.pictureshow.core.control.ViewingIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class TouchCastScreenTest {
    @get:Rule val compose = createComposeRule()

    private val viewing = CastState(
        OwnerState(ViewingIdentity("s", "v", "route", "c", 2, "cast"), ViewingIdentity("s", "v", "phone", "p", 1), phase = "disconnected")
    )

    private fun controlsUi(volume: Boolean): CastUi {
        val observed = app.pictureshow.core.control.CastObservation(
            mediaSession = 7,
            generation = 2,
            playerState = "playing",
            ticks = 870000000,
            durationTicks = 6000000000,
            entry = "a",
            commands = listOf("play", "pause", "seek", "stop", "tracks") + if (volume) listOf("volume") else emptyList()
        )
        val state = viewing.copy(
            owner = viewing.owner.copy(phase = "active"),
            link = "connected",
            observed = observed,
            commands = app.pictureshow.core.control.CommandState(viewing.owner.owner)
        )
        val controls = app.pictureshow.core.control.castControls(
            app.pictureshow.core.control.CastControlsInput(
                commands = observed.commands,
                durationTicks = observed.durationTicks,
                tracks = listOf(
                    app.pictureshow.core.control.CastTrack(-2, "audio", "English"),
                    app.pictureshow.core.control.CastTrack(-3, "audio", "Unavailable", available = false),
                    app.pictureshow.core.control.CastTrack(4, "subtitle", "English subtitles")
                ),
                activeTracks = listOf(-2, -3, 4)
            )
        )
        return CastUi(
            cast = state,
            deviceName = "Living room",
            title = "An episode",
            controls = controls,
            volume = 0.45,
            queue = listOf(
                app.pictureshow.core.control.CastViewedItem("a", "episode", title = "An episode"),
                app.pictureshow.core.control.CastViewedItem("b", "next", title = "Next episode")
            )
        )
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val scale = 900.0 / maxOf(bitmap.width, bitmap.height)
        val small = android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
        val folder = java.io.File("build/reports/tests/cast-ui").apply { mkdirs() }
        java.io.File(folder, "$name.jpg").outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, it) }
        java.io.File(folder, "$name.txt").writeText(compose.onRoot().printToString())
    }

    @Test fun volumeUnavailableIsDisabledWithReasonAndUnavailableAudioIsNeverSelected() {
        compose.setContent { TouchTheme { TouchCastScreen(controlsUi(false), inertCastSender(), {}) } }
        compose.onNodeWithTag("cast:volume-slider").assertIsNotEnabled()
        compose.onNodeWithTag("cast:mute").assertIsNotEnabled()
        compose.onNodeWithTag("cast:volume-reason").assertIsDisplayed()
        capture("phone-fixed-volume")
        compose.onNodeWithTag("cast:track:-3").performScrollTo().assertIsNotEnabled().assertIsNotSelected()
        capture("phone-tracks")
        compose.onNodeWithTag("cast:queue:b").performScrollTo().assertIsDisplayed()
        capture("phone-queue")
    }

    @Test fun waitingForFreshReceiverCommandsExplainsDisabledControls() {
        val ui = controlsUi(true)
        val waiting = ui.copy(cast = ui.cast!!.copy(commands = ui.cast.commands!!.copy(fresh = false)))
        compose.setContent { TouchTheme { TouchCastScreen(waiting, inertCastSender(), {}) } }
        compose.onNodeWithTag("cast:waiting").assertIsDisplayed()
        compose.onNodeWithTag("cast:volume-slider").assertIsNotEnabled()
        capture("phone-waiting")
    }

    @Test fun castDeviceVolumeLayout() {
        compose.setContent { TouchTheme { TouchCastScreen(controlsUi(true), inertCastSender(), {}) } }
        compose.onNodeWithTag("cast:volume-slider").assertIsEnabled()
        compose.onNodeWithTag("cast:toggle").assertIsEnabled()
        capture("phone-volume")
    }

    @Test
    @Config(qualifiers = "w840dp-h800dp-land-hdpi")
    fun tabletCastControlsLayout() {
        compose.setContent { TouchTheme { TouchCastScreen(controlsUi(true), inertCastSender(), {}) } }
        compose.onNodeWithTag("cast:volume-slider").assertIsEnabled()
        capture("tablet-volume")
    }

    /** Stop, Close and Forget all end with no viewing. "Nothing is casting" was a dead end the viewer had to back out of. */
    @Test fun endingTheViewingGoesBackToWhereItBegan() {
        var backs = 0
        val ui = androidx.compose.runtime.mutableStateOf(CastUi(cast = viewing, deviceName = "Den"))
        val host = inertCastSender()
        compose.setContent { TouchTheme { TouchCastScreen(ui.value, host, onBack = { backs += 1 }) } }
        compose.waitForIdle()
        assertEquals(0, backs)
        ui.value = CastUi()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    // SET-12: off stops this device reaching for Cast devices, so there's nothing the button could do.
    @Test fun reconnectIsOfferedOnlyWhileGoogleCastIsOn() {
        val host = inertCastSender()
        compose.setContent { TouchTheme { TouchCastScreen(CastUi(cast = viewing, deviceName = "Den"), host, onBack = {}) } }
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

    // A real press and release that land off the dialog's window, as a tap on the player behind it does.
    private fun tapOutside() {
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(0, 0, action, -50f, -50f, 0)
            compose.runOnIdle { dialog.dispatchTouchEvent(event) }
            event.recycle()
        }
        compose.waitForIdle()
    }

    // Recheck D4: the viewer is looking at the player while this waits, and a stray tap there would throw the pick away.
    @Test fun backOnAStopNobodyConfirmedDoesNotPlayHere() {
        val host = stopUnknownCastSender()
        compose.setContent {
            val ui by host.state.collectAsState()
            MaterialTheme { TouchCastAttention(ui, host) }
        }
        compose.waitForIdle()
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("cast:stop-unknown").assertExists()
        assertEquals("return-uncertain", host.state.value.cast?.owner?.phase)
        assertNull(host.state.value.localStart, "the TV may still be playing, and Back didn't say play here")
        compose.onNodeWithText("Play here anyway").performClick()
        compose.waitForIdle()
        assertNotNull(host.state.value.localStart, "the button still does")
    }

    @Test fun aStrayTapLeavesAPickStartingAndBackCancelsIt() {
        val host = signedInCastSender()
        host.openChooser(CastPlayRequest(listOf(CastPlayItem("0123456789abcdef0123456789abcde1", "A film"))))
        host.pick(CHOOSER_ROUTE)
        compose.setContent {
            val ui by host.state.collectAsState()
            MaterialTheme { TouchCastAttention(ui, host) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("cast:starting").assertExists()
        tapOutside()
        compose.onNodeWithTag("cast:starting").assertExists()
        assertNotNull(host.state.value.cast?.owner?.candidate)
        compose.runOnIdle { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        compose.waitForIdle()
        assertNull(host.state.value.cast?.owner?.candidate)
    }

    // Recheck D3: only a Pictureshow TV can take a queue today, so with Cast playing the chooser could only say "None found".
    @Test fun castDoesNotHideDiscoveryOfAnotherPictureshowTv() {
        assertEquals(true, offersTvQueue(CastUi(), tvAttached = false))
        assertEquals(true, offersTvQueue(CastUi(cast = viewing), tvAttached = false))
        assertEquals(true, offersTvQueue(CastUi(cast = viewing), tvAttached = true))
        val local = viewing.copy(owner = viewing.owner.copy(owner = viewing.owner.local))
        assertEquals(true, offersTvQueue(CastUi(cast = local), tvAttached = false))
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
