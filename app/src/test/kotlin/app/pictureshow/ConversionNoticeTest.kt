/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.media3.common.text.Cue
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.playback.ConversionNotice
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** PLAY-27: a brief, polite, non-focusable notice, once per viewing and announced once, with a Settings switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConversionNoticeTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun decision(vararg reasons: String) = PlayDecision(
        "film",
        MediaSourceInfo(),
        PlayMethod.Transcode,
        "https://server.example/videos/film/master.m3u8?VideoCodec=h264&AudioCodec=aac",
        emptyMap(),
        "session",
        reasons.toList()
    )

    private fun tvNotice(vararg reasons: String) = requireNotNull(ConversionNotice.of(decision(*reasons), -1, CONVERSION_NOTICE_DEVICE))

    @Test fun noticeAppearsOnceWithoutTakingFocusAndHidesAfterItsTime() {
        val codec = tvNotice("VideoCodecNotSupported", "AudioCodecNotSupported")
        val range = tvNotice("VideoRangeTypeNotSupported")
        var notice by mutableStateOf<ConversionNotice?>(null)
        var allowed by mutableStateOf(false)
        var pressed = 0
        // State written from the test thread with a paused clock: send apply notifications so the composition sees it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val focus = remember { FocusRequester() }
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { pressed++ }, modifier = Modifier.focusRequester(focus).testTag("play")) { Text("Play") }
                    ConversionNoticeHost(notice, allowed, Modifier.align(Alignment.TopStart)) {}
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("play").assertIsFocused()

        // Not yet ready: a qualifying decision waits rather than flashing behind "Preparing playback".
        notice = codec
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()

        allowed = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        val node = compose.onNodeWithTag("player:conversion-notice").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertNull(node.config.getOrNull(SemanticsProperties.Focused), "the notice must not be an input-focus target")
        compose.onNodeWithText("Converting this video for this TV").assertExists()
        compose.onNodeWithText("Its video format won't play on this TV. Press Down, then choose Playback info.").assertExists()
        // The remote still drives the focused control.
        compose.onNodeWithTag("play").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(1, pressed) }

        compose.mainClock.advanceTimeBy(CONVERSION_NOTICE_MS)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()

        // A renegotiation later in the same viewing does not repeat the notice.
        notice = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        notice = range
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()
    }

    @Test fun aNoticeLeavesAtOnceWhenALaterDecisionStopsConvertingAndDoesNotReturnInThatViewing() {
        val converting = tvNotice("VideoCodecNotSupported")
        var notice by mutableStateOf<ConversionNotice?>(converting)
        var allowed by mutableStateOf(true)
        var viewing by mutableStateOf(1)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                // PlayerScreen keys the host to the viewing: the next item is a new viewing.
                key(viewing) { ConversionNoticeHost(notice, allowed) {} }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player:conversion-notice").assertExists()

        // 2 s in, a later decision in this viewing no longer converts (say, burned-in subtitles turned off).
        compose.mainClock.advanceTimeBy(2_000)
        notice = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()

        // Converting again inside the old 10 s window, or after a panel closes,
        // neither shows nor announces the notice again in this viewing.
        compose.mainClock.advanceTimeBy(1_000)
        notice = converting
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()
        allowed = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(500)
        allowed = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(CONVERSION_NOTICE_MS)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()

        viewing = 2
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        val next = compose.onNodeWithTag("player:conversion-notice").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, next.config.getOrNull(SemanticsProperties.LiveRegion), "the next item is a new viewing")
        // A hidden notice whose conversion stops while a panel is up does not come back either.
        allowed = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(500)
        notice = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(500)
        notice = converting
        allowed = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()
    }

    @Test fun touchingTheNoticeAsksForPlaybackInfo() {
        val notice = tvNotice("VideoCodecNotSupported")
        var details = 0
        compose.setContent { MaterialTheme { ConversionNoticeCard(notice) { details++ } } }
        compose.onNodeWithTag("player:conversion-notice").performClick()
        compose.runOnIdle { assertEquals(1, details) }
    }

    @Test fun aPanelHidingTheNoticeDoesNotMakeAScreenReaderAnnounceItAgain() {
        val notice = tvNotice("VideoCodecNotSupported")
        var allowed by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { ConversionNoticeHost(notice, allowed) {} } }
        // Each appearance of the card is one chance for TalkBack to speak a polite live region.
        val appearances = mutableListOf<LiveRegionMode?>()
        fun appearance() =
            compose.onNodeWithTag("player:conversion-notice").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        compose.mainClock.advanceTimeBy(100)
        appearances += appearance()
        // Playback info opens and closes inside the 10 s.
        allowed = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()
        allowed = true
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        appearances += appearance()
        assertEquals(listOf(LiveRegionMode.Polite, null), appearances)
        assertEquals(1, appearances.count { it == LiveRegionMode.Polite }, "exactly one announcement per viewing")
    }

    @Test fun switchOffSuppressesTheNoticeInThePlayerHost() {
        var preferences by mutableStateOf(PlaybackPreferences.decode(conversionNotice = false))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                PlayerConversionNotice(decision("VideoCodecNotSupported"), -1, preferences, allowed = true) {}
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()
        // The same host and decision do show it with the switch On, so the absence above is the switch.
        preferences = PlaybackPreferences.decode()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("player:conversion-notice").assertExists()
    }

    @Test fun everyNoticeIsATitleAndOneLineOfAtMostTwentyWords() {
        val codes = listOf(
            "VideoCodecNotSupported",
            "VideoRangeTypeNotSupported",
            "VideoProfileNotSupported",
            "VideoResolutionNotSupported",
            "VideoFramerateNotSupported",
            "InterlacedVideoNotSupported",
            "AnamorphicVideoNotSupported",
            "VideoRotationNotSupported"
        )
        val notices = codes.map { tvNotice(it) }
        notices.forEach { notice ->
            assertEquals("Converting this video for this TV", notice.title)
            val line = "${notice.reason} $CONVERSION_NOTICE_ACTION"
            assertTrue(line.split(' ').size <= 20, "too long for 10 s: $line")
            assertFalse("device" in line || "transcod" in line.lowercase(), line)
        }
    }

    @Test fun topPositionedCuesAreRecognised() {
        fun cue(line: Float, type: Int) = Cue.Builder().setText("sign").setLine(line, type).build()
        assertFalse(cuesNearTop(listOf(Cue.Builder().setText("dialogue").build())), "default cues sit at the bottom")
        assertTrue(cuesNearTop(listOf(cue(0.05f, Cue.LINE_TYPE_FRACTION))), "SSA top alignment")
        assertTrue(cuesNearTop(listOf(cue(0f, Cue.LINE_TYPE_NUMBER))), "WebVTT line:0")
        assertFalse(cuesNearTop(listOf(cue(-1f, Cue.LINE_TYPE_NUMBER))), "negative lines count from the bottom")
        assertFalse(cuesNearTop(listOf(cue(0.85f, Cue.LINE_TYPE_FRACTION))), "positioned bottom cue")
        assertFalse(cuesNearTop(emptyList()))
    }

    @Test fun settingsSwitchHasItsOwnRowAfterAudioPassthroughAndTurnsTheNoticeOff() {
        var state by mutableStateOf(PlaybackSettingsState(loading = false))
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    state,
                    "PCM only",
                    onLocal = { state = state.copy(local = it(state.local)) },
                    onServer = { _, _ -> },
                    onRetry = {},
                    onBack = {}
                )
            }
        }
        assertTrue(state.local.conversionNotice, "default is on")
        assertTrue(PlaybackPreferences.decode().conversionNotice, "a missing stored value is on")
        assertEquals(false, PlaybackPreferences.decode(conversionNotice = false).conversionNotice)
        compose.onNodeWithText("Conversion notice: On").assertExists()
        compose.onNodeWithText("Shows a short note when the server has to convert a video or a song for this TV.").assertExists()
        val toggle = compose.onNodeWithTag("settings:conversion-notice").fetchSemanticsNode()
        assertEquals("On", toggle.config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("settings:passthrough").requestFocusNow()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithTag("settings:conversion-notice").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, state.local.conversionNotice) }
        compose.onNodeWithText("Conversion notice: Off").assertExists()
    }

    private fun SemanticsNodeInteraction.requestFocusNow() {
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertIsFocused()
    }
}
