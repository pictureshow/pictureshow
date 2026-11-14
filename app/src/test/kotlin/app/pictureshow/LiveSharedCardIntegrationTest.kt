/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w384dp-h853dp-mdpi")
class LiveSharedCardIntegrationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sharedCardKeepsRecordingCaptionAndMeasuredProgressIndependentOfWatchedState() {
        val show = mutableStateOf(true)
        var primary = Color.Unspecified
        val item = MediaItem("recording", "Generated recording", "Episode", userData = ItemUserData(played = true))
        val account = Account(Server("s", "Demo", "https://media.example.test", "12"), User("u", "Viewer"), "fake")
        compose.setContent {
            TouchTheme {
                primary = MaterialTheme.colorScheme.primary
                TouchCatalogCard(
                    item,
                    account,
                    true,
                    {},
                    Modifier.width(240.dp),
                    artHeight = 100.dp,
                    caption = { Text("Recording metadata") },
                    artworkProgress = .5f,
                    showProgress = show.value
                )
            }
        }
        compose.onNodeWithText("Recording metadata").assertIsDisplayed()
        fun progressPixel() = compose.onNodeWithTag("touch:card:recording").captureToImage().toPixelMap()[40, 102]
        assertEquals(primary, progressPixel(), "Measured recording progress remains visible even when the item was watched")
        compose.runOnIdle { show.value = false }
        assertNotEquals(primary, progressPixel(), "Unmeasured growing recordings must not borrow watched-file progress")
        compose.onNodeWithText("Recording metadata").assertIsDisplayed()
    }
}
