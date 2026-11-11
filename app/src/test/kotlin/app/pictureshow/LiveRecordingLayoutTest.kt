/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LivePolicy
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
class LiveRecordingLayoutTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")
    private val recording = MediaItem(
        "r",
        "Generated episode",
        "Episode",
        seriesName = "Generated series",
        runTimeTicks = 1200000000,
        canDownload = true,
        recordingStatus = "Completed",
        overview = "An original generated recording description."
    )

    @Test fun recordingDetailKeepsPlayAndDownloadInOneVisibleNativeActionRow() {
        var downloads = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, recording, loading = false),
                    {},
                    {},
                    {},
                    {},
                    {},
                    recordingContext = true,
                    onDownload = { _, _, _ -> downloads++ }
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val play = compose.onNodeWithTag("detail:play").assertIsDisplayed().assertIsFocused().getUnclippedBoundsInRoot()
        val download = compose.onNodeWithTag("detail:download").assertIsDisplayed().getUnclippedBoundsInRoot()
        listOf("played", "favorite", "download").forEach { id ->
            val bounds = compose.onNodeWithTag("detail:$id").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(kotlin.math.abs(bounds.top.value - play.top.value) <= 3, "All actions share the same row: $id $bounds / $play")
            assertTrue(bounds.bottom <= 508.dp && bounds.bottom - bounds.top in 48.dp..53.dp)
        }
        compose.onNodeWithTag("detail:hero", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("detail:title").getUnclippedBoundsInRoot().top >= 32.dp)
        assertTrue(download.right <= 912.dp)
        compose.onNodeWithTag("detail:download").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertEquals(1, downloads)
        assertTrue(liveRecordingItemRoute(recording).endsWith("&liveRecording=true"))
        assertTrue(!itemRoute(recording).contains("liveRecording"), "Ordinary item routes retain their layout default")
    }

    @Test fun ordinaryCatalogDetailRetainsItsExisting220dpHero() {
        compose.setContent {
            PictureshowTheme { CatalogDetail(CatalogDetailState(account, recording, loading = false), {}, {}, {}, {}, {}) }
        }
        val hero = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(220.dp, hero.bottom - hero.top)
    }

    @Test fun recordingCardLongPressAndMenuOpenOptionsWithoutActivatingPlayback() {
        var opened = 0
        var options = 0
        compose.setContent {
            PictureshowTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    liveRecordingShelves(
                        LiveTvState(
                            loading = false,
                            policy = LivePolicy(access = true, deleteContent = true),
                            recordings = listOf(recording)
                        ),
                        account, true, false, null, 0, {}, { opened++ }, { options++ }
                    )
                }
            }
        }
        compose.onNodeWithTag("live:r:r").assertIsFocused().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnLongClick
        ) { assertTrue(it()) }
        compose.onNodeWithTag("live:r:r").performKeyInput { pressKey(Key.Menu) }
        assertEquals(2, options)
        assertEquals(0, opened)
    }
}
