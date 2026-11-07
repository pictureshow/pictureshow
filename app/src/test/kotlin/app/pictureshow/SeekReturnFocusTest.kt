/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.InterruptedIOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SeekReturnFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private var exits = 0
    private lateinit var host: android.view.View

    @After fun cleanup() {
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    @Test fun visibleControlsDoNotOfferTheSurfaceAsAFocusRecoveryTargetAfterSeekDone() {
        launch(true)
        val surface = compose.onNodeWithTag("player:surface")
        surface.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        surface.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
        timeline.assert(hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true)))
        val surfaceId = surface.fetchSemanticsNode().id
        val timelineId = timeline.fetchSemanticsNode().id
        compose.runOnIdle {
            val provider = host.accessibilityNodeProvider!!
            val background = provider.createAccessibilityNodeInfo(surfaceId)
            assertTrue(background == null || !background.isVisibleToUser)
            assertTrue(checkNotNull(provider.createAccessibilityNodeInfo(timelineId)).isVisibleToUser)
        }
        timeline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.onNodeWithText("Done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        timeline.assertIsFocused()
        surface.assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun aFocusedSkipActionReturnsToTheTimelineWhenItDisappearsWithControlsVisible() = accessibleSkipReturn(false)

    @Test fun skipReturnUsesAccessibilityEnabledAfterThePlayerOpened() = accessibleSkipReturn(true)

    private fun accessibleSkipReturn(enableAfterLaunch: Boolean) {
        launch(!enableAfterLaunch)
        if (enableAfterLaunch) {
            compose.runOnIdle {
                val manager = host.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                assertTrue(!manager.isEnabled, "Accessibility must start disabled for this transition")
                shadowOf(manager).setScreenReader(true)
                assertTrue(manager.isEnabled)
            }
            compose.waitForIdle()
        }
        val timeline = compose.onNodeWithContentDescription("Playback position")
        timeline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        timeline.assertIsFocused()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        val skip = compose.onNodeWithTag("skip:Intro")
        val titleId = compose.onNodeWithText("An episode").fetchSemanticsNode().id
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        skip.assertIsFocused()
        compose.mainClock.autoAdvance = false
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.waitForIdle()
        // Deliver SideEffect snapshot writes between frames before querying the native tree.
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertTrue(compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isEmpty())
        skip.assertDoesNotExist()
        compose.runOnIdle {
            val title = host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(titleId)
            assertTrue(title == null || !title.isVisibleToUser, "Only the position is offered during accessible action recovery")
        }
        compose.onNodeWithContentDescription("Playback position").assertIsFocused()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("player:surface").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun ordinarySkipRemovalKeepsItsSurfaceInputTargetWhenTheTimelineIsNotFocusable() {
        launch(false)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        val skip = compose.onNodeWithTag("skip:Intro")
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        skip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        skip.assertDoesNotExist()
        compose.onNodeWithTag("player:surface").assertIsFocused()
        compose.onNodeWithContentDescription("Playback position").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun speedReturnOffersTheReaderOnlyItsOpenerUntilFocusIsAcknowledged() = openerReturn("player:speed")

    @Test fun playbackInfoReturnOffersTheReaderOnlyItsOpenerUntilFocusIsAcknowledged() = openerReturn("player:info")

    /** rc29: after Back from these panels the reader landed on the title, sent there by the host view's empty focus event. */
    private fun openerReturn(tag: String) {
        launch(true)
        val titleId = compose.onNodeWithText("An episode").fetchSemanticsNode().id
        val stopId = compose.onNodeWithText("Stop").fetchSemanticsNode().id
        val positionId = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().id
        val opener = compose.onNodeWithTag(tag)
        opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.runOnIdle {
            val provider = host.accessibilityNodeProvider!!
            listOf(titleId, stopId, positionId).forEach { id ->
                val node = provider.createAccessibilityNodeInfo(id)
                assertTrue(node == null || !node.isVisibleToUser, "Only the opener is offered while the reader returns to it")
            }
            assertTrue(!hostFocusReachesReader(), "The host's empty focus event must not send the reader to the first text")
        }
        // No reader acknowledges focus here, so the wait fails open and the controls are whole again.
        compose.mainClock.advanceTimeBy(1_600)
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(tag).assertIsFocused()
        compose.runOnIdle {
            val provider = host.accessibilityNodeProvider!!
            listOf(titleId, stopId, positionId).forEach { id ->
                assertTrue(checkNotNull(provider.createAccessibilityNodeInfo(id)).isVisibleToUser)
            }
            assertTrue(hostFocusReachesReader())
            assertEquals(0, exits)
        }
    }

    @Suppress("DEPRECATION")
    private fun hostFocusReachesReader(): Boolean {
        val parent = host.parent as android.view.ViewGroup
        val event = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
            .apply { className = "android.view.ViewGroup" }
        val delegate = androidx.core.view.ViewCompat.getAccessibilityDelegate(parent) ?: return true
        return delegate.onRequestSendAccessibilityEvent(parent, host, event)
    }

    @Test fun hiddenControlsLetReaderArrowsSeekAndReturnToThePosition() {
        launch(true)
        val positionNode = compose.onNodeWithContentDescription("Playback position")
        val before = positionNode.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        hideControls()
        val surfaceId = compose.onNodeWithTag("player:surface").fetchSemanticsNode().id
        compose.runOnIdle {
            val provider = host.accessibilityNodeProvider!!
            val surface = checkNotNull(provider.createAccessibilityNodeInfo(surfaceId))
            // TalkBack keeps the arrows; at "no next item" it scrolls a focused scrollable node in the arrow's direction.
            assertTrue(surface.isScrollable)
            val actions = surface.actionList.map { it.id }
            listOf(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            ).forEach { assertTrue(it.id in actions, "Missing $it") }
            val labels = surface.actionList.mapNotNull { it.label?.toString() }
            assertTrue("Back 10 seconds" in labels && "Forward 10 seconds" in labels && "Show controls" in labels)
            assertTrue(
                provider.performAction(
                    surfaceId,
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                    null
                )
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Playback position").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Focused) == true
        }
        val after = compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]
        assertTrue(before != after, "The position read after a reader seek must be the new one: $before")
        compose.onNodeWithTag("player:surface").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun hiddenControlsLetReaderUpAndDownRevealTheControls() {
        launch(true)
        hideControls()
        val surfaceId = compose.onNodeWithTag("player:surface").fetchSemanticsNode().id
        compose.runOnIdle {
            assertTrue(
                host.accessibilityNodeProvider!!.performAction(
                    surfaceId,
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                    null
                )
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player:surface").assertIsNotFocused()
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun oneReaderArrowThatArrivesAsBothKeyAndScrollSeeksOnce() {
        // Review fix round 1: a reader or service that forwards the arrow and also scrolls must not move 20 s.
        launch(true)
        for (keyFirst in listOf(false, true)) {
            hideControls()
            val surfaceId = compose.onNodeWithTag("player:surface").fetchSemanticsNode().id
            val before = seekLines().size
            compose.runOnIdle {
                // One press, delivered in one frame on both paths before either can recompose the surface.
                if (keyFirst) key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                host.accessibilityNodeProvider!!.performAction(
                    surfaceId,
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                    null
                )
                if (!keyFirst) key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.ACTION_UP)
            }
            compose.waitForIdle()
            val lines = seekLines().drop(before)
            assertEquals(1, lines.count { "dropped" !in it }, "keyFirst=$keyFirst: $lines")
            assertTrue(lines.single { "dropped" !in it }.contains("deltaMs=10000"), lines.toString())
            if (!keyFirst) assertTrue(lines.any { "source=key" in it && "dropped=paired" in it }, lines.toString())
            compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        }
        // Separate reader presses are separate seeks.
        repeat(2) {
            hideControls()
            val surfaceId = compose.onNodeWithTag("player:surface").fetchSemanticsNode().id
            compose.runOnIdle {
                host.accessibilityNodeProvider!!.performAction(
                    surfaceId,
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id,
                    null
                )
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        }
        val readerBack = seekLines().filter { "source=reader deltaMs=-10000" in it }
        assertEquals(2, readerBack.count { "dropped" !in it }, readerBack.toString())
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun withAReaderOnTheHiddenSurfaceTheRemoteKeysKeepTheirMeanings() {
        launch(true)
        compose.waitUntil(5_000) { activeIntro() }
        hideControls()
        // A visible Ask segment takes OK first: one skip, and the controls stay hidden.
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(20_000L, output.player.value!!.currentPosition) }
        assertTrue(compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isEmpty(), "OK skipped instead")
        // Past the segment, OK shows the controls.
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        for (code in listOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
            hideControls()
            val before = seekLines().size
            press(code)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, seekLines().size, "Up and Down show the controls without seeking")
        }
        hideControls()
        val before = seekLines().size
        press(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForIdle()
        // Held: the sixth repeat steps 30 s, as without a reader; each event is one seek.
        compose.runOnIdle {
            for (repeat in 0..5) key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, repeat = repeat)
            key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.ACTION_UP)
        }
        compose.waitForIdle()
        assertEquals(
            listOf(-10_000L) + List(5) { 10_000L } + 30_000L,
            seekLines().drop(before).map { it.substringAfter("deltaMs=").substringBefore(' ').toLong() }
        )
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun inRightToLeftLayoutsTheRemotesRightArrowStillSeeksForward() {
        launch(true, rtl = true)
        hideControls()
        var before = seekLines().size
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        assertEquals(listOf("player-seek source=key deltaMs=10000"), seekLines().drop(before))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        // A later press: real time passes between presses, while this test's clock otherwise stands still.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
        hideControls()
        before = seekLines().size
        val surfaceId = compose.onNodeWithTag("player:surface").fetchSemanticsNode().id
        compose.runOnIdle {
            host.accessibilityNodeProvider!!.performAction(
                surfaceId,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id,
                null
            )
        }
        compose.waitForIdle()
        assertEquals(listOf("player-seek source=reader deltaMs=10000"), seekLines().drop(before))
    }

    @Test fun backWhileTheReaderReturnsToItsOpenerEndsTheReturnAtOnce() {
        launch(true)
        val surfaceId = compose.onNodeWithTag("player:surface").fetchSemanticsNode().id
        closePanelFrom("player:speed")
        compose.onNodeWithTag("player:speed").assertIsFocused()
        // Back again hides the controls: the opener is gone, so nothing may stay suppressed for it.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(3)
        compose.runOnIdle {
            assertTrue(hostFocusReachesReader(), "The return must end with its opener")
            assertTrue(checkNotNull(host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(surfaceId)).isVisibleToUser)
        }
        compose.onNodeWithTag("player:surface").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {
            check(it())
        }
        frames(3)
        // Well inside the 1.5 s wait: the returned controls are whole at once.
        assertControlsOffered()
        assertTrue(playbackLines().any { it.startsWith("panel-return cancelled controls=false") }, playbackLines().toString())
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, exits) }
    }

    /**
     * rc30: TalkBack read the closing pane's window-state event as a new screen. It dropped the opener's focus event,
     * spoke the app's name and reached the button about 2 s late. The real pane events pass the real delegate here.
     */
    @Test fun closingAPanelKeepsItsPaneEventFromTheReaderWhileOpeningStillAnnounces() {
        val sent = mutableListOf<AccessibilityEvent>()
        val content = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        androidx.core.view.ViewCompat.setAccessibilityDelegate(
            content,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onRequestSendAccessibilityEvent(
                    parent: android.view.ViewGroup,
                    child: android.view.View,
                    event: AccessibilityEvent
                ): Boolean {
                    sent += AccessibilityEvent.obtain(event)
                    return true
                }
            }
        )
        spokenServiceEnabled()
        launch(true)
        val paneEvents = {
            sent.filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }.map { it.contentChangeTypes }
        }
        val opener = compose.onNodeWithTag("player:speed")
        opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player:speed").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) {
            paneEvents().contains(AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED)
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player:speed").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle {
            assertTrue(paneEvents().none { it and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0 }, paneEvents().toString())
            assertTrue(playbackLines().any { it == "panel-close pane-event suppressed" }, playbackLines().toString())
        }
    }

    @Test fun withoutAReaderAPaneEventIsNotFiltered() {
        launch(false)
        compose.runOnIdle { assertTrue(hostPaneEventReachesReader(AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED)) }
    }

    /** TalkBack hears no close, so the same title again would be no change of window title and no focus handoff to the row. */
    @Test fun reopeningThePanelGivesTalkBackADifferentTitleWithTheSameWords() {
        launch(true)
        val titles = (1..3).map {
            val opener = compose.onNodeWithTag("player:info")
            opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
            opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("player:panel").fetchSemanticsNodes().isNotEmpty() }
            val title = compose.onNodeWithTag("player:panel").fetchSemanticsNode().config[SemanticsProperties.PaneTitle]
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("player:panel").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("player:info").fetchSemanticsNodes().isNotEmpty() }
            title
        }
        assertEquals("Playback info", titles[0])
        assertTrue(titles[0] != titles[1] && titles[1] != titles[2], titles.toString())
        assertEquals(setOf("Playback info"), titles.map { it.replace("\u200B", "") }.toSet())
    }

    /** rc30: "Button. Skip credits" was spoken at +215 ms, ahead of the opener. It's offered once the opener has been reached. */
    @Test fun aSkipOfferWaitsForTheOpenerAfterAPanelCloses() {
        launch(true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        closePanelFrom("player:speed")
        compose.onNodeWithTag("player:speed").assertIsFocused()
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_600)
        frames(3)
        compose.onNodeWithTag("skip:Intro").assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aSkipOfferAppearsOnceTheReaderReachesTheOpener() {
        spokenServiceEnabled()
        launch(true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        closePanelFrom("player:speed")
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        val opener = compose.onNodeWithTag("player:speed").fetchSemanticsNode().id
        compose.runOnIdle {
            val focused = host.accessibilityNodeProvider!!.performAction(
                opener,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
            )
            assertTrue(focused)
        }
        // Well short of the 1.5 s the return waits when nothing acknowledges it.
        frames(6)
        compose.onNodeWithTag("skip:Intro").assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aSkipOfferDoesNotWaitOnAnOpenerThatHasGone() {
        launch(true)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("skip:Intro").fetchSemanticsNodes().isNotEmpty() }
        closePanelFrom("player:speed")
        compose.onNodeWithTag("skip:Intro").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(6)
        compose.onNodeWithTag("player:speed").assertDoesNotExist()
        compose.onNodeWithTag("skip:Intro").assertExists()
        compose.mainClock.autoAdvance = true
    }

    /** The screen's real end path: the player is released and the toggle, still focused, says what it said. */
    @Test fun theEndOfTheVideoLeavesTheFocusedToggleInThePlayerSaying() {
        launch(true)
        val toggle = compose.onNodeWithText("Pause")
        toggle.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        toggle.assertIsFocused()
        val before = toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("Playing", before)
        compose.runOnIdle {
            val decision = Media3PlaybackOutput::class.java.getDeclaredField("current")
                .apply { isAccessible = true }.get(output) as app.pictureshow.core.playback.PlayDecision
            output.onEnded(decision)
        }
        compose.waitUntil(5_000) { output.player.value == null }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue(playbackLines().any { it.startsWith("continuation item=") }, playbackLines().toString())
        // The label names the next action, so it does change.
        compose.onNodeWithText("Play").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, before))
    }

    /** PLAY-06: the position control's words are fresh on open. Hiding the controls forgets the last capture. */
    @Test fun openingTheControlsCapturesWhereHiddenPlaybackHasReached() {
        launch(true)
        val spokenPosition = {
            compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        }
        assertTrue(spokenPosition().startsWith("0:00"), spokenPosition())
        hideControls()
        // A jump the screen didn't ask for, which is what time passing looks like to it.
        compose.runOnIdle { output.player.value!!.seekTo(45_000) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("player:surface").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick
        ) { check(it()) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Playback position").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(spokenPosition().startsWith("0:45"), spokenPosition())
    }

    /** rc30: two utterance ids on each hide. The app announces nothing: one focus move and one change of the node's description. */
    /**
     * rc31: TalkBack read "Controls hidden" from the description change and again from the focus it moved
     * to the same node, whenever its queue reached the change after the focus had landed.
     */
    @Test fun hidingTheControlsLeavesOnlyTheFocusMoveToSpeak() {
        val sent = recordEventsReachingTheReader()
        spokenServiceEnabled()
        launch(true)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { sent.clear() }
        hideControls()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Controls hidden").assertIsFocused()
        compose.runOnIdle {
            assertTrue(sent.none { it.startsWith("TYPE_ANNOUNCEMENT") }, sent.toString())
            assertEquals(1, sent.count { it.startsWith("TYPE_VIEW_FOCUSED") }, sent.toString())
            assertEquals(0, sent.count { "desc=Controls hidden" in it }, sent.toString())
            assertEquals(1, playbackLines().count { it == "controls-hidden description-event suppressed" })
        }
    }

    /** With focus already on the surface nothing moves, so the description change is the only thing that can speak. */
    @Test fun aHideThatMovesNoFocusKeepsTheDescriptionChange() {
        val sent = recordEventsReachingTheReader()
        spokenServiceEnabled()
        launch(true)
        hideControls()
        compose.onNodeWithTag("player:surface").assertIsFocused()
        compose.onNodeWithTag("player:surface").performTouchInput { click() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player:surface").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { sent.clear() }
        hideControls()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(0, sent.count { it.startsWith("TYPE_VIEW_FOCUSED") }, sent.toString())
            assertEquals(1, sent.count { "desc=Controls hidden" in it }, sent.toString())
        }
    }

    @Test fun withoutAReaderAHideFiltersNothing() {
        launch(false)
        compose.onNodeWithTag("player:surface").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {
            check(it())
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            val parent = host.parent as android.view.ViewGroup
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION
                contentDescription = "Controls hidden"
            }
            val delegate = androidx.core.view.ViewCompat.getAccessibilityDelegate(parent)
            assertTrue(delegate == null || delegate.onRequestSendAccessibilityEvent(parent, host, event))
            assertTrue(playbackLines().none { it == "controls-hidden description-event suppressed" })
        }
    }

    private fun recordEventsReachingTheReader(): MutableList<String> {
        val sent = mutableListOf<String>()
        val content = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        androidx.core.view.ViewCompat.setAccessibilityDelegate(
            content,
            object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onRequestSendAccessibilityEvent(
                    parent: android.view.ViewGroup,
                    child: android.view.View,
                    event: AccessibilityEvent
                ): Boolean {
                    sent += AccessibilityEvent.eventTypeToString(event.eventType) +
                        " changes=${event.contentChangeTypes} text=${event.text} desc=${event.contentDescription} class=${event.className}"
                    return true
                }
            }
        )
        return sent
    }

    /** Compose sends accessibility events only when a service is enabled; the manager's enabled flag isn't enough. */
    private fun spokenServiceEnabled() {
        val manager = compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabledAccessibilityServiceList(
            listOf(
                android.accessibilityservice.AccessibilityServiceInfo().apply {
                    feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
                }
            )
        )
        shadowOf(manager).setTouchExplorationEnabled(true)
    }

    private fun hostPaneEventReachesReader(types: Int): Boolean {
        val parent = host.parent as android.view.ViewGroup
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { contentChangeTypes = types }
        val delegate = androidx.core.view.ViewCompat.getAccessibilityDelegate(parent) ?: return true
        return delegate.onRequestSendAccessibilityEvent(parent, host, event)
    }

    @Test fun reopeningAPanelDuringTheReturnHidesNothingAndTheNextReturnEnds() {
        launch(true)
        closePanelFrom("player:info")
        compose.onNodeWithTag("player:info").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {
            check(it())
        }
        frames(6)
        compose.runOnIdle { assertTrue(hostFocusReachesReader(), "Nothing is suppressed for a return while a panel is open") }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(6)
        compose.onNodeWithTag("player:info").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_600)
        frames(3)
        assertControlsOffered()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, exits) }
    }

    @Test fun aReturnWithoutAReaderDoesNotWait() {
        launch(false)
        compose.onNodeWithTag("player:surface").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {
            check(it())
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player:speed").fetchSemanticsNodes().isNotEmpty() }
        closePanelFrom("player:speed")
        compose.onNodeWithTag("player:speed").assertIsFocused()
        val lines = playbackLines()
        assertTrue(lines.any { it.startsWith("panel-return requested=true") }, lines.toString())
        assertTrue(lines.none { it.startsWith("panel-return target=") }, "No reader acknowledgment is awaited: $lines")
        compose.runOnIdle { assertTrue(hostFocusReachesReader()) }
        compose.mainClock.autoAdvance = true
    }

    /** Opens the panel from [tag], then Back closes it and six frames pass with the clock held. */
    private fun closePanelFrom(tag: String) {
        val opener = compose.onNodeWithTag(tag)
        opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { check(it()) }
        opener.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { check(it()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(6)
    }

    private fun assertControlsOffered() {
        val ids = listOf(
            compose.onNodeWithText("An episode").fetchSemanticsNode().id,
            compose.onNodeWithText("Stop").fetchSemanticsNode().id,
            compose.onNodeWithContentDescription("Playback position").fetchSemanticsNode().id
        )
        compose.runOnIdle {
            val provider = host.accessibilityNodeProvider!!
            ids.forEach { assertTrue(checkNotNull(provider.createAccessibilityNodeInfo(it)).isVisibleToUser) }
            assertTrue(hostFocusReachesReader())
        }
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun activeIntro(): Boolean =
        compose.onAllNodesWithText("Skip intro", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    private fun playbackLines(): List<String> = org.robolectric.shadows.ShadowLog.getLogsForTag("PictureshowPlayback").map { it.msg }

    private fun seekLines(): List<String> = playbackLines().filter { it.startsWith("player-seek ") }

    private fun key(code: Int, action: Int = android.view.KeyEvent.ACTION_DOWN, repeat: Int = 0) {
        host.dispatchKeyEvent(android.view.KeyEvent(0L, 0L, action, code, repeat))
    }

    private fun press(code: Int) = compose.runOnIdle {
        key(code)
        key(code, android.view.KeyEvent.ACTION_UP)
    }

    private fun hideControls() {
        compose.onNodeWithText("An episode").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An episode").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("player:surface")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Controls hidden")))
    }

    private fun launch(accessible: Boolean, rtl: Boolean = false) {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        val item = MediaItem("episode", "An episode", "Episode", seriesId = "series")
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val json = when {
                        request.url.encodedPath.endsWith(
                            "/PlaybackInfo"
                        ) ->
                            """{"PlaySessionId":"viewing","MediaSources":[{"Id":"source","RunTimeTicks":600000000,
                                "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                                "TranscodingUrl":"/Videos/episode/master.m3u8","MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"}]}]}"""
                        request.url.encodedPath.endsWith(
                            "/Episodes"
                        ) ->
                            """{"Items":[{"Id":"episode","Type":"Episode"},
                                {"Id":"next","Name":"Next story","Type":"Episode"}]}"""
                        request.url.encodedPath == "/Items/episode" ->
                            """{"Id":"episode","Name":"An episode",
                                "Type":"Episode","SeriesId":"series"}"""
                        request.url.encodedPath.startsWith(
                            "/MediaSegments"
                        ) -> """{"Items":[{"Id":"intro","Type":"Intro","StartTicks":0,"EndTicks":200000000}]}"""
                        else -> return@addHandler respond("", HttpStatusCode.NoContent)
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        startKoin {
            modules(
                module {
                    single { inertRemoteTarget() }
                    single { http }
                    single { DeviceIdentityStore(store) }
                    single { PlaybackPreferencesStore(store) }
                }
            )
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val catalog = CatalogSession(CatalogModelsTest.FakeCatalog(), accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setScreenReader(accessible)
        compose.setContent {
            host = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
            ) {
                PictureshowTheme { PlayerItemScreen(account, item, 0, catalog, {}, { exits++ }, providedOutput = output) }
            }
        }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            output.player.value != null
        }
        compose.waitForIdle()
    }

    /** The decoder is deliberately held; renderer timing is supplied at the real output callback boundary. */
    private class WaitingSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        private var uri: android.net.Uri? = null
        private var manifest: java.io.ByteArrayInputStream? = null
        override fun getUri(): android.net.Uri? = uri
        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            manifest = if (dataSpec.uri.path?.endsWith(".m3u8") == true) {
                (
                    "#EXTM3U\n#EXT-X-TARGETDURATION:61\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        "#EXTINF:60.008,\nsegment.ts\n#EXT-X-ENDLIST\n"
                    ).byteInputStream()
            } else {
                null
            }
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            manifest?.let { return it.read(buffer, offset, length) }
            return try {
                Thread.sleep(Long.MAX_VALUE)
                C.RESULT_END_OF_INPUT
            } catch (_: InterruptedException) {
                throw InterruptedIOException()
            }
        }
        override fun close() = Unit
    }
}
