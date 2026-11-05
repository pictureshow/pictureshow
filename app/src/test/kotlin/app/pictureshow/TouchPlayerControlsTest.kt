/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
        assertEquals(bounds("touch:panel").right.value - 16f, bounds("touch:stop").right.value, 1f)
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

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun launch(paused: Boolean = false, fontScale: Float? = null, onNext: (MediaItem) -> Unit = {}) {
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder(!paused)
            host = PlaybackHostState(MediaItem("movie", "A film"), 20_000, output, onNext, { exits++ })
            host.playerSource = androidx.compose.runtime.mutableStateOf(decoder)
            host.stateSource = androidx.compose.runtime.mutableStateOf(PlaybackState(preparing = false))
            host.duration = 60_000
            host.metadata = host.item.copy(chapters = listOf(Chapter(0, "Opening"), Chapter(300_000_000, "Middle")))
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) { TouchPlayerContent(account, host) }
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
