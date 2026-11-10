/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.syncUtcTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Rule

/** Rendering is a fake clock, while all orchestration and wire parsing are production code. */
class SyncPlayHostTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    private val account = Account(Server("s", "Server", "https://media.example.test/jf", "12.4"), User("a", "Ana"), "token-a")
    private val other = account.copy(user = User("b", "Bea"), accessToken = "token-b")

    private data class Applied(val position: Long?, val playing: Boolean?, val rate: Double)

    private class Renderer : SyncRenderer {
        var value = SyncRendererState("local-item", 123_456_789L, 9_000_000_000L, true, false, true, true)
        val applied = mutableListOf<Applied>()
        override fun snapshot() = value
        override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double) {
            applied += Applied(positionTicks, playing, rate)
            value = value.copy(positionTicks = positionTicks ?: value.positionTicks, playing = playing ?: value.playing)
        }
    }

    private inner class Rig(val scope: TestScope) : SyncPlayGateway {
        val sent = mutableListOf<Pair<Account, SyncRequest>>()
        val renderer = Renderer()
        val opens = mutableListOf<SyncOpen>()
        var policy = "CreateAndJoinGroups"
        var httpStatus = 204
        var sendGate: CompletableDeferred<Unit>? = null
        var accessGate: CompletableDeferred<Unit>? = null
        var failClock = false
        val epoch = 1_762_776_000_000L
        fun wall() = epoch + scope.testScheduler.currentTime
        val host = SyncPlayHost(scope.backgroundScope, this, ::wall, { scope.testScheduler.currentTime })

        init {
            scope.backgroundScope.launch { host.opens.collect { opens += it } }
            host.bind(account)
            host.socket(true)
            host.bindRenderer(renderer, renderer)
        }

        override suspend fun groups(account: Account) = ClientResult.Success(listOf(group()))
        override suspend fun access(account: Account): ClientResult<String> {
            accessGate?.let { withContext(NonCancellable) { it.await() } }
            return ClientResult.Success(policy)
        }
        override suspend fun time(account: Account): ClientResult<SyncUtcTime> = if (failClock) {
            ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Clock unavailable"))
        } else {
            ClientResult.Success(SyncUtcTime(wall(), wall()))
        }
        override suspend fun send(account: Account, request: SyncRequest): Int {
            sent += account to request
            sendGate?.let { withContext(NonCancellable) { it.await() } }
            return httpStatus
        }
        fun group() = SyncGroup("g", "Evening", "Paused", listOf("Ana", "Bea"), epoch * 10_000)
        fun refresh() {
            host.refresh()
            scope.runCurrent()
        }
        fun join() {
            refresh()
            host.join("g")
            scope.runCurrent()
        }
        fun joined() {
            host.receive(
                update(
                    "GroupJoined",
                    buildJsonObject {
                        put("GroupId", "g")
                        put("GroupName", "Evening")
                        put("State", "Paused")
                        put("Participants", JsonArray(listOf(JsonPrimitive("Ana"), JsonPrimitive("Bea"))))
                        put("LastUpdatedAt", syncUtcTimestamp(epoch))
                    }
                )
            )
        }
        fun queue(item: String = "local-item", entry: String = "entry-a", ticks: Long = 55_000_000L) {
            host.receive(
                update(
                    "PlayQueue",
                    buildJsonObject {
                        put("Reason", "NewPlaylist")
                        put("LastUpdate", syncUtcTimestamp(wall()))
                        put(
                            "Playlist",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("ItemId", item)
                                        put("PlaylistItemId", entry)
                                    }
                                )
                            )
                        )
                        put("PlayingItemIndex", 0)
                        put("StartPositionTicks", ticks)
                        put("IsPlaying", false)
                        put("RepeatMode", "RepeatNone")
                        put("ShuffleMode", "Sorted")
                    }
                )
            )
        }
        fun snapshot() {
            joined()
            queue()
            scope.runCurrent()
        }
        fun operations() = sent.map { it.second.operation }
        fun command(command: String, delayMs: Long = 0, ticks: Long = 90_000_000L, id: String? = null): String = buildJsonObject {
            put("MessageType", "SyncPlayCommand")
            id?.let { put("MessageId", it) }
            put(
                "Data",
                buildJsonObject {
                    put("GroupId", "g")
                    put("PlaylistItemId", "entry-a")
                    put("Command", command)
                    put("PositionTicks", ticks)
                    put("When", syncUtcTimestamp(wall() + delayMs))
                    put("EmittedAt", syncUtcTimestamp(wall()))
                }
            )
        }.toString()
        fun update(type: String, data: JsonObject? = null): String = buildJsonObject {
            put("MessageType", "SyncPlayGroupUpdate")
            put(
                "Data",
                buildJsonObject {
                    put("GroupId", "g")
                    put("Type", type)
                    put("Data", data ?: JsonNull)
                }
            )
        }.toString()
    }

    @Test fun joinUsesTheServerQueueAndNeverTheUnrelatedLocalTitle() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined()
        assertTrue(rig.opens.isEmpty())
        rig.queue("server-item", "server-entry", 987_654_321L)
        runCurrent()
        val open = rig.opens.single()
        assertEquals("server-item", open.itemId)
        assertEquals("server-entry", open.entryId)
        assertEquals(987_654_321L, open.ticks)
        assertTrue(rig.operations().contains("Join"))
        assertFalse(rig.operations().contains("SetNewQueue"))
    }

    @Test fun createSeedsCurrentItemOnlyAfterTheServerConfirmsMembership() = runTest {
        val rig = Rig(this)
        rig.refresh()
        val at = rig.renderer.value.positionTicks
        rig.host.create("Evening")
        runCurrent()
        assertTrue(rig.operations().contains("New"))
        assertFalse(rig.operations().contains("SetNewQueue"))
        rig.joined()
        runCurrent()
        val body = assertNotNull(rig.sent.single { it.second.operation == "SetNewQueue" }.second.body)
        assertEquals(JsonArray(listOf(JsonPrimitive("local-item"))), body["PlayingQueue"])
        assertEquals(at, body.getValue("StartPositionTicks").jsonPrimitive.long)
    }

    @Test fun queueArrivingBeforeGroupMetadataStillOpensItsAuthoritativeEntry() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.queue("server-item", "server-entry")
        rig.joined()
        runCurrent()
        assertEquals("server-item", rig.opens.single().itemId)
        assertTrue(rig.host.membership.hasSnapshot)
    }

    @Test fun futureUnpauseWaitsForTheCalibratedWhenAndDoesNotEcho() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.renderer.applied.clear()
        rig.host.receive(rig.command("Unpause", delayMs = 1_000))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.playing == true })
        assertFalse(rig.renderer.applied.any { it.position != null })
        advanceTimeBy(1)
        runCurrent()
        assertTrue(rig.renderer.value.playing)
        assertEquals(90_000_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.operations().contains("Unpause"))
        assertFalse(rig.operations().contains("Seek"))
    }

    @Test fun outgoingPauseSeekAndNextDoNotMoveLocalOutputBeforeTheServerCommand() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.renderer.applied.clear()
        val before = rig.renderer.value
        assertTrue(rig.host.transport("pause"))
        assertTrue(rig.host.transport("seek", 45_000))
        assertTrue(rig.host.transport("next"))
        runCurrent()
        assertEquals(before, rig.renderer.value)
        assertTrue(rig.renderer.applied.isEmpty())
        assertTrue(rig.operations().containsAll(listOf("Pause", "Seek", "NextItem")))
        val next = rig.sent.single { it.second.operation == "NextItem" }.second.body!!
        assertEquals(JsonPrimitive("entry-a"), next["PlaylistItemId"])
    }

    @Test fun bufferingAndReadyCarryActualEntryPositionAndRendererState() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 222_333_444L, ready = false, buffering = true, playing = true)
        rig.host.tick()
        runCurrent()
        val buffer = rig.sent.single { it.second.operation == "Buffering" }.second.body!!
        assertEquals(222_333_444L, buffer.getValue("PositionTicks").jsonPrimitive.long)
        assertEquals(JsonPrimitive("entry-a"), buffer["PlaylistItemId"])
        assertTrue(buffer.getValue("IsPlaying").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(syncUtcTimestamp(rig.wall())), buffer["When"])
        assertFalse(rig.renderer.value.playing)
        rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false)
        rig.host.tick()
        runCurrent()
        val ready = rig.sent.single { it.second.operation == "Ready" }.second.body!!
        assertEquals(222_333_444L, ready.getValue("PositionTicks").jsonPrimitive.long)
        assertEquals(JsonPrimitive("entry-a"), ready["PlaylistItemId"])
        assertFalse(ready.getValue("IsPlaying").jsonPrimitive.boolean)
        rig.host.tick()
        runCurrent()
        assertEquals(1, rig.sent.count { it.second.operation == "Ready" })
    }

    @Test fun leavePausesOnceAndNeverStopsTheGroupOrReplaysLateCommands() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.receive(rig.command("Unpause", delayMs = 1_000))
        runCurrent()
        rig.sent.clear()
        val position = rig.renderer.value.positionTicks
        rig.host.leave()
        rig.host.leave()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        rig.host.receive(rig.command("Unpause"))
        runCurrent()
        assertEquals(listOf("Leave"), rig.operations())
        assertFalse(rig.renderer.value.playing)
        assertEquals(position, rig.renderer.value.positionTicks)
        assertFalse(rig.host.participating)
    }

    @Test fun socketLossPausesAndRequiresFreshQueueAndClockBeforeOutput() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.receive(rig.command("Unpause"))
        runCurrent()
        assertTrue(rig.renderer.value.playing)
        rig.host.socket(false)
        assertFalse(rig.renderer.value.playing)
        assertEquals("Reconnecting", rig.host.membership.phase)
        assertFalse(rig.host.membership.hasSnapshot)
        assertTrue(rig.host.transport("play"))
        rig.host.socket(true)
        runCurrent()
        assertEquals(2, rig.operations().count { it == "Join" })
        assertFalse(rig.renderer.value.playing)
        assertFalse(rig.host.membership.hasSnapshot)
    }

    @Test fun deniedOrUnknownEventsPauseAndKeepTimelineIntentsConsumedUntilExplicitLeave() = runTest {
        for (kind in listOf("LibraryAccessDenied", "FutureEvent")) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            rig.host.receive(rig.command("Unpause"))
            runCurrent()
            rig.host.receive(rig.update(kind))
            runCurrent()
            assertFalse(rig.renderer.value.playing, kind)
            assertTrue(rig.host.transport("play"), "$kind must not release the grouped output to local controls")
            assertTrue(rig.host.transport("seek", 1234), kind)
            rig.host.leave()
            runCurrent()
            assertFalse(rig.host.transport("play"), kind)
        }
    }

    @Test fun anUnpauseCannotArmABufferingRendererToStartOutsideTheWaitBarrier() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.renderer.value = rig.renderer.value.copy(ready = false, buffering = true)
        rig.host.tick()
        runCurrent()
        rig.renderer.applied.clear()
        rig.host.receive(rig.command("Unpause"))
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.playing == true })
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun staleRefreshCannotPublishThePreviousAccountPolicyOrGroups() = runTest {
        val rig = Rig(this)
        rig.accessGate = CompletableDeferred()
        rig.host.refresh()
        runCurrent()
        rig.host.bind(other)
        rig.accessGate!!.complete(Unit)
        runCurrent()
        assertEquals("Bea", rig.host.state.value.viewer)
        assertEquals("None", rig.host.state.value.policy)
        assertTrue(rig.host.state.value.groups.isEmpty())
    }

    @Test fun staleJoinCompletionCannotReacquireMembershipAfterTheAccountChanges() = runTest {
        val rig = Rig(this)
        rig.refresh()
        rig.sendGate = CompletableDeferred()
        rig.host.join("g")
        runCurrent()
        rig.host.bind(other)
        rig.sendGate!!.complete(Unit)
        runCurrent()
        assertEquals("Bea", rig.host.state.value.viewer)
        assertFalse(rig.host.participating)
        assertFalse(rig.host.membership.hasSnapshot)
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun invalidClockKeepsOutputPausedEvenIfTheServerSaysPlay() = runTest {
        val rig = Rig(this)
        rig.failClock = true
        rig.join()
        rig.snapshot()
        rig.host.receive(rig.command("Unpause"))
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        assertTrue(rig.host.state.value.status.contains("clock", ignoreCase = true))
        assertFalse(rig.operations().contains("Ping"))
    }

    @Test fun accessPolicyDistinguishesCreateJoinAndNoneBeforeDispatch() = runTest {
        for (policy in listOf("None", "JoinGroups", "CreateAndJoinGroups")) {
            val rig = Rig(this)
            rig.policy = policy
            rig.refresh()
            rig.host.create("Evening")
            runCurrent()
            assertEquals(policy == "CreateAndJoinGroups", rig.operations().contains("New"), policy)
            rig.host.leave()
            runCurrent()
            rig.sent.clear()
            rig.host.join("g")
            runCurrent()
            assertEquals(policy != "None", rig.operations().contains("Join"), policy)
        }
    }

    @Test fun onlyTheCurrentRendererCanClearItselfAndLateResolutionsAreRejected() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.queue("server-item")
        rig.joined()
        runCurrent()
        val open = rig.opens.single()
        val replacement = Renderer()
        rig.host.bindRenderer(replacement, replacement)
        replacement.value = replacement.value.copy(playing = true)
        rig.host.clearRenderer(rig.renderer)
        rig.host.leave()
        runCurrent()
        assertFalse(rig.host.resolved(open, true))
        assertFalse(replacement.value.playing)
    }

    @Test fun externalRouteSelectionRequiresExplicitLeaveAndCancelKeepsTheGroup() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val before = rig.renderer.value
        var routes = 0
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        runCurrent()
        assertEquals(0, routes)
        assertNotNull(rig.host.pendingExternalRoute)
        assertTrue(rig.host.participating)
        assertEquals(before, rig.renderer.value)
        assertFalse(rig.operations().contains("Leave"))
        rig.host.cancelExternalRoute()
        runCurrent()
        assertEquals(null, rig.host.pendingExternalRoute)
        assertTrue(rig.host.participating)
        assertEquals(before, rig.renderer.value)
        assertEquals(0, routes)
        assertTrue(rig.sent.isEmpty())
    }

    @Test fun externalRouteStartsOnlyAfterTheExplicitLeaveHasBeenConfirmed() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.sendGate = CompletableDeferred()
        var routes = 0
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(0, routes)
        assertFalse(rig.renderer.value.playing)
        rig.sendGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, routes)
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(1, routes)
        assertEquals(listOf("Leave"), rig.operations())
    }

    @Test fun aFailedLeaveNeverStartsTheExternalRoute() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.httpStatus = 503
        var routes = 0
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(0, routes)
        assertFalse(rig.renderer.value.playing)
        assertTrue(rig.host.state.value.status.contains("not confirmed Leave"))
        assertEquals(null, rig.host.pendingExternalRoute)
    }

    @Test fun oldSocketCallbacksCannotChangeAnActiveRejoinToTheSameGroup() = runTest {
        for (kind in listOf("GroupLeft", "PlayQueue", "Command")) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            val oldGeneration = rig.host.membership.generation
            val oldSocket: (String) -> Unit = { text -> rig.host.receive(text, oldGeneration) }
            rig.host.leave()
            runCurrent()
            rig.join()
            rig.snapshot()
            assertTrue(rig.host.membership.generation > oldGeneration)
            // Each fresh authoritative queue selects its entry, including the deliberate rejoin.
            assertEquals(
                listOf(
                    SyncOpen(oldGeneration, "entry-a", "local-item", 55_000_000L),
                    SyncOpen(rig.host.membership.generation, "entry-a", "local-item", 55_000_000L)
                ),
                rig.opens
            )
            rig.opens.clear()
            val membership = rig.host.membership
            val renderer = rig.renderer.value
            val applied = rig.renderer.applied.toList()
            val message = when (kind) {
                "GroupLeft" -> rig.update("GroupLeft")

                "PlayQueue" -> rig.update(
                    "PlayQueue",
                    buildJsonObject {
                        put("Reason", "NewPlaylist")
                        put("LastUpdate", syncUtcTimestamp(rig.wall()))
                        put(
                            "Playlist",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("ItemId", "stale-item")
                                        put("PlaylistItemId", "stale-entry")
                                    }
                                )
                            )
                        )
                        put("PlayingItemIndex", 0)
                        put("StartPositionTicks", 900_000_000L)
                        put("IsPlaying", true)
                        put("RepeatMode", "RepeatAll")
                        put("ShuffleMode", "Shuffle")
                    }
                )

                else -> rig.command("Unpause")
            }
            oldSocket(message)
            runCurrent()
            assertEquals(membership, rig.host.membership, kind)
            assertEquals(renderer, rig.renderer.value, kind)
            assertEquals(applied, rig.renderer.applied, kind)
            assertTrue(rig.opens.isEmpty(), kind)
        }
    }

    @Test fun joinWaitsForTheRotatedMembershipSocketBeforePosting() = runTest {
        val rig = Rig(this)
        rig.refresh()
        val socketRequests = mutableListOf<Pair<Long, Boolean>>()
        rig.host.membershipSocket = { epoch, joining -> socketRequests += epoch to joining }
        rig.host.join("g")
        runCurrent()
        assertEquals(listOf(rig.host.membership.generation to true), socketRequests)
        assertFalse(rig.operations().contains("Join"))
        rig.host.socket(false)
        advanceTimeBy(100)
        runCurrent()
        assertFalse(rig.operations().contains("Join"))
        rig.host.socket(true)
        runCurrent()
        assertEquals(1, rig.operations().count { it == "Join" })
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun aDefiniteJoinDenialReleasesLocalMembershipInterception() = runTest {
        val rig = Rig(this)
        rig.refresh()
        rig.httpStatus = 403
        rig.host.join("g")
        runCurrent()
        assertEquals(listOf("Join"), rig.operations())
        assertFalse(rig.host.participating)
        assertFalse(rig.host.transport("play"))
        assertFalse(rig.renderer.value.playing)
        assertEquals(null, rig.host.membership.groupId)
        assertTrue(rig.host.state.value.status.contains("denied", ignoreCase = true))
    }

    @Test fun anUnknownJoinOutcomeRemainsPausedAndInterceptedUntilExplicitLeave() = runTest {
        for (status in listOf(0, 503)) {
            val rig = Rig(this)
            rig.refresh()
            rig.httpStatus = status
            rig.host.join("g")
            runCurrent()
            assertEquals(listOf("Join"), rig.operations(), "status=$status")
            assertTrue(rig.host.participating, "status=$status")
            assertEquals("Reconnecting", rig.host.membership.phase, "status=$status")
            assertFalse(rig.renderer.value.playing, "status=$status")
            assertTrue(rig.host.transport("play"), "status=$status")
            runCurrent()
            assertFalse(rig.operations().contains("Unpause"), "status=$status")
            rig.httpStatus = 204
            rig.host.leave()
            runCurrent()
            assertFalse(rig.host.participating, "status=$status")
            assertFalse(rig.host.transport("play"), "status=$status")
        }
    }

    @Test fun anAbsentRendererConsumesSeekWithoutSendingAZeroPositionToTheGroup() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.host.clearRenderer(rig.renderer)
        assertTrue(rig.host.transport("seek", 45_000))
        runCurrent()
        assertTrue(rig.host.participating)
        assertFalse(rig.operations().contains("Seek"))
        assertTrue(rig.sent.isEmpty())
    }

    @Test fun anUnknownOrNegativeDurationConsumesSeekWithoutMutatingTheGroup() = runTest {
        for (duration in listOf(0L, -1L)) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            rig.sent.clear()
            rig.renderer.value = rig.renderer.value.copy(durationTicks = duration)
            val before = rig.renderer.value
            rig.renderer.applied.clear()
            assertTrue(rig.host.transport("seek", 45_000), "duration=$duration")
            runCurrent()
            assertTrue(rig.host.participating, "duration=$duration")
            assertTrue(rig.sent.isEmpty(), "duration=$duration")
            assertEquals(before, rig.renderer.value, "duration=$duration")
            assertTrue(rig.renderer.applied.isEmpty(), "duration=$duration")
        }
    }

    @Test fun aKnownPositiveDurationBoundsTheGroupSeekWithoutMovingLocalOutputFirst() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.renderer.value = rig.renderer.value.copy(durationTicks = 123_456_789L)
        val before = rig.renderer.value
        rig.renderer.applied.clear()
        assertTrue(rig.host.transport("seek", 45_000))
        assertTrue(rig.host.transport("seek", 5_000))
        runCurrent()
        val positions = rig.sent.filter { it.second.operation == "Seek" }.map {
            it.second.body!!.getValue("PositionTicks").jsonPrimitive.long
        }
        assertEquals(listOf(123_456_789L, 50_000_000L), positions)
        assertEquals(before, rig.renderer.value)
        assertTrue(rig.renderer.applied.isEmpty())
    }

    @Test fun ignoreWaitPresentationChangesOnlyAfterItsSuccessfulHttpReply() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.sendGate = CompletableDeferred()
        rig.host.action("ignore-wait", "true")
        runCurrent()
        assertEquals(listOf("SetIgnoreWait"), rig.operations())
        assertFalse(rig.host.state.value.ignoreWait)
        rig.sendGate!!.complete(Unit)
        runCurrent()
        assertTrue(rig.host.state.value.ignoreWait)
        rig.sendGate = CompletableDeferred()
        rig.host.action("ignore-wait", "false")
        runCurrent()
        assertTrue(rig.host.state.value.ignoreWait)
        rig.sendGate!!.complete(Unit)
        runCurrent()
        assertFalse(rig.host.state.value.ignoreWait)
    }

    @Test fun deniedOrUnconfirmedIgnoreWaitKeepsTheLastConfirmedPresentation() = runTest {
        for (status in listOf(0, 403, 503)) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            rig.sent.clear()
            rig.httpStatus = status
            rig.host.action("ignore-wait", "true")
            runCurrent()
            assertFalse(rig.host.state.value.ignoreWait, "status=$status")
            rig.httpStatus = 204
            rig.host.action("ignore-wait", "true")
            runCurrent()
            assertTrue(rig.host.state.value.ignoreWait, "status=$status")
            rig.httpStatus = status
            rig.host.action("ignore-wait", "false")
            runCurrent()
            assertTrue(rig.host.state.value.ignoreWait, "status=$status")
            assertEquals(listOf("SetIgnoreWait", "SetIgnoreWait", "SetIgnoreWait"), rig.operations())
        }
    }

    @Test fun overlappingIgnoreWaitChoicesAreDispatchedInInvocationOrder() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val firstReply = CompletableDeferred<Unit>()
        val secondReply = CompletableDeferred<Unit>()
        rig.sendGate = firstReply
        rig.host.action("ignore-wait", "true")
        runCurrent()
        rig.sendGate = secondReply
        rig.host.action("ignore-wait", "false")
        runCurrent()
        assertEquals(listOf("SetIgnoreWait"), rig.operations())
        assertTrue(rig.sent.single().second.body!!.getValue("IgnoreWait").jsonPrimitive.boolean)
        assertFalse(rig.host.state.value.ignoreWait)
        firstReply.complete(Unit)
        runCurrent()
        assertEquals(listOf("SetIgnoreWait", "SetIgnoreWait"), rig.operations())
        assertFalse(rig.sent.last().second.body!!.getValue("IgnoreWait").jsonPrimitive.boolean)
        assertTrue(rig.host.state.value.ignoreWait)
        secondReply.complete(Unit)
        runCurrent()
        assertFalse(rig.host.state.value.ignoreWait)
    }

    @Test fun leavingAndRejoiningDropsQueuedIgnoreWaitFromTheOldMembership() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val oldReply = CompletableDeferred<Unit>()
        rig.sendGate = oldReply
        rig.host.action("ignore-wait", "true")
        runCurrent()
        rig.host.action("ignore-wait", "false")
        runCurrent()
        assertEquals(1, rig.operations().count { it == "SetIgnoreWait" })
        val oldGeneration = rig.host.membership.generation
        rig.sendGate = null
        rig.host.leave()
        runCurrent()
        rig.join()
        rig.snapshot()
        val rejoined = rig.host.membership
        assertTrue(rejoined.generation > oldGeneration)
        assertFalse(rig.host.state.value.ignoreWait)
        oldReply.complete(Unit)
        runCurrent()
        assertEquals(1, rig.operations().count { it == "SetIgnoreWait" })
        assertEquals(rejoined, rig.host.membership)
        assertFalse(rig.host.state.value.ignoreWait)
    }

    @Test fun aReadyNonseekableSourceStaysPausedAndBuffersOnceEvenAfterGroupUnpause() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false, seekable = false, playing = true)
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        rig.renderer.applied.clear()
        rig.host.receive(rig.command("Unpause"))
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        assertFalse(rig.renderer.applied.any { it.playing == true })
        val buffering = rig.sent.filter { it.second.operation == "Buffering" }
        assertEquals(1, buffering.size)
        assertEquals(JsonPrimitive("entry-a"), buffering.single().second.body!!["PlaylistItemId"])
        assertFalse(rig.operations().contains("Ready"))
        assertTrue(rig.host.state.value.status.contains("unavailable", ignoreCase = true))
        assertTrue(rig.host.state.value.status.contains("Leave", ignoreCase = true))
        assertTrue(rig.host.state.value.status.contains("separately", ignoreCase = true))
    }

    @Test fun aReadyNonseekableMismatchedSourceIsPausedWithAnUnavailableExplanation() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.renderer.value = rig.renderer.value.copy(
            itemId = "different-source",
            ready = true,
            buffering = false,
            seekable = false,
            playing = true
        )
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        assertTrue(rig.host.participating)
        assertFalse(rig.operations().contains("Ready"))
        assertTrue(rig.host.state.value.status.contains("unavailable", ignoreCase = true))
        assertTrue(rig.host.state.value.status.contains("Leave", ignoreCase = true))
        assertTrue(rig.host.state.value.status.contains("separately", ignoreCase = true))
    }

    @Test fun aPreparingSelectionThatBecomesNonseekableNeverReplacesTheGroupQueue() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val selected = Renderer().apply {
            value = value.copy(itemId = "selected-item", ready = false, seekable = true, playing = false)
        }
        rig.host.bindRenderer(selected, selected, SyncLocalSelection("selected-item", 200_000_000L))
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        selected.value = selected.value.copy(ready = true, seekable = false, playing = true)
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        assertEquals("local-item", rig.host.membership.queue?.current?.itemId)
        assertFalse(selected.value.playing)
    }

    @Test fun aFiniteReadySelectionRequestsOneBoundedQueueReplacementWithoutOptimisticOutput() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val selected = Renderer().apply {
            value = value.copy(
                itemId = "selected-item",
                positionTicks = 33_000_000L,
                durationTicks = 100_000_000L,
                ready = false,
                seekable = true,
                playing = false
            )
        }
        rig.host.bindRenderer(selected, selected, SyncLocalSelection("selected-item", 200_000_000L))
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        val queueBefore = rig.host.membership.queue
        selected.value = selected.value.copy(ready = true)
        val outputBefore = selected.value
        selected.applied.clear()
        rig.host.tick()
        runCurrent()
        rig.host.tick()
        runCurrent()
        val replacement = rig.sent.single { it.second.operation == "SetNewQueue" }.second.body!!
        assertEquals(JsonArray(listOf(JsonPrimitive("selected-item"))), replacement["PlayingQueue"])
        assertEquals(100_000_000L, replacement.getValue("StartPositionTicks").jsonPrimitive.long)
        assertEquals(queueBefore, rig.host.membership.queue)
        assertEquals(outputBefore, selected.value)
        assertFalse(selected.applied.any { it.playing == true || it.position != null })
    }

    @Test fun leaveAndRejoinInvalidateAPreparingLocalSelection() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val selected = Renderer().apply {
            value = value.copy(itemId = "selected-item", ready = false, playing = false)
        }
        rig.host.bindRenderer(selected, selected, SyncLocalSelection("selected-item", 50_000_000L))
        rig.host.tick()
        runCurrent()
        rig.host.leave()
        runCurrent()
        rig.join()
        rig.snapshot()
        selected.value = selected.value.copy(ready = true)
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        assertEquals("local-item", rig.host.membership.queue?.current?.itemId)
        assertFalse(selected.value.playing)
    }

    @Test fun theServerSelectingThePendingItemDiscardsTheStagedReplacement() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val selected = Renderer().apply {
            value = value.copy(itemId = "selected-item", ready = false, playing = false)
        }
        rig.host.bindRenderer(selected, selected, SyncLocalSelection("selected-item", 50_000_000L))
        rig.queue("selected-item", "server-selected-entry", 77_000_000L)
        runCurrent()
        selected.value = selected.value.copy(ready = true)
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        assertEquals("server-selected-entry", rig.host.membership.queue?.current?.playlistItemId)
        assertEquals(77_000_000L, rig.host.membership.queue?.startPositionTicks)
        assertFalse(selected.value.playing)
    }

    @Test fun disposingTheSelectionRendererCannotLeakItsStagedRequestToAReplacementRenderer() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        val preparing = Renderer().apply {
            value = value.copy(itemId = "selected-item", ready = false, playing = false)
        }
        rig.host.bindRenderer(preparing, preparing, SyncLocalSelection("selected-item", 50_000_000L))
        rig.host.clearRenderer(preparing)
        val replacement = Renderer().apply {
            value = value.copy(itemId = "selected-item", ready = true, playing = false)
        }
        rig.host.bindRenderer(replacement, replacement)
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("SetNewQueue"))
        assertEquals("local-item", rig.host.membership.queue?.current?.itemId)
        assertFalse(replacement.value.playing)
    }

    @Test fun aStagedSelectionAcknowledgementReusesItsRendererButLaterDuplicateEntriesOpenNormally() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        // The initial authoritative queue legitimately opens its entry before this local selection.
        assertEquals(
            listOf(SyncOpen(rig.host.membership.generation, "entry-a", "local-item", 55_000_000L)),
            rig.opens
        )
        rig.opens.clear()
        rig.sent.clear()
        val selected = Renderer().apply {
            value = value.copy(
                itemId = "selected-item",
                positionTicks = 33_000_000L,
                durationTicks = 100_000_000L,
                ready = false,
                seekable = true,
                playing = false
            )
        }
        rig.host.bindRenderer(selected, selected, SyncLocalSelection("selected-item", 50_000_000L))
        selected.value = selected.value.copy(ready = true)
        rig.host.tick()
        runCurrent()
        assertEquals(1, rig.operations().count { it == "SetNewQueue" })
        assertTrue(rig.opens.isEmpty())
        rig.queue("selected-item", "acknowledged-entry", 88_000_000L)
        runCurrent()
        assertTrue(rig.opens.isEmpty(), "the queue acknowledgement must keep the already prepared viewing")
        assertEquals("acknowledged-entry", rig.host.membership.queue?.current?.playlistItemId)
        assertEquals("selected-item", rig.host.membership.queue?.current?.itemId)
        assertEquals(88_000_000L, selected.value.positionTicks)
        assertFalse(selected.value.playing)
        rig.queue("selected-item", "later-duplicate-entry", 99_000_000L)
        runCurrent()
        val later = rig.opens.single()
        assertEquals("later-duplicate-entry", later.entryId)
        assertEquals("selected-item", later.itemId)
        assertEquals(99_000_000L, later.ticks)
        assertEquals(rig.host.membership.generation, later.generation)
        assertEquals("later-duplicate-entry", rig.host.membership.queue?.current?.playlistItemId)
    }
}
