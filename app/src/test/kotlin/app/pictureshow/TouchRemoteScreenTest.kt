/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.control.CommandState
import app.pictureshow.core.control.RemoteControllerState
import app.pictureshow.core.control.RemoteFollowUp
import app.pictureshow.core.control.RemoteNowPlaying
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.RemoteTrack
import app.pictureshow.core.control.ViewingIdentity
import app.pictureshow.core.control.remoteTargets
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The remote's controls take repeated taps: anything that moves them turns a second Forward into Previous, which seeks to 0.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h2400dp-port-xhdpi")
class TouchRemoteScreenTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = RemoteNowPlaying(
        item = "item",
        title = "A film",
        durationTicks = 36_000_000_000,
        positionTicks = 600_000_000,
        canSeek = true,
        tracks = listOf(
            RemoteTrack("Audio", 1, "English"),
            RemoteTrack("Audio", 2, "Spanish"),
            RemoteTrack("Subtitle", 3, "English")
        )
    )
    private val tv = RemoteTarget(
        session = "s1",
        device = "tv",
        name = "Living room",
        viewer = "me",
        sameViewer = true,
        commands = listOf("MoveUp", "SendString"),
        now = now
    )
    private val host = RemoteControllerHost(
        CoroutineScope(Dispatchers.Unconfined),
        object : RemoteControllerGateway {
            override suspend fun targets(account: Account) = ClientResult.Success(emptyList<RemoteTarget>())
            override suspend fun send(account: Account, request: RemoteRequest) = 204
        }
    ) { 0L }
    private val controls = listOf(
        "remote:seek", "remote:previous", "remote:back", "remote:playpause", "remote:forward", "remote:next",
        "remote:stop", "remote:dpad-toggle", "remote:text", "remote:send", "remote:disconnect"
    )

    private fun attached(result: String?, playing: RemoteNowPlaying = now, fresh: Boolean = true) = RemoteControllerUi(
        RemoteControllerState(
            "server",
            "me",
            phase = "attached",
            target = tv.copy(now = playing),
            command = CommandState(ViewingIdentity("server", "me", "tv", "s1", 1), fresh = fresh),
            followUp = result?.let { RemoteFollowUp(1, "next", 0, result = it) }
        )
    )

    private fun bounds() = controls.associateWith { compose.onNodeWithTag(it).getBoundsInRoot() }

    private fun assertNothingMoves(fontScale: Float?) {
        var state by mutableStateOf(attached(null))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme { TouchRemoteScreen(state, host, {}, {}) }
            }
        }
        compose.waitForIdle()
        val start = bounds()
        listOf("sent", "unknown", "rejected", null).forEach { result ->
            compose.runOnIdle { state = attached(result) }
            compose.waitForIdle()
            if (result != null) compose.onNodeWithTag("remote:status").assertExists()
            assertEquals(start, bounds(), "status from result $result moved a control")
        }
        compose.runOnIdle { state = attached(null, fresh = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("remote:checking").assertExists()
        assertEquals(start, bounds(), "Checking moved a control")
        compose.runOnIdle { state = attached(null, now.copy(paused = true)) }
        compose.waitForIdle()
        assertEquals(start, bounds(), "pausing moved a control")
        compose.runOnIdle { state = attached(null) }
        compose.waitForIdle()
        compose.onNodeWithTag("remote:text").performTextInput("x".repeat(300))
        compose.onNodeWithTag("remote:send").performClick()
        compose.waitForIdle()
        assertEquals(start, bounds(), "the text failure moved a control")
    }

    @Test fun noControlMovesWhenAStatusComesOrGoes() = assertNothingMoves(null)

    @Config(qualifiers = "w320dp-h2400dp-port-xhdpi")
    @Test
    fun noControlMovesAtALargeFontInANarrowWindow() = assertNothingMoves(1.5f)

    private fun show(state: RemoteControllerUi, onBack: () -> Unit = {}) {
        compose.setContent { MaterialTheme { TouchRemoteScreen(state, host, {}, onBack) } }
        compose.waitForIdle()
    }

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
    private val unselected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)

    // The catalog's own Back, on the catalog's page margin, rather than an outlined pill of its own.
    @Test fun backIsTheCatalogsOnTheCatalogsMargin() {
        var backs = 0
        show(attached(null)) { backs++ }
        val back = compose.onNodeWithTag("touch:back")
        back.assertTextContains("‹ Back")
        assertEquals(20f, back.getBoundsInRoot().left.value, 0.5f)
        assertEquals(20f, compose.onNodeWithTag("remote:previous").getBoundsInRoot().left.value, 0.5f)
        back.performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    // "Off" drawn as disabled read as unavailable, at 2.6:1, when it was the current choice.
    @Test fun theCurrentTrackIsSelectedNotDisabled() {
        show(attached(null, now.copy(audio = 1, subtitle = -1)))
        compose.onNodeWithText("Off").performScrollTo().assert(selected).assertIsEnabled()
        compose.onNodeWithText("Spanish").performScrollTo().assert(unselected).assertIsEnabled()
    }

    @Test fun theCurrentTrackStaysSelectedWhileCommandsCannotGo() {
        show(attached(null, now.copy(audio = 1, subtitle = -1), fresh = false))
        compose.onNodeWithText("Off").performScrollTo().assert(selected).assertIsEnabled()
        compose.onNodeWithText("Spanish").performScrollTo().assert(unselected).assertIsNotEnabled()
    }

    @Test fun choosingATrackMovesNothing() {
        var state by mutableStateOf(attached(null, now.copy(audio = null, subtitle = null)))
        compose.setContent { MaterialTheme { TouchRemoteScreen(state, host, {}, {}) } }
        compose.waitForIdle()
        val tracks = listOf("Off", "Spanish")
        fun places() = (bounds().toList() + tracks.map { it to compose.onNodeWithText(it).getBoundsInRoot() })
        val before = places()
        compose.runOnIdle { state = attached(null, now.copy(audio = 2, subtitle = 3)) }
        compose.waitForIdle()
        assertEquals(before, places())
        compose.onNodeWithText("Off").assert(unselected)
        compose.onNodeWithText("Spanish").assert(selected)
        compose.runOnIdle { state = attached(null, now.copy(audio = 2, subtitle = -1)) }
        compose.waitForIdle()
        assertEquals(before, places())
        compose.onNodeWithText("Off").assert(selected)
    }

    // PLAY-20: a TV reports no index for its automatic choice, so "not reported" can't be drawn as Off.
    @Test fun anUnreportedTrackSelectsNothingAndSaysSo() {
        show(attached(null, now.copy(audio = null, subtitle = null)))
        compose.onNodeWithText("Audio \u00b7 $REMOTE_TRACK_UNREPORTED").performScrollTo()
        compose.onNodeWithText("Subtitles \u00b7 $REMOTE_TRACK_UNREPORTED").performScrollTo()
        listOf("Off", "English", "Spanish").forEach { label ->
            compose.onAllNodes(hasText(label).and(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))).assertCountEquals(0)
        }
    }

    @Test fun aReportedTrackDropsTheNote() {
        show(attached(null, now.copy(audio = 2, subtitle = -1)))
        compose.onNodeWithText("Audio").performScrollTo()
        compose.onNodeWithText("Audio \u00b7 $REMOTE_TRACK_UNREPORTED").assertDoesNotExist()
        compose.onNodeWithText("Spanish").performScrollTo().assert(selected)
    }

    // A double tap on the current choice does nothing, so a reader shouldn't be offered it.
    @Test fun theCurrentTrackOffersAReaderNoAction() {
        show(attached(null, now.copy(audio = 1, subtitle = -1)))
        compose.onNodeWithText("Off").performScrollTo().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Spanish").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    // The fill alone is 2:1 against the page; the state needs 3:1.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun theCurrentTrackHasAnEdgeThatClearsThreeToOne() {
        compose.setContent { TouchTheme { TouchRemoteScreen(attached(null, now.copy(audio = 1, subtitle = -1)), host, {}, {}) } }
        compose.waitForIdle()
        val image = compose.onNodeWithText("Off").performScrollTo().captureToImage().toPixelMap()
        val edge = image[1, image.height / 2]
        assertTrue(contrast(edge, Color(0xFF10131C)) >= 3f, "edge against the page: $edge")
    }

    // On its own row Stop's default outline read as disabled beside the transport's.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun stopsEdgeClearsThreeToOne() {
        compose.setContent { TouchTheme { TouchRemoteScreen(attached(null), host, {}, {}) } }
        compose.waitForIdle()
        val image = compose.onNodeWithTag("remote:stop").captureToImage().toPixelMap()
        val edge = (0 until image.width / 4).map { image[it, image.height / 2] }.maxBy { contrast(it, Color(0xFF10131C)) }
        assertTrue(contrast(edge, Color(0xFF10131C)) >= 3f, "edge against the page: $edge")
    }

    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    // One title, one name for a track: the server's "English - SUBRIP" is not what the player's own lists say.
    @Test fun tracksCarryThePlayersNamesNotTheServersCodecText() {
        val session = Json.parseToJsonElement(
            """[{"Id":"s1","DeviceId":"tv","DeviceName":"TV","UserId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","SupportsRemoteControl":true,
            "PlayableMediaTypes":["Video"],"SupportedCommands":[],"PlayState":{"CanSeek":true},
            "NowPlayingItem":{"Id":"cccccccccccccccccccccccccccccccc","Name":"A film","RunTimeTicks":36000000000,"MediaStreams":[
            {"Type":"Audio","Index":1,"Codec":"aac","Language":"eng","Channels":2,"DisplayTitle":"English - AAC - Stereo","Mystery":1},
            {"Type":"Audio","Index":2,"Codec":"ac3","Language":"spa","Channels":6,"DisplayTitle":"Spanish - Dolby Digital - 5.1"},
            {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"eng","DisplayTitle":"English - SUBRIP","IsDefault":null}]}}]"""
        ).jsonArray
        val playing = remoteTargets(session, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "phone").single().now!!
        show(attached(null, playing))
        compose.onNodeWithText("English \u00b7 SRT").performScrollTo()
        compose.onNodeWithText("English - SUBRIP").assertDoesNotExist()
    }

    @Test fun aStreamTheServerDidntDescribeKeepsItsWording() {
        assertEquals(listOf("English"), remoteTrackLabels(listOf(RemoteTrack("Audio", 1, "English"))))
    }

    private val transport = listOf("remote:previous", "remote:back", "remote:playpause", "remote:forward", "remote:next")

    // Learned by position: Pause used to trade lines with its neighbours as the font grew.
    private fun assertOneFixedRow() {
        val boxes = transport.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        boxes.forEach { assertEquals(boxes[0].top, it.top, "one line") }
        boxes.zipWithNext { a, b -> assertEquals((a.right - a.left).value, (b.right - b.left).value, 1f, "equal cells") }
    }

    @Test fun transportIsOneRowOfEqualCells() {
        show(attached(null))
        assertOneFixedRow()
        val stop = compose.onNodeWithTag("remote:stop").getBoundsInRoot()
        val next = compose.onNodeWithTag("remote:next").getBoundsInRoot()
        assertTrue(stop.top >= next.bottom, "Stop is apart")
        assertEquals(next.right.value, stop.right.value, 1f, "Stop at the far end")
    }

    @Config(qualifiers = "w320dp-h2400dp-port-xhdpi")
    @Test
    fun transportStaysOneRowAtALargeFontInANarrowWindow() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { TouchRemoteScreen(attached(null), host, {}, {}) }
            }
        }
        compose.waitForIdle()
        assertOneFixedRow()
    }

    @Test fun aTitleThatCantSeekKeepsPauseWhereItWas() {
        var state by mutableStateOf(attached(null))
        compose.setContent { MaterialTheme { TouchRemoteScreen(state, host, {}, {}) } }
        compose.waitForIdle()
        val pause = compose.onNodeWithTag("remote:playpause").getBoundsInRoot()
        compose.runOnIdle { state = attached(null, now.copy(canSeek = false)) }
        compose.waitForIdle()
        compose.onNodeWithTag("remote:back").assertDoesNotExist()
        assertEquals(pause, compose.onNodeWithTag("remote:playpause").getBoundsInRoot())
    }

    // The reserved slot stays, but the longest wording fits one line, so it no longer leaves a hole under the title.
    @Test fun theStatusSlotIsOneLineOnAPhone() {
        show(attached(null))
        val page = compose.onNodeWithTag("touch:remote").getBoundsInRoot()
        val title = compose.onNodeWithTag("remote:title", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(title.top - page.top <= 48.dp, "slot and its gap: ${title.top - page.top}")
    }

    @Test fun everyStatusWordingHasItsRoomReserved() {
        listOf("next", "queue", "seek").forEach { op ->
            listOf("sent", "unknown", "rejected").forEach { result ->
                val wording = remoteCommandStatus(attached(null).controller!!.copy(followUp = RemoteFollowUp(1, op, 0, result = result)), 0)
                assertTrue(wording in REMOTE_STATUS_WORDINGS, "$op $result: $wording")
            }
        }
    }
}
