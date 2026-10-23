/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityContrastTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun ratio(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    private fun assertArtworkKeyline(focused: Boolean) {
        var outline = Color.Transparent
        val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Viewer"), "fake")
        compose.setContent {
            PictureshowTheme {
                outline = if (focused) MaterialTheme.colorScheme.border else SelectedSeasonOutline
                Box(Modifier.fillMaxSize().background(Color(0xFF10131C)).padding(40.dp)) {
                    CatalogCard(MediaItem("season", "Season 1", "Season"), account, false, {
                    }, Modifier.testTag("card"), selected = !focused)
                }
            }
        }
        val node = compose.onNodeWithTag("card")
        if (focused) node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val bounds = node.getUnclippedBoundsInRoot()
        val width = bounds.right.value - bounds.left.value
        val height = bounds.bottom.value - bounds.top.value
        val expansion = if (focused) .05f else 0f
        val left = bounds.left.value - width * expansion
        val right = bounds.right.value + width * expansion
        val top = bounds.top.value - height * expansion
        val bottom = bounds.bottom.value + height * expansion
        val midX = (left + right) / 2
        val midY = top + (bottom - top) / 4
        val density = compose.activity.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) }
        val expected = Color(0xFF10131C).toArgb()
        try {
            listOf(left + 2.5f to midY, right - 2.5f to midY, midX to top + 2.5f, midX to bottom - 2.5f).forEach { (x, y) ->
                val pixel = bitmap.getPixel((x * density).roundToInt(), (y * density).roundToInt())
                assertTrue(
                    listOf(16, 8, 0).all { shift -> abs(((pixel shr shift) and 255) - ((expected shr shift) and 255)) <= 8 },
                    "Artwork reaches outline's inner adjacency at ($x,$y): ${Integer.toHexString(pixel)}"
                )
            }
            listOf(left to midY, right - .5f to midY, midX to top, midX to bottom - .5f).forEach { (x, y) ->
                val pixel = bitmap.getPixel((x * density).roundToInt(), (y * density).roundToInt())
                val color = Color(pixel)
                assertTrue(ratio(color, Color(expected)) >= 3f, "Outline lost contrast at ($x,$y): ${Integer.toHexString(pixel)}")
                assertTrue(
                    listOf(16, 8, 0).all { shift ->
                        abs(((pixel shr shift) and 255) - ((outline.toArgb() shr shift) and 255)) <= 35
                    },
                    "Outline missing"
                )
            }
            val gutter = Color(bitmap.getPixel(((left - 4f) * density).roundToInt(), (midY * density).roundToInt()))
            assertTrue(ratio(outline, gutter) >= 3f, "Outline lost contrast against outer gutter")
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun focusedCardOutlineHasKnownDarkPixelsOnItsArtworkSide() = assertArtworkKeyline(true)

    @Test
    fun selectedCardOutlineHasKnownDarkPixelsOnItsArtworkSide() = assertArtworkKeyline(false)

    @Test
    fun preparingStatusKeepsContrastOverAWhiteDecodedFrame() {
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.fillMaxSize().background(Color.White).padding(40.dp)) { PlaybackStatus("Preparing playback…") }
            }
        }
        val node = compose.onNodeWithText("Preparing playback…")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val foreground = layouts.single().layoutInput.style.color
        val bounds = node.getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) }
        try {
            val background =
                Color(bitmap.getPixel(((bounds.left.value + 2f) * density).roundToInt(), ((bounds.top.value + 2f) * density).roundToInt()))
            assertTrue(ratio(foreground, background) >= 4.5f, "status text contrast ${ratio(foreground, background)}")
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun themeTextFocusAndArtworkScrimsMeetAaIncludingWhiteVideoFrames() {
        lateinit var scheme: ColorScheme
        compose.setContent { PictureshowTheme { scheme = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        val window = Color(0xFF10131C)
        val textPairs = listOf(
            "window" to (scheme.onSurface to window),
            "card" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "button" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "focused button" to (scheme.inverseOnSurface to scheme.inverseSurface),
            "muted" to (Color(0xFFB9C1D6) to window),
            "field" to (Color.White to Color(0xFF202635)),
            "error" to (Color(0xFFFFCCB8) to window),
            "badge over white art" to (scheme.onSurfaceVariant to Color(0xDD202635).compositeOver(Color.White)),
            "artwork fallback" to (scheme.onSurfaceVariant to Color(0xFF262D3D)),
            "controls over white video" to (scheme.onSurface to Color(0xE610131C).compositeOver(Color.White)),
            "track panel over white video" to (scheme.onSurface to Color(0xF510131C).compositeOver(Color.White)),
            "buffer error over white video" to (scheme.onSurface to Color(0xEE10131C).compositeOver(Color.White))
        )
        textPairs.forEach { (label, pair) ->
            val actual = ratio(pair.first, pair.second)
            println("$label: $actual")
            assertTrue(actual >= 4.5f, "$label contrast $actual")
        }
        listOf(window, scheme.surfaceVariant).forEach { background ->
            assertTrue(ratio(scheme.border, background) >= 3f, "card focus border against $background")
            assertTrue(ratio(Color(0xFFB8C4FF), background) >= 3f, "selection and field border against $background")
        }
        assertTrue(ratio(Color(0xFFB8C4FF), Color.DarkGray) >= 3f, "player progress")
    }
}
