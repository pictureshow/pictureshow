/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PlaybackCapabilityChangesTest {
    @Test fun localGatewayRecordsTheRouteWithoutChangingItsDecisionOrOpeningAServer() = kotlinx.coroutines.runBlocking {
        var changes = 0
        var revision = 1L
        var captures = 0
        val updates = PlaybackCapabilityChanges { changes++ }
        val local = ReconnectLocalGateway()
        val request = app.pictureshow.core.playback.PlaybackRequest()
        val expected = local.prepare(request)
        val delegate = object : PlaybackGateway by local {
            override suspend fun prepare(request: app.pictureshow.core.playback.PlaybackRequest) = expected
        }
        val gateway = capabilityTrackedGateway(delegate, {
            captures++
            revision
        }, updates)
        updates.observed(1)
        assertSame(expected, gateway.prepare(request))
        updates.settled(true)
        updates.observed(1)
        assertEquals(0, changes)
        revision = 2
        updates.observed(2)
        assertEquals(1, changes)
        gateway.prepare(request)
        updates.settled(true)
        updates.observed(2)
        assertEquals(1, changes)
        assertEquals(2, captures)
    }

    @Test fun delayedInitialObserverCannotReplaceTheStreamAlreadyPreparedWithItsRevision() {
        var changes = 0
        val updates = PlaybackCapabilityChanges { changes++ }
        updates.observed(1) // Observer started with A.
        updates.negotiating()
        updates.prepared(2) // Initial HTTP preparation already consumed newer B.
        updates.observed(2) // Forced audio event completes while HTTP is in flight.
        assertEquals(0, changes)
        updates.settled(true)
        updates.observed(1) // An older asynchronous completion cannot move the baseline backwards.
        assertEquals(0, changes)
    }

    @Test fun aNewRouteDuringPreparationWaitsForOpenAndReplacesExactlyOnce() {
        var changes = 0
        val updates = PlaybackCapabilityChanges { changes++ }
        updates.negotiating()
        updates.prepared(1)
        updates.observed(2)
        assertEquals(0, changes)
        updates.settled(true)
        assertEquals(1, changes)
        updates.observed(2)
        updates.settled(true)
        assertEquals(1, changes)
        updates.negotiating()
        updates.prepared(2)
        updates.settled(true)
        assertEquals(1, changes)
        updates.observed(3) // Equal codec facts can still have a new physical-route revision.
        assertEquals(2, changes)
    }

    @Test fun unchangedDisplayAndBackgroundResumeDoNotReplaceTheMountedStream() {
        var changes = 0
        val updates = PlaybackCapabilityChanges { changes++ }
        updates.negotiating()
        updates.prepared(1)
        updates.observed(1)
        updates.settled(true)
        updates.settled(false)
        updates.observed(1)
        updates.settled(true)
        assertEquals(0, changes)
    }
}
