/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import androidx.compose.runtime.MutableState
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.sync.SyncClockAnchor
import app.pictureshow.core.sync.SyncCommand
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncMembership
import app.pictureshow.core.sync.SyncQueue
import app.pictureshow.core.sync.SyncQueueEntry
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule

/** Actual publisher/sink tests. Reflection only seeds Host-owned input facts and invokes
 * its private publisher; no copied quality/phase calculation or new production seam. */
class SyncPlayPresentationTruthDiagnosticTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    private data class Receipt(val text: String, val assigned: SyncPlayPresentation, val reads: Int)

    private class Rig(scope: TestScope, phase: String = "Playing", wait: String? = null) : SyncPlayGateway {
        var now = 1000L
        val epoch = 1_762_776_000_000L
        var snapshotReads = 0
        var lastSnapshotReadStart = -1L
        var lastSnapshotReadEnd = -1L
        var readsAtOldPublished = -1
        val receipts = mutableListOf<Receipt>()
        val groupSecret = "GROUP-PRIVATE-SENTINEL"
        val itemSecret = "ITEM-PRIVATE-SENTINEL"
        val entrySecret = "ENTRY-PRIVATE-SENTINEL"
        val identifying = listOf(
            groupSecret,
            itemSecret,
            entrySecret,
            "VIEWER-PRIVATE-SENTINEL",
            "TOKEN-PRIVATE-SENTINEL",
            "https://private.invalid/secret",
            "PERSON-PRIVATE-SENTINEL"
        )
        val host: SyncPlayHost = SyncPlayHost(scope.backgroundScope, this, { epoch + now }, { now }) { line ->
            if (line.startsWith("trace-published ")) readsAtOldPublished = snapshotReads
            if (line.startsWith("trace-presentation-v1 ")) {
                receipts += Receipt(line, hostValue().state.value, snapshotReads)
            }
        }

        // Calling through a method avoids referring to an incompletely initialized property
        // from the diagnostic lambda; SyncPlayHost construction itself emits nothing.
        private fun hostValue() = host
        val renderer = object : SyncRenderer {
            override fun snapshot(): SyncRendererState {
                snapshotReads++
                lastSnapshotReadStart = now
                now += 7 // Deliberate observable read duration, not an assumed atomic sample.
                lastSnapshotReadEnd = now
                return SyncRendererState(
                    itemSecret, 1_000_000L + snapshotReads * 10_000L, 900_000_000L,
                    ready = true, buffering = false, playing = true, seekable = true,
                    playlistItemId = entrySecret,
                    // Old constructor: no dependency on a not-yet-added isPlaying argument.
                    native = SyncNativeRendererState(
                        17,
                        3,
                        false,
                        0,
                        false,
                        700L + snapshotReads,
                        900,
                        200
                    ),
                    playWhenReady = false
                )
            }
            override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double) = false
        }

        init {
            field(
                "account",
                Account(
                    Server("SERVER-PRIVATE-SENTINEL", "Server", identifying[5], "12.4"),
                    User("USER-PRIVATE-SENTINEL", identifying[3]),
                    identifying[4]
                )
            )
            val queue = SyncQueue(
                listOf(SyncQueueEntry(itemSecret, entrySecret)),
                0,
                1_000_000,
                false,
                "RepeatNone",
                "Sorted",
                epoch * 10_000
            )

            @Suppress("UNCHECKED_CAST")
            val membershipState = SyncPlayHost::class.java.getDeclaredField("membership\$delegate").let {
                it.isAccessible = true
                it.get(host) as MutableState<SyncMembership>
            }
            membershipState.value = SyncMembership(
                generation = 23,
                groupId = groupSecret,
                phase = phase,
                group = SyncGroup(groupSecret, identifying[6], phase, listOf(identifying[6]), epoch * 10_000),
                queue = queue
            )
            field("renderer", renderer)
            field("rendererToken", Any())
            field("connected", true)
            field("waitReason", wait)
            field("timelineApplied", true)
            field(
                "timeline",
                SyncCommand(
                    groupSecret,
                    entrySecret,
                    "Pause",
                    epoch + now,
                    1_000_000,
                    epoch * 10_000
                )
            )
            field("anchor", SyncClockAnchor((epoch + now) * 10_000, epoch + now, now, 10_000))
        }

        fun field(name: String, value: Any?) {
            SyncPlayHost::class.java.getDeclaredField(name).let {
                it.isAccessible = true
                it.set(host, value)
            }
        }
        fun publish(): Receipt {
            SyncPlayHost::class.java.getDeclaredMethod("publish").let {
                it.isAccessible = true
                it.invoke(host)
            }
            assertEquals(1, receipts.size, "Actual final presentation diagnostic is required")
            return receipts.single()
        }
        override suspend fun groups(account: Account): ClientResult<List<SyncGroup>> = ClientResult.Success(emptyList())
        override suspend fun access(account: Account): ClientResult<String> = ClientResult.Success("CreateAndJoinGroups")
        override suspend fun time(account: Account): ClientResult<SyncUtcTime> = ClientResult.Success(SyncUtcTime(epoch + now, epoch + now))
        override suspend fun send(account: Account, request: SyncRequest) = 204
    }

    private fun fields(text: String): Map<String, String> {
        val parts = text.split(' ').filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(parts.size, parts.map { it.first }.toSet().size, "Ambiguous duplicate diagnostic key")
        return parts.toMap()
    }

    @Test fun finalTraceMatchesStateAlreadyAssignedAtSinkForPlayingAndWaiting() = runTest {
        for ((phase, reason) in listOf("Playing" to null, "Waiting" to "Buffer", "Paused" to null)) {
            val rig = Rig(this, phase, reason)
            val receipt = rig.publish()
            val f = fields(receipt.text)
            assertEquals(receipt.assigned, rig.host.state.value)
            assertEquals(phase, receipt.assigned.phase)
            assertEquals(receipt.assigned.phase, f["presentationPhase"])
            assertEquals(reason ?: "none", f["presentationWait"])
            assertEquals(receipt.assigned.quality.name, f["presentationQuality"])
            assertEquals(receipt.assigned.localPlayback.name, f["presentationLocal"])
            if (phase == "Waiting") assertEquals("UNKNOWN", f["presentationQuality"]) else assertEquals("IN_SYNC", f["presentationQuality"])
        }
    }

    @Test fun diagnosticReusesDerivationSnapshotAndBracketsItsSequentialReads() = runTest {
        val rig = Rig(this)
        val receipt = rig.publish()
        val f = fields(receipt.text)
        assertTrue(rig.readsAtOldPublished >= 0, "Preserve old trace compatibility")
        assertEquals(rig.readsAtOldPublished + 1, receipt.reads, "Final diagnostic must not resnapshot")
        assertEquals(1_000_000L + receipt.reads * 10_000L, f["positionTicks"]?.toLong())
        assertEquals(700L + receipt.reads, f["nativePositionMs"]?.toLong())
        val start = assertNotNull(f["observationStartMs"]).toLong()
        val end = assertNotNull(f["observationEndMs"]).toLong()
        assertTrue(start <= rig.lastSnapshotReadStart)
        assertTrue(end >= rig.lastSnapshotReadEnd)
        assertTrue(end - start >= 7, "Do not call sequential native reads instantaneous")
        assertTrue(end <= assertNotNull(f["elapsedMs"]).toLong(), "Emission follows observation")
    }

    @Test fun nullRendererAndUnknownNativePlayingRemainUnknown() = runTest {
        val rig = Rig(this)
        rig.field("renderer", null)
        val nullReceipt = rig.publish()
        val absent = fields(nullReceipt.text)
        assertEquals(0, rig.snapshotReads)
        assertEquals("false", absent["renderer"])
        assertEquals("false", absent["native"])
        assertEquals("null", absent["nativePlaying"])
        assertEquals("UNAVAILABLE", absent["presentationLocal"])
        assertEquals("UNKNOWN", absent["presentationQuality"])
        val other = Rig(this)
        val f = fields(other.publish().text)
        assertEquals("true", f["playing"], "Exposed renderer is deliberately playing")
        assertEquals("false", f["nativePwr"])
        assertEquals("null", f["nativePlaying"], "Missing literal native isPlaying must not borrow exposed playing/PWR")
        assertEquals("17", f["nativeGeneration"])
        assertEquals("23", f["generation"])
        assertEquals("3", f["nativeState"])
        assertEquals("0", f["nativeSuppression"])
        assertEquals("false", f["pending"])
        assertEquals("true", f["applied"])
    }

    @Test fun finalTraceOmitsIdentifyingTextAndWhitelistsUnknownWaitReason() = runTest {
        val rig = Rig(this, "Waiting", "TOKEN-PRIVATE-SENTINEL https://private.invalid/secret")
        val receipt = rig.publish()
        val f = fields(receipt.text)
        assertEquals("other", f["presentationWait"])
        for (secret in rig.identifying + listOf("SERVER-PRIVATE-SENTINEL", "USER-PRIVATE-SENTINEL")) {
            assertFalse(receipt.text.contains(secret), "Diagnostic leaked identifying fixture data")
        }
        assertFalse(receipt.text.contains("https://"))
        assertFalse(receipt.text.contains("SyncGroup("))
        assertEquals("Waiting", f["presentationPhase"])
    }
}
