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
import java.io.File
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        override var sourceIdentity: Any = Any()
        override var positionApplicationPending = false
        var value = SyncRendererState("local-item", 123_456_789L, 9_000_000_000L, true, false, true, true)
        val applied = mutableListOf<Applied>()
        var bufferOnSeek = false
        var skipCorrectionAtExecution = false
        var appliedObservation: ((Applied, SyncRendererState) -> Unit)? = null
        override fun correct(positionTicks: Long, playing: Boolean, rate: Double, sampledAtMillis: Long): Boolean =
            if (skipCorrectionAtExecution) false else apply(positionTicks, playing, rate)
        override fun snapshot() = value
        override fun apply(positionTicks: Long?, playing: Boolean?, rate: Double): Boolean {
            val positionChanged =
                !positionApplicationPending &&
                    positionTicks != null &&
                    kotlin.math.abs(value.positionTicks / 10_000 - positionTicks / 10_000) > 1
            applied += Applied(positionTicks.takeIf { positionChanged }, playing, rate)
            value =
                value.copy(
                    positionTicks = positionTicks.takeIf { positionChanged } ?: value.positionTicks,
                    playing =
                    playing ?: value.playing,
                    playWhenReady = value.playWhenReady?.let { playing ?: it },
                    native = value.native?.let { native -> native.copy(playWhenReady = playing ?: native.playWhenReady) }
                )
            if (bufferOnSeek && positionChanged) value = value.copy(ready = false, buffering = true)
            appliedObservation?.invoke(applied.last(), value)
            return positionChanged
        }
    }

    private inner class Rig(val scope: TestScope) : SyncPlayGateway {
        val sent = mutableListOf<Pair<Account, SyncRequest>>()
        val renderer = Renderer()
        val opens = mutableListOf<SyncOpen>()
        val diagnostics = mutableListOf<String>()
        val titleCalls = mutableListOf<Pair<Account, String>>()
        val titleGates = mutableListOf<CompletableDeferred<String?>>()
        override suspend fun title(account: Account, itemId: String): ClientResult<String?> {
            titleCalls += account to itemId
            val gate = titleGates.removeFirstOrNull()
            return ClientResult.Success(if (gate != null) withContext(NonCancellable) { gate.await() } else null)
        }
        var policy = "CreateAndJoinGroups"
        var httpStatus = 204
        var sendGate: CompletableDeferred<Unit>? = null
        var accessGate: CompletableDeferred<Unit>? = null
        var failAccess = false
        var failClock = false
        var clockGate: CompletableDeferred<Unit>? = null
        val epoch = 1_762_776_000_000L
        var timingOffsetMs = 0L
        fun wall() = epoch + scope.testScheduler.currentTime + timingOffsetMs
        val host = SyncPlayHost(scope.backgroundScope, this, ::wall, { scope.testScheduler.currentTime + timingOffsetMs }, diagnostics::add)

        init {
            scope.backgroundScope.launch { host.opens.collect { opens += it } }
            host.bind(account)
            host.socket(true)
            host.bindRenderer(renderer, renderer)
        }

        override suspend fun groups(account: Account) = ClientResult.Success(listOf(group()))
        override suspend fun access(account: Account): ClientResult<String> {
            accessGate?.let { withContext(NonCancellable) { it.await() } }
            return if (failAccess) {
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Access check unavailable"))
            } else {
                ClientResult.Success(policy)
            }
        }
        override suspend fun time(account: Account): ClientResult<SyncUtcTime> {
            clockGate?.await()
            return if (failClock) {
                ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Clock unavailable"))
            } else {
                ClientResult.Success(SyncUtcTime(wall(), wall()))
            }
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
        fun joined(state: String = "Paused") {
            host.receive(
                update(
                    "GroupJoined",
                    buildJsonObject {
                        put("GroupId", "g")
                        put("GroupName", "Evening")
                        put("State", state)
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
            preparedEntry()
        }
        fun preparedEntry() {
            renderer.value = renderer.value.copy(playlistItemId = host.membership.queue?.current?.playlistItemId)
        }
        fun operations() = sent.map { it.second.operation }
        fun command(command: String, delayMs: Long = 0, ticks: Long = 90_000_000L, id: String? = null, entry: String = "entry-a"): String =
            buildJsonObject {
                put("MessageType", "SyncPlayCommand")
                id?.let { put("MessageId", it) }
                put(
                    "Data",
                    buildJsonObject {
                        put("GroupId", "g")
                        put("PlaylistItemId", entry)
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

    private fun clockRecoveryBoundary(): Pair<Long, Long> {
        val roots = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { File(it, "spec/vectors/sync-clock.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-clock", suite.getValue("suite").jsonPrimitive.content)
        val cases = suite.getValue("cases").jsonArray.map { it.jsonObject }
        fun boundary(id: String, expected: Boolean): Long {
            val row = cases.single { it.getValue("id").jsonPrimitive.content == id }
            assertEquals("clock-refresh-due", row.getValue("op").jsonPrimitive.content)
            assertEquals(expected, row.getValue("expected").jsonPrimitive.boolean)
            val input = row.getValue("input").jsonObject
            assertEquals(JsonNull, input.getValue("anchor"))
            assertEquals(0L, input.getValue("lastAttemptFinishedMillis").jsonPrimitive.long)
            return input.getValue("monotonicMillis").jsonPrimitive.long
        }
        val before = boundary("clock-refresh-null-before", false)
        val due = boundary("clock-refresh-null-exact", true)
        assertEquals(30_000L, due, "Recovery cadence is the existing active30s contract")
        assertEquals(due - 1, before)
        return before to due
    }

    @Test fun titleLookupsAreBoundedAndQueuedOldAccountRequestsNeverStart() = runTest {
        val rig = Rig(this)
        val gates = List(6) { CompletableDeferred<String?>() }
        rig.titleGates += gates
        rig.join()
        rig.joined()
        rig.host.receive(
            rig.update(
                "PlayQueue",
                buildJsonObject {
                    put("Reason", "NewPlaylist")
                    put("LastUpdate", syncUtcTimestamp(rig.wall()))
                    put(
                        "Playlist",
                        JsonArray(
                            (1..6).map { index ->
                                buildJsonObject {
                                    put("ItemId", "film-$index")
                                    put("PlaylistItemId", "entry-$index")
                                }
                            }
                        )
                    )
                    put("PlayingItemIndex", 0)
                    put("StartPositionTicks", 0)
                    put("IsPlaying", false)
                    put("RepeatMode", "RepeatNone")
                    put("ShuffleMode", "Sorted")
                }
            )
        )
        runCurrent()
        assertEquals(4, rig.titleCalls.size)
        rig.host.bind(other)
        gates.forEach { it.complete("Old account title") }
        runCurrent()
        assertEquals(4, rig.titleCalls.size, "Two waiting requests must be fenced before using the old credentials")
        assertTrue(rig.host.state.value.queue.isEmpty())
    }

    @Test fun titleReplyFromRemovedQueueCannotLabelItsReplacement() = runTest {
        val rig = Rig(this)
        val old = CompletableDeferred<String?>()
        val replacement = CompletableDeferred<String?>()
        rig.titleGates += old
        rig.titleGates += replacement
        rig.join()
        rig.snapshot()
        assertEquals(null, rig.host.state.value.queue.single().title)
        rig.queue("second-item", "entry-b")
        runCurrent()
        assertEquals("second-item", rig.host.state.value.queue.single().itemId)
        old.complete("The old film")
        runCurrent()
        assertEquals(null, rig.host.state.value.queue.single().title)
        replacement.complete("The new film")
        runCurrent()
        assertEquals("The new film", rig.host.state.value.queue.single().title)
        assertEquals("entry-b", rig.host.state.value.queue.single().id)
    }

    @Test fun accountAndMembershipReplacementRejectOldTitleReplies() = runTest {
        val rig = Rig(this)
        val oldAccount = CompletableDeferred<String?>()
        rig.titleGates += oldAccount
        rig.join()
        rig.snapshot()
        rig.host.bind(other)
        oldAccount.complete("Private old title")
        runCurrent()
        assertEquals("Bea", rig.host.state.value.viewer)
        assertTrue(rig.host.state.value.queue.isEmpty())
        val oldMembership = CompletableDeferred<String?>()
        rig.titleGates += oldMembership
        rig.join()
        rig.snapshot()
        rig.host.leave()
        runCurrent()
        rig.join()
        rig.snapshot()
        oldMembership.complete("Prior membership title")
        runCurrent()
        assertEquals(null, rig.host.state.value.queue.single().title)
        assertTrue(rig.host.state.value.queue.single().titleUnavailable)
    }

    @Test fun queueTitlesPreserveDuplicatePlaylistEntriesAndUseOneLookup() = runTest {
        val rig = Rig(this)
        val title = CompletableDeferred<String?>()
        rig.titleGates += title
        rig.join()
        rig.joined()
        rig.host.receive(
            rig.update(
                "PlayQueue",
                buildJsonObject {
                    put("Reason", "NewPlaylist")
                    put("LastUpdate", syncUtcTimestamp(rig.wall()))
                    put(
                        "Playlist",
                        JsonArray(
                            listOf("one", "two").map { entry ->
                                buildJsonObject {
                                    put("ItemId", "same-film")
                                    put("PlaylistItemId", entry)
                                }
                            }
                        )
                    )
                    put("PlayingItemIndex", 1)
                    put("StartPositionTicks", 0)
                    put("IsPlaying", false)
                    put("RepeatMode", "RepeatNone")
                    put("ShuffleMode", "Sorted")
                }
            )
        )
        runCurrent()
        title.complete("A real film title")
        runCurrent()
        assertEquals(listOf("one", "two"), rig.host.state.value.queue.map { it.id })
        assertEquals(listOf("A real film title", "A real film title"), rig.host.state.value.queue.map { it.title })
        assertEquals(listOf(false, true), rig.host.state.value.queue.map { it.current })
        assertEquals(listOf(account to "same-film"), rig.titleCalls)
    }

    @Test fun clockErrorSurvivesInformationalSpeedNoticeAndTicksWithoutNumericUiStatus() = runTest {
        val rig = Rig(this)
        rig.failClock = true
        rig.join()
        rig.snapshot()
        rig.host.tick()
        assertEquals(SyncNoticeKind.CLOCK, rig.host.state.value.notice?.kind)
        assertTrue(rig.host.transport("speed"))
        repeat(8) { rig.host.tick() }
        assertEquals(SyncNoticeKind.CLOCK, rig.host.state.value.notice?.kind)
        assertEquals(SyncNoticeTone.ERROR, rig.host.state.value.notice?.tone)
        assertEquals(SyncNoticeAction.REJOIN, rig.host.state.value.notice?.action)
        assertEquals(app.pictureshow.core.sync.SyncQuality.UNKNOWN, rig.host.state.value.quality)
        assertFalse(rig.host.state.value.status.contains("drift"))
    }

    @Test fun waitingReasonIsGroupOnlyAndClearedOnRejoin() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.receive(
            rig.update(
                "StateUpdate",
                buildJsonObject {
                    put("State", "Waiting")
                    put("Reason", "Buffer")
                }
            )
        )
        assertEquals("Buffer", rig.host.state.value.waitReason)
        assertEquals(listOf("Ana", "Bea"), rig.host.state.value.participants)
        assertEquals(app.pictureshow.core.sync.SyncQuality.UNKNOWN, rig.host.state.value.quality)
        rig.host.join("g")
        assertEquals(null, rig.host.state.value.waitReason)
        assertTrue(rig.host.state.value.participants.isEmpty())
    }

    @Test fun diagnosticSnapshotsIncludeTheNoRendererGateWithoutRepeatingWithinOneSecond() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.host.clearRenderer(rig.renderer)
        rig.diagnostics.clear()
        repeat(20) { rig.host.tick() }
        assertEquals(1, rig.diagnostics.count { it.startsWith("trace-snapshot ") })
        val first = rig.diagnostics.single { it.startsWith("trace-snapshot ") }
        assertTrue(first.contains("gate=no-renderer"))
        assertTrue(first.contains("elapsedMs=0"))
        assertTrue(first.contains("renderer=false"))
        assertTrue(first.contains("generation=${rig.host.membership.generation}"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, rig.diagnostics.count { it.startsWith("trace-snapshot ") })
    }

    @Test fun diagnosticClockFailureKeepsPendingCommandAndReportsActualNativePauseFacts() = runTest {
        val rig = Rig(this)
        rig.failClock = true
        rig.renderer.value = rig.renderer.value.copy(
            native = SyncNativeRendererState(17, 3, false, 0, false, 12345, 99999, 87654)
        )
        rig.join()
        rig.snapshot()
        rig.host.tick()
        rig.host.receive(rig.command("Unpause", ticks = rig.renderer.value.positionTicks))
        runCurrent()
        assertEquals(5, rig.diagnostics.count { it.startsWith("trace-clock-sample ") && it.contains("outcome=request-failed") })
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-clock-end ") &&
                    it.contains("validSamples=0 oldAnchor=false") &&
                    it.contains("newAnchor=false")
            }
        )
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-snapshot ") &&
                    it.contains("gate=clock-unavailable") &&
                    it.contains("nativeGeneration=17") &&
                    it.contains("nativeState=3") &&
                    it.contains("nativePwr=false") &&
                    it.contains("nativePositionMs=12345") &&
                    it.contains("nativeBufferedDurationMs=87654")
            }
        )
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-schedule-pending ") &&
                    it.contains("command=Unpause") &&
                    it.contains("whenMs=${rig.wall()}") &&
                    it.contains("pending=true") &&
                    it.contains("applied=false")
            }
        )
        assertTrue(rig.diagnostics.any { it.startsWith("trace-schedule-abort ") && it.contains("reason=clock-before-wait") })
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun diagnosticRefreshReportsAnchorLossWithoutChangingTheExistingRetryPolicy() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.failClock = true
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-clock-end ") &&
                    it.contains("validSamples=0 oldAnchor=true") &&
                    it.contains("newAnchor=false")
            }
        )
        val attempts = rig.diagnostics.count { it.startsWith("trace-clock-begin ") }
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(
            attempts,
            rig.diagnostics.count {
                it.startsWith("trace-clock-begin ")
            },
            "instrumentation must not repair the null-anchor retry path"
        )
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-snapshot ") &&
                    it.contains("gate=clock-unavailable") &&
                    it.contains("anchor=false")
            }
        )
    }

    @Test fun diagnosticsLinkRequestsAuthorityBindingsAndApplicationsWithoutPrivateText() = runTest {
        val rig = Rig(this)
        rig.join()
        // Distinct private sentinel: "entry-a" also prefixes the fixed "trace-entry-ack" event name.
        val privateEntry = "private-playlist-entry-731"
        rig.joined()
        rig.queue(entry = privateEntry)
        runCurrent()
        rig.preparedEntry()
        rig.host.transport("play")
        runCurrent()
        rig.host.receive(rig.command("Pause", delayMs = 1_000, ticks = 55_000_000L, id = "secret-message", entry = privateEntry))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-receive ") &&
                    it.contains("event=queue accepted=true") &&
                    it.contains("afterEntry=")
            }
        )
        assertTrue(rig.diagnostics.any { it.startsWith("trace-request-begin ") && it.contains("operation=Unpause") })
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-request-end ") &&
                    it.contains("operation=Unpause") &&
                    it.contains("status=204 current=true")
            }
        )
        assertTrue(rig.diagnostics.any { it.startsWith("trace-apply-before ") && it.contains("source=scheduled") })
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-apply-after ") &&
                    it.contains("source=scheduled") &&
                    it.contains("applied=true")
            }
        )
        // Preserve the historical event used by measurement count filters.
        assertTrue(rig.diagnostics.any { it.startsWith("scheduled-apply uptimeMs=") })
        rig.host.clearRenderer(Any())
        rig.host.background()
        runCurrent()
        assertTrue(rig.diagnostics.any { it.startsWith("trace-clear ") && it.contains("accepted=false") })
        assertTrue(rig.diagnostics.any { it.startsWith("trace-lifecycle ") && it.contains("source=background") })
        assertTrue(rig.diagnostics.any { it.startsWith("trace-clock-reset ") && it.contains("reason=leave") })
        val text = rig.diagnostics.joinToString("\n")
        listOf("https://", "media.example.test", "token-a", "Ana", "Bea", "Evening", "local-item", privateEntry, "secret-message")
            .forEach { assertFalse(text.contains(it), "private value leaked: $it") }
    }

    @Test fun diagnosticStormsAreBoundedAndExplicitlyReportDroppedRecords() = runTest {
        val rig = Rig(this)
        rig.diagnostics.clear()
        repeat(100) { rig.host.transport("private-command-https://hidden.invalid/token") }
        assertTrue(rig.diagnostics.size <= 64)
        assertTrue(rig.diagnostics.all { it.contains("command=unknown") })
        advanceTimeBy(1_000)
        rig.host.transport("private-command-https://hidden.invalid/token")
        val last = rig.diagnostics.last()
        assertTrue(Regex("suppressed=([1-9][0-9]*)").containsMatchIn(last))
        assertFalse(rig.diagnostics.any { it.contains("hidden.invalid") || it.contains("private-command") })
    }

    @Test fun diagnosticScheduleDistinguishesRendererLossFromClockFailure() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.receive(rig.command("Pause", delayMs = 1_000))
        runCurrent()
        rig.host.clearRenderer(rig.renderer)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(rig.diagnostics.any { it.startsWith("trace-schedule-abort ") && it.contains("reason=no-renderer-after-wait") })
        assertFalse(rig.diagnostics.any { it.startsWith("trace-schedule-abort ") && it.contains("reason=clock-before-wait") })
        assertTrue(rig.diagnostics.any { it.startsWith("trace-schedule-end ") && it.contains("outcome=no-renderer-after-wait") })
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

    @Test fun futureUnpauseReplacesAnOldPausedSeekBeforeItsPlayingPhaseCanAdvanceTheWait() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.receive(rig.command("Seek", ticks = 50_000_000L))
        runCurrent()
        assertEquals(50_000_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.renderer.value.playing)
        advanceTimeBy(3_000)
        runCurrent()
        rig.renderer.applied.clear()
        rig.sent.clear()
        rig.host.receive(
            rig.update(
                "StateUpdate",
                buildJsonObject {
                    put("State", "Playing")
                    put("Reason", "Ready")
                }
            )
        )
        // StateUpdate and transport are separate socket events; the ticker can run between them.
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.position != null || it.playing == true })
        assertEquals(50_000_000L, rig.renderer.value.positionTicks)
        rig.host.receive(rig.command("Unpause", delayMs = 1_000, ticks = 50_000_000L))
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.position != null || it.playing == true })
        assertEquals(50_000_000L, rig.renderer.value.positionTicks)
        advanceTimeBy(999)
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.position != null || it.playing == true })
        assertFalse(rig.renderer.value.playing)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(rig.renderer.value.playing)
        assertEquals(50_000_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.operations().any { it in setOf("Seek", "Unpause") })
    }

    @Test fun aPendingPlayingSeekRetainsItsIntentAcrossLateReadinessAndWaitingEvenWhenAligned() = runTest {
        for ((readyAtWhen, barrier) in listOf(false to "Waiting", true to "Waiting", false to "Paused", true to "Paused")) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            rig.joined("Playing")
            rig.renderer.value = rig.renderer.value.copy(positionTicks = 50_000_000L, playing = false)
            rig.host.receive(rig.command("Seek", delayMs = 1_000, ticks = 50_000_000L))
            runCurrent()
            rig.renderer.value = rig.renderer.value.copy(ready = readyAtWhen, buffering = !readyAtWhen)
            rig.host.receive(
                rig.update(
                    "StateUpdate",
                    buildJsonObject {
                        put("State", barrier)
                        put("Reason", "Buffering")
                    }
                )
            )
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse(rig.renderer.value.playing)
            val heldApplyCount = rig.renderer.applied.size
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(heldApplyCount, rig.renderer.applied.size, "A closed $barrier barrier must not reapply pending output every tick")
            rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false)
            rig.renderer.applied.clear()
            rig.host.tick()
            runCurrent()
            assertFalse(rig.renderer.applied.any { it.playing == true }, "Waiting holds both unready and prepared pending output")
            rig.host.receive(
                rig.update(
                    "StateUpdate",
                    buildJsonObject {
                        put("State", "Playing")
                        put("Reason", "Ready")
                    }
                )
            )
            // Late target is already aligned: a drift-only retry would never apply Play.
            rig.renderer.value = rig.renderer.value.copy(positionTicks = 60_000_000L, ready = true, buffering = false)
            rig.renderer.applied.clear()
            rig.sent.clear()
            rig.host.tick()
            runCurrent()
            assertTrue(rig.renderer.value.playing, "Pending play intent survives readyAtWhen=$readyAtWhen")
            assertEquals(60_000_000L, rig.renderer.value.positionTicks)
            assertFalse(rig.renderer.applied.any { it.position != null })
            assertTrue(rig.renderer.applied.any { it.playing == true })
            assertFalse(rig.operations().any { it in setOf("Seek", "Unpause") })
            rig.host.leave()
            runCurrent()
        }
    }

    @Test fun aSeekAcceptedDuringWaitingDoesNotInventPlayAuthorityFromALaterPhaseNotification() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.receive(
            rig.update(
                "StateUpdate",
                buildJsonObject {
                    put("State", "Waiting")
                    put("Reason", "Buffering")
                }
            )
        )
        rig.host.receive(rig.command("Seek", ticks = 50_000_000L))
        runCurrent()
        assertEquals(50_000_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.renderer.value.playing)
        rig.renderer.applied.clear()
        rig.host.receive(
            rig.update(
                "StateUpdate",
                buildJsonObject {
                    put("State", "Playing")
                    put("Reason", "Ready")
                }
            )
        )
        rig.host.tick()
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.playing == true || it.position != null })
        rig.host.receive(rig.command("Unpause", delayMs = 1_000, ticks = 50_000_000L))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(rig.renderer.value.playing)
        assertEquals(50_000_000L, rig.renderer.value.positionTicks)
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

    @Test fun nearAlignedFutureUnpauseStartsOnTimeWithoutSeekingOrManufacturingBuffering() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 49_950_000L, playing = false)
        rig.renderer.bufferOnSeek = true
        rig.renderer.applied.clear()
        rig.sent.clear()
        rig.host.receive(rig.command("Unpause", delayMs = 1_000, ticks = 50_000_000L))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        assertFalse(rig.renderer.applied.any { it.position != null })
        advanceTimeBy(1)
        runCurrent()
        assertTrue(rig.renderer.value.playing)
        assertTrue(rig.renderer.value.ready)
        assertFalse(rig.renderer.value.buffering)
        assertEquals(49_950_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.renderer.applied.any { it.position != null })
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().any { it in setOf("Buffering", "Unpause", "Seek") })
    }

    @Test fun deferredNativeCompletionRearmsReadinessAndCooldownForShortAndLongBufferCycles() = runTest {
        for (longBuffer in listOf(false, true)) {
            val rig = Rig(this)
            rig.join()
            rig.joined("Playing")
            rig.queue()
            runCurrent()
            rig.preparedEntry()
            rig.host.receive(rig.command("Unpause", ticks = 50_000_000L))
            runCurrent()
            rig.host.tick()
            runCurrent()
            val before = rig.operations().count { it == "Ready" }
            rig.renderer.skipCorrectionAtExecution = true
            advanceTimeBy(2_100)
            runCurrent()
            // The actual deferred native seek finishes now, independent of the ticker.
            rig.renderer.value = rig.renderer.value.copy(positionTicks = 70_000_000L, ready = !longBuffer, buffering = longBuffer)
            rig.host.rendererPositionApplied(
                rig.renderer,
                rig.renderer,
                rig.renderer.sourceIdentity,
                rig.host.membership.generation,
                "entry-a"
            )
            rig.renderer.skipCorrectionAtExecution = false
            rig.renderer.applied.clear()
            rig.host.tick()
            runCurrent()
            if (longBuffer) {
                assertEquals("Buffering", rig.operations().last())
                rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false)
                rig.host.tick()
                runCurrent()
            }
            assertEquals(before + 1, rig.operations().count { it == "Ready" }, "completion requires a fresh Ready at the actual position")
            assertEquals(
                70_000_000L,
                rig.sent.last {
                    it.second.operation == "Ready"
                }.second.body!!.getValue("PositionTicks").jsonPrimitive.long
            )
            assertFalse(rig.renderer.applied.any { it.position != null }, "completion starts a real seek cooldown")
        }
    }

    @Test fun aDelayedCallbackCannotBorrowTheNextEntryForTheSameMediaAndDecoder() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Paused")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        val generation = rig.host.membership.generation
        val decoder = rig.renderer.sourceIdentity
        rig.host.tick()
        runCurrent()
        rig.queue(entry = "entry-b")
        runCurrent()
        rig.preparedEntry()
        // Rebind the exact same decoder after the second destination's opening fence clears.
        assertTrue(rig.host.bindRenderer(rig.renderer, rig.renderer, opening = SyncOpen(generation, "entry-b", "local-item", 0)))
        rig.host.tick()
        runCurrent()
        val before = rig.operations().count { it == "Ready" }
        rig.host.rendererPositionApplied(rig.renderer, rig.renderer, decoder, generation, "entry-a")
        rig.host.rendererPositionApplied(rig.renderer, rig.renderer, decoder, generation - 1, "entry-b")
        rig.host.tick()
        runCurrent()
        assertEquals(before, rig.operations().count { it == "Ready" })
        rig.host.rendererPositionApplied(rig.renderer, rig.renderer, decoder, generation, "entry-b")
        rig.host.tick()
        runCurrent()
        assertEquals(before + 1, rig.operations().count { it == "Ready" })
    }

    @Test fun aRetiredDecoderCannotRearmCurrentRendererReadiness() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.host.tick()
        runCurrent()
        val before = rig.operations().count { it == "Ready" }
        val staleSource = rig.renderer.sourceIdentity
        rig.renderer.sourceIdentity = Any()
        rig.host.rendererPositionApplied(rig.renderer, rig.renderer, staleSource, rig.host.membership.generation, "entry-a")
        rig.host.rendererPositionApplied(Any(), rig.renderer, rig.renderer.sourceIdentity, rig.host.membership.generation, "entry-a")
        rig.host.rendererPositionApplied(rig.renderer, Renderer(), rig.renderer.sourceIdentity, rig.host.membership.generation, "entry-a")
        rig.host.tick()
        runCurrent()
        assertEquals(before, rig.operations().count { it == "Ready" })
    }

    @Test fun aSkippedCorrectionExecutionDoesNotMoveTheLastActualSeekDeadline() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L))
        runCurrent()
        rig.renderer.applied.clear()
        rig.renderer.skipCorrectionAtExecution = true
        advanceTimeBy(2_000)
        runCurrent()
        rig.host.tick()
        assertFalse(rig.renderer.applied.any { it.position != null })
        // A fresh execution sample skipped the requested correction. A later
        // genuine discrepancy can seek now; no imaginary seek starts a new2s wait.
        rig.renderer.skipCorrectionAtExecution = false
        rig.host.tick()
        assertTrue(rig.renderer.applied.any { it.position != null })
    }

    @Test fun aPreparedFutureUnpauseResumesWithoutADeadlineSeekWhenItsTimerWakesLate() = runTest {
        for (lateMillis in listOf(211L, 360L)) {
            val rig = Rig(this)
            rig.join()
            rig.joined("Paused")
            rig.queue(ticks = 1_953_957_848L)
            runCurrent()
            rig.preparedEntry()
            rig.renderer.value = rig.renderer.value.copy(
                positionTicks = 1_953_950_000L,
                ready = true,
                buffering = false,
                playing = false,
                playWhenReady = false,
                rateSupported = true
            )
            rig.renderer.bufferOnSeek = true
            var firstResume: Pair<Applied, SyncRendererState>? = null
            rig.renderer.appliedObservation = { application, state ->
                if (application.playing == true && firstResume == null) firstResume = application to state
            }
            rig.host.receive(rig.command("Unpause", delayMs = 500, ticks = 1_953_957_848L))
            runCurrent()
            assertFalse(rig.renderer.value.unpaused, "Future intent cannot start before When")
            rig.timingOffsetMs = lateMillis
            advanceTimeBy(500)
            runCurrent()
            val (application, state) = assertNotNull(firstResume)
            assertEquals(null, application.position, "Timer lateness must not replace prepared resume with a seek: $lateMillis")
            assertTrue(state.ready && !state.buffering && state.unpaused, "The scheduled resume must keep its prepared output ready")
        }
    }

    @Test fun lateOnReceiptAndMisalignedFutureUnpauseStillApplyTheirPositions() = runTest {
        for (alreadyLate in listOf(true, false)) {
            val rig = Rig(this)
            rig.join()
            rig.joined("Paused")
            rig.queue(ticks = 1_953_957_848L)
            runCurrent()
            rig.preparedEntry()
            rig.renderer.value = rig.renderer.value.copy(
                positionTicks = if (alreadyLate) 1_953_950_000L else 1_853_950_000L,
                ready = true,
                buffering = false,
                playing = false,
                playWhenReady = false
            )
            var firstResume: Applied? = null
            rig.renderer.appliedObservation = { application, _ ->
                if (application.playing == true && firstResume == null) firstResume = application
            }
            rig.host.receive(rig.command("Unpause", delayMs = if (alreadyLate) -211 else 500, ticks = 1_953_957_848L))
            runCurrent()
            if (!alreadyLate) {
                rig.timingOffsetMs = 211
                advanceTimeBy(500)
                runCurrent()
            }
            assertNotNull(assertNotNull(firstResume).position, "Only a future already-prepared source may omit positioning")
        }
    }

    @Test fun aReplacedNativeSourceCannotBorrowAFutureUnpausePreparation() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Paused")
        rig.queue(ticks = 1_953_957_848L)
        runCurrent()
        rig.preparedEntry()
        rig.renderer.value = rig.renderer.value.copy(
            positionTicks = 1_953_950_000L,
            ready = true,
            buffering = false,
            playing = false,
            playWhenReady = false
        )
        var firstResume: Applied? = null
        rig.renderer.appliedObservation = { application, _ ->
            if (application.playing == true && firstResume == null) firstResume = application
        }
        rig.host.receive(rig.command("Unpause", delayMs = 500, ticks = 1_953_957_848L))
        runCurrent()
        rig.renderer.sourceIdentity = Any()
        rig.timingOffsetMs = 211
        advanceTimeBy(500)
        runCurrent()
        assertNotNull(assertNotNull(firstResume).position, "A decoder replacement discards the prepared-position claim")
    }

    @Test fun aFreshEmissionOfAnAppliedUnpauseResumesWithoutRepeatingItsPositionalSeek() = runTest {
        for (waiting in listOf(false, true)) {
            val rig = Rig(this)
            rig.join()
            rig.joined("Playing")
            rig.queue()
            runCurrent()
            rig.preparedEntry()
            rig.host.receive(rig.command("Unpause", ticks = 50_000_000L, id = "initial"))
            runCurrent()
            assertTrue(rig.renderer.value.playing)
            rig.renderer.applied.clear()
            rig.renderer.bufferOnSeek = true
            advanceTimeBy(100)
            if (waiting) {
                rig.host.receive(
                    rig.update(
                        "StateUpdate",
                        buildJsonObject {
                            put("State", "Waiting")
                            put("Reason", "Buffering")
                        }
                    )
                )
                rig.renderer.value = rig.renderer.value.copy(playing = false)
            }
            // Ready can elicit a fresh emission of the exact same timeline. Its new
            // MessageId/EmittedAt do not turn it into another explicit position change.
            rig.host.receive(rig.command("Unpause", delayMs = -100, ticks = 50_000_000L, id = "ready-response"))
            if (waiting) {
                // Unpause itself announces Playing. The subsequent StateUpdate can
                // arrive before its coroutine runs and hold that scheduled mode.
                rig.host.receive(
                    rig.update(
                        "StateUpdate",
                        buildJsonObject {
                            put("State", "Waiting")
                            put("Reason", "Buffering")
                        }
                    )
                )
            }
            runCurrent()
            if (waiting) {
                assertFalse(rig.renderer.value.playing)
                rig.host.receive(
                    rig.update(
                        "StateUpdate",
                        buildJsonObject {
                            put("State", "Playing")
                            put("Reason", "Ready")
                        }
                    )
                )
                rig.host.tick()
                runCurrent()
            }
            assertFalse(rig.renderer.applied.any { it.position != null }, "an applied Unpause echo must not bypass the seek cooldown")
            assertTrue(rig.renderer.value.ready, "an equivalent timeline must not manufacture BUFFERING")
            assertTrue(rig.renderer.value.playing, "a repeated timeline still restores its permitted playback mode")
            assertFalse(rig.operations().any { it in setOf("Seek", "Unpause") })
        }
    }

    @Test fun anEquivalentUnpausePreservesTheRateAndOriginalCorrectionDeadline() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L, id = "initial-rate-timeline"))
        runCurrent()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 49_000_000L, rateSupported = true)
        rig.host.tick()
        runCurrent()
        assertEquals(1.03, rig.renderer.applied.last().rate)
        advanceTimeBy(100)
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 50_000_000L)
        rig.renderer.applied.clear()
        rig.host.receive(rig.command("Unpause", delayMs = -100, ticks = 50_000_000L, id = "ready-rate-echo"))
        runCurrent()
        assertTrue(rig.renderer.applied.isNotEmpty())
        assertTrue(
            rig.renderer.applied.all { it.position == null && it.playing == true && it.rate == 1.03 },
            "An equivalent applied timeline must preserve the active correction without a seek"
        )
        // Move both clocks together without running unrelated periodic ticks. The original
        // 5-second correction window has expired; the response cannot grant another window.
        // 100 ms have already elapsed. At exactly the original deadline a wrongly
        // restarted window would still have 100 ms left; keep output 100 ms behind.
        rig.timingOffsetMs = 4_900
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 99_000_000L)
        rig.renderer.applied.clear()
        rig.host.tick()
        runCurrent()
        assertTrue(
            rig.renderer.applied.any { it.position != null && it.rate == 1.0 },
            "The original correction deadline must still fall back to a bounded seek"
        )
    }

    @Test fun anUnpauseEchoMustStillPositionAnUnappliedOrReplacedRenderer() = runTest {
        for (replacement in listOf("aborted", "bridge", "decoder", "pending-position")) {
            val aborted = replacement == "aborted"
            val rig = Rig(this)
            rig.join()
            rig.joined("Playing")
            rig.queue()
            runCurrent()
            rig.preparedEntry()
            rig.renderer.value = rig.renderer.value.copy(ready = !aborted)
            rig.renderer.positionApplicationPending = replacement == "pending-position"
            rig.host.receive(rig.command("Unpause", ticks = 50_000_000L, id = "initial"))
            runCurrent()
            advanceTimeBy(100)
            val receiving = if (replacement != "bridge") {
                rig.renderer
            } else {
                Renderer().also {
                    it.value = rig.renderer.value.copy(positionTicks = 49_000_000L, playing = false)
                    rig.host.bindRenderer(it, it)
                }
            }
            if (replacement == "decoder") receiving.sourceIdentity = Any()
            receiving.value = receiving.value.copy(ready = true, buffering = false)
            receiving.positionApplicationPending = false
            receiving.applied.clear()
            rig.host.receive(rig.command("Unpause", delayMs = -100, ticks = 50_000_000L, id = "new-emission"))
            runCurrent()
            assertTrue(
                receiving.applied.any {
                    it.position != null
                },
                "no completed application may be borrowed from an aborted or replaced renderer"
            )
            assertTrue(receiving.value.playing)
        }
    }

    @Test fun equivalentEmissionsWhileAPositionIsDeferredCannotEstablishAnAppliedMarker() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.renderer.positionApplicationPending = true
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L, id = "initial"))
        runCurrent()
        advanceTimeBy(100)
        rig.host.receive(rig.command("Unpause", delayMs = -100, ticks = 50_000_000L, id = "still-deferred"))
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.position != null }, "the native seek command remains unavailable")
        rig.renderer.positionApplicationPending = false
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 50_000_000L)
        advanceTimeBy(100)
        rig.host.receive(rig.command("Unpause", delayMs = -200, ticks = 50_000_000L, id = "available"))
        runCurrent()
        assertTrue(
            rig.renderer.applied.any {
                it.position == 52_000_000L
            },
            "deferred echoes cannot suppress the first actual timed alignment"
        )
    }

    @Test fun aChangedUnpauseTimelineAndExplicitPauseInvalidateTheAppliedMarker() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L))
        runCurrent()
        advanceTimeBy(100)
        rig.host.receive(rig.command("Unpause", ticks = 70_000_000L, id = "changed-timeline"))
        runCurrent()
        assertEquals(70_000_000L, rig.renderer.value.positionTicks)
        rig.host.receive(rig.command("Pause", ticks = 70_020_000L))
        runCurrent()
        assertFalse(rig.renderer.value.playing)
        assertEquals(70_020_000L, rig.renderer.value.positionTicks)
        rig.renderer.applied.clear()
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L, id = "new-after-pause"))
        runCurrent()
        assertTrue(rig.renderer.applied.any { it.position == 50_000_000L }, "an explicit paused timeline supersedes the earlier Unpause")
    }

    @Test fun smallExplicitPauseAndSeekStillApplyTheirAuthoritativePositions() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 50_000_000L)
        rig.renderer.applied.clear()
        rig.host.receive(rig.command("Pause", ticks = 50_020_000L))
        runCurrent()
        assertEquals(50_020_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.renderer.value.playing)
        rig.host.receive(rig.command("Seek", ticks = 50_040_000L))
        runCurrent()
        assertEquals(50_040_000L, rig.renderer.value.positionTicks)
        assertFalse(rig.renderer.value.playing)
        assertEquals(listOf(50_020_000L, 50_040_000L), rig.renderer.applied.mapNotNull { it.position })
        assertFalse(rig.operations().any { it in setOf("Pause", "Seek") })
    }

    @Test fun scheduledPositionChangesKeepTheCorrectionCooldownAcrossBufferReadyUnpause() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L))
        runCurrent()
        rig.renderer.applied.clear()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 40_000_000L, rateSupported = false)
        rig.host.tick()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.position != null }, "A scheduled seek must retain the two-second correction cooldown")
        advanceTimeBy(1)
        rig.host.tick()
        assertTrue(rig.renderer.applied.any { it.position != null }, "Correction resumes at the exact cooldown boundary")

        // A real buffering/Ready cycle may schedule another Unpause for the same entry.
        advanceTimeBy(100)
        rig.renderer.value = rig.renderer.value.copy(buffering = true, ready = false)
        rig.host.tick()
        rig.renderer.value = rig.renderer.value.copy(buffering = false, ready = true)
        rig.host.tick()
        rig.host.receive(rig.command("Unpause", ticks = 80_000_000L))
        runCurrent()
        rig.renderer.applied.clear()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 70_000_000L)
        rig.host.tick()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(
            rig.renderer.applied.any {
                it.position != null
            },
            "A Ready-triggered Unpause must not re-arm immediate correction seeks"
        )
        advanceTimeBy(1)
        rig.host.tick()
        assertTrue(rig.renderer.applied.any { it.position != null })
        assertFalse(rig.operations().contains("Seek"), "Applied corrections never echo group transport requests")
    }

    @Test fun anAlignedFutureUnpausePreservesTheActualSeekDeadlineInsteadOfExtendingOrErasingIt() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.host.receive(rig.command("Unpause", ticks = 50_000_000L))
        runCurrent()
        advanceTimeBy(1_000)
        rig.host.receive(rig.command("Unpause", delayMs = 500, ticks = 65_000_000L))
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 65_000_000L, rateSupported = false)
        advanceTimeBy(500)
        runCurrent()
        rig.renderer.applied.clear()
        rig.renderer.value = rig.renderer.value.copy(positionTicks = 55_000_000L)
        rig.host.tick()
        advanceTimeBy(499)
        runCurrent()
        assertFalse(rig.renderer.applied.any { it.position != null }, "No-op Unpause preserves the existing cooldown")
        advanceTimeBy(1)
        rig.host.tick()
        assertTrue(rig.renderer.applied.any { it.position != null }, "No-op Unpause must not extend the actual seek deadline")
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

    @Test fun anAppliedSeekRefreshesReadinessEvenWhenItsBufferCycleFallsBetweenTicks() = runTest {
        for (command in listOf("Pause", "Seek")) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            rig.host.tick()
            runCurrent()
            assertEquals(1, rig.operations().count { it == "Ready" })
            rig.host.receive(
                rig.update(
                    "StateUpdate",
                    buildJsonObject {
                        put("State", "Waiting")
                        put("Reason", "Buffering")
                    }
                )
            )
            rig.host.receive(rig.command(command, delayMs = 100, ticks = 2_490_590_000L))
            runCurrent()
            rig.host.tick()
            runCurrent()
            assertEquals(1, rig.operations().count { it == "Ready" }, "a future seek cannot acknowledge the old position")
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2_490_590_000L, rig.renderer.value.positionTicks)
            // Native BUFFERING -> READY can complete in 86 ms, before the next 250 ms tick.
            // The fake remains READY throughout, making the missing sampled transition explicit.
            assertTrue(rig.renderer.value.ready)
            rig.host.tick()
            runCurrent()
            val ready = rig.sent.filter { it.second.operation == "Ready" }
            assertEquals(2, ready.size, "$command must refresh readiness after an actual positional application")
            val body = requireNotNull(ready.last().second.body)
            assertEquals(2_490_590_000L, body.getValue("PositionTicks").jsonPrimitive.long)
            assertFalse(body.getValue("IsPlaying").jsonPrimitive.boolean)
            assertEquals(JsonPrimitive("entry-a"), body["PlaylistItemId"])
            rig.host.tick()
            runCurrent()
            assertEquals(2, rig.operations().count { it == "Ready" }, "one acknowledgement, not a Ready loop")
        }
    }

    @Test fun aPositionApplicationStillBufferingReportsBufferingThenOneFreshReady() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.tick()
        runCurrent()
        rig.renderer.bufferOnSeek = true
        rig.host.receive(rig.command("Seek", ticks = 2_490_590_000L))
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertEquals(1, rig.operations().count { it == "Buffering" })
        assertEquals(1, rig.operations().count { it == "Ready" })
        rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false)
        rig.host.tick()
        runCurrent()
        assertEquals(2, rig.operations().count { it == "Ready" })
        rig.host.tick()
        runCurrent()
        assertEquals(2, rig.operations().count { it == "Ready" })
    }

    @Test fun anAlignedTransportWhichDoesNotSeekDoesNotRepeatPreparedReadiness() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.tick()
        runCurrent()
        val position = rig.renderer.value.positionTicks
        rig.host.receive(rig.command("Seek", ticks = position))
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertEquals(1, rig.operations().count { it == "Ready" })
        assertTrue(rig.renderer.applied.none { it.position != null })
    }

    @Test fun bufferingReportsUnpausedIntentInsteadOfMistakingNoFramesForPause() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.renderer.value = rig.renderer.value.copy(
            positionTicks = 144_100_000L,
            ready = false,
            buffering = true,
            playing = false,
            native = SyncNativeRendererState(4, 2, true, 0, false, 14_410, 98_416, 84_006)
        )
        rig.host.tick()
        runCurrent()
        val buffer = rig.sent.single { it.second.operation == "Buffering" }.second.body!!
        assertEquals(JsonPrimitive(true), buffer["IsPlaying"], "Buffering does not cancel native play intent")
        assertEquals(JsonPrimitive(144_100_000L), buffer["PositionTicks"])
        assertEquals(JsonPrimitive("entry-a"), buffer["PlaylistItemId"])
        assertEquals(JsonPrimitive(syncUtcTimestamp(rig.wall())), buffer["When"])
        assertFalse(rig.renderer.value.playing)
        assertFalse(rig.renderer.value.unpaused, "the host wait actually pauses native intent")
        // The client has actually paused for the barrier. Its next Ready reports that observed intent,
        // rather than borrowing the earlier play intent or a not-yet-applied server Unpause.
        rig.renderer.value = rig.renderer.value.copy(
            ready = true,
            buffering = false
        )
        rig.host.tick()
        runCurrent()
        val ready = rig.sent.single { it.second.operation == "Ready" }.second.body!!
        assertEquals(JsonPrimitive(false), ready["IsPlaying"])
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

    @Test fun aFirstQueueInAnIdleGroupReportsPreparedReadinessButStopDoesNotRestartIt() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Idle")
        rig.queue()
        runCurrent()
        rig.preparedEntry()
        rig.sent.clear()
        rig.host.tick()
        runCurrent()
        val ready = rig.sent.single { it.second.operation == "Ready" }.second.body!!
        assertEquals(JsonPrimitive("entry-a"), ready["PlaylistItemId"])
        assertFalse(rig.renderer.value.playing, "readiness must await the server's playback command")

        rig.host.receive(rig.command("Stop"))
        runCurrent()
        rig.sent.clear()
        rig.renderer.value = rig.renderer.value.copy(ready = false, buffering = true)
        rig.host.tick()
        runCurrent()
        rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false)
        rig.host.tick()
        runCurrent()
        assertTrue(rig.sent.isEmpty(), "a stopped entry must not re-enter the ready barrier")
        assertFalse(rig.renderer.value.playing)

        rig.queue(entry = "entry-b")
        rig.preparedEntry()
        rig.host.tick()
        runCurrent()
        val replacement = rig.sent.single { it.second.operation == "Ready" }.second.body!!
        assertEquals(JsonPrimitive("entry-b"), replacement["PlaylistItemId"])
    }

    @Test fun aStopOlderThanTheNewQueueCannotSuppressItsInitialReady() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.joined("Idle")
        val oldStop = rig.command("Stop")
        advanceTimeBy(1)
        rig.queue()
        rig.preparedEntry()
        runCurrent()
        rig.sent.clear()
        rig.host.receive(oldStop)
        rig.host.tick()
        runCurrent()
        assertEquals("Idle", rig.host.membership.phase)
        assertEquals(1, rig.operations().count { it == "Ready" })
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun aRepeatedMediaIdCannotReadyOrPlayItsNewEntryUntilTheReplacementRendererBinds() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.tick()
        runCurrent()
        rig.sent.clear()
        rig.renderer.applied.clear()
        rig.queue(entry = "entry-b", ticks = 77_000_000L)
        runCurrent()
        val open = rig.opens.last()
        assertEquals("entry-b", open.entryId)
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("Ready"), "the old same-media decoder still belongs to entry-a")
        rig.host.receive(rig.command("Unpause", ticks = 77_000_000L, entry = "entry-b"))
        runCurrent()
        assertFalse(rig.renderer.value.playing, "a scheduled command cannot start the old entry")
        assertTrue(rig.host.resolved(open, true), "catalogue lookup alone is not renderer preparation")
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("Ready"))
        val replacement = Renderer().apply {
            value = value.copy(positionTicks = 77_000_000L, ready = false, buffering = true, playing = false)
        }
        assertTrue(rig.host.bindRenderer(replacement, replacement, opening = open))
        rig.host.tick()
        runCurrent()
        assertEquals(1, rig.operations().count { it == "Buffering" })
        assertFalse(replacement.value.playing)
        replacement.value = replacement.value.copy(ready = true, buffering = false)
        rig.host.tick()
        runCurrent()
        val ready = rig.sent.single { it.second.operation == "Ready" }.second.body!!
        assertEquals(JsonPrimitive("entry-b"), ready["PlaylistItemId"])
        assertEquals(77_000_000L, ready.getValue("PositionTicks").jsonPrimitive.long)
        assertTrue(replacement.value.playing)
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun aStaleSameMediaReplacementCannotAcknowledgeOrReplaceTheLatestOpen() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.queue(entry = "entry-b", ticks = 66_000_000L)
        runCurrent()
        val earlier = rig.opens.last()
        assertTrue(rig.host.resolved(earlier, true))
        rig.queue(entry = "entry-c", ticks = 77_000_000L)
        runCurrent()
        val latest = rig.opens.last()
        val staleRenderer = Renderer().apply { value = value.copy(positionTicks = earlier.ticks) }
        assertFalse(rig.host.bindRenderer(staleRenderer, staleRenderer, opening = earlier))
        assertFalse(staleRenderer.value.playing)
        rig.sent.clear()
        rig.host.tick()
        runCurrent()
        assertFalse(rig.operations().contains("Ready"))
        val replacement = Renderer().apply { value = value.copy(positionTicks = latest.ticks, playing = false) }
        assertFalse(rig.host.bindRenderer(replacement, replacement, opening = latest.copy(generation = latest.generation - 1)))
        assertTrue(rig.host.bindRenderer(replacement, replacement, opening = latest))
        rig.host.tick()
        runCurrent()
        assertEquals(1, rig.operations().count { it == "Ready" })
        assertFalse(
            rig.host.bindRenderer(staleRenderer, staleRenderer, opening = earlier),
            "a stale bind stays invalid after acknowledgement"
        )
        rig.host.receive(rig.command("Unpause", ticks = latest.ticks, entry = "entry-c"))
        runCurrent()
        assertTrue(replacement.value.playing)
        assertEquals(latest.ticks, replacement.value.positionTicks)
        assertFalse(staleRenderer.value.playing)
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
        assertFalse(rig.host.state.value.policyKnown)
        assertFalse(rig.host.state.value.accessAllowed)
        assertTrue(rig.host.state.value.canRefresh)
        assertFalse(rig.host.state.value.accessNotice.orEmpty().contains("not allowed"))
        assertTrue(rig.host.state.value.groups.isEmpty())
    }

    @Test fun checkingAccessIsUnknownRatherThanDeniedAndCannotDispatch() = runTest {
        val rig = Rig(this)
        assertFalse(rig.host.state.value.policyKnown)
        assertTrue(rig.host.state.value.canRefresh)
        rig.accessGate = CompletableDeferred()
        rig.refresh()
        val checking = rig.host.state.value
        assertTrue(checking.busy)
        assertFalse(checking.policyKnown)
        assertFalse(checking.accessAllowed)
        assertFalse(checking.canRefresh)
        assertEquals("Checking watch-together access…", checking.accessNotice)
        rig.host.create("Evening")
        rig.host.join("g")
        runCurrent()
        assertTrue(rig.sent.isEmpty())
        assertFalse(rig.host.state.value.status.contains("not allowed"))
        rig.accessGate!!.complete(Unit)
        runCurrent()
        assertTrue(rig.host.state.value.policyKnown)
        assertTrue(rig.host.state.value.accessAllowed)
        assertTrue(rig.host.state.value.canRefresh)
        assertEquals(null, rig.host.state.value.accessNotice)
        assertEquals("", rig.host.state.value.status)
        assertEquals(listOf("g"), rig.host.state.value.groups.map { it.id })
    }

    @Test fun failedAccessClearsOldGroupsAndPermissionWithoutInventingADenial() = runTest {
        val rig = Rig(this)
        rig.refresh()
        assertTrue(rig.host.state.value.accessAllowed)
        assertTrue(rig.host.state.value.groups.isNotEmpty())
        rig.failAccess = true
        rig.refresh()
        val failed = rig.host.state.value
        assertFalse(failed.policyKnown)
        assertFalse(failed.accessAllowed)
        assertTrue(failed.canRefresh)
        assertTrue(failed.groups.isEmpty())
        assertEquals("Access check unavailable", failed.status)
        assertFalse(failed.accessNotice.orEmpty().contains("not allowed"))
        rig.host.create("Evening")
        rig.host.join("g")
        runCurrent()
        assertTrue(rig.sent.isEmpty())
        rig.failAccess = false
        rig.refresh()
        assertTrue(rig.host.state.value.policyKnown)
        assertTrue(rig.host.state.value.accessAllowed)
        assertEquals("", rig.host.state.value.status)
        assertEquals(listOf("g"), rig.host.state.value.groups.map { it.id })
    }

    @Test fun confirmedDeniedAccessCanRefreshAfterTheAdministratorChangesIt() = runTest {
        val rig = Rig(this)
        rig.policy = "None"
        rig.refresh()
        val denied = rig.host.state.value
        assertTrue(denied.policyKnown)
        assertFalse(denied.accessAllowed)
        assertTrue(denied.canRefresh)
        assertTrue(denied.accessNotice.orEmpty().contains("not allowed"))
        assertTrue(denied.groups.isEmpty())
        rig.host.create("Evening")
        rig.host.join("g")
        runCurrent()
        assertTrue(rig.sent.isEmpty())
        rig.policy = "CreateAndJoinGroups"
        rig.refresh()
        assertTrue(rig.host.state.value.policyKnown)
        assertTrue(rig.host.state.value.accessAllowed)
        assertEquals(null, rig.host.state.value.accessNotice)
        rig.host.create("Evening")
        runCurrent()
        assertTrue(rig.operations().contains("New"))
    }

    @Test fun switchingAccountsDiscardsConfirmedAccessBeforeTheNewViewerCheck() = runTest {
        val rig = Rig(this)
        rig.refresh()
        assertTrue(rig.host.state.value.accessAllowed)
        rig.host.bind(other)
        assertEquals("Bea", rig.host.state.value.viewer)
        assertEquals("None", rig.host.state.value.policy)
        assertFalse(rig.host.state.value.policyKnown)
        assertTrue(rig.host.state.value.groups.isEmpty())
        assertTrue(rig.host.state.value.canRefresh)
        rig.accessGate = CompletableDeferred()
        rig.policy = "None"
        rig.refresh()
        rig.host.join("g")
        rig.host.create("Evening")
        runCurrent()
        assertTrue(rig.sent.isEmpty())
        assertFalse(rig.host.state.value.accessNotice.orEmpty().contains("not allowed"))
        rig.accessGate!!.complete(Unit)
        runCurrent()
        assertTrue(rig.host.state.value.policyKnown)
        assertFalse(rig.host.state.value.accessAllowed)
        assertTrue(rig.host.state.value.canRefresh)
        assertTrue(rig.host.state.value.accessNotice.orEmpty().contains("not allowed"))
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

    @Test fun earlyMembershipIntentUsesTheBoundedCurrentSocketWait() = runTest {
        val roots = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val vectors = roots.map { java.io.File(it, "spec/vectors/sync-membership-registration.json") }.first { it.isFile }
        val cases = kotlinx.serialization.json.Json.parseToJsonElement(vectors.readText()).jsonObject.getValue("cases").jsonArray
        for (element in cases) {
            val case = element.jsonObject
            val creating = case.getValue("intent").jsonPrimitive.content == "create"
            val rig = Rig(this)
            rig.refresh()
            if (case["priorMembership"]?.jsonPrimitive?.boolean == true) {
                rig.httpStatus = case["priorHttp"]?.jsonPrimitive?.int ?: 204
                rig.join()
                if (rig.httpStatus == 204) rig.snapshot()
                rig.httpStatus = 204
                if (case["priorLeft"]?.jsonPrimitive?.boolean == true) {
                    rig.host.receive(rig.update("GroupLeft"))
                    runCurrent()
                }
                rig.sent.clear()
            }
            rig.host.socket(false)
            val rotations = mutableListOf<Pair<Long, Boolean>>()
            rig.host.membershipSocket = { epoch, joining -> rotations += epoch to joining }
            if (creating) rig.host.create("Evening") else rig.host.join("g")
            runCurrent()
            assertEquals("Joining", rig.host.membership.phase, case.getValue("id").jsonPrimitive.content)
            assertEquals(listOf(rig.host.membership.generation to true), rotations)
            assertTrue(rig.operations().isEmpty())
            advanceTimeBy(9_999)
            runCurrent()
            assertTrue(rig.operations().isEmpty())
            var routes = 0
            when (case.getValue("terminal").jsonPrimitive.content) {
                "ready" -> rig.host.socket(true)
                "leave" -> {
                    rig.host.leave()
                    runCurrent()
                    rig.host.socket(true)
                    advanceTimeBy(10_001)
                }
                "route" -> {
                    assertTrue(rig.host.confirmExternalRoute { routes++ })
                    rig.host.leaveForExternalRoute()
                    runCurrent()
                    rig.host.socket(true)
                    advanceTimeBy(10_001)
                }
                "timeout" -> advanceTimeBy(2)
                else -> error("Unknown registration terminal")
            }
            runCurrent()
            val expected = case.getValue("posted").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, rig.operations(), "No server membership mutation for an unsent cancelled intent")
            assertEquals(if (case.getValue("terminal").jsonPrimitive.content == "route") 1 else 0, routes)
            assertEquals(case.getValue("phase").jsonPrimitive.content, rig.host.membership.phase)
            assertFalse(rig.renderer.value.playing)
            rig.host.leave()
            runCurrent()
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

    @Test fun snapshotLossAndBindingReinstallationCannotReplaceTheServerQueueFromLocalResume() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.host.receive(rig.command("Pause", id = "accepted"))
        runCurrent()
        // A different older command must still fail closed and invalidate the queue.
        val old = rig.command("Seek", id = "older").replace(
            "\"EmittedAt\":\"${syncUtcTimestamp(rig.wall())}\"",
            "\"EmittedAt\":\"${syncUtcTimestamp(rig.wall() - 1)}\""
        )
        rig.host.receive(old)
        assertEquals("Reconnecting", rig.host.membership.phase)
        assertFalse(rig.host.membership.hasSnapshot)
        rig.host.clearRenderer(rig.renderer)
        rig.host.bindRenderer(rig.renderer, rig.renderer, SyncLocalSelection("local-item", 0))
        rig.host.tick()
        runCurrent()
        assertFalse("SetNewQueue" in rig.operations(), "recovery must never reseed from the existing decoder")
        rig.host.receive(
            rig.update(
                "StateUpdate",
                buildJsonObject {
                    put("State", "Waiting")
                    put("Reason", "Buffering")
                }
            )
        )
        assertEquals("Joining", rig.host.membership.phase)
        rig.host.clearRenderer(rig.renderer)
        rig.host.bindRenderer(rig.renderer, rig.renderer, SyncLocalSelection("local-item", 0))
        rig.host.tick()
        runCurrent()
        assertFalse("SetNewQueue" in rig.operations(), "a later state event does not restore queue authority")
    }
// PRIVATE APPEND-ONLY FRAGMENT: insert INSIDE existing SyncPlayHostTest before its final brace.
// No fixture/model replacement. Uses existing private Rig, Renderer, real host and wire parser.
// Requires the explicitly documented production API scaffold; see adapter-contract.md.

    @Test fun departureStopSurvivesHiddenDelayRefreshAndChooserOpen() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        val accountScope = rig.host.departureState.scope
        rig.host.leave(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED)
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        assertEquals("Evening", fact.groupName)
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED, fact.cause)
        assertFalse(rig.host.panelOpen)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(fact, rig.host.state.value.departure)
        rig.refresh() // actual successful access/groups while hidden, not a fake Refresh event
        assertEquals(fact, rig.host.state.value.departure)
        rig.host.show()
        runCurrent()
        rig.host.bind(account.copy())
        runCurrent() // same account+token is not a scope reset
        assertEquals(fact, rig.host.state.value.departure)
        val chooser = rig.host.chooserSession
        rig.host.refresh()
        runCurrent()
        rig.host.show()
        runCurrent() // already open is not another chooser session
        assertEquals(chooser, rig.host.chooserSession)
        assertEquals(fact, rig.host.state.value.departure)
        assertEquals(accountScope, rig.host.departureState.scope, "Leave's request/membership generations are not account scope")
        assertEquals(null, rig.host.departureState.presentedIn, "show/refresh are not visible layout acknowledgement")
    }

    @Test fun departureManualPausedLeaveAndStopKeepDistinctTruthfulCauses() = runTest {
        val manual = Rig(this)
        manual.join()
        manual.snapshot()
        manual.renderer.value = manual.renderer.value.copy(playing = false)
        manual.host.leave()
        runCurrent()
        val fact = assertNotNull(manual.host.state.value.departure)
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.MANUAL_LEAVE, fact.cause)
        assertEquals("Evening", fact.groupName)
        assertEquals("Left Watch together. This device is paused; the group can keep playing.", manual.host.state.value.status)
        val stop = Rig(this)
        stop.join()
        stop.snapshot()
        stop.host.leave(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED)
        runCurrent()
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED, assertNotNull(stop.host.state.value.departure).cause)
        assertEquals("Evening", stop.host.state.value.departure?.groupName)
    }

    @Test fun departureCoveredByActualAccessErrorCannotBeAcknowledgedOrConsumed() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.leave()
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        rig.failAccess = true
        rig.host.show()
        runCurrent()
        assertEquals(SyncNoticeTone.ERROR, rig.host.state.value.notice?.tone)
        val scope = rig.host.departureState.scope
        val session = rig.host.chooserSession
        // Defense against a stale/faulty layout callback: the selected banner is the real error.
        rig.host.departurePresented(scope, fact.id, session)
        assertEquals(null, rig.host.departureState.presentedIn)
        rig.host.dismissChooser(scope, fact.id, session)
        runCurrent()
        assertEquals(fact, rig.host.state.value.departure)
        rig.failAccess = false
        rig.host.show()
        runCurrent()
        rig.host.departurePresented(scope, fact.id, rig.host.chooserSession)
        rig.host.dismissChooser(scope, fact.id, rig.host.chooserSession)
        runCurrent()
        assertEquals(null, rig.host.state.value.departure)
    }

    @Test fun departureHiddenAndWrongScopeIdSessionCallbacksCannotConsume() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.leave()
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        val scope = rig.host.departureState.scope
        rig.host.departurePresented(scope, fact.id, rig.host.chooserSession) // hidden
        assertEquals(null, rig.host.departureState.presentedIn)
        rig.host.show()
        runCurrent()
        val session = rig.host.chooserSession
        rig.host.dismissChooser(scope, fact.id, session) // never presented
        assertEquals(fact, rig.host.state.value.departure)
        rig.host.show()
        runCurrent()
        val open = rig.host.chooserSession
        rig.host.departurePresented(scope - 1, fact.id, open)
        rig.host.departurePresented(scope, fact.id + 1, open)
        rig.host.departurePresented(scope, fact.id, open - 1)
        assertEquals(null, rig.host.departureState.presentedIn)
        rig.host.dismissChooser(scope - 1, fact.id, open)
        rig.host.dismissChooser(scope, fact.id + 1, open)
        rig.host.dismissChooser(scope, fact.id, open - 1)
        assertEquals(fact, rig.host.state.value.departure)
    }

    @Test fun departureVisibleLayoutThenMatchingDismissConsumesOnceAndDoesNotRepeat() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.leave()
        runCurrent()
        rig.host.show()
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        val scope = rig.host.departureState.scope
        val session = rig.host.chooserSession
        rig.host.departurePresented(scope, fact.id, session)
        assertEquals(session, rig.host.departureState.presentedIn)
        rig.host.dismissChooser(scope, fact.id, session)
        runCurrent()
        assertEquals(null, rig.host.state.value.departure)
        assertFalse(rig.host.panelOpen)
        rig.host.dismissChooser(scope, fact.id, session)
        rig.host.show()
        runCurrent()
        assertTrue(rig.host.chooserSession > session)
        assertEquals(null, rig.host.state.value.departure)
    }

    @Test fun departureNewFactSurvivesOldCapturedLayoutAndDismissCallbacks() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.leave()
        runCurrent()
        rig.host.show()
        runCurrent()
        val first = assertNotNull(rig.host.state.value.departure)
        val scope = rig.host.departureState.scope
        val oldSession = rig.host.chooserSession
        rig.host.departurePresented(scope, first.id, oldSession)
        rig.join()
        rig.snapshot() // accepted membership clears the old pending fact
        assertEquals(null, rig.host.state.value.departure)
        rig.host.leave(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED)
        runCurrent()
        val second = assertNotNull(rig.host.state.value.departure)
        assertTrue(second.id > first.id)
        rig.host.departurePresented(scope, first.id, oldSession)
        rig.host.dismissChooser(scope, first.id, oldSession)
        assertEquals(second, rig.host.state.value.departure)
        assertEquals(null, rig.host.departureState.presentedIn)
    }

    @Test fun departureAccountOrTokenEpochResetCannotLeakNameOrAcceptOldAcknowledgement() = runTest {
        for (replacement in listOf(other, account.copy(accessToken = "rotated-test-token"), null)) {
            val rig = Rig(this)
            rig.join()
            rig.snapshot()
            rig.host.leave()
            runCurrent()
            rig.host.show()
            runCurrent()
            val fact = assertNotNull(rig.host.state.value.departure)
            val oldScope = rig.host.departureState.scope
            val oldSession = rig.host.chooserSession
            rig.host.bind(replacement)
            runCurrent()
            assertTrue(rig.host.departureState.scope > oldScope)
            assertEquals(null, rig.host.state.value.departure)
            rig.host.departurePresented(oldScope, fact.id, oldSession)
            rig.host.dismissChooser(oldScope, fact.id, oldSession)
            assertEquals(null, rig.host.state.value.departure)
        }
    }

    @Test fun departureFailedJoinIntentRetainsButAcceptedCurrentMembershipClears() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        val oldGeneration = rig.host.membership.generation
        rig.host.leave(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED)
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        rig.httpStatus = 403
        rig.host.join("g")
        runCurrent()
        assertEquals(fact, rig.host.state.value.departure)
        rig.host.receive(
            rig.update(
                "GroupJoined",
                buildJsonObject {
                    put("GroupId", "g")
                    put("GroupName", "Old group")
                    put("State", "Paused")
                    put("Participants", JsonArray(emptyList()))
                    put("LastUpdatedAt", syncUtcTimestamp(rig.epoch))
                }
            ),
            oldGeneration
        )
        assertEquals(fact, rig.host.state.value.departure, "Rejected stale Joined cannot acknowledge new membership")
        rig.httpStatus = 204
        rig.host.join("g")
        runCurrent()
        assertEquals(fact, rig.host.state.value.departure, "HTTP intent/success alone is not accepted GroupJoined")
        rig.joined()
        runCurrent()
        assertEquals(null, rig.host.state.value.departure)
    }

    @Test fun departureOnlyAcceptedCurrentGroupLeftRecordsNeutralMembershipLoss() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        val generation = rig.host.membership.generation
        rig.host.receive(rig.update("GroupLeft"), generation - 1)
        rig.host.receive(rig.update("GroupLeft").replace("\"GroupId\":\"g\"", "\"GroupId\":\"wrong-group\""), generation)
        assertEquals(null, rig.host.state.value.departure)
        assertTrue(rig.host.participating)
        rig.host.receive(rig.update("GroupLeft"), generation)
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        assertEquals("Evening", fact.groupName)
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.MEMBERSHIP_LOST, fact.cause)
        rig.host.receive(rig.update("GroupLeft"), generation)
        assertEquals(fact, rig.host.state.value.departure)
        rig.join()
        rig.snapshot()
        assertEquals(null, rig.host.state.value.departure)
        rig.host.receive(rig.update("GroupLeft"), generation)
        assertTrue(rig.host.participating)
        assertEquals(null, rig.host.state.value.departure)
    }

    @Test fun departureRepeatedLeaveSendsOnceAndNeverReplacesStopIdentity() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.host.leave(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED)
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        rig.host.leave()
        rig.host.leave()
        runCurrent()
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(fact, rig.host.state.value.departure)
        assertFalse(rig.renderer.value.playing)
    }

    @Test fun departureUnconfirmedLeaveRetryRetainsOriginalIdentityAndCause() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.httpStatus = 503
        rig.host.leave(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_STOPPED)
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        assertEquals(SyncNoticeKind.LEAVE_UNCONFIRMED, rig.host.state.value.notice?.kind)
        rig.httpStatus = 204
        rig.host.leave()
        runCurrent()
        assertEquals(listOf("Leave", "Leave"), rig.operations(), "One request per deliberate retry, no automatic storm")
        assertEquals(fact, rig.host.state.value.departure)
    }

    @Test fun departureCastCancelHasNoFactAndConfirmedRouteHasDistinctCauseOnce() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        var routes = 0
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        assertEquals(null, rig.host.state.value.departure)
        rig.host.cancelExternalRoute()
        runCurrent()
        assertEquals(null, rig.host.state.value.departure)
        assertEquals(0, routes)
        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.host.participating)
        rig.sendGate = CompletableDeferred()
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        rig.host.leaveForExternalRoute()
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.EXTERNAL_ROUTE, fact.cause)
        assertEquals("Evening", fact.groupName)
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(0, routes)
        rig.sendGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, routes)
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(1, routes)
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(fact, rig.host.state.value.departure)
    }

    @Test fun departureFailedCastLeaveKeepsErrorAndNeverStartsRoute() = runTest {
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        rig.httpStatus = 503
        var routes = 0
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        rig.host.leaveForExternalRoute()
        runCurrent()
        val fact = assertNotNull(rig.host.state.value.departure)
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.EXTERNAL_ROUTE, fact.cause)
        assertEquals(SyncNoticeKind.LEAVE_UNCONFIRMED, rig.host.state.value.notice?.kind)
        assertEquals(0, routes)
        assertEquals(listOf("Leave"), rig.operations())
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(0, routes)
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(fact, rig.host.state.value.departure)
    }

    @Test fun departureNoParticipationAndBlankFormerNameNeverFabricateFactsOrRawIds() = runTest {
        val solo = Rig(this)
        solo.host.leave()
        runCurrent()
        assertEquals(null, solo.host.state.value.departure)
        assertFalse(solo.operations().contains("Leave"))
        val joined = Rig(this)
        joined.join()
        joined.host.receive(
            joined.update(
                "GroupJoined",
                buildJsonObject {
                    put("GroupId", "g")
                    put("GroupName", "  ")
                    put("State", "Paused")
                    put("Participants", JsonArray(emptyList()))
                    put("LastUpdatedAt", syncUtcTimestamp(joined.epoch))
                }
            )
        )
        runCurrent()
        joined.host.leave()
        runCurrent()
        val fact = assertNotNull(joined.host.state.value.departure)
        assertEquals(null, fact.groupName, "Do not fall back to raw group ID g")
    }

    // Frozen scope-boundary traces use the actual host and route callback, not a duplicate reducer.
    private suspend fun TestScope.routeConsentScopeCase(id: String) {
        val roots = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val vectors = roots.map { java.io.File(it, "spec/vectors/sync-route-consent.json") }.first { it.isFile }
        val traces = kotlinx.serialization.json.Json.parseToJsonElement(vectors.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(10, traces.size, "All consent scenarios belong to the portable case inventory")
        val cases = traces.filter { "rebind" in it.jsonObject }
        assertEquals(4, cases.size)
        val case = cases.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        var routes = 0
        assertTrue(rig.host.confirmExternalRoute { routes++ })
        val replacement = when (case.getValue("rebind").jsonPrimitive.content) {
            "account" -> other
            "token" -> account.copy(accessToken = "replacement-test-token")
            "logout" -> null
            "same" -> account.copy()
            else -> error("Unknown consent scope case")
        }
        rig.host.bind(replacement)
        runCurrent()
        assertEquals(
            case.getValue("expectedPending").jsonPrimitive.boolean,
            rig.host.pendingExternalRoute != null,
            id + " pending consent"
        )
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(case.getValue("expectedRoutes").jsonPrimitive.int, routes, id + " route callbacks")
        rig.host.leaveForExternalRoute()
        runCurrent()
        assertEquals(case.getValue("expectedRoutes").jsonPrimitive.int, routes, id + " no repeated route")
    }

    @Test fun routeConsentDifferentAccountInvalidatesOldConsent() = runTest { routeConsentScopeCase("consent-account") }

    @Test fun routeConsentTokenRotationInvalidatesOldConsent() = runTest { routeConsentScopeCase("consent-token") }

    @Test fun routeConsentLogoutInvalidatesOldConsent() = runTest { routeConsentScopeCase("consent-logout") }

    @Test fun routeConsentSameAccountAndTokenRetainsConsent() = runTest { routeConsentScopeCase("consent-same") }

    private suspend fun TestScope.routeConsentIdentityCase(id: String) {
        val roots = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val vectors = roots.map { java.io.File(it, "spec/vectors/sync-route-consent.json") }.first { it.isFile }
        val traces = kotlinx.serialization.json.Json.parseToJsonElement(vectors.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(10, traces.size, "All consent scenarios belong to the portable case inventory")
        val cases = traces.filter { "rebind" !in it.jsonObject }
        assertEquals(6, cases.size)
        val case = cases.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.sent.clear()
        var oldRoutes = 0
        var newRoutes = 0
        assertTrue(rig.host.confirmExternalRoute { oldRoutes++ })
        val old = assertNotNull(rig.host.externalRouteConsent)
        when (id) {
            "duplicate" -> {
                rig.host.leaveForExternalRoute(old.scope, old.id)
                runCurrent()
                rig.host.leaveForExternalRoute(old.scope, old.id)
                runCurrent()
            }
            "scope-change-during-leave" -> {
                rig.sendGate = CompletableDeferred()
                rig.host.leaveForExternalRoute(old.scope, old.id)
                runCurrent()
                assertEquals(0, oldRoutes)
                rig.host.bind(account.copy(accessToken = "rotated-inflight-test-token"))
                runCurrent()
                rig.sendGate!!.complete(Unit)
                runCurrent()
            }
            else -> {
                assertTrue(rig.host.confirmExternalRoute { newRoutes++ })
                val fresh = assertNotNull(rig.host.externalRouteConsent)
                assertTrue(fresh.id > old.id)
                when (id) {
                    "stale-confirm" -> rig.host.leaveForExternalRoute(old.scope, old.id)
                    "stale-cancel" -> rig.host.cancelExternalRoute(old.scope, old.id)
                    "wrong-scope-confirm" -> rig.host.leaveForExternalRoute(fresh.scope - 1, fresh.id)
                    "wrong-scope-cancel" -> rig.host.cancelExternalRoute(fresh.scope - 1, fresh.id)
                    else -> error("Unknown consent identity case")
                }
                runCurrent()
                assertEquals(
                    case.getValue("expectedPendingAfterObsolete").jsonPrimitive.boolean,
                    rig.host.pendingExternalRoute != null,
                    id + " obsolete callback must retain fresh consent"
                )
                assertEquals(case.getValue("expectedRoutesAfterObsolete").jsonPrimitive.int, oldRoutes + newRoutes, id)
                assertTrue(rig.host.participating, id + " obsolete callback must not Leave")
                assertTrue(rig.sent.isEmpty(), id + " obsolete callback must send nothing")
                rig.host.leaveForExternalRoute(fresh.scope, fresh.id)
                runCurrent()
                assertEquals(0, oldRoutes)
            }
        }
        assertEquals(case.getValue("expectedFinalRoutes").jsonPrimitive.int, oldRoutes + newRoutes, id)
        assertEquals(case.getValue("expectedLeaveRequests").jsonPrimitive.int, rig.operations().count { it == "Leave" }, id)
        assertEquals(null, rig.host.pendingExternalRoute)
        assertEquals(null, rig.host.externalRouteConsent)
    }

    @Test fun routeConsentIdentityStaleConfirm() = runTest { routeConsentIdentityCase("stale-confirm") }

    @Test fun routeConsentIdentityStaleCancel() = runTest { routeConsentIdentityCase("stale-cancel") }

    @Test fun routeConsentIdentityWrongScopeConfirm() = runTest { routeConsentIdentityCase("wrong-scope-confirm") }

    @Test fun routeConsentIdentityWrongScopeCancel() = runTest { routeConsentIdentityCase("wrong-scope-cancel") }

    @Test fun routeConsentIdentityDuplicate() = runTest { routeConsentIdentityCase("duplicate") }

    @Test fun routeConsentIdentityScopeChangeDuringLeave() = runTest { routeConsentIdentityCase("scope-change-during-leave") }

    /** Native completion uses the parsed authoritative queue, never the Rig's one-entry default. */
    private fun TestScope.naturalCompletionRig(repeatMode: String, currentIndex: Int): Rig {
        val rig = Rig(this)
        rig.join()
        rig.joined("Playing")
        rig.host.receive(
            rig.update(
                "PlayQueue",
                buildJsonObject {
                    put("Reason", "NewPlaylist")
                    put("LastUpdate", syncUtcTimestamp(rig.wall()))
                    put(
                        "Playlist",
                        JsonArray(
                            listOf("entry-a", "entry-b").map { entry ->
                                buildJsonObject {
                                    put("ItemId", "local-item")
                                    put("PlaylistItemId", entry)
                                }
                            }
                        )
                    )
                    put("PlayingItemIndex", currentIndex)
                    put("StartPositionTicks", 55_000_000L)
                    put("IsPlaying", true)
                    put("RepeatMode", repeatMode)
                    put("ShuffleMode", "Sorted")
                }
            )
        )
        runCurrent()
        rig.preparedEntry()
        // A drained decoder need not be isPlaying; actual play intent remains true at natural EOS.
        rig.renderer.value = rig.renderer.value.copy(
            positionTicks = rig.renderer.value.durationTicks,
            playing = false,
            playWhenReady = true
        )
        rig.sent.clear()
        rig.renderer.applied.clear()
        return rig
    }

    @Test fun naturalLastRepeatNoneLeavesOnceWithoutStoppingGroupAndAllowsLocalCompletion() = runTest {
        val rig = naturalCompletionRig("RepeatNone", 1)
        val position = rig.renderer.value.positionTicks
        assertFalse(rig.host.transport("ended"), "terminal completion must reach the caller's local cleanup")
        runCurrent()
        assertEquals(listOf("Leave"), rig.operations(), "natural terminal departure is neither NextItem nor group Stop")
        assertFalse(rig.host.participating)
        assertFalse(rig.renderer.value.unpaused)
        assertEquals(position, rig.renderer.value.positionTicks)
        assertTrue(rig.renderer.applied.none { it.position != null }, "departure must not rewind or seek")
        val fact = assertNotNull(rig.host.state.value.departure)
        assertEquals(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_ENDED, fact.cause)
        assertEquals("Evening", fact.groupName)
        assertFalse(rig.host.transport("ended"), "a duplicate decoder completion cannot rejoin or leave twice")
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("Leave"), rig.operations())
        assertEquals(fact, rig.host.state.value.departure, "hidden completion retains the departure identity")
    }

    @Test fun naturalQueueContinuationUsesServerWireWhileExplicitNextKeepsItsOwnMeaning() = runTest {
        for ((repeatMode, index) in listOf(
            "RepeatNone" to 0,
            "RepeatAll" to 0,
            "RepeatAll" to 1,
            "RepeatOne" to 0,
            "RepeatOne" to 1
        )) {
            val rig = naturalCompletionRig(repeatMode, index)
            val before = rig.renderer.value
            val entry = if (index == 0) "entry-a" else "entry-b"
            assertTrue(rig.host.transport("ended"), "$repeatMode/$index waits for the authoritative continuation")
            runCurrent()
            val operation = if (repeatMode == "RepeatOne") "SetPlaylistItem" else "NextItem"
            assertEquals(listOf(operation), rig.operations(), "$repeatMode/$index must not Leave or group Stop")
            assertEquals(JsonPrimitive(entry), rig.sent.single().second.body!!["PlaylistItemId"])
            assertEquals(before, rig.renderer.value, "a request cannot optimistically restart or advance local output")
            assertTrue(rig.renderer.applied.isEmpty())
            assertTrue(rig.host.participating)
            assertEquals(null, rig.host.state.value.departure)
        }
        for (repeatMode in listOf("RepeatNone", "RepeatOne")) {
            val rig = naturalCompletionRig(repeatMode, 1)
            val before = rig.renderer.value
            assertTrue(rig.host.transport("next"), "an explicit Next is not a natural terminal event")
            runCurrent()
            assertEquals(listOf("NextItem"), rig.operations(), "$repeatMode explicit Next retains the server's policy")
            assertEquals(JsonPrimitive("entry-b"), rig.sent.single().second.body!!["PlaylistItemId"])
            assertEquals(before, rig.renderer.value)
            assertTrue(rig.renderer.applied.isEmpty())
            assertTrue(rig.host.participating)
            assertEquals(null, rig.host.state.value.departure)
        }
    }

    @Test fun unownedRecoveringOrPausedCompletionCannotLeaveOrAdvanceTheGroup() = runTest {
        for (scenario in listOf("missing-queue", "recovering", "wrong-item", "wrong-entry", "paused-end")) {
            val rig = if (scenario == "missing-queue") {
                Rig(this).also {
                    it.join()
                    it.joined("Playing")
                    runCurrent()
                    it.renderer.value = it.renderer.value.copy(playing = false, playWhenReady = true)
                }
            } else {
                naturalCompletionRig("RepeatNone", 1)
            }
            when (scenario) {
                "recovering" -> rig.host.socket(false)
                "wrong-item" -> rig.renderer.value = rig.renderer.value.copy(itemId = "obsolete-item")
                "wrong-entry" -> rig.renderer.value = rig.renderer.value.copy(playlistItemId = "entry-a")
                "paused-end" -> rig.renderer.value = rig.renderer.value.copy(playWhenReady = false)
            }
            runCurrent()
            rig.sent.clear()
            rig.renderer.applied.clear()
            val before = rig.renderer.value
            assertTrue(rig.host.transport("ended"), "$scenario must consume an unsafe completion")
            runCurrent()
            assertTrue(rig.sent.isEmpty(), "$scenario cannot issue Leave, Stop, NextItem or a repeat restart")
            assertEquals(before, rig.renderer.value, "$scenario cannot seek or change native play intent")
            assertTrue(rig.renderer.applied.isEmpty())
            assertTrue(rig.host.participating)
            assertEquals(null, rig.host.state.value.departure)
        }
    }

    @Test fun rendererCompletionRejectsObsoleteIdentityBeforeCurrentTerminalDeparture() = runTest {
        for (scenario in listOf("wrong-token", "wrong-output", "old-source", "old-generation", "wrong-entry")) {
            val rig = naturalCompletionRig("RepeatNone", 1)
            val sourceBeforeReplacement = rig.renderer.sourceIdentity
            if (scenario == "old-source") rig.renderer.sourceIdentity = Any()
            val foreignOutput = Renderer().also {
                it.value = rig.renderer.value
                it.sourceIdentity = rig.renderer.sourceIdentity
            }
            val generation = rig.host.membership.generation
            val before = rig.renderer.value
            val foreignBefore = foreignOutput.value
            assertTrue(
                rig.host.rendererEnded(
                    token = if (scenario == "wrong-token") Any() else rig.renderer,
                    output = if (scenario == "wrong-output") foreignOutput else rig.renderer,
                    source = if (scenario == "old-source") sourceBeforeReplacement else rig.renderer.sourceIdentity,
                    generation = if (scenario == "old-generation") generation - 1 else generation,
                    // Both queue entries name the same media: an obsolete entry must not end its replacement.
                    entryId = if (scenario == "wrong-entry") "entry-a" else "entry-b"
                ),
                "$scenario completion must be consumed before it can reach local cleanup"
            )
            runCurrent()
            assertTrue(rig.sent.isEmpty(), "$scenario cannot issue Leave, NextItem, Stop or restart")
            assertEquals(before, rig.renderer.value, "$scenario cannot move or pause current output")
            assertEquals(foreignBefore, foreignOutput.value, "$scenario cannot move a different output bridge")
            assertTrue(rig.renderer.applied.isEmpty())
            assertTrue(foreignOutput.applied.isEmpty())
            assertTrue(rig.host.participating)
            assertEquals(null, rig.host.state.value.departure)

            assertFalse(
                rig.host.rendererEnded(rig.renderer, rig.renderer, rig.renderer.sourceIdentity, generation, "entry-b"),
                "$scenario rejection must leave the current completion eligible for terminal cleanup"
            )
            runCurrent()
            assertEquals(listOf("Leave"), rig.operations())
            assertFalse(rig.host.participating)
            assertFalse(rig.renderer.value.unpaused)
            assertEquals(before.positionTicks, rig.renderer.value.positionTicks)
            assertTrue(rig.renderer.applied.none { it.position != null }, "current completion must not rewind")
            val departure = assertNotNull(rig.host.state.value.departure)
            assertEquals(app.pictureshow.core.sync.SyncDepartureCause.PLAYBACK_ENDED, departure.cause)
            assertEquals("Evening", departure.groupName)
        }
    }

    @Test fun initialInvalidClockRecoversAfterThirtySecondsWithoutRejoiningOrPlayingEarly() = runTest {
        val (before, due) = clockRecoveryBoundary()
        val rig = Rig(this)
        rig.failClock = true
        rig.join()
        rig.snapshot()
        val generation = rig.host.membership.generation
        val entry = rig.host.membership.queue?.current?.playlistItemId
        rig.host.receive(rig.command("Unpause", ticks = rig.renderer.value.positionTicks, id = "pending-initial-clock"))
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertTrue(rig.host.participating)
        assertEquals(SyncNoticeKind.CLOCK, rig.host.state.value.notice?.kind)
        assertFalse(rig.renderer.value.unpaused)
        assertTrue(rig.renderer.applied.none { it.playing == true })
        val attempts = rig.diagnostics.count { it.startsWith("trace-clock-begin ") }
        assertEquals(1, attempts)
        rig.sent.clear()
        rig.failClock = false
        advanceTimeBy(before)
        runCurrent()
        assertEquals(attempts, rig.diagnostics.count { it.startsWith("trace-clock-begin ") })
        assertTrue(rig.operations().none { it in setOf("Ready", "Ping", "NewGroup", "JoinGroup", "Leave", "Unpause") })
        assertFalse(rig.renderer.value.unpaused, "A restored network alone cannot borrow the invalid clock")
        advanceTimeBy(due - before)
        runCurrent()
        assertEquals(attempts + 1, rig.diagnostics.count { it.startsWith("trace-clock-begin ") }, "One bounded recovery attempt")
        advanceTimeBy(250)
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertTrue(rig.operations().contains("Ping"), "Fresh valid clock must be measured and reported")
        assertTrue(rig.operations().contains("Ready"), "Actual eligible entry must report timed readiness after clock recovery")
        assertTrue(rig.renderer.value.unpaused, "The retained authoritative pending Unpause may apply only after valid calibration")
        assertTrue(rig.renderer.applied.any { it.playing == true })
        assertEquals(generation, rig.host.membership.generation)
        assertEquals(entry, rig.host.membership.queue?.current?.playlistItemId)
        assertTrue(rig.host.participating)
        assertEquals(null, rig.host.state.value.departure)
        assertTrue(
            rig.operations().none { it in setOf("NewGroup", "JoinGroup", "Leave", "Unpause") },
            "Clock recovery cannot manufacture a new group intent or echo the server command"
        )
    }

    @Test fun failedRefreshRetriesThirtySecondsAfterCompletionAndRecoversTheHeldServerCommand() = runTest {
        val (before, due) = clockRecoveryBoundary()
        val rig = Rig(this)
        rig.join()
        rig.snapshot()
        rig.host.tick()
        runCurrent()
        val generation = rig.host.membership.generation
        val entry = rig.host.membership.queue?.current?.playlistItemId
        // Mirror the actual chain: report an unready renderer while the previous clock is still valid.
        rig.renderer.value = rig.renderer.value.copy(ready = false, buffering = true)
        rig.host.tick()
        runCurrent()
        assertTrue(rig.operations().contains("Buffering"), "Readiness cache must hold a real acknowledged unready observation")
        val initialAttempts = rig.diagnostics.count { it.startsWith("trace-clock-begin ") }
        rig.failClock = true
        val gate = CompletableDeferred<Unit>()
        rig.clockGate = gate
        advanceTimeBy(due)
        runCurrent()
        assertEquals(initialAttempts + 1, rig.diagnostics.count { it.startsWith("trace-clock-begin ") })
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(
            initialAttempts + 1,
            rig.diagnostics.count { it.startsWith("trace-clock-begin ") },
            "Only one calibration may remain in flight"
        )
        gate.complete(Unit)
        runCurrent()
        assertTrue(
            rig.diagnostics.any {
                it.startsWith("trace-clock-end ") &&
                    it.contains("oldAnchor=true") &&
                    it.contains("newAnchor=false")
            }
        )
        rig.clockGate = null
        rig.host.receive(rig.command("Unpause", ticks = rig.renderer.value.positionTicks, id = "pending-after-refresh"))
        runCurrent()
        rig.host.receive(
            rig.update(
                "StateUpdate",
                buildJsonObject {
                    put("State", "Waiting")
                    put("Reason", "Buffer")
                }
            )
        )
        rig.renderer.value = rig.renderer.value.copy(ready = true, buffering = false)
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertEquals(SyncNoticeKind.CLOCK, rig.host.state.value.notice?.kind)
        assertEquals("Waiting", rig.host.state.value.phase)
        assertEquals("Buffer", rig.host.state.value.waitReason)
        assertTrue(rig.renderer.value.ready && !rig.renderer.value.buffering)
        assertFalse(rig.renderer.value.unpaused)
        rig.renderer.applied.clear()
        rig.sent.clear()
        val failedAttempts = rig.diagnostics.count { it.startsWith("trace-clock-begin ") }
        rig.failClock = false
        // Failed attempt started30s and completed35s. This spans the incorrect60s start-based retry boundary.
        advanceTimeBy(before)
        runCurrent()
        assertEquals(failedAttempts, rig.diagnostics.count { it.startsWith("trace-clock-begin ") })
        assertFalse(rig.renderer.value.unpaused)
        assertTrue(rig.renderer.applied.none { it.playing == true })
        assertTrue(rig.operations().none { it in setOf("Ready", "Ping", "NewGroup", "JoinGroup", "Leave", "Unpause") })
        advanceTimeBy(due - before)
        runCurrent()
        assertEquals(
            failedAttempts + 1,
            rig.diagnostics.count { it.startsWith("trace-clock-begin ") },
            "Retry must begin30s after failed completion, at65s, not at60s or every ticker tick"
        )
        advanceTimeBy(250)
        runCurrent()
        rig.host.tick()
        runCurrent()
        assertTrue(rig.operations().contains("Ping"))
        assertTrue(rig.operations().contains("Ready"), "Recovered clock must report the renderer's actual READY transition")
        assertEquals("Waiting", rig.host.state.value.phase)
        assertFalse(rig.renderer.value.unpaused, "A fresh clock and Ready cannot override the server's Waiting barrier")
        assertTrue(rig.renderer.applied.none { it.playing == true })
        assertEquals(generation, rig.host.membership.generation)
        assertEquals(entry, rig.host.membership.queue?.current?.playlistItemId)
        assertTrue(rig.operations().none { it in setOf("NewGroup", "JoinGroup", "Leave", "Unpause") })
        // Only one fresh authoritative response may resume output, at its actual future When.
        rig.host.receive(
            rig.command("Unpause", delayMs = 500, ticks = rig.renderer.value.positionTicks, id = "fresh-after-ready")
        )
        runCurrent()
        assertFalse(rig.renderer.value.unpaused, "Receiving a future Unpause cannot apply it early")
        advanceTimeBy(499)
        runCurrent()
        assertFalse(rig.renderer.value.unpaused, "Hold output until the server When")
        assertTrue(rig.renderer.applied.none { it.playing == true })
        advanceTimeBy(1)
        runCurrent()
        assertTrue(rig.renderer.value.unpaused)
        assertTrue(rig.renderer.applied.any { it.playing == true })
        assertEquals(generation, rig.host.membership.generation)
        assertEquals(entry, rig.host.membership.queue?.current?.playlistItemId)
        assertTrue(rig.host.participating)
        assertEquals(null, rig.host.state.value.departure)
        assertTrue(rig.operations().none { it in setOf("NewGroup", "JoinGroup", "Leave", "Unpause") })
    }

    @Test fun clockRecoveryDeadlineCannotRetryOrApplyAfterLeaveOrAccountReplacement() = runTest {
        val (_, due) = clockRecoveryBoundary()
        for (replaceAccount in listOf(false, true)) {
            val rig = Rig(this)
            rig.failClock = true
            rig.join()
            rig.snapshot()
            rig.host.receive(rig.command("Unpause", ticks = rig.renderer.value.positionTicks, id = "obsolete-clock-scope"))
            runCurrent()
            assertFalse(rig.renderer.value.unpaused)
            val attempts = rig.diagnostics.count { it.startsWith("trace-clock-begin ") }
            advanceTimeBy(10_000)
            runCurrent()
            rig.sent.clear()
            if (replaceAccount) rig.host.bind(other) else rig.host.leave()
            runCurrent()
            assertFalse(rig.host.participating)
            assertFalse(rig.renderer.value.unpaused)
            assertTrue(rig.operations().all { it == "Leave" })
            assertTrue(rig.operations().size <= 1, "Only the deliberate departure may dispatch")
            val departureOperations = rig.operations()
            rig.renderer.applied.clear()
            rig.failClock = false
            advanceTimeBy(due + 250)
            runCurrent()
            rig.host.tick()
            runCurrent()
            assertEquals(
                attempts,
                rig.diagnostics.count {
                    it.startsWith("trace-clock-begin ")
                },
                "Cancelled account/membership cannot retry"
            )
            assertEquals(departureOperations, rig.operations(), "Old deadline cannot send readiness, ping, join or another leave")
            assertFalse(rig.host.participating)
            assertFalse(rig.renderer.value.unpaused)
            assertTrue(rig.renderer.applied.isEmpty(), "Old pending command cannot mutate the renderer")
        }
    }

    @Test fun navigationAuthorityFollowsOnlyTheCurrentConnectedQueueSnapshot() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertEquals(null, rig.host.state.value.navigation, "A default presentation repeat is not queue authority")
        rig.join()
        assertFalse(rig.host.membership.hasSnapshot)
        assertEquals(null, rig.host.state.value.navigation, "Joined intent alone cannot manufacture queue bounds")
        rig.snapshot()
        runCurrent()
        assertTrue(rig.host.membership.hasSnapshot)
        val queue = assertNotNull(rig.host.membership.queue)
        val published = assertNotNull(rig.host.state.value.navigation)
        assertEquals(queue.repeatMode, published.repeatMode)
        assertEquals(queue.entries.map { it.playlistItemId }, published.entryIds)
        assertEquals(queue.current?.playlistItemId, published.currentEntryId)
        assertEquals("RepeatNone", published.repeatMode)
        assertEquals(listOf("entry-a"), published.entryIds)
        assertEquals("entry-a", published.currentEntryId)
        // Same media under a replacement queue-entry identity must publish the new server scope.
        rig.queue("local-item", "entry-b")
        runCurrent()
        val replacement = assertNotNull(rig.host.state.value.navigation)
        assertEquals(listOf("entry-b"), replacement.entryIds)
        assertEquals("entry-b", replacement.currentEntryId)
        rig.host.socket(false)
        runCurrent()
        assertFalse(rig.host.membership.hasSnapshot)
        assertEquals(null, rig.host.state.value.navigation, "Retained queue rows cannot retain navigation authority on disconnect")
        rig.host.socket(true)
        runCurrent()
        assertFalse(rig.host.membership.hasSnapshot)
        assertEquals(null, rig.host.state.value.navigation, "Connected without a fresh snapshot is still unconfirmed")
        rig.snapshot()
        runCurrent()
        assertTrue(rig.host.membership.hasSnapshot)
        val restored = assertNotNull(rig.host.state.value.navigation)
        assertEquals(listOf("entry-a"), restored.entryIds)
        assertEquals("entry-a", restored.currentEntryId)
        assertTrue(rig.operations().none { it in setOf("NextItem", "PreviousItem") })
        rig.host.bind(other)
        runCurrent()
        assertEquals(null, rig.host.state.value.navigation, "An old account queue cannot publish navigation authority")
        assertTrue(rig.host.state.value.queue.isEmpty())
    }
}
