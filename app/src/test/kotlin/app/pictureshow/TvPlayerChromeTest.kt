/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TrickplayInfo
import app.pictureshow.core.TrickplayTile
import app.pictureshow.core.User
import app.pictureshow.core.playback.SeekSteps
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TvPlayerChromeTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    private val account = Account(Server("s", "Demo", "https://media.example.test", "12"), User("u", "Viewer"), "token")

    @Test fun seekPreviewBubbleShowsFallbackTimeAndChapterWithoutTrickplay() {
        compose.setContent {
            PictureshowTheme {
                TvSeekPreviewBubble(
                    fraction = 0.5f,
                    positionMs = 125_000L,
                    chapterName = "Opening",
                    cache = null,
                    sourceId = null,
                    tile = null
                )
            }
        }
        compose.onNodeWithTag("player:seek-preview").assertIsDisplayed()
        compose.onNodeWithText("2:05", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Opening", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun seekPreviewBubbleLoadsTrickplayFixtureAndShowsTimeAndChapter() {
        val sheetBytes = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        }.toByteArray()
        var fetched = 0
        val http = HttpClient(
            MockEngine {
                fetched++
                respond(sheetBytes)
            }
        )
        val cache = TrickplayCache(http, account, ClientIdentity("TV", "device"), "film")
        val tile = TrickplayTile(TrickplayInfo(320, 180, 2, 2, 4, 10_000), 0, 1, 0)
        try {
            compose.setContent {
                PictureshowTheme {
                    TvSeekPreviewBubble(
                        fraction = 0.25f,
                        positionMs = 15_000L,
                        chapterName = "Museum",
                        cache = cache,
                        sourceId = "source",
                        tile = tile
                    )
                }
            }
            compose.waitUntil(5_000) { fetched == 1 }
            compose.onNodeWithTag("player:seek-preview").assertIsDisplayed()
            compose.onNodeWithText("0:15", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Museum", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            cache.close()
            http.close()
        }
    }

    @Test fun endsAtFormatsIn12And24HourClocksAndHonorsPlaybackSpeed() {
        // 2026-11-25T20:00:00Z + 1 hour at 2x speed = 20:30 UTC.
        val now = 1_795_636_800_000L
        val endsAt = assertNotNull(SeekSteps.endsAtEpochMs(now, positionMs = 0L, durationMs = 3_600_000L, speed = 2.0))
        assertEquals(now + 1_800_000L, endsAt)
        val clock24 = LiveTime(twentyFourHour = true, locale = Locale.US, zone = ZoneOffset.UTC, now = now)
        val clock12 = LiveTime(twentyFourHour = false, locale = Locale.US, zone = ZoneOffset.UTC, now = now)
        val formatted24 = clock24.clock(endsAt)
        val formatted12 = clock12.clock(endsAt)
        assertEquals(true, "20" in formatted24 && "30" in formatted24, formatted24)
        assertEquals(true, "8" in formatted12 && "30" in formatted12, formatted12)
        assertNull(SeekSteps.endsAtEpochMs(now, positionMs = 0L, durationMs = 0L, speed = 1.0))
    }
}
