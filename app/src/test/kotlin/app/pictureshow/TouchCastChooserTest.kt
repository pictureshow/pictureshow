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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.pictureshow.core.control.RemoteTarget
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        thisDevice: Boolean = true
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TouchCastChooser(remote, ui, true, if (thisDevice) ({}) else null, {}, {}, {}, {})
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

    // Turning Cast off is a setting, not somewhere to play: its words start where the body's do.
    @Test
    fun theSettingSitsOnTheBodysEdge() {
        chooser()
        // Unmerged: the merged node is the whole button, whose edge doesn't move with its padding.
        val words = compose.onNodeWithText("Turn off Google Cast", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(compose.onNodeWithTag("cast:this-device").getBoundsInRoot().left.value, words.left.value, 1f)
        val target = compose.onNodeWithTag("cast:disable").fetchSemanticsNode().touchBoundsInRoot
        val density = compose.onNodeWithTag("cast:disable").fetchSemanticsNode().layoutInfo.density.density
        assertTrue(target.height / density >= 47.5f, "still a 48 dp target: $target")
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun placesToPlayAreInThePrimaryColourAndTheSettingIsNot() {
        val scheme = darkColorScheme()
        val target = RemoteTarget("session", "device", "Bedroom TV")
        val route = CastRoute("route-1234", "Living room")
        chooser(RemoteControllerUi(targets = listOf(target)), CastUi(chooserOpen = true, routes = listOf(route)))
        listOf("This device", "Bedroom TV", "Living room").forEach { label ->
            assertTrue(scheme.primary in colours(label), "$label reads as an action")
        }
        val setting = colours("Turn off Google Cast")
        assertTrue(scheme.onSurfaceVariant in setting, "the setting is quiet")
        assertFalse(scheme.primary in setting, "the setting isn't a place to play")
    }

    private fun colours(text: String): Set<Color> {
        val pixels = compose.onNodeWithText(text, substring = true).captureToImage().toPixelMap()
        return buildSet { for (x in 0 until pixels.width) for (y in 0 until pixels.height) add(pixels[x, y]) }
    }
}
