/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.pictureshow.core.control.CastCapability
import app.pictureshow.core.control.RemoteTarget
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
class TouchCastChooserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun chooser(
        remote: RemoteControllerUi = RemoteControllerUi(),
        ui: CastUi = CastUi(chooserOpen = true),
        thisDevice: Boolean = true,
        showCast: Boolean = true
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TouchCastChooser(remote, ui, showCast, if (thisDevice) ({}) else null, {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
    }

    private fun text(text: String) = compose.onNodeWithText(text).getBoundsInRoot()

    private fun assertHeading(text: String) {
        compose.onNodeWithText(text).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun theNoTvsLineSitsUnderItsOwnHeading() {
        chooser()
        assertHeading("Pictureshow TVs")
        assertHeading("Google Cast")
        val empty = text("None found. Open Pictureshow on your TV and sign in.")
        val heading = text("Pictureshow TVs")
        assertTrue(heading.bottom <= empty.top, "the line belongs to the heading above it")
        assertTrue(compose.onNodeWithTag("cast:this-device").getBoundsInRoot().bottom < heading.top)
    }

    @Test
    fun aSectionHeadingHasMoreAirAboveItThanInsideASection() {
        chooser()
        val thisDevice = compose.onNodeWithTag("cast:this-device").getBoundsInRoot()
        val tvs = text("Pictureshow TVs")
        val empty = text("None found. Open Pictureshow on your TV and sign in.")
        val cast = text("Google Cast")
        assertEquals(16f, (tvs.top - thisDevice.bottom).value, 1f, "above the TVs")
        assertEquals(16f, (cast.top - empty.bottom).value, 1f, "above Cast")
        assertEquals(8f, (empty.top - tvs.bottom).value, 1f, "inside a section")
    }

    @Test
    fun withoutThisDeviceTheHeadingLeadsTheBody() {
        chooser(thisDevice = false)
        val title = text("Play on")
        val tvs = text("Pictureshow TVs")
        assertEquals(0, compose.onAllNodes(hasTestTag("cast:this-device")).fetchSemanticsNodes().size)
        // AlertDialog already puts its own 16 dp under the title; the section spacer would double it.
        assertTrue(tvs.top - title.bottom <= 18.dp, "$title $tvs")
    }

    private val castOff = CastUi(chooserOpen = true, capability = CastCapability("viewer-disabled", visible = true, canDiscover = false))

    // The way back to casting: its words start where the body's do.
    // Native: without real text measurement the words come out about 10 dp wide and centre in the button's 58 dp minimum.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aRecoveryActionSitsOnTheBodysEdge() {
        chooser(ui = castOff)
        // Unmerged: the merged node is the whole button, whose edge doesn't move with its padding.
        val words = compose.onNodeWithText("Turn on Google Cast", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(compose.onNodeWithTag("cast:this-device").getBoundsInRoot().left.value, words.left.value, 1f)
        val target = compose.onNodeWithTag("cast:enable").fetchSemanticsNode().touchBoundsInRoot
        val density = compose.onNodeWithTag("cast:enable").fetchSemanticsNode().layoutInfo.density.density
        assertTrue(target.height / density >= 47.5f, "still a 48 dp target: $target")
    }

    @Test
    fun turningCastOffIsNotOneOfThePlaces() {
        chooser()
        compose.onNodeWithText("Turn off Google Cast").assertDoesNotExist()
        compose.onNodeWithTag("cast:disable").assertDoesNotExist()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun placesToPlayAndTheWayBackToCastingAreInThePrimaryColour() {
        val scheme = darkColorScheme()
        val target = RemoteTarget("session", "device", "Bedroom TV")
        val route = CastRoute("route-1234", "Living room")
        chooser(RemoteControllerUi(targets = listOf(target)), castOff.copy(routes = listOf(route)))
        listOf("This device", "Bedroom TV", "Living room", "Turn on Google Cast").forEach { label ->
            assertTrue(scheme.primary in colours(label), "$label reads as an action")
        }
    }

    @Test
    fun aRouteDescribingItselfWithItsOwnNameSaysItOnce() {
        val same = CastRoute("route-1111", "Living room", "Living room")
        val other = CastRoute("route-2222", "Bedroom", "Chromecast")
        chooser(ui = CastUi(chooserOpen = true, routes = listOf(same, other)))
        assertEquals(1, compose.onAllNodesWithText("Living room", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Chromecast", useUnmergedTree = true).assertExists()
    }

    @Test
    fun capabilityLinesAndTheFailedListUseOneNameAndAskForNothing() {
        assertEquals(
            "Google Cast needs a newer version of Google Play services.",
            castCapabilityMessage("services-update-required")
        )
        assertTrue(castCapabilityMessage("services-missing")!!.endsWith("Pictureshow TVs still work."))
        chooser(RemoteControllerUi(listFailed = true))
        compose.onNodeWithText("Couldn't reach the server. Check your connection.").assertExists()
    }

    private val tv = RemoteTarget("session", "device", "Bedroom TV")
    private val route = CastRoute("route-1234", "Living room")

    private fun manyTvs() = RemoteControllerUi(targets = List(40) { RemoteTarget("session-$it", "device-$it", "TV $it") })

    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aShortWideWindowPutsTheSectionsSideBySide() {
        chooser(RemoteControllerUi(targets = listOf(tv)), CastUi(chooserOpen = true, routes = listOf(route)))
        val thisDevice = compose.onNodeWithTag("cast:this-device").getBoundsInRoot()
        val tvRow = compose.onNodeWithTag("remote:target:session").getBoundsInRoot()
        val castRow = compose.onNodeWithTag("cast:route:1234").getBoundsInRoot()
        assertEquals(512f, (thisDevice.right - thisDevice.left).value, 1f, "This device spans a 560 dp dialog's body")
        assertEquals(thisDevice.left.value, tvRow.left.value, 1f)
        assertEquals(thisDevice.right.value, castRow.right.value, 1f)
        assertEquals(24f, (castRow.left - tvRow.right).value, 1f, "the gutter")
        assertEquals((tvRow.right - tvRow.left).value, (castRow.right - castRow.left).value, 1f, "equal columns")
        assertEquals(text("Pictureshow TVs").top.value, text("Google Cast").top.value, 1f, "the headings share a line")
        assertEquals(16f, (text("Pictureshow TVs").top - thisDevice.bottom).value, 1f)
        assertEquals(8f, (tvRow.top - text("Pictureshow TVs").bottom).value, 1f)
        compose.onNodeWithTag("cast:more-below").assertDoesNotExist()
        compose.onNodeWithTag("cast:more-above").assertDoesNotExist()
    }

    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @Test
    fun withoutCastItStaysOneColumn() {
        chooser(RemoteControllerUi(targets = listOf(tv)), showCast = false)
        val row = compose.onNodeWithTag("remote:target:session").getBoundsInRoot()
        val thisDevice = compose.onNodeWithTag("cast:this-device").getBoundsInRoot()
        assertEquals(thisDevice.right.value, row.right.value, 1f, "no empty half: $row")
    }

    @Config(qualifiers = "w590dp-h360dp-land-xxhdpi")
    @Test
    fun aShortNarrowWindowStaysOneColumn() {
        chooser(RemoteControllerUi(targets = listOf(tv)), CastUi(chooserOpen = true, routes = listOf(route)))
        assertTrue(text("Google Cast").top > text("Pictureshow TVs").bottom)
    }

    @Config(qualifiers = "w780dp-h500dp-land-xxhdpi")
    @Test
    fun aWideWindowWithHeightStaysOneColumn() {
        chooser(RemoteControllerUi(targets = listOf(tv)), CastUi(chooserOpen = true, routes = listOf(route)))
        assertTrue(text("Google Cast").top > text("Pictureshow TVs").bottom)
    }

    // The rule is about the window the app is in. Compose gives a dialog's content its own window info, so the
    // decision has to be taken outside the dialog: faking the outer window must change it, whatever the real one is.
    private class FixedWindow(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, density: Float) :
        androidx.compose.ui.platform.WindowInfo {
        override val isWindowFocused = true
        override val containerSize = androidx.compose.ui.unit.IntSize((width.value * density).toInt(), (height.value * density).toInt())
    }

    private fun chooserInWindow(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalWindowInfo provides FixedWindow(width, height, density)
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val ui = CastUi(chooserOpen = true, routes = listOf(route))
                    TouchCastChooser(RemoteControllerUi(targets = listOf(tv)), ui, true, {}, {}, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Config(qualifiers = "w400dp-h800dp-port-xhdpi")
    @Test
    fun theColumnsFollowTheAppsWindowEvenInsideANarrowRealOne() {
        chooserInWindow(780.dp, 360.dp)
        assertEquals(text("Pictureshow TVs").top.value, text("Google Cast").top.value, 1f)
    }

    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @Test
    fun theColumnsDontFollowTheDialogsOwnWindow() {
        chooserInWindow(400.dp, 800.dp)
        assertTrue(text("Google Cast").top > text("Pictureshow TVs").bottom)
    }

    @Test
    fun aDialogsContentHasItsOwnWindowInfo() {
        val outer = FixedWindow(780.dp, 360.dp, 1f)
        var inner: androidx.compose.ui.platform.WindowInfo? = null
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides outer) {
                androidx.compose.ui.window.Dialog(onDismissRequest = {}) { inner = androidx.compose.ui.platform.LocalWindowInfo.current }
            }
        }
        compose.waitForIdle()
        assertNotNull(inner)
        assertTrue(inner !== outer, "if this ever fails, the reason the decision sits outside the dialog has gone")
    }

    @Test
    fun anEdgeThatCanStillScrollIsMarked() = assertEdgesFollowTheScroll(showCast = false)

    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @Test
    fun anEdgeThatCanStillScrollIsMarkedInColumnsToo() = assertEdgesFollowTheScroll(showCast = true)

    // Stacked, the Cast section would come after the last TV, so that TV wouldn't be the end of the list.
    private fun assertEdgesFollowTheScroll(showCast: Boolean) {
        chooser(manyTvs(), showCast = showCast)
        compose.onNodeWithTag("cast:more-below").assertExists()
        compose.onNodeWithTag("cast:more-above").assertDoesNotExist()
        compose.onNodeWithTag("remote:target:session-20").performScrollTo()
        compose.onNodeWithTag("cast:more-above").assertExists()
        compose.onNodeWithTag("cast:more-below").assertExists()
        compose.onNodeWithTag("remote:target:session-39").performScrollTo()
        compose.onNodeWithTag("cast:more-above").assertExists()
        compose.onNodeWithTag("cast:more-below").assertDoesNotExist()
    }

    private fun dialog(
        sender: CastSenderHost,
        remote: RemoteControllerUi = RemoteControllerUi(targets = listOf(tv)),
        onAttach: () -> Unit = {},
        onOpened: () -> Unit = {}
    ) {
        // Fixed state, so nothing recomposes it: this is the frame a late tap lands in.
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TouchPlayOnDialog(remote, CastUi(chooserOpen = true), sender, { onAttach() }, { null }, {}, onOpened, {})
            }
        }
        compose.waitForIdle()
    }

    private fun filmRequest() = CastPlayRequest(listOf(CastPlayItem("film", "A film")))

    @Test
    fun aTapOnTheChooserOnScreenAttachesTheTvAndOpensTheRemote() {
        val sender = signedInCastSender()
        var attached = 0
        var opened = 0
        var picked = 0
        sender.openChooser(CastPlayRequest(listOf(CastPlayItem("film", "A film")), onJellyfinPick = { picked++ }))
        dialog(sender, onAttach = { attached++ }, onOpened = { opened++ })
        compose.onNodeWithTag("remote:target:session").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1, 1, 1), listOf(attached, picked, opened))
        assertFalse(sender.state.value.chooserOpen)
    }

    @Test
    fun aTapOnAChooserThatIsNoLongerTheCurrentOneDoesNothing() {
        val sender = signedInCastSender()
        var attached = 0
        var opened = 0
        var picked = 0
        sender.openChooser(CastPlayRequest(listOf(CastPlayItem("film", "A film")), onJellyfinPick = { picked++ }))
        dialog(sender, onAttach = { attached++ }, onOpened = { opened++ })
        compose.runOnIdle { sender.openChooser(filmRequest()) }
        compose.onNodeWithTag("remote:target:session").performClick()
        compose.waitForIdle()
        assertEquals(listOf(0, 0, 0), listOf(attached, picked, opened))
    }

    @Test
    fun thisDeviceOnScreenCancelsAPickWaitingOnARejoin() {
        val sender = detachedCastSender()
        val player = object : CastLocalOutput {
            override suspend fun pauseAndRead() = CastLocalMoment(0, false)
            override fun release() = Unit
            override fun resume() = Unit
        }
        sender.openChooser(CastPlayRequest(listOf(CastPlayItem("another", "Another film")), local = player))
        sender.pick(CHOOSER_ROUTE)
        sender.openChooser(CastPlayRequest(listOf(CastPlayItem("another", "Another film")), local = player))
        assertTrue(sender.holdsNextStep(player))
        dialog(sender)
        compose.onNodeWithTag("cast:this-device").performClick()
        compose.waitForIdle()
        assertFalse(sender.holdsNextStep(player))
    }

    private fun colours(text: String): Set<Color> {
        val pixels = compose.onNodeWithText(text, substring = true).captureToImage().toPixelMap()
        return buildSet { for (x in 0 until pixels.width) for (y in 0 until pixels.height) add(pixels[x, y]) }
    }
}
