/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.KeyEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PlaybackExitHandoffTest {
    private var time = 0L
    private val lines = mutableListOf<String>()
    private val handoff = PlaybackExitHandoff({ time }, lines::add)

    @Test fun completionMeasuresTheWholeReloadAndRecordsTheFirstInput() {
        handoff.begin("private-entry")
        time = 2_100
        handoff.input(KeyEvent.KEYCODE_DPAD_RIGHT)
        time = 3_000
        handoff.input(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(lines.isEmpty())
        time = 8_100
        handoff.finish("private-entry", "primary", true)
        assertEquals(1, lines.size)
        assertEquals(
            "exit-handoff phase=complete elapsedMs=8100 target=primary focused=true " +
                "inputScope=activity inputBefore=true firstInput=right inputMs=2100",
            lines.single()
        )
        handoff.finish("private-entry", "primary", true)
        assertEquals(1, lines.size)
    }

    @Test fun aFailedFocusRequestStillFinishesTheObservation() {
        handoff.begin("private-entry")
        time = 8_000
        handoff.finish("private-entry", "retry", false)
        assertEquals(1, lines.size)
        assertEquals(
            "exit-handoff phase=complete elapsedMs=8000 target=retry focused=false " +
                "inputScope=activity inputBefore=false firstInput=none inputMs=none",
            lines.single()
        )
    }

    @Test fun leavingTheDestinationAbandonsItAndRejectsItsLateCompletion() {
        handoff.begin("old-entry")
        time = 200
        handoff.input(KeyEvent.KEYCODE_BACK)
        handoff.destinationChanged("new-entry")
        time = 8_000
        handoff.finish("old-entry", "primary", true)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("phase=abandoned elapsedMs=200 reason=left"))
        assertTrue(lines.single().endsWith("inputBefore=true firstInput=back inputMs=200"))
    }

    @Test fun aNewExitCannotBeCompletedByAnOlderPage() {
        handoff.begin("old-entry")
        time = 100
        handoff.begin("new-entry")
        handoff.destinationChanged("new-entry")
        handoff.finish("old-entry", "primary", true)
        time = 300
        handoff.finish("new-entry", "primary", true)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("phase=abandoned elapsedMs=100 reason=superseded"))
        assertTrue(lines[1].contains("phase=complete elapsedMs=200"))
    }

    @Test fun arbitraryKeysTargetsAndEntryIdentifiersAreNotWritten() {
        handoff.input(KeyEvent.KEYCODE_DPAD_DOWN)
        handoff.begin("private-entry")
        handoff.input(KeyEvent.KEYCODE_A)
        handoff.finish("private-entry", "Private node title", true)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("target=other"))
        assertTrue(lines.single().contains("inputBefore=false"))
        assertFalse(lines.single().contains("Private") || lines.single().contains("private-entry"))
    }

    @Test fun aPendingRightPressIsDeliveredOnlyAfterTheFreshTargetHasFocus() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right-down" })
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, false, 0) { delivered += "right-up" })
        assertTrue(delivered.isEmpty())
        time = 8_500
        handoff.finish("entry", "primary", true)
        assertEquals(listOf("right-down", "right-up"), delivered)
        handoff.finish("entry", "primary", true)
        assertEquals(2, delivered.size)
    }

    @Test fun backLeavesImmediatelyAndDropsTheDeferredPress() {
        val delivered = mutableListOf<Int>()
        handoff.begin("entry")
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) { delivered += 1 })
        assertFalse(handoff.defer(KeyEvent.KEYCODE_BACK, true, 0) { delivered += 2 })
        handoff.destinationChanged("elsewhere")
        handoff.finish("entry", "primary", true)
        assertTrue(delivered.isEmpty())
    }

    @Test fun aFailedFocusRequestDoesNotReplayAnActionAgainstAnotherTarget() {
        val delivered = mutableListOf<Int>()
        handoff.begin("entry")
        handoff.defer(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true, 0) { delivered += 1 }
        handoff.finish("entry", "none", false)
        assertTrue(delivered.isEmpty())
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += 2 })
    }

    @Test fun deferredKeysKeepTheirOrderAndStopWhenTheDestinationChanges() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right" }
        handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) {
            delivered += "ok"
            handoff.destinationChanged("player")
        }
        handoff.defer(KeyEvent.KEYCODE_DPAD_LEFT, true, 0) { delivered += "left" }
        handoff.finish("entry", "primary", true)
        assertEquals(listOf("right", "ok"), delivered)
    }

    @Test fun anUnmatchedKeyUpAndUnrelatedKeysAreNotDeferred() {
        handoff.begin("entry")
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, false, 0) {})
        assertFalse(handoff.defer(KeyEvent.KEYCODE_A, true, 0) {})
    }

    @Test fun aHeldDirectionIsOnePendingMoveAndAnExpiredOkCannotStartPlayback() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right" }
        repeat(50) { n -> assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, n + 1) { delivered += "repeat" }) }
        handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, false, 0) { delivered += "up" }
        handoff.finish("entry", "primary", true)
        assertEquals(listOf("right", "up"), delivered)
        handoff.begin("next")
        handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) { delivered += "stale ok" }
        time = 30_000
        handoff.finish("next", "primary", true)
        assertEquals(listOf("right", "up"), delivered)
        assertTrue(lines.any { it == "exit-input phase=dropped reason=deadline reports=1" })
    }

    @Test fun aLongBurstHasBoundedStorageAndCannotReplayAHalfPress() {
        val delivered = mutableListOf<Int>()
        handoff.begin("entry")
        repeat(33) { handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += it } }
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) { delivered += 99 })
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, false, 0) { delivered += 100 })
        assertFalse(handoff.defer(KeyEvent.KEYCODE_BACK, true, 0) {})
        handoff.finish("entry", "primary", true)
        assertTrue(delivered.isEmpty())
        assertTrue(lines.any { it == "exit-input phase=dropped reason=overflow reports=32" })
    }

    @Test fun aTalkBackSelectIsDeferredAndReportsAccessibilityAsItsSource() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        time = 700
        assertTrue(handoff.accessibleSelect("entry") { delivered += "play" })
        assertTrue(delivered.isEmpty())
        time = 8_500
        handoff.finish("entry", "primary", true)
        assertEquals(listOf("play"), delivered)
        assertTrue(lines.single().endsWith("inputScope=accessibility inputBefore=true firstInput=ok inputMs=700"))
    }

    @Test fun accessibilitySelectCannotEscapeItsDestinationOrTheDeadline() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        assertFalse(handoff.accessibleSelect("other") { delivered += "wrong" })
        handoff.accessibleSelect("entry") { delivered += "cancelled" }
        handoff.destinationChanged("left")
        handoff.finish("entry", "primary", true)
        assertTrue(delivered.isEmpty())
        handoff.begin("next")
        time = 30_000
        assertFalse(handoff.accessibleSelect("next") { delivered += "late" })
        handoff.finish("next", "primary", true)
        assertTrue(delivered.isEmpty())
    }

    @Test fun readerMovementBelongsToItsDestinationAndKeepsTheFirstObservedInput() {
        handoff.begin("entry")
        time = 400
        handoff.accessibleFocus("other")
        time = 2_500
        handoff.accessibleFocus("entry")
        time = 3_000
        handoff.input(KeyEvent.KEYCODE_DPAD_CENTER)
        handoff.finish("entry", "played", true)
        assertTrue(lines.single().endsWith("inputScope=accessibility inputBefore=true firstInput=focus inputMs=2500"))
    }

    @Test fun livePendingControlsReceiveDirectionsAndSelectWhileMediaWaits() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        handoff.live("entry", true)
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right" })
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) { delivered += "select" })
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, false, 0) { delivered += "release" })
        assertTrue(handoff.defer(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true, 0) { delivered += "media" })
        assertTrue(delivered.isEmpty())
        handoff.finish("entry", "primary", true)
        assertEquals(listOf("media"), delivered)
    }

    @Test fun bufferedDirectionsReachTheLiveHeaderBeforeALaterSelection() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right-down" }
        handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, false, 0) { delivered += "right-up" }
        handoff.defer(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true, 0) { delivered += "media" }
        handoff.live("entry", true)
        assertEquals(listOf("right-down", "right-up"), delivered)
        handoff.accessibleSelect("entry") { delivered += "selected" }
        handoff.finish("entry", "played", true)
        assertEquals(listOf("right-down", "right-up", "media", "selected"), delivered)
    }

    @Test fun aSelectThatStartedPendingCannotReleaseAgainstTheFreshHandler() {
        var freshClick = 0
        var intended = 0
        handoff.begin("entry")
        handoff.live("entry", true)
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) { freshClick++ })
        // The pending button handles DOWN and queues its guarded intent.
        handoff.accessibleSelect("entry") { intended++ }
        handoff.live("entry", false)
        handoff.finish("entry", "played", true)
        assertEquals(1, intended)
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, false, 0) { freshClick++ })
        assertEquals(0, freshClick)
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) {})
        assertFalse(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, false, 0) {})
    }

    @Test fun anotherDestinationCannotExposeThePendingControls() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        handoff.live("other", true)
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right" })
        assertTrue(delivered.isEmpty())
        handoff.live("entry", true)
        assertEquals(listOf("right"), delivered)
    }

    @Test fun openingLiveControlsCannotReleaseAnExpiredInput() {
        val delivered = mutableListOf<String>()
        handoff.begin("entry")
        handoff.defer(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0) { delivered += "right" }
        time = 30_000
        handoff.live("entry", true)
        assertTrue(delivered.isEmpty())
        assertTrue(handoff.defer(KeyEvent.KEYCODE_DPAD_CENTER, true, 0) {})
        handoff.finish("entry", "primary", true)
        assertTrue(delivered.isEmpty())
    }
}
