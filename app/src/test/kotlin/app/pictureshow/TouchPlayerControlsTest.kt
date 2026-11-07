/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentType
import app.pictureshow.core.Server
import app.pictureshow.core.TrickplayInfo
import app.pictureshow.core.TrickplayTile
import app.pictureshow.core.User
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import com.google.common.util.concurrent.Futures
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlayerControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val account = Account(Server("server", "Server", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var host: PlaybackHostState
    private var exits = 0
    private var liveFontScale by androidx.compose.runtime.mutableStateOf<Float?>(null)
    private var playerShown by androidx.compose.runtime.mutableStateOf(true)

    @After fun cleanup() {
        compose.runOnIdle {
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
    }

    @Test fun tapHidesAndShowsControls() {
        launch()
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        assertEquals("Show controls", compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
    }

    // PLAY-04: the shared 10 s, not a shorter touch one.
    @Test fun playingHidesAfterTenSecondsAndPausePinsControls() {
        compose.mainClock.autoAdvance = false
        launch()
        compose.mainClock.advanceTimeBy(9_000)
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        compose.runOnIdle { decoder.pause() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test fun leftAndRightDoubleTapSeekTenSecondsAndClamp() {
        launch(paused = true)
        compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.2f, height * 0.3f)) }
        compose.runOnIdle { assertEquals(10_000, host.position) }
        compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.8f, height * 0.3f)) }
        compose.runOnIdle { assertEquals(20_000, host.position) }
        compose.onNodeWithTag("touch:scrubber").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(58_000f)) }
        compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.8f, height * 0.3f)) }
        compose.runOnIdle { assertEquals(60_000, host.position) }
        compose.onNodeWithTag("touch:scrubber").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(2_000f)) }
        compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.2f, height * 0.3f)) }
        compose.runOnIdle { assertEquals(0, host.position) }
    }

    @Test fun scrubberSemanticsCommitsAbsolutePositionToDecoder() {
        launch(paused = true)
        compose.onNodeWithTag("touch:scrubber").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(37_000f)) }
        compose.runOnIdle {
            assertEquals(37_000, host.position)
            assertEquals(37_000, decoder.currentPosition)
        }
    }

    @Test fun quickDoubleTapsAccumulateAgainstTheLastTarget() {
        launch(paused = true)
        repeat(2) { compose.onNodeWithTag("touch:gestures").performTouchInput { doubleClick(Offset(width * 0.8f, height * 0.3f)) } }
        compose.runOnIdle { assertEquals(40_000, decoder.seeks.last()) }
    }

    @Test fun scrubberDragPreviewsBeforeCommitAndCommitsOnRelease() {
        launch(paused = true)
        compose.onNodeWithTag("touch:scrubber").performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveTo(Offset(width * 0.7f, height / 2f))
        }
        compose.runOnIdle {
            assertEquals(20_000, host.position, "Dragging previews without committing")
            assertEquals(20_000, decoder.currentPosition, "The decoder must not seek until release")
        }
        compose.onNodeWithTag("touch:scrubber").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(host.position > 20_000)
            assertEquals(host.position, decoder.currentPosition)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun loadedThumbnailDoesNotMoveSliderDuringHeldDrag() {
        launch(paused = true)
        val info = TrickplayInfo(16, 9, 10, 6, 60, 1_000)
        val image = ByteArrayOutputStream().also {
            Bitmap.createBitmap(160, 54, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val http = HttpClient(MockEngine { respond(image) })
        val cache = TrickplayCache(http, account, ClientIdentity("Phone", "device"), "movie")
        try {
            // Load the actual sheet before the gesture, so the test includes bitmap layout.
            runBlocking { checkNotNull(cache.thumbnail("source", TrickplayTile(info, 0, 0, 0))) }
            compose.runOnIdle {
                host.metadata = host.metadata.copy(trickplay = mapOf("source" to mapOf("16" to info)))
                host.tiles = cache
                host.stateSource = androidx.compose.runtime.mutableStateOf(
                    PlaybackState(
                        preparing = false,
                        decision = PlayDecision(
                            "movie",
                            MediaSourceInfo(id = "source"),
                            PlayMethod.DirectPlay,
                            "https://example.test/video",
                            emptyMap(),
                            "session"
                        )
                    )
                )
            }
            val before = compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("touch:scrubber").performTouchInput {
                down(Offset(width * 0.3f, height / 2f))
                moveTo(Offset(width * 0.7f, height / 2f))
            }
            compose.onNodeWithTag("touch:thumbnail").assertIsDisplayed()
            val during = compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode().boundsInRoot
            assertEquals(before.center.y, during.center.y, "A loaded preview must not move the track away from the held finger")
            compose.runOnIdle { assertEquals(20_000, decoder.currentPosition) }
            compose.onNodeWithTag("touch:scrubber").performTouchInput { up() }
            compose.runOnIdle {
                assertTrue(host.position > 20_000)
                assertEquals(host.position, decoder.currentPosition)
            }
        } finally {
            cache.close()
            http.close()
        }
    }

    @Test fun heldScrubberSuspendsAutomaticNextEpisodeCountdown() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            host.position = 50_000
            host.segments = listOf(MediaSegment("outro", SegmentType.Outro, 450_000_000, 600_000_000))
            host.nextLookup = EpisodeLookup(0, ClientResult.Success(MediaItem("next", "Another episode")))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("next:play").assertIsDisplayed()
        compose.onNodeWithTag("touch:scrubber").performTouchInput {
            down(Offset(width * 0.8f, height / 2f))
            moveTo(Offset(width * 0.3f, height / 2f))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(15_000)
        compose.runOnIdle { assertEquals(0, exits, "Continuation must not replace the item during a held seek") }
        compose.onNodeWithTag("next:play").assertIsDisplayed()
        compose.onNodeWithTag("touch:scrubber").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(decoder.seeks.last() < 45_000, "Release commits the seek back before the outro")
            assertEquals(host.position, decoder.seeks.last())
        }
    }

    @Test fun allSheetsOpenAndDoneDismisses() {
        launch()
        TouchPlayerSheet.entries.forEach { sheet ->
            compose.onNodeWithTag("touch:open:$sheet").performClick()
            compose.onNodeWithTag("touch:sheet:$sheet").assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("touch:sheet:$sheet").assertDoesNotExist()
        }
    }

    @Test fun chapterSelectionSeeksAndDismisses() {
        launch()
        compose.onNodeWithTag("touch:open:Chapters").performClick()
        compose.onNodeWithTag("touch:chapter:1").performClick()
        compose.runOnIdle { assertEquals(30_000, host.position) }
        compose.onNodeWithTag("touch:sheet:Chapters").assertDoesNotExist()
    }

    @Test fun systemBackClosesSheetThenControlsThenExitsOnce() {
        launch()
        compose.onNodeWithTag("touch:open:Audio").performClick()
        back()
        compose.onNodeWithTag("touch:sheet:Audio").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        back()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        back()
        back()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun systemBackLeavesAPausedPlayerTheSameWay() {
        launch(paused = true)
        compose.onNodeWithTag("touch:open:Audio").performClick()
        back()
        compose.onNodeWithTag("touch:sheet:Audio").assertDoesNotExist()
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
        back()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, exits) }
        back()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    // The sheet measures once as it opens: choices prepared with the player mean it never measures a waiting line.
    @Test fun aTrackSheetOpensWithItsChoicesAlreadyThere() {
        launch()
        decided(PlayMethod.DirectPlay)
        compose.waitUntil(5_000) { host.state.decision != null }
        Thread.sleep(200) // the labels are prepared on a background dispatcher
        settled()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("touch:open:Audio").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Waiting for playback information…").assertDoesNotExist()
        settled()
    }

    // Phone 15b: a paused player with no controls looks dead, however the controls came to be down.
    @Test fun comingBackToAPausedPlayerShowsTheControls() {
        launch(paused = true)
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        settled()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        compose.runOnIdle { output.setForeground(false) }
        compose.waitForIdle()
        compose.runOnIdle { output.setForeground(true) }
        settled()
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed()
    }

    // Tablet N1: every rate's word starts on the column's edge.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    @Test
    fun theSpeedLabelStartsOnItsColumn() {
        launch()
        settled()
        val cell = bounds("touch:open:Speed")
        val word = compose.onNodeWithText("Speed · 1×", useUnmergedTree = true).getBoundsInRoot()
        assertEquals((cell.left + 8.dp).value, word.left.value, 1f)
    }

    // Phone T2: a wide window holds the notice beside the column, so Skip stays at the top, on the player's one edge.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    @Test
    fun aWideWindowHoldsTheNoticeBesideTheActions() {
        launch()
        decided(PlayMethod.Transcode)
        compose.runOnIdle { host.segments = listOf(MediaSegment("intro", SegmentType.Intro, 0, 600_000_000)) }
        settled()
        val notice = bounds("touch:conversion-notice")
        val actions = bounds("touch:actions")
        assertTrue(notice.right + 12.dp <= actions.left + 16.dp + 1.dp, "notice $notice beside column $actions")
        assertTrue(bounds("touch:skip").top < 24.dp, "Skip at the top: ${bounds("touch:skip")}")
        assertEquals(914f - 16f, bounds("touch:skip").right.value, 1f)
    }

    @Test fun subtitleDelayIsBoundedAndResettable() {
        launch()
        decided(PlayMethod.DirectPlay)
        compose.onNodeWithTag("touch:open:Subtitles").performClick()
        compose.onNodeWithText("Later").performClick()
        compose.runOnIdle { assertEquals(100, host.subtitleDelayMs) }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(0, host.subtitleDelayMs) }
    }

    @Test fun talkBackCanShowControlsAndReachEveryActionWithoutAutoHide() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val manager = compose.activity.getSystemService(
                android.content.Context.ACCESSIBILITY_SERVICE
            ) as android.view.accessibility.AccessibilityManager
            org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        }
        launch()
        compose.mainClock.advanceTimeBy(12_000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("touch:gestures").assertHasClickAction()
        assertEquals("Hide controls", compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("touch:gestures").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitForIdle()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        assertEquals("Show controls", compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("touch:gestures").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitForIdle()
        compose.onNodeWithTag("touch:toggle").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("touch:open:Audio").assertHasClickAction()
        compose.onNodeWithTag("touch:open:Subtitles").assertHasClickAction()
        compose.onNodeWithTag("touch:open:Chapters").assertHasClickAction()
        compose.onNodeWithTag("touch:stop").assertHasClickAction()
        compose.onNodeWithTag("touch:scrubber").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(25_000f)) }
        compose.runOnIdle { assertEquals(25_000, decoder.seeks.last()) }
    }

    @Test fun speedSheetChangesTheViewingSpeedAndTheButtonNamesIt() {
        launch()
        compose.onNodeWithText("Speed · 1×").assertIsDisplayed()
        compose.onNodeWithTag("touch:open:Speed").performClick()
        compose.onNodeWithText(PLAYBACK_SPEED_NOTE).assertIsDisplayed()
        compose.onNodeWithTag("touch:speed:150").performClick()
        compose.onNodeWithTag("touch:sheet:Speed").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1.5f, output.speed.value.speed) }
        compose.onNodeWithText("Speed · 1.5×").assertIsDisplayed()
    }

    @Test fun encodedAudioOnlyOffersNormalSpeed() {
        assertEquals(listOf(1f), availablePlaybackSpeeds(PlaybackSpeedState(encodedAudio = true)))
        assertTrue(availablePlaybackSpeeds(PlaybackSpeedState()).size > 1)
    }

    @Test fun playbackInfoSheetCarriesWhyWhatTheServerReportsAndWhatArrived() {
        launch()
        decided(PlayMethod.Transcode)
        compose.onNodeWithTag("touch:open:Info").performClick()
        compose.onNodeWithTag("touch:info").assertIsDisplayed()
        listOf("Why the server converts this stream", "Server-reported media", "Stream received by this device").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(PLAYBACK_INFORMATION_NOTE).performScrollTo().assertIsDisplayed()
    }

    @Test fun playbackInfoExplainsAConversionOnlyWhenThereIsOne() {
        val sections = { method: PlayMethod ->
            playbackInformationSections(decision(method), 1, -1, null, androidx.media3.common.Tracks.EMPTY).map { it.title }
        }
        assertEquals("Why the server converts this stream", sections(PlayMethod.Transcode).first())
        assertEquals(listOf("Server-reported media", "Stream received by this device"), sections(PlayMethod.DirectPlay))
    }

    @Test fun conversionNoticeNamesThisPhoneAndATapOpensPlaybackInfo() {
        launch()
        decided(PlayMethod.Transcode)
        compose.onNodeWithTag("touch:conversion-notice").assertIsDisplayed()
        compose.onNodeWithText(TOUCH_CONVERSION_NOTICE_ACTION, substring = true).assertIsDisplayed()
        compose.onNodeWithText("this phone", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("touch:conversion-notice").performClick()
        compose.onNodeWithTag("touch:sheet:Info").assertIsDisplayed()
        compose.onNodeWithTag("touch:conversion-notice").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "sw800dp-w800dp-h1280dp-xhdpi")
    fun conversionNoticeNamesThisTablet() {
        launch()
        decided(PlayMethod.Transcode)
        compose.onNodeWithText("this tablet", substring = true).assertIsDisplayed()
    }

    @Test fun conversionNoticeStaysOffWhenDirectPlayOrSwitchedOff() {
        launch()
        decided(PlayMethod.DirectPlay)
        compose.onNodeWithTag("touch:conversion-notice").assertDoesNotExist()
        compose.runOnIdle { host.localPreferences = host.localPreferences.copy(conversionNotice = false) }
        decided(PlayMethod.Transcode)
        compose.onNodeWithTag("touch:conversion-notice").assertDoesNotExist()
    }

    @Test fun conversionNoticeLeavesAfterTenSeconds() {
        launch()
        decided(PlayMethod.Transcode)
        compose.onNodeWithTag("touch:conversion-notice").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(CONVERSION_NOTICE_MS + 500)
        compose.onNodeWithTag("touch:conversion-notice").assertDoesNotExist()
    }

    @Test fun deferredNextWaitsForPlayWithoutACountdownAndThePlayButtonTakesIt() {
        var played: MediaItem? = null
        launch(paused = true, onNext = { played = it })
        val next = MediaItem("next", "Another episode")
        compose.runOnIdle {
            host.advanceReported = true
            host.deferredNext = next
        }
        compose.onNodeWithTag("next:play").assertIsDisplayed()
        compose.onNodeWithText("Next episode · ", substring = true).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(20_000)
        compose.runOnIdle { assertEquals(null, played, "A deferred next never starts itself") }
        compose.onNodeWithTag("touch:toggle").performClick()
        compose.runOnIdle { assertEquals(next, played) }
    }

    @Test fun readerGetsSeekActionsWithoutShowingControlsAndNamedSteps() {
        launch()
        compose.onNodeWithContentDescription("Back 10 seconds").assertHasClickAction()
        compose.onNodeWithContentDescription("Forward 10 seconds").assertHasClickAction()
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        val actions = compose.onNodeWithTag("touch:gestures").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Back 10 seconds", "Forward 10 seconds"), actions.map { it.label })
        compose.runOnIdle { assertTrue(actions.first().action!!()) }
        compose.runOnIdle { assertTrue(decoder.seeks.last() in 10_000..12_000, "A step moves from where playback really is") }
    }

    @Test fun scrubberSpeaksThePositionInWordsAndNeverPerTick() {
        launch(paused = true)
        val spoken = { compose.onNodeWithTag("touch:scrubber").fetchSemanticsNode().config[SemanticsProperties.StateDescription] }
        compose.waitForIdle()
        val first = spoken()
        assertEquals(spokenPlaybackPosition(20_000, 60_000), first)
        compose.runOnIdle { host.position = 21_000 }
        compose.waitForIdle()
        assertEquals(first, spoken(), "A paused clock's tick must not change what the reader hears")
    }

    // Skip sits at the card's end, so the inner margin matches both sides, and Cancel stays put whether it comes or goes.
    @Test fun skipEndsOnTheCardsMarginAndCancelHoldsStill() {
        var skip by mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp)) {
                    TouchNextEpisodeCard("Another episode", 12, true, onPlay = {}, onCancel = {}, skipLabel = skip)
                }
            }
        }
        compose.waitForIdle()
        val cancel = compose.onNodeWithTag("next:cancel").getBoundsInRoot()
        compose.runOnIdle { skip = "Skip credits" }
        compose.waitForIdle()
        assertEquals(cancel, compose.onNodeWithTag("next:cancel").getBoundsInRoot())
        assertEquals(
            compose.onNodeWithTag("next:card").getBoundsInRoot().right.value - 12f,
            compose.onNodeWithTag("touch:skip").getBoundsInRoot().right.value,
            1f
        )
    }

    @Test fun readerHearsTheNextEpisodeArriveAndCanActOnIt() {
        var plays = 0
        var cancels = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TouchNextEpisodeCard(
                    "Another episode",
                    12,
                    false,
                    onPlay = { plays++ },
                    onCancel = { cancels++ },
                    announcement = "Season 1, Episode 2, Another episode"
                )
            }
        }
        compose.onNodeWithContentDescription("Next episode, Season 1, Episode 2, Another episode").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        )
        compose.onNodeWithContentDescription("Play now, next episode, Season 1, Episode 2, Another episode").performClick()
        compose.onNodeWithContentDescription("Cancel next episode").performClick()
        compose.runOnIdle { assertEquals(1 to 1, plays to cancels) }
    }

    @Test fun errorCardSaysWhenProgressCouldNotBeSaved() {
        launch()
        compose.runOnIdle {
            val failed = PlaybackState(preparing = false, error = "Playback failed", reportError = "Offline")
            host.stateSource = androidx.compose.runtime.mutableStateOf(failed)
        }
        compose.onNodeWithText("Could not save progress: Offline").assertIsDisplayed()
    }

    @Test fun leavingDoesNotBringTheControlsBackWhileTheExitFinishes() {
        launch()
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        settled()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        compose.runOnIdle {
            host.exitRequested = true
            decoder.pause()
        }
        settled()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
    }

    private fun settled() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun creditsCard(position: Long = 50_000) {
        compose.runOnIdle {
            host.position = position
            host.segments = listOf(MediaSegment("outro", SegmentType.Outro, 450_000_000, 600_000_000))
            host.nextLookup = EpisodeLookup(0, ClientResult.Success(MediaItem("next", "Another episode")))
        }
        settled()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot()

    // TOUCH-13: a number shows only while it is really counting.
    @Test fun nextCardCountsOnlyWhileTheCountdownRuns() {
        launch()
        creditsCard()
        compose.onNodeWithText("Next episode · ", substring = true).assertIsDisplayed()
        compose.runOnIdle { decoder.pause() }
        settled()
        compose.onNodeWithText("s left", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("next:play").assertIsDisplayed()
    }

    @Test fun nextCardShowsNoCountdownAfterABackgroundReturn() {
        launch()
        creditsCard()
        compose.runOnIdle {
            output.setForeground(false)
            output.setForeground(true)
        }
        settled()
        compose.onNodeWithText("s left", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("next:play").assertIsDisplayed()
    }

    // TOUCH-13, at the call site: with no credits the number is real time at the current speed, not media time.
    @Test fun aNoCreditsCardCountsRealTimeAtTwiceSpeed() {
        launch()
        compose.onNodeWithTag("touch:open:Speed").performClick()
        settled()
        compose.onNodeWithTag("touch:speed:200").performScrollTo().performClick()
        settled()
        compose.runOnIdle {
            host.position = 52_000
            host.nextLookup = EpisodeLookup(0, ClientResult.Success(MediaItem("next", "Another episode")))
        }
        settled()
        compose.onNodeWithText("Next episode · 4 s left").assertIsDisplayed()
    }

    // Tablet B1: a tap aimed at Cancel lands on Cancel, whatever else is happening.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-mdpi")
    @Test
    fun cancelKeepsOnePlaceWithControlsShownOrHiddenAndWithOrWithoutSkip() {
        launch()
        compose.runOnIdle { host.deferredNext = MediaItem("next", "Another episode") }
        settled()
        val shown = bounds("next:cancel")
        compose.onNodeWithTag("touch:gestures").performClick()
        compose.mainClock.advanceTimeBy(400)
        settled()
        compose.onNodeWithTag("touch:toggle").assertDoesNotExist()
        assertEquals(shown, bounds("next:cancel"), "hiding the controls moved Cancel")
        compose.runOnIdle {
            host.position = 50_000
            host.segments = listOf(MediaSegment("outro", SegmentType.Outro, 450_000_000, 600_000_000))
        }
        settled()
        assertEquals(1, compose.onAllNodesWithTag("touch:skip").fetchSemanticsNodes().size, "Skip travels inside the card")
        assertTrue(bounds("touch:skip").let { it.top >= bounds("next:card").top && it.bottom <= bounds("next:card").bottom })
        assertEquals(shown, bounds("next:cancel"), "Skip arriving moved Cancel")
    }

    @Test fun cardsAndSkipShareOneRightEdge() {
        launch()
        compose.runOnIdle {
            host.position = 10_000
            host.segments = listOf(MediaSegment("intro", SegmentType.Intro, 0, 200_000_000))
        }
        settled()
        val skip = bounds("touch:skip")
        compose.runOnIdle { host.deferredNext = MediaItem("next", "Another episode") }
        settled()
        assertEquals(skip.right, bounds("next:card").right)
    }

    @Test fun narrowNoticeAndNextCardDoNotOverlap() {
        launch()
        decided(PlayMethod.Transcode)
        compose.runOnIdle { host.deferredNext = MediaItem("next", "Another episode") }
        settled()
        assertTrue(bounds("touch:conversion-notice").bottom <= bounds("next:card").top)
    }

    // TOUCH-13: Cancel never moves, across the notice's end too.
    @Test fun theNoticeEndingLeavesTheNextCardWhereItIs() {
        launch()
        decided(PlayMethod.Transcode)
        compose.runOnIdle { host.deferredNext = MediaItem("next", "Another episode") }
        settled()
        val cancel = bounds("next:cancel")
        compose.onNodeWithTag("touch:conversion-notice").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(CONVERSION_NOTICE_MS + 100)
        compose.waitForIdle()
        compose.onNodeWithTag("touch:conversion-notice").assertDoesNotExist()
        assertEquals(cancel, bounds("next:cancel"))
    }

    @Test fun aFontChangeLetsGoOfTheGapHeldFromTheOldSize() {
        launch()
        decided(PlayMethod.Transcode)
        compose.runOnIdle {
            host.segments = listOf(MediaSegment("intro", SegmentType.Intro, 150_000_000, 300_000_000))
        }
        settled()
        val held = bounds("touch:skip")
        compose.runOnIdle { liveFontScale = 1.8f }
        settled()
        compose.onNodeWithTag("touch:conversion-notice").assertIsDisplayed()
        compose.runOnIdle { liveFontScale = 1f }
        settled()
        assertEquals(held, bounds("touch:skip"))
    }

    @Test fun stopsTargetAnswersRightToItsEdge() {
        launch()
        settled()
        compose.onNodeWithTag("touch:stop").performTouchInput { click(Offset(width - 2f, height / 2f)) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun theNoticeGapClosesOnceTheColumnIsEmpty() {
        launch()
        decided(PlayMethod.Transcode)
        compose.runOnIdle {
            host.segments = listOf(MediaSegment("intro", SegmentType.Intro, 150_000_000, 300_000_000))
        }
        settled()
        val held = bounds("touch:skip")
        assertTrue(bounds("touch:conversion-notice").bottom <= held.top)
        compose.mainClock.advanceTimeBy(CONVERSION_NOTICE_MS + 100)
        compose.waitForIdle()
        assertEquals(held, bounds("touch:skip"), "Skip keeps its place when the notice ends")
        compose.runOnIdle { host.position = 40_000 }
        settled()
        compose.onNodeWithTag("touch:skip").assertDoesNotExist()
        compose.runOnIdle { host.position = 20_000 }
        settled()
        assertTrue(bounds("touch:skip").top < held.top, "the next Skip starts at the top")
    }

    // Phone B9: the most used control moves nothing when it changes.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun playAndPauseTakeOneWidth() {
        launch()
        settled()
        val pausing = listOf("touch:toggle", "touch:back10", "touch:forward10", "touch:stop").map(::bounds)
        compose.onNodeWithTag("touch:toggle").performClick()
        settled()
        compose.onNodeWithText("Play").assertIsDisplayed()
        assertEquals(pausing, listOf("touch:toggle", "touch:back10", "touch:forward10", "touch:stop").map(::bounds))
    }

    // Phone B4: a deliberate layout, not wherever the width ran out.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun portraitControlsAreTransportThenEqualColumns() {
        launch()
        settled()
        val row = bounds("touch:toggle").let { (it.top + it.bottom) / 2 }
        listOf("touch:back10", "touch:forward10", "touch:stop").forEach {
            assertEquals(row.value, bounds(it).let { box -> (box.top + box.bottom) / 2 }.value, 1f, it)
        }
        assertTrue(bounds("touch:stop").right > bounds("touch:open:Subtitles").right, "Stop ends the row")
        assertEquals(bounds("touch:open:Audio").left, bounds("touch:open:Speed").left)
        assertEquals(bounds("touch:open:Subtitles").left, bounds("touch:open:Info").left)
        assertEquals(bounds("touch:open:Chapters").left, bounds("touch:orientation").left)
        val first = bounds("touch:open:Subtitles").left - bounds("touch:open:Audio").left
        val second = bounds("touch:open:Chapters").left - bounds("touch:open:Subtitles").left
        assertEquals(first.value, second.value, 1f, "equal cells")
    }

    // TOUCH-03: at a large font nothing in the transport is squeezed or pushed off the panel.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w320dp-h700dp-port-xhdpi")
    @Test
    fun aLargeFontKeepsTheTransportWhole() {
        launch(fontScale = 1.5f)
        settled()
        val panel = bounds("touch:panel")
        val transport = listOf("touch:toggle", "touch:back10", "touch:forward10", "touch:stop").map(::bounds)
        transport.forEach {
            assertTrue(it.left >= panel.left && it.right <= panel.right, "inside the panel: $it")
            assertTrue(it.right - it.left >= 48.dp, "a whole target: $it")
        }
        val stop = bounds("touch:stop")
        assertTrue(stop.bottom - stop.top < 56.dp, "Stop stays on one line: $stop")
        transport.zipWithNext { a, b -> assertTrue(a.right <= b.left || a.bottom <= b.top, "no overlap: $a $b") }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    @Test
    fun landscapeControlsHoldOneLineWithStopAtTheFarEnd() {
        launch()
        settled()
        val row = bounds("touch:toggle").let { (it.top + it.bottom) / 2 }
        TouchPlayerSheet.entries.map { "touch:open:$it" }.plus(listOf("touch:orientation", "touch:stop")).forEach {
            assertEquals(row.value, bounds(it).let { box -> (box.top + box.bottom) / 2 }.value, 1f, it)
        }
        assertEquals(bounds("touch:panel").right.value - 8f, bounds("touch:stop").right.value, 1f)
        assertTrue(bounds("touch:open:Audio").left - bounds("touch:forward10").right >= 16.dp)
    }

    // Phone B3: on a short window every rate shows at once, and the video stays in view.
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    @Test
    fun speedOnAShortWindowIsOneRowWithEveryRateInView() {
        launch()
        compose.onNodeWithTag("touch:open:Speed").performClick()
        settled()
        val row = bounds("touch:speed:50").top
        listOf(50, 75, 100, 125, 150, 175, 200).forEach {
            compose.onNodeWithTag("touch:speed:$it").assertIsDisplayed()
            assertEquals(row, bounds("touch:speed:$it").top)
        }
        compose.onNodeWithTag("touch:speed:100").assertIsSelected()
        compose.onNodeWithTag("touch:speed:200").performClick()
        settled()
        compose.onNodeWithText("Speed · 2×").assertIsDisplayed()
    }

    // PLAY-25: panel entry is the current rate.
    @Config(qualifiers = "w620dp-h500dp-land-xhdpi")
    @Test
    fun speedListOpensOnTheCurrentRate() {
        launch()
        compose.onNodeWithTag("touch:open:Speed").performClick()
        settled()
        compose.onNodeWithTag("touch:speed:200").performScrollTo().performClick()
        settled()
        compose.onNodeWithTag("touch:open:Speed").performClick()
        settled()
        compose.onNodeWithTag("touch:speed:200").assertIsDisplayed().assertIsSelected()
    }

    // Phone B5, B6: the one shared row, and a header on one line.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun sheetChoicesAreRadioRowsAndDoneSitsOnTheTitleLine() {
        launch()
        compose.onNodeWithTag("touch:open:Speed").performClick()
        settled()
        compose.onNodeWithTag("touch:speed:100").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val title = compose.onNodeWithText("Playback speed").getBoundsInRoot()
        val done = bounds("touch:sheet:done")
        assertEquals((title.top + title.bottom).value / 2, (done.top + done.bottom).value / 2, 1f)
    }

    // Check 2: guards the one route the latch could cross, a shared output. Whether the player really plays is a device fact.
    @Test fun aNewViewingDoesNotShareTheBackgroundLatch() {
        val hosts = mutableListOf<PlaybackHostState>()
        val first = MediaItem("first", "First episode")
        val next = MediaItem("next", "Next episode")
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerViewing(account, first, 0, {}) { current, start, onNext ->
                    val viewing = rememberPlaybackHostState(account, current, start, onNext, {})
                    SideEffect { if (viewing !in hosts) hosts += viewing }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            hosts.single().output.setForeground(false)
            hosts.single().output.setForeground(true)
            assertTrue(hosts.single().output.pauseIntent.value, "the return keeps the viewing paused until a deliberate Play")
            hosts.single().advanceReported = true
            hosts.single().finish(next)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, hosts.size)
            assertEquals(next.id, hosts.last().item.id)
            assertEquals(false, hosts.last().output.pauseIntent.value)
        }
    }

    private fun decision(method: PlayMethod) = PlayDecision(
        "movie",
        MediaSourceInfo(id = "source"),
        method,
        "https://example.test/video",
        emptyMap(),
        "session",
        if (method == PlayMethod.Transcode) listOf("VideoCodecNotSupported") else emptyList()
    )

    private fun decided(method: PlayMethod) {
        compose.runOnIdle {
            val state = PlaybackState(preparing = false, decision = decision(method))
            host.stateSource = androidx.compose.runtime.mutableStateOf(state)
        }
        compose.waitForIdle()
    }

    // TOUCH-10: on a large screen the lock left the whole player in a band across the display.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "sw800dp-w800dp-h1280dp-port-mdpi")
    @Test
    fun aLargeScreenOffersNoOrientationToggle() {
        launch()
        settled()
        compose.onNodeWithTag("touch:orientation").assertDoesNotExist()
        compose.onNodeWithTag("touch:open:Speed").assertExists()
    }

    @Config(qualifiers = "sw360dp-w360dp-h780dp-port-xxhdpi")
    @Test
    fun aPhoneKeepsItsToggle() {
        launch()
        settled()
        compose.onNodeWithTag("touch:orientation").performClick()
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, compose.activity.requestedOrientation)
    }

    private fun playerWindow(smallestWidth: Int, before: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED): PlayerWindowProbe {
        compose.runOnIdle { compose.activity.requestedOrientation = before }
        val probe = PlayerWindowProbe(smallestWidth)
        compose.setContent {
            if (probe.showing) {
                val base = LocalConfiguration.current
                val configuration = remember(base, probe.smallestWidth) {
                    Configuration(base).apply { smallestScreenWidthDp = probe.smallestWidth }
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) { TouchPlayerWindow() }
            }
        }
        compose.waitForIdle()
        return probe
    }

    private class PlayerWindowProbe(smallestWidth: Int) {
        var showing by mutableStateOf(true)
        var smallestWidth by mutableStateOf(smallestWidth)
    }

    @Test fun aPhonePlayerRequestsLandscapeAndGivesBackWhatWasThereOnExit() {
        val probe = playerWindow(360, ActivityInfo.SCREEN_ORIENTATION_SENSOR)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.showing = false }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, compose.activity.requestedOrientation)
    }

    @Test fun aLargeScreenPlayerRequestsNothingOnTheWayInOrOut() {
        val probe = playerWindow(600, ActivityInfo.SCREEN_ORIENTATION_SENSOR)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.showing = false }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, compose.activity.requestedOrientation)
    }

    // A foldable keeps the same player across the fold, so the rule has to follow the window while it plays.
    @Test fun unfoldingMidPlayHandsTheLockBackAndFoldingTakesItUpAgain() {
        val probe = playerWindow(360)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.smallestWidth = 673 }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.smallestWidth = 360 }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.showing = false }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    // Folded back, the viewer who chose Portrait still has Portrait, not a fresh landscape.
    @Test fun foldingAgainKeepsTheViewersToggle() {
        val probe = playerWindow(360)
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT }
        compose.runOnIdle { probe.smallestWidth = 673 }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.smallestWidth = 360 }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, compose.activity.requestedOrientation)
        compose.runOnIdle { probe.showing = false }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
    }

    private fun textBounds(label: String) = compose.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()

    // TextButton's minimum centred a short word, so Audio started right of Pause and of whatever shared its column.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aShortOptionStartsOnItsColumnsLine() {
        launch()
        settled()
        val columns = TouchPlayerSheet.entries.groupBy { bounds("touch:open:$it").left.value }.values
        assertTrue(columns.any { it.size > 1 }, "the options wrap into columns here")
        columns.forEach { column ->
            val starts = column.map { sheet ->
                val word = if (sheet == TouchPlayerSheet.Speed) "Speed ·" else sheet.title
                compose.onAllNodesWithText(word, substring = true, useUnmergedTree = true).onFirst().getBoundsInRoot().left.value
            }
            assertEquals(starts.first(), starts.max(), 0.5f, "text in ${column.first().title}'s column")
            assertEquals(starts.first(), starts.min(), 0.5f, "text in ${column.first().title}'s column")
        }
        assertEquals(bounds("touch:toggle").left.value, textBounds("Audio").left.value, 0.5f, "and on Pause's edge")
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    @Test
    fun stopsTextEndsOnThePanelsLine() {
        launch()
        settled()
        assertEquals(bounds("touch:panel").right.value - 16f, textBounds("Stop").right.value, 1f)
    }

    private fun optionBounds() = (
        TouchPlayerSheet.entries.map { "touch:open:$it" } + "touch:orientation"
        ).map(::bounds)

    /** Transport and Stop on one line, every option on the next one, its text starting on Pause's edge. */
    private fun assertTwoLines() {
        val toggle = bounds("touch:toggle")
        val stop = bounds("touch:stop")
        val middle = (toggle.top + toggle.bottom) / 2
        listOf("touch:back10", "touch:forward10", "touch:stop").forEach {
            assertEquals(middle.value, bounds(it).let { box -> (box.top + box.bottom) / 2 }.value, 1f, it)
        }
        assertEquals(bounds("touch:panel").right.value - 8f, stop.right.value, 1f, "Stop at the far end, its text on the panel's line")
        val options = optionBounds()
        options.forEach { assertEquals(options[0].top.value, it.top.value, 0.5f, "one line: $options") }
        assertTrue(options[0].top >= toggle.bottom, "options below the transport: ${options[0]} $toggle")
        assertEquals(toggle.left.value, options[0].left.value + 8f, 1f, "first option's text on Pause's edge")
        options.zipWithNext { a, b -> assertTrue(a.right <= b.left, "no overlap: $a $b") }
        assertTrue(options.last().right <= stop.right + 8.dp, "on the panel: ${options.last()}")
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @Test
    fun aRowThatDoesNotFitTakesTwoLinesWithNothingOrphaned() {
        launch()
        settled()
        assertTwoLines()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @Test
    fun aLargerFontTakesTwoLinesAtAPhonesLandscapeWidth() {
        launch(fontScale = 1.3f)
        settled()
        assertTwoLines()
    }

    // The choice is what fits, not the window's width: the same window holds one line at 1x and two at 1.8x.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w1000dp-h500dp-land-xhdpi")
    @Test
    fun theSameWindowChoosesByTheFontScale() {
        launch()
        settled()
        val row = bounds("touch:toggle").let { (it.top + it.bottom) / 2 }
        optionBounds().forEach { assertEquals(row.value, ((it.top + it.bottom) / 2).value, 1f, "$it") }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w1000dp-h500dp-land-xhdpi")
    @Test
    fun theSameWindowAtALargeFontTakesTwoLines() {
        launch(fontScale = 1.8f)
        settled()
        val options = optionBounds()
        assertTrue(options[0].top >= bounds("touch:toggle").bottom, "two lines at 1.8x: ${options[0]}")
    }

    // A title with no chapters needs less room, so it keeps the single line a chaptered title has to give up.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w790dp-h360dp-land-xxhdpi")
    @Test
    fun aTitleWithNoChaptersCountsInTheFit() {
        launch()
        settled()
        val spare = bounds("touch:play-on").left - bounds("touch:forward10").right - 32.dp
        val options = optionBounds()
        val chapters = bounds("touch:open:Chapters")
        val needed = options.last().right - options.first().left
        val withoutChapters = needed - (chapters.right - chapters.left) - 4.dp
        assertTrue(needed > spare && withoutChapters <= spare, "fixture must straddle the fit: $needed $withoutChapters $spare")
        compose.runOnIdle { host.metadata = host.item.copy(chapters = emptyList()) }
        settled()
        val row = bounds("touch:toggle").let { (it.top + it.bottom) / 2 }
        assertEquals(row.value, bounds("touch:open:Audio").let { (it.top + it.bottom) / 2 }.value, 1f, "one line again")
    }

    @Test fun optionLinesKeepToTheRoomAndLeaveNoOptionAlone() {
        assertEquals(listOf(listOf(0, 1, 2)), touchOptionLines(listOf(100, 100, 100), 320, 4))
        assertEquals(listOf(listOf(0, 1), listOf(2)), touchOptionLines(listOf(150, 150, 400), 400, 4), "too wide to pair")
        assertEquals(listOf(listOf(0), listOf(1, 2)), touchOptionLines(listOf(100, 100, 100), 210, 4))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), touchOptionLines(List(6) { 100 }, 210, 4))
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Where this player's clock reads; the fixture's decoder never reaches the output. */
    private fun playerAt(ms: Long) = compose.runOnIdle {
        Media3PlaybackOutput::class.java.getDeclaredField("saved").apply { isAccessible = true }
            .set(output, PlaybackPosition(ms, false))
    }

    private fun tapPlayOn() = compose.onNodeWithTag("touch:play-on").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }

    @Test
    fun playOnOpensTheChooserForThisPlayerAtItsPosition() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        decided(PlayMethod.DirectPlay)
        playerAt(37_000)
        tapPlayOn()
        compose.runOnIdle {
            assertTrue(sender.state.value.chooserOpen)
            val request = requireNotNull(sender.chooserRequest)
            assertEquals("movie", request.items.single().id)
            assertEquals(37_000, request.startMs, "where this player is; it opened at ${host.startMs}")
            assertEquals("source", request.mediaSourceId, "the source this player chose")
            assertTrue(request.local != null, "a handoff from this player has to be able to stop it")
        }
    }

    @Test
    fun aPausedPlayerAsksTheChooserToKeepItPaused() {
        val sender = inertCastSender()
        launch(paused = true, cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle {
            val local = requireNotNull(requireNotNull(sender.chooserRequest).local)
            assertEquals(true, kotlinx.coroutines.runBlocking { local.pauseAndRead() }.paused)
        }
    }

    @Test
    fun aPauseIntentCountsAsPausedForTheChooser() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        compose.runOnIdle { output.pause() }
        settled()
        tapPlayOn()
        compose.runOnIdle {
            val local = requireNotNull(requireNotNull(sender.chooserRequest).local)
            assertEquals(true, kotlinx.coroutines.runBlocking { local.pauseAndRead() }.paused)
        }
    }

    // Headset keys and audio focus still reach the player under the dialog.
    @Test
    fun thePlayerSaysWhetherItWasPausedAsItStopsForTheReceiver() {
        val local = chooserLocal()
        assertEquals(false, readPaused(local), "it was playing")
        settled()
        assertEquals(false, readPaused(local), "the handoff's own stop isn't the viewer's pause, or a second try would load paused")
        compose.runOnIdle { output.pause() }
        settled()
        assertEquals(true, readPaused(local), "and now the viewer has paused")
    }

    private fun chooserLocal(): CastLocalOutput {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        return compose.runOnIdle { requireNotNull(requireNotNull(sender.chooserRequest).local) }
    }

    private fun readPaused(local: CastLocalOutput) = compose.runOnIdle { kotlinx.coroutines.runBlocking { local.pauseAndRead() }.paused }

    // The link takes seconds, and the viewer can lock the phone while it comes up: the TV should still play.
    @Test
    fun goingToTheBackgroundIsNotTheViewersPause() {
        val local = chooserLocal()
        compose.runOnIdle { output.setForeground(false) }
        settled()
        assertEquals(true, compose.runOnIdle { output.pauseIntent.value }, "the app has paused it")
        assertEquals(false, readPaused(local))
    }

    @Test
    fun comingBackDoesNotMakeTheAppsPauseTheViewers() {
        val local = chooserLocal()
        compose.runOnIdle { output.setForeground(false) }
        compose.runOnIdle { output.setForeground(true) }
        settled()
        assertEquals(false, readPaused(local))
    }

    @Test
    fun aViewersPauseSurvivesGoingToTheBackground() {
        val local = chooserLocal()
        compose.runOnIdle { output.pause() }
        compose.runOnIdle { output.setForeground(false) }
        compose.runOnIdle { output.setForeground(false) }
        settled()
        assertEquals(true, readPaused(local))
    }

    // Pressing play, here or from a controller, makes whatever the app remembered from earlier out of date.
    @Test
    fun aViewersPlayEndsTheirEarlierPause() {
        val local = chooserLocal()
        compose.runOnIdle {
            output.pause()
            output.setForeground(false)
            output.setForeground(true)
            output.playIntent(true)
            output.setForeground(false)
            output.setForeground(true)
        }
        settled()
        assertEquals(false, readPaused(local))
    }

    @Test
    fun leavingThePlayerIsNotTheViewersPause() {
        val local = chooserLocal()
        compose.runOnIdle { host.finish(reason = "back") }
        settled()
        assertEquals(false, readPaused(local))
    }

    @Test
    fun aPlaybackErrorsPauseIsNotTheViewers() {
        val local = chooserLocal()
        compose.runOnIdle { output.pauseForApp() }
        settled()
        assertEquals(false, readPaused(local), "the TV may play what this device can't")
    }

    @Test
    fun aPlayerWithoutTheSenderOffersNoPlayOn() {
        launch(cast = null)
        compose.onNodeWithTag("touch:stop").assertHasClickAction()
        compose.onNodeWithTag("touch:play-on").assertDoesNotExist()
    }

    /** A TV that's attached and listening, and what it was told. */
    private fun attachedTv(body: (RemoteControllerHost, List<app.pictureshow.core.control.RemoteRequest>) -> Unit) {
        val sent = java.util.concurrent.CopyOnWriteArrayList<app.pictureshow.core.control.RemoteRequest>()
        val tv = app.pictureshow.core.control.RemoteTarget(
            session = "s1",
            device = "tv",
            name = "Living room",
            viewer = "viewer",
            sameViewer = true,
            commands = listOf("MoveUp", "SendString")
        )
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val remote = RemoteControllerHost(
            scope,
            object : RemoteControllerGateway {
                override suspend fun targets(account: Account) = app.pictureshow.core.ClientResult.Success(listOf(tv))
                override suspend fun send(account: Account, request: app.pictureshow.core.control.RemoteRequest): Int {
                    sent += request
                    return 204
                }
            }
        ) { 0L }
        try {
            remote.bind(account)
            remote.setForeground(true)
            remote.attach(tv)
            body(remote, sent)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // CAST-23: the player keeps playing under the chooser, so the TV starts from the pick, in ticks.
    @Test
    fun aJellyfinPickStartsTheTvWhereThePlayerIsAtThePick() = attachedTv { remote, sent ->
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, remote), itemId = "0123456789abcdef0123456789abcdef")
        playerAt(90_000)
        tapPlayOn()
        playerAt(110_000) // 20 s of reading the list
        compose.runOnIdle { requireNotNull(requireNotNull(sender.chooserRequest).onJellyfinPick).invoke() }
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        compose.runOnIdle {
            assertEquals("1100000000", sent.single().query["startPositionTicks"])
            assertEquals(1, exits, "the local player gives way to the TV")
        }
    }

    /** A touch player under [PlayerViewing], as the app has it: autoplay swaps the host in place, under the chooser. */
    private fun launchEpisodes(cast: TouchPlayerCast, first: MediaItem): List<PlaybackHostState> {
        val hosts = mutableListOf<PlaybackHostState>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalTouchPlayerCast provides cast) {
                    PlayerViewing(account, first, 0, {}) { current, start, onNext ->
                        val viewing = remember {
                            val output = Media3PlaybackOutput(compose.activity, account.server)
                            PlaybackHostState(current, start, output, onNext, { exits++ }).apply {
                                playerSource = mutableStateOf(Decoder(true))
                                stateSource = mutableStateOf(PlaybackState(preparing = false))
                                duration = 60_000
                                advanceReported = true
                            }
                        }
                        SideEffect { if (viewing !in hosts) hosts += viewing }
                        TouchPlayerContent(account, viewing)
                    }
                }
            }
        }
        compose.waitForIdle()
        return hosts
    }

    private fun PlaybackHostState.reachCredits() {
        position = 50_000
        segments = listOf(MediaSegment("outro", SegmentType.Outro, 450_000_000, 600_000_000))
        nextLookup = EpisodeLookup(0, ClientResult.Success(MediaItem("0123456789abcdef0123456789abcde2", "Next episode")))
    }

    private val firstEpisode = MediaItem("0123456789abcdef0123456789abcde1", "First episode")

    // CAST-23: the chooser closes when this player goes: left open, a pick told the TV to play the finished episode and
    // the old host's exit popped the new episode's player.
    @Test
    fun anEndOfFileAdvanceClosesAChooserOpenedFromThatPlayer() {
        val sender = inertCastSender()
        val hosts = launchEpisodes(TouchPlayerCast(sender, inertRemoteController()), firstEpisode)
        tapPlayOn()
        compose.runOnIdle {
            hosts.single().reachCredits()
            hosts.single().ended = true
        }
        settled()
        compose.runOnIdle {
            assertEquals(2, hosts.size, "the end of a file advances whatever is open")
            assertEquals(false, sender.state.value.chooserOpen, "the player it was opened from has gone")
            assertEquals(null, sender.chooserRequest)
            assertEquals(0, exits)
        }
    }

    @Test
    fun anOpenChooserHoldsTheCreditsCountdownAsASheetDoes() {
        val sender = inertCastSender()
        val hosts = launchEpisodes(TouchPlayerCast(sender, inertRemoteController()), firstEpisode)
        tapPlayOn()
        compose.runOnIdle { hosts.single().reachCredits() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, hosts.size, "the viewer is choosing where this episode goes")
            assertTrue(sender.state.value.chooserOpen)
            sender.closeChooser()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, hosts.size, "closing it lets the countdown go on") }
    }

    // The link can take up to the load timeout, so a pick in the credits would otherwise lose to the countdown.
    @Test
    fun aPickStillStartingHoldsTheCreditsCountdown() {
        val sender = signedInCastSender()
        val hosts = launchEpisodes(TouchPlayerCast(sender, inertRemoteController()), firstEpisode)
        tapPlayOn()
        compose.runOnIdle {
            sender.pick(CHOOSER_ROUTE)
            assertNotNull(sender.state.value.cast?.owner?.candidate)
            hosts.single().reachCredits()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, hosts.size, "this episode is on its way somewhere")
            sender.cancel()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, hosts.size, "once the pick has resolved the countdown goes on") }
    }

    @Test
    fun reachingTheEndOfTheFileDropsAPickStillStarting() {
        val sender = signedInCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { sender.pick(CHOOSER_ROUTE) }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull(sender.state.value.cast?.owner?.candidate) }
        compose.runOnIdle { host.ended = true }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(sender.state.value.cast, "it would have read the finished title's last moment") }
    }

    @Test
    fun takingTheNextEpisodeDropsAPickStillStarting() {
        val sender = signedInCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { sender.pick(CHOOSER_ROUTE) }
        compose.waitForIdle()
        compose.runOnIdle { host.finish(MediaItem("0123456789abcdef0123456789abcde2", "Second episode")) }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(sender.state.value.cast) }
    }

    @Test
    fun leavingForGoodLeavesAPickStillStartingToFinish() {
        val sender = signedInCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { sender.pick(CHOOSER_ROUTE) }
        compose.waitForIdle()
        compose.runOnIdle { host.finish(reason = "system-stop") }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull(sender.state.value.cast?.owner?.candidate, "going to another app still asked for this") }
    }

    // With a screen reader on, or after a trip away, a finished player waits for the viewer; position() is then the duration.
    @Test
    fun reachingTheEndOfTheFileClosesTheChooser() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { host.ended = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, sender.state.value.chooserOpen, "a pick here would start the TV at the last second") }
        compose.onNodeWithTag("touch:play-on").assertIsNotEnabled()
        compose.runOnIdle { host.ended = false }
        compose.waitForIdle()
        compose.onNodeWithTag("touch:play-on").assertIsEnabled()
    }

    // The next episode was found, then the viewer left: Stop was already reported, and this player waits with it deferred.
    @Test
    fun aDeferredNextEpisodeLeavesNothingToSend() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { host.deferredNext = firstEpisode }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, sender.state.value.chooserOpen) }
        compose.onNodeWithTag("touch:play-on").assertIsNotEnabled()
    }

    // Nothing but the composition's own disposal calls this when a player goes without finishing.
    @Test
    fun aPlayerRemovedWithoutFinishingClosesItsChooser() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { playerShown = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(false, sender.state.value.chooserOpen)
            assertEquals(false, host.exitRequested, "this path doesn't go through finish")
        }
    }

    @Test
    fun leavingThePlayerClosesItsChooserAtOnce() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        tapPlayOn()
        compose.runOnIdle { host.finish(reason = "error-back") }
        compose.runOnIdle { assertEquals(false, sender.state.value.chooserOpen) }
    }

    @Test
    fun aChooserOpenedElsewhereStaysWhenThePlayerGoes() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        compose.runOnIdle {
            sender.openChooser(CastPlayRequest(listOf(CastPlayItem("other", "Another"))))
            host.finish(reason = "error-back")
        }
        compose.runOnIdle { assertTrue(sender.state.value.chooserOpen, "not this player's to close") }
    }

    // M1: Play on is a mark just before Stop on the transport line, so it adds no height at any size.
    private fun assertMarkHasItsTarget() {
        val node = compose.onNodeWithTag("touch:play-on").fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val target = node.touchBoundsInRoot
        assertTrue(target.width / density >= 47.5f && target.height / density >= 47.5f, "a 48 dp target: $target at $density")
    }

    private fun assertMarkBesideStop() {
        val toggle = bounds("touch:toggle")
        val mark = bounds("touch:play-on")
        val stop = bounds("touch:stop")
        val line = (toggle.top + toggle.bottom).value / 2
        assertEquals(line, (mark.top + mark.bottom).value / 2, 1f, "on the transport line")
        assertEquals(line, (stop.top + stop.bottom).value / 2, 1f, "Stop on it too")
        assertTrue(mark.left >= bounds("touch:forward10").right, "after the transport: $mark")
        assertTrue(stop.left - mark.right >= 8.dp, "air before Stop, which exits at once: $mark $stop")
        assertTrue(stop.left - mark.right <= 24.dp, "and beside it: $mark $stop")
        assertMarkHasItsTarget()
        val markNode = compose.onNodeWithTag("touch:play-on").fetchSemanticsNode()
        val stopTarget = compose.onNodeWithTag("touch:stop").fetchSemanticsNode().touchBoundsInRoot
        val gap = (stopTarget.left - markNode.touchBoundsInRoot.right) / markNode.layoutInfo.density.density
        assertTrue(gap >= 7.5f, "the tap areas stay apart too: $gap dp")
        assertInsideTheWindow(mark)
        compose.onNodeWithContentDescription("Play on").assertHasClickAction()
    }

    private fun assertInsideTheWindow(box: androidx.compose.ui.unit.DpRect) {
        val window = compose.onNodeWithTag("touch:player").getBoundsInRoot()
        val inside = box.left >= window.left && box.right <= window.right && box.top >= window.top && box.bottom <= window.bottom
        assertTrue(inside, "$box in $window")
    }

    /** Text tops of the options, grouped into lines, as counts per line. */
    private fun optionsPerLine(tags: List<String>): List<Int> {
        val lines = tags.map(::bounds).groupBy { it.top.value.toInt() }
        return lines.toSortedMap().values.map { it.size }
    }

    private val chaptered = (TouchPlayerSheet.entries.map { "touch:open:$it" } + "touch:orientation")
    private val withoutChapters = chaptered - "touch:open:Chapters"

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    @Test
    fun playOnSitsBeforeStopInAPhonesLandscape() {
        launch()
        settled()
        assertMarkBesideStop()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-mdpi")
    @Test
    fun playOnSitsBeforeStopOnATabletsOneLine() {
        launch()
        settled()
        assertMarkBesideStop()
        val row = bounds("touch:toggle").let { (it.top + it.bottom) / 2 }
        TouchPlayerSheet.entries.map { bounds("touch:open:$it") }.forEach { assertEquals(row.value, ((it.top + it.bottom) / 2).value, 1f) }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    @Test
    fun aChapteredPortraitPanelTakesTwoFullLinesOfOptions() {
        launch()
        settled()
        assertMarkBesideStop()
        assertEquals(listOf(3, 3), optionsPerLine(chaptered))
        assertInsideTheWindow(bounds("touch:panel"))
        // Same columns line after line, on Pause's edge.
        val audio = bounds("touch:open:Audio")
        assertEquals(audio.left.value, bounds("touch:open:${TouchPlayerSheet.entries[3]}").left.value, 1f)
        assertEquals(bounds("touch:toggle").left.value, audio.left.value + 8f, 1f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    @Test
    fun aTitleWithoutChaptersTakesThreeThenTwo() {
        launch()
        compose.runOnIdle { host.metadata = host.item.copy(chapters = emptyList()) }
        settled()
        assertEquals(listOf(3, 2), optionsPerLine(withoutChapters))
    }

    /** No label wraps: each stays one text line tall and ends before the next one in its line starts. */
    private fun assertLabelsOnOneLine(columns: Int) {
        val perLine = optionsPerLine(chaptered)
        assertEquals(chaptered.size, perLine.sum())
        assertEquals(columns, perLine.max(), "$columns columns: $perLine")
        val audio = bounds("touch:open:Audio")
        chaptered.forEach { tag ->
            val box = bounds(tag)
            assertEquals(audio.bottom.value - audio.top.value, (box.bottom - box.top).value, 0.5f, "$tag stays one line")
        }
        chaptered.map(::bounds).groupBy { it.top.value.toInt() }.values.forEach { line ->
            line.sortedBy { it.left.value }.zipWithNext { a, b ->
                // Each label's target hangs its 8 dp inset past the text on both sides, so this is text to text.
                assertTrue((b.left + 8.dp) - (a.right - 8.dp) >= 7.5.dp, "8 dp of air between labels: $a $b")
            }
        }
        chaptered.forEach { assertTrue(bounds(it).right - 8.dp <= bounds("touch:panel").right - 16.dp, "$it inside the panel's content") }
        assertInsideTheWindow(bounds("touch:panel"))
    }

    private fun chooseSpeed(tag: String) {
        compose.onNodeWithTag("touch:open:Speed").performClick()
        settled()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        settled()
        // A choice closes the sheet by itself on a phone-sized list; a sheet with Done waits for it.
        if (compose.onAllNodesWithTag("touch:sheet:done").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("touch:sheet:done").performClick()
            settled()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    @Test
    fun aLargerFontKeepsThreeColumnsOnThisPhoneWithNothingWrapped() {
        launch(fontScale = 1.3f)
        settled()
        assertLabelsOnOneLine(3)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    @Test
    fun eachWideSpeedStaysOnOneLineAtALargerFont() {
        launch(fontScale = 1.3f)
        settled()
        listOf("touch:speed:175" to "Speed · 1.75×", "touch:speed:125" to "Speed · 1.25×").forEach { (tag, label) ->
            chooseSpeed(tag)
            compose.onNodeWithText(label).assertExists()
            assertLabelsOnOneLine(3)
            assertEquals(
                textBounds("Audio").let { it.bottom - it.top }.value,
                textBounds(label).let { it.bottom - it.top }.value,
                0.5f,
                "$label is one text line"
            )
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    @Test
    fun aNarrowPhoneKeepsThreeColumnsAtItsDefaultFont() {
        launch()
        settled()
        assertMarkBesideStop()
        assertLabelsOnOneLine(3)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    @Test
    fun aNarrowPhoneDropsToTwoColumnsAtALargerFont() {
        launch(fontScale = 1.3f)
        settled()
        assertLabelsOnOneLine(2)
        // The mark and Stop leave together if the transport line can't hold them, and stay at the far end.
        assertEquals(bounds("touch:panel").right.value - 8f, bounds("touch:stop").right.value, 1f)
        val stop = bounds("touch:stop")
        val mark = bounds("touch:play-on")
        assertEquals((stop.top + stop.bottom).value / 2, (mark.top + mark.bottom).value / 2, 1f, "the pair stays together")
        assertTrue(stop.left - mark.right >= 8.dp)
        assertMarkHasItsTarget()
    }

    // Android applies a font change in place here, so the same grid has to choose again.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h780dp-port-xxhdpi")
    @Test
    fun aFontChangeInPlaceChoosesTheColumnsAgain() {
        launch()
        settled()
        assertLabelsOnOneLine(3)
        compose.runOnIdle { liveFontScale = 1.3f }
        settled()
        assertLabelsOnOneLine(2)
        compose.runOnIdle { liveFontScale = 1f }
        settled()
        assertLabelsOnOneLine(3)
    }

    @Test fun columnsAreTheMostThatHoldEveryLabel() {
        assertEquals(3, touchOptionColumns(listOf(100, 100, 292), 900, 8), "292 fits in 300 less the air")
        assertEquals(2, touchOptionColumns(listOf(100, 100, 293), 900, 8), "one label decides for all")
        assertEquals(2, touchOptionColumns(listOf(442), 900, 8))
        assertEquals(1, touchOptionColumns(listOf(443), 900, 8), "still no wrap")
    }

    // While something plays elsewhere the mark fills in and a reader hears what; the browsing bar is hidden in the player.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theMarkSaysWhenThisViewerIsCasting() {
        val sender = inertCastSender()
        launch(cast = TouchPlayerCast(sender, inertRemoteController()))
        settled()
        val mark = compose.onNodeWithContentDescription("Play on")
        mark.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        val idle = compose.onNodeWithTag("touch:play-on").captureToImage()
        val identity = app.pictureshow.core.control.ViewingIdentity("server", "viewer", "route", "cast-1", 1, "cast")
        compose.runOnIdle {
            val flow = CastSenderHost::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(sender)
            @Suppress("UNCHECKED_CAST")
            (flow as kotlinx.coroutines.flow.MutableStateFlow<CastUi>).value = CastUi(
                cast = app.pictureshow.core.control.CastState(
                    app.pictureshow.core.control.OwnerState(identity),
                    observed = app.pictureshow.core.control.CastObservation(playerState = "playing")
                ),
                deviceName = "Living room"
            )
        }
        settled()
        mark.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Living room: Playing"))
        val casting = compose.onNodeWithTag("touch:play-on").captureToImage()
        assertTrue(idle.toPixelMap().buffer.toList() != casting.toPixelMap().buffer.toList(), "the mark itself changes")
        val owner = app.pictureshow.core.control.OwnerState(identity)
        val notCasting = mapOf(
            "Living room: checking…" to app.pictureshow.core.control.CastState(owner),
            "Living room: not connected" to app.pictureshow.core.control.CastState(owner.copy(phase = "disconnected")),
            "Living room: reconnecting…" to app.pictureshow.core.control.CastState(owner, link = "suspended"),
            "Stopping on Living room…" to app.pictureshow.core.control.CastState(owner.copy(phase = "returning")),
            "Starting on Living room…" to app.pictureshow.core.control.CastState(owner.copy(candidate = identity))
        )
        notCasting.forEach { (line, state) ->
            compose.runOnIdle { castFlow(sender).value = CastUi(cast = state, deviceName = "Living room") }
            settled()
            mark.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, line))
            val drawn = compose.onNodeWithTag("touch:play-on").captureToImage()
            assertEquals(idle.toPixelMap().buffer.toList(), drawn.toPixelMap().buffer.toList(), "the mark stays hollow for: $line")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun castFlow(sender: CastSenderHost) =
        CastSenderHost::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(sender)
            as kotlinx.coroutines.flow.MutableStateFlow<CastUi>

    private fun launch(
        paused: Boolean = false,
        fontScale: Float? = null,
        // What the touch app ships: its only player route provides the sender, so layout is proven with Play on in it.
        cast: TouchPlayerCast? = TouchPlayerCast(inertCastSender(), inertRemoteController()),
        itemId: String = "movie",
        onNext: (MediaItem) -> Unit = {}
    ) {
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder(!paused)
            host = PlaybackHostState(MediaItem(itemId, "A film"), 20_000, output, onNext, { exits++ })
            host.playerSource = androidx.compose.runtime.mutableStateOf(decoder)
            host.stateSource = androidx.compose.runtime.mutableStateOf(PlaybackState(preparing = false))
            host.duration = 60_000
            host.metadata = host.item.copy(chapters = listOf(Chapter(0, "Opening"), Chapter(300_000_000, "Middle")))
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, liveFontScale ?: fontScale ?: density.fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(LocalTouchPlayerCast provides cast) {
                        if (playerShown) TouchPlayerContent(account, host)
                    }
                }
            }
        }
    }

    private class Decoder(playing: Boolean) : SimpleBasePlayer(Looper.getMainLooper()) {
        val seeks = mutableListOf<Long>()
        private var value = State.Builder().setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE
            ).build()
        ).setPlaylist(listOf(MediaItemData.Builder("example").setDurationUs(60_000_000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(20_000).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = Futures.immediateVoidFuture().also {
            seeks += positionMs
            value = value.buildUpon().setContentPositionMs(positionMs).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
