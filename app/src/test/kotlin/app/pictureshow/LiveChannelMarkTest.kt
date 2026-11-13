/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LiveChannel
import coil3.asImage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveChannelMarkTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvLogoFailureAndReplacementRetainMonograms() = logoLifecycle(true, false)

    @Test fun compactTvLogoFailureAndReplacementRetainMonograms() = logoLifecycle(true, true)

    @Test fun touchLogoFailureAndReplacementRetainMonograms() = logoLifecycle(false, false)

    @Test fun compactTouchLogoFailureAndReplacementRetainMonograms() = logoLifecycle(false, true)

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    private fun logoLifecycle(tv: Boolean, compact: Boolean) {
        val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")
        val channel = mutableStateOf(LiveChannel("c", "Aurora", "1"))
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val failed = AtomicInteger()
        val image = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.RED)
        val loader = coil3.ImageLoader.Builder(compose.activity).components {
            add(
                coil3.intercept.Interceptor { chain ->
                    started.incrementAndGet()
                    if (chain.request.data.toString().contains("success")) {
                        coil3.request.SuccessResult(image.asImage(), chain.request)
                    } else {
                        gate.await()
                        failed.incrementAndGet()
                        coil3.request.ErrorResult(null, chain.request, java.io.IOException("Generated missing logo"))
                    }
                }
            )
        }.build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        try {
            compose.setContent {
                if (tv) {
                    PictureshowTheme { Box { LiveChannelMark(channel.value, account, tv, compact) } }
                } else {
                    TouchTheme { Box { LiveChannelMark(channel.value, account, tv, compact) } }
                }
            }
            compose.onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
            val original = compose.onNodeWithTag("live:channel-mark:c").getUnclippedBoundsInRoot()
            val size = if (!compact) {
                44.dp
            } else if (tv) {
                32.dp
            } else {
                28.dp
            }
            assertEquals(size, original.right - original.left)
            compose.runOnIdle { channel.value = channel.value.copy(imageTags = mapOf("Primary" to "missing")) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.waitUntil(10_000) { started.get() >= 1 }
            compose.onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
            gate.complete(Unit)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.waitUntil(10_000) { failed.get() >= 1 }
            compose.waitForIdle()
            compose.onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(original, compose.onNodeWithTag("live:channel-mark:c").getUnclippedBoundsInRoot())
            compose.runOnIdle { channel.value = channel.value.copy(imageTags = mapOf("Primary" to "success")) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("A", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("A", useUnmergedTree = true).assertDoesNotExist()
            compose.runOnIdle {
                channel.value = channel.value.copy(name = "Borealis", imageTags = mapOf("Primary" to "another-missing"))
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.waitUntil(10_000) { failed.get() >= 2 }
            compose.waitForIdle()
            compose.onNodeWithText("B", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(original, compose.onNodeWithTag("live:channel-mark:c").getUnclippedBoundsInRoot())
            compose.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            gate.complete(Unit)
            coil3.SingletonImageLoader.reset()
            loader.shutdown()
            image.recycle()
        }
    }
}
