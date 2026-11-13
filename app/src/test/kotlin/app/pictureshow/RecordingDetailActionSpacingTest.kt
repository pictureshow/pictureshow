/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
class RecordingDetailActionSpacingTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun normalTextKeepsEqualControlGapsWithFocusCaptions() = verify(1f)

    @Test fun largeTextKeepsEqualControlGapsWithFocusCaptions() = verify(1.3f)

    private fun verify(fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                PictureshowTheme {
                    Box(Modifier.fillMaxSize().background(LiveBackground).padding(48.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(48.dp).testTag("preceding"))
                            listOf("played" to "Mark as unwatched", "favorite" to "Remove from favorites").forEach { (target, label) ->
                                RecordingDetailMutationButton(
                                    target,
                                    label,
                                    false,
                                    true,
                                    true,
                                    Modifier.testTag(target),
                                    containerModifier = Modifier.testTag("$target-slot"),
                                    onClick = {}
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val preceding = bounds("preceding")
        val played = bounds("played-slot")
        val favorite = bounds("favorite-slot")
        assertEquals(48.dp, played.width, "A hidden or focus-visible caption must not reserve horizontal space")
        assertEquals(48.dp, favorite.width)
        assertEquals(16.dp, played.left - preceding.right)
        assertEquals(16.dp, favorite.left - played.right)
        assertEquals(played.top, favorite.top)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val rest = pixels[(favorite.left.value + 24).toInt(), (favorite.top.value + 8).toInt()]
        assertTrue(rest.red > LiveBackground.red + .025f, "An unselected action keeps a quiet visible circular container: $rest")
        compose.onNodeWithTag("played").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("played").assertIsFocused()
        assertEquals(favorite, bounds("favorite-slot"), "Focusing the neighbouring icon must not move this target")
    }
}
