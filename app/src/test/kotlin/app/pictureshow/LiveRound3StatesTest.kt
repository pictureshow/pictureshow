/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LivePolicy
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.LiveTvService
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi", shadows = [LiveClockPatternShadow::class])
class LiveRound3StatesTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")

    @Test fun deniedTvHasOneFocusedRealActionAndNoUnavailableTools() = denied(true)

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun deniedPhoneHasOneRealActionAndNoUnavailableTools() = denied(false)

    private fun denied(tv: Boolean) {
        val fake = LiveFake().apply { rights = LivePolicy() }
        var backs = 0
        compose.setContent {
            if (tv) {
                PictureshowTheme {
                    LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, tv, {}, {}, { backs++ })
                }
            } else {
                TouchTheme {
                    LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, tv, {}, {}, { backs++ })
                }
            }
        }
        compose.onNodeWithText("Live TV isn't available on this account").assertIsDisplayed()
        compose.onNodeWithText("Ask your server administrator to allow Live TV access.").assertIsDisplayed()
        compose.onNodeWithTag("live:filters").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search channels").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh").assertDoesNotExist()
        val action = compose.onNodeWithTag("live:empty-action").assertIsDisplayed()
        if (tv) action.assertIsFocused()
        val left = compose.onNodeWithText("Live TV isn't available on this account").getUnclippedBoundsInRoot().left
        assertEquals(if (tv) 32.dp else 16.dp, left)
        action.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun emptyFavoritesExplainsStarsAndShowAllRestoresChannels() {
        compose.setContent {
            PictureshowTheme {
                LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, LiveFake()) }, account, true, {}, {}, {})
            }
        }
        compose.onNodeWithText("Favorites").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Favorites").assertIsSelected()
        compose.onNodeWithText("No favourite channels yet").assertIsDisplayed()
        compose.onNodeWithText("Star a channel to pin it here.").assertIsDisplayed()
        compose.onNodeWithTag("live:empty-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.onNodeWithTag("live:empty").assertDoesNotExist()
    }

    @Test fun coldFailedReadHasRetryAndNeverPretendsTheGuideWasSuccessfullyEmpty() {
        val fake = LiveFake().apply { failPrograms = true }
        compose.setContent {
            PictureshowTheme {
                LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, true, {}, {}, {})
            }
        }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Couldn't load Live TV").assertIsDisplayed()
        compose.onNodeWithText("No guide data").assertDoesNotExist()
        compose.runOnIdle { fake.failPrograms = false }
        compose.onNodeWithTag("live:empty-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Couldn't load Live TV").assertDoesNotExist()
        compose.onNodeWithText("No guide data").assertIsDisplayed()
        compose.onNodeWithTag("live:guide-rows").assertDoesNotExist()
        compose.onNodeWithTag("live:empty-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
    }

    @Test fun cachedReadFailureHasATimestampAndInlineRetryThatRecoversWithoutTuning() {
        val clock = System.currentTimeMillis()
        val fake = LiveFake().apply {
            show =
                show.copy(
                    startDate = java.time.Instant.ofEpochMilli(clock - 60_000).toString(),
                    endDate = java.time.Instant.ofEpochMilli(
                        clock + 1_800_000
                    ).toString()
                )
        }
        val vm = LiveTvViewModel(account, fake)
        var plays = 0
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, { plays++ }, {}, {}) } }
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.runOnIdle {
            fake.failPrograms = true
            vm.refresh()
        }
        compose.onNodeWithText("Couldn't reach the server. Showing the guide from ", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("live:c:c").assertIsDisplayed()
        compose.runOnIdle { fake.failPrograms = false }
        compose.onNodeWithContentDescription("Retry").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:guide-warning").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(false, vm.state.value.offline)
        }
    }

    @Test fun aSuccessfullyEmptyGuideRetainsTimeNavigationAboveTheSingleMessage() {
        val vm = LiveTvViewModel(account, LiveFake())
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("No guide data").assertIsDisplayed()
        compose.onNodeWithTag("live:guide-rows").assertDoesNotExist()
        val before = vm.state.value.windowStart
        compose.onNodeWithContentDescription("Later").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(before + LiveTvViewModel.WINDOW, vm.state.value.windowStart) }
        compose.onNodeWithText("No guide data").assertIsDisplayed()
    }

    @Test fun seriesRowsOmitOnlyTheRedundantRuleBadgeAndRetainARealFailureStatus() {
        val row = androidx.compose.runtime.mutableStateOf(
            LiveSchedule(
                buildJsonObject {
                    put("Id", "rule")
                    put("Name", "Field Notes")
                }
            )
        )
        compose.setContent { PictureshowTheme { LiveScheduleRow(row.value, true, Modifier, true) {} } }
        compose.onNodeWithText("Series rule").assertDoesNotExist()
        compose.runOnIdle {
            row.value =
                LiveSchedule(
                    buildJsonObject {
                        put("Id", "rule")
                        put("Name", "Field Notes")
                        put("Status", "Error")
                    }
                )
        }
        compose.onNodeWithText("Failed").assertIsDisplayed()
    }

    @Test fun deleteErrorUsesTheSameHttpFreeCopyAsTheScreen() {
        compose.setContent { PictureshowTheme { LiveRecordingDeleteDialog("Blue Hour", true, false, "Request failed (503).", {}, {}) } }
        compose.onNodeWithText("Couldn't complete this change. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Request failed (503).").assertDoesNotExist()
        compose.onNodeWithTag("live:delete:cancel").assertIsFocused()
    }

    @Test fun tvChannelArtworkAndHeadlineHaveSixteenDpOfActualNativeSpace() {
        compose.setContent {
            PictureshowTheme {
                LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, LiveFake()) }, account, true, {}, {}, {})
            }
        }
        val mark = compose.onNodeWithTag("live:channel-mark:c", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val headline = compose.onNodeWithText("Generated", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(16.dp, headline.left - mark.right)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun phoneFavoritesHasASelectedNativeChipAndDecorativeCheck() {
        compose.setContent {
            TouchTheme {
                LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, LiveFake()) }, account, false, {}, {}, {})
            }
        }
        compose.onNodeWithTag("live:filter-check", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Favorites").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Favorites").assertIsSelected()
        compose.onNodeWithTag("live:filter-check", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun unknownPermissionAfterAColdFailureStillOffersWorkingRetryOnSchedules() {
        val fake = LiveFake()
        var failed = true
        val api = object : LiveTvService by fake {
            override suspend fun policy(account: Account) =
                if (failed) ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Request failed (503).")) else fake.policy(account)
        }
        val vm = LiveTvViewModel(account, api)
        compose.setContent { TouchTheme { LiveTvScreen(vm, account, false, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Schedules").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Couldn't load Live TV").assertIsDisplayed()
        compose.onNodeWithText("Recording schedules aren't available").assertDoesNotExist()
        compose.runOnIdle { failed = false }
        compose.onNodeWithTag("live:empty-action").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("No scheduled recordings").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tabletGuideUsesTheWideNativeChannelColumnAndKeepsDateArrowsTogether() {
        val now = System.currentTimeMillis()
        val fake = LiveFake().apply {
            show =
                show.copy(
                    startDate = java.time.Instant.ofEpochMilli(now - 60_000).toString(),
                    endDate = java.time.Instant.ofEpochMilli(
                        now + 1_800_000
                    ).toString()
                )
        }
        compose.setContent {
            TouchTheme { LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, false, {}, {}, {}) }
        }
        compose.onNodeWithTag("live:tab:Guide").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(200.dp, compose.onNodeWithTag("live:guide-channel:c", useUnmergedTree = true).getUnclippedBoundsInRoot().width)
        assertTrue(compose.onNodeWithTag("live:guide-time").getUnclippedBoundsInRoot().width <= 400.dp)
    }

    @Test fun tvRecordingMenuStartsOnPlayAndInvokesOrdinaryPlaybackWithoutDeleting() {
        val fake = LiveFake().apply { recordingRows = listOf(MediaItem("rec", "Blue Hour", "Episode", canDelete = true)) }
        var played: String? = null
        compose.setContent {
            PictureshowTheme {
                LiveTvScreen(androidx.compose.runtime.remember { LiveTvViewModel(account, fake) }, account, true, {
                    played =
                        it.id
                }, {}, {})
            }
        }
        compose.onNodeWithTag("live:tab:Recordings").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:r:rec").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("Play").assertIsDisplayed().assertIsFocused().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals("rec", played)
            assertEquals(0, fake.deleted)
        }
        compose.onNodeWithTag("live:delete-dialog").assertDoesNotExist()
    }

    @Test fun warningPaintIsDarkAndContrastsWithItsTextInsteadOfLookingFocused() {
        compose.setContent {
            PictureshowTheme { Box(Modifier.fillMaxSize()) { LiveGuideWarning("Guide is out of date.", true, true, onRefresh = {}) } }
        }
        val image = compose.onNodeWithTag("live:guide-warning").captureToImage().toPixelMap()
        val paint = image[image.width / 2, 2]
        assertTrue(abs(paint.red - LiveWarningContainer.red) < .02f)
        assertTrue(abs(paint.green - LiveWarningContainer.green) < .02f)
        assertTrue(paint.luminance() < .15f, "A warning is a dark tonal surface; light fill is reserved for focus")
        assertTrue((LiveWarning.luminance() + .05f) / (paint.luminance() + .05f) >= 4.5f)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun librariesOffersLiveTvAsANativeCardWithAnIndependentCallback() {
        val library = MediaItem("movies", "Movies", "CollectionFolder")
        var live = 0
        var opened = 0
        compose.setContent {
            TouchTheme {
                TouchCatalogHome(CatalogHomeState(libraries = listOf(library), loading = false), account, {
                    opened++
                }, {}, librariesOnly = true, onLiveTv = { live++ })
            }
        }
        val card = compose.onNodeWithTag("touch:card:destination:live-tv").assertIsDisplayed()
        assertEquals(20.dp, card.getUnclippedBoundsInRoot().left)
        card.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(1, live)
            assertEquals(0, opened)
        }
        compose.onNodeWithTag("touch:card:movies").assertIsDisplayed()
        assertEquals("libraries", touchTabFor("live-tv"))
    }
}
