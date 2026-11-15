/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
import app.pictureshow.core.playback.PlayDecision
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** Drive the real screen, gateway, track reopen, native seek and output's EOS callback; hold only the optional API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h800dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlaybackHostTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
    private val episode = MediaItem("episode", "An episode", "Episode", seriesId = "series")
    private val releaseLookup = CompletableDeferred<Unit>()
    private val metadataGate = CompletableDeferred<Unit>()
    private val lookups = AtomicInteger()
    private val reachedEnd = java.util.concurrent.atomic.AtomicBoolean()
    private var lookupsAtEnd = 0
    private val preparations = AtomicInteger()
    private val reports = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var configuration by androidx.compose.runtime.mutableStateOf(android.content.res.Configuration())
    private var callbackVersion by androidx.compose.runtime.mutableStateOf(1)
    private lateinit var observedHost: PlaybackHostState
    private var exits = 0
    private val next = mutableListOf<String>()
    private lateinit var output: Media3PlaybackOutput
    private lateinit var http: HttpClient
    private val catalogApi = CatalogModelsTest.FakeCatalog()
    private var terminalGroupScope: kotlinx.coroutines.CoroutineScope? = null
    private var terminalOpening by androidx.compose.runtime.mutableStateOf<SyncOpen?>(null)

    @After fun cleanup() {
        releaseLookup.complete(Unit)
        metadataGate.complete(Unit)
        compose.runOnIdle { if (::output.isInitialized) output.release() }
        terminalGroupScope?.cancel()
        stopKoin()
        if (::http.isInitialized) http.close()
    }

    @Test fun touchEndWaitsForPendingLookupThenPlaysExactlyOnce() {
        launch()
        reopenAndEnd()
        compose.runOnIdle { assertEquals(0, exits, "Pending does not mean there is no next episode") }
        releaseLookup.complete(Unit)
        waitUntil { next.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("next"), next)
            assertEquals(0, exits)
            assertEquals(1, reports.count { it == "/Sessions/Playing" })
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
        }
    }

    @Test fun failedInitialLookupIsRetriedAtEndInsteadOfSilentlyLosingNextEpisode() {
        launch(failFirst = true)
        waitUntil { lookups.get() >= 1 }
        releaseLookup.complete(Unit)
        reopenAndEnd()
        waitUntil { next.isNotEmpty() || exits > 0 }
        compose.runOnIdle {
            assertEquals(listOf("next"), next)
            assertEquals(lookupsAtEnd + 1, lookups.get(), "End retries once after the initial failure")
            assertEquals(0, exits)
        }
    }

    @Test fun talkBackWaitsForExplicitPlayWhenLookupCompletesAfterTheEnd() {
        launch()
        reopenAndEnd(enableAccessibilityAtEnd = true)
        compose.runOnIdle { assertEquals(0, exits) }
        releaseLookup.complete(Unit)
        waitUntil {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("next:play")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertTrue(next.isEmpty()) }
        compose.onNodeWithTag("next:play").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        waitUntil { next.isNotEmpty() }
        assertEquals(listOf("next"), next)
    }

    @Test fun completedLookupWithNoNextEpisodeExitsAfterThePendingResponseArrives() {
        launch(noNext = true)
        reopenAndEnd()
        compose.runOnIdle { assertEquals(0, exits) }
        releaseLookup.complete(Unit)
        waitUntil { exits > 0 }
        assertEquals(1, exits)
        assertTrue(next.isEmpty())
    }

    /** The next local viewing starts where the online one stopped: the wiring from the player's session down to the download's store. */
    @Test fun anOnlineViewingOfADownloadedTitleTeachesTheDownloadItsPlace() = onlineViewing(
        me = { """{"Id":"${account.user.id}"}""" },
        userData = """{"PlaybackPositionTicks":7000,"Played":false,"PlayCount":0}"""
    ) { store, _ -> assertEquals(7000L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") }) }

    @Test fun backFromAnOnlineViewingTeachesTheDownloadWithoutAPlayedReport() = onlineViewing(
        me = { """{"Id":"${account.user.id}"}""" },
        userData = """{"PlaybackPositionTicks":30000,"Played":false}""",
        naturalEnd = false
    ) { store, _ ->
        assertEquals(30_000L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") })
        assertTrue(catalogApi.actions.none { it.first == "played" })
    }

    /** The same URL can answer as someone else: their place says nothing about what this viewer has unsent. */
    @Test fun anOnlineViewingReadAsAnotherViewerLeavesAnUnsentPlaceAlone() = onlineViewing(
        me = { """{"Id":"someone-else"}""" },
        userData = """{"PlaybackPositionTicks":30000,"Played":false,"PlayCount":0}"""
    ) { store, paths ->
        assertEquals(500L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") })
        assertTrue(paths.none { it.contains("/UserData") }, "Nothing is read once the viewer is someone else: $paths")
    }

    @Test fun anOnlineViewingThatCannotProveTheViewerLeavesAnUnsentPlaceAlone() = onlineViewing(
        me = { null },
        userData = """{"PlaybackPositionTicks":30000,"Played":false,"PlayCount":0}"""
    ) { store, _ -> assertEquals(500L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") }) }

    /** An empty answer isn't position 0. */
    @Test fun anOnlineViewingReadWithAnEmptyBodyLeavesAnUnsentPlaceAlone() = onlineViewing(
        me = { """{"Id":"${account.user.id}"}""" },
        userData = "{}"
    ) { store, _ -> assertEquals(500L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") }) }

    @Test fun anOnlineViewingReadWithoutPlayedLeavesAnUnsentPlaceAlone() = onlineViewing(
        me = { """{"Id":"${account.user.id}"}""" },
        userData = """{"PlaybackPositionTicks":30000}"""
    ) { store, _ -> assertEquals(500L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") }) }

    @Test fun anOnlineViewingReadWithoutAPositionLeavesAnUnsentPlaceAlone() = onlineViewing(
        me = { """{"Id":"${account.user.id}"}""" },
        userData = """{"Played":false}"""
    ) { store, _ -> assertEquals(500L, kotlinx.coroutines.runBlocking { store.resumeTicks("episode") }) }

    /** A downloaded title holding an unsent place of 500, base 100, then one online viewing against a server answering these. */
    private fun onlineViewing(
        me: () -> String?,
        userData: String,
        naturalEnd: Boolean = true,
        check: (OfflineProgressStore, List<String>) -> Unit
    ) {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val path = request.path.orEmpty()
                paths += path
                val body = if (path.startsWith("/Users/Me")) me() else userData
                return if (body == null) {
                    okhttp3.mockwebserver.MockResponse().setResponseCode(500)
                } else {
                    okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
        }
        server.start()
        account = account.copy(server = account.server.copy(url = server.url("/").toString().trimEnd('/')))
        val engineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val entry = app.pictureshow.core.offline.OfflineEntry(
                "episode:original",
                "episode",
                app.pictureshow.core.offline.OfflineMediaKind.episode,
                preset = app.pictureshow.core.offline.OfflinePreset.original,
                labels = app.pictureshow.core.offline.OfflineLabels("An episode"),
                job = app.pictureshow.core.offline.OfflineJob(phase = app.pictureshow.core.offline.OfflinePhase.ready)
            )
            OfflineFiles(compose.activity.noBackupFilesDir).manifest(account.key).apply {
                parentFile!!.mkdirs()
                writeText(
                    app.pictureshow.core.offline.offlineEncodeManifest(
                        app.pictureshow.core.offline.OfflineManifest(entries = listOf(entry))
                    )
                )
            }
            val accounts = AccountStore(
                object : TextPersistence {
                    private var text: String? = null
                    override suspend fun read() = text
                    override suspend fun write(value: String) {
                        text = value
                    }
                }
            )
            kotlinx.coroutines.runBlocking { accounts.remember(account) }
            val engine = OfflineEngine(
                compose.activity,
                accounts,
                { app.pictureshow.core.ClientIdentity("Test", "install-id", "1") },
                object : OfflineForeground {
                    override fun update(activeTitles: List<String>) = Unit
                },
                networkState = { OfflineNetwork.none },
                scope = engineScope
            )
            kotlinx.coroutines.runBlocking { engine.start().join() }
            val store = kotlinx.coroutines.runBlocking { engine.progressStore(account) }
            kotlinx.coroutines.runBlocking {
                store.remember("episode", app.pictureshow.core.offline.OfflineUserData(positionTicks = 100))
                store.viewing("episode", store.newSequence(), 500)
            }
            launch(noNext = true, offlineEngine = engine)
            releaseLookup.complete(Unit)
            if (naturalEnd) {
                reopenAndEnd()
            } else {
                compose.runOnIdle {
                    observedHost.seekTo(30_000)
                    observedHost.finish(reason = "back")
                }
            }
            waitUntil { exits == 1 }
            // Back returns before final reporting finishes. Keep Android Main moving while that finite scope drains.
            waitUntil { paths.isNotEmpty() }
            // Reads run in call order, so one that fails at once comes after everything the viewing started.
            kotlinx.coroutines.runBlocking {
                engine.onlineViewingEnded(account, "episode") { throw java.io.IOException("the end of the queue") }.join()
            }
            check(store, paths)
        } finally {
            engineScope.cancel()
            server.shutdown()
        }
    }

    @Test fun equalLookupFailureAfterRetryExitsOnceWithoutAnotherAttempt() {
        launch(failAlways = true)
        reopenAndEnd()
        waitUntil { exits > 0 }
        compose.runOnIdle {
            assertEquals(lookupsAtEnd + 1, lookups.get(), "End retries once after the initial failure")
            assertEquals(1, exits)
            assertTrue(next.isEmpty())
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
        }
    }

    @Test fun backDuringPendingEndExitsOnceAndNeverStartsTheLateNextEpisode() {
        launch()
        reopenAndEnd()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntil { exits > 0 }
        releaseLookup.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertTrue(next.isEmpty())
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
        }
    }

    @Test fun configurationRotationKeepsOutputPositionAndTrackSession() {
        launch()
        val active = output.player.value
        val count = preparations.get()
        compose.runOnIdle {
            output.player.value!!.seekTo(23_000)
            observedHost.subtitleDelayMs = 300
            configuration = android.content.res.Configuration(configuration).apply {
                orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 800
                screenHeightDp = 400
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(output.player.value === active)
            assertEquals(23_000, output.player.value!!.currentPosition)
            assertEquals(count, preparations.get())
            assertEquals(300, observedHost.subtitleDelayMs)
            assertEquals(0, observedHost.state.subtitleIndex)
        }
    }

    @Test fun backgroundStopPausesWithoutCreatingAnotherSession() {
        launch()
        compose.runOnIdle { output.player.value!!.play() }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(false, output.player.value!!.playWhenReady)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertEquals(false, output.player.value!!.playWhenReady)
            assertEquals(1, preparations.get())
        }
    }

    @Test fun simultaneousSystemBackAndNaturalEndNeverExitsTwiceOrStartsNext() {
        launch(noNext = true)
        releaseLookup.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle {
            output.player.value!!.seekTo(output.player.value!!.duration)
            output.onEnded(decision())
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntil { exits > 0 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertTrue(next.isEmpty())
        }
    }

    @Test fun hostUsesLatestExitCallbackAfterRecomposition() {
        launch()
        compose.runOnIdle { callbackVersion = 2 }
        compose.waitForIdle()
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        waitUntil { exits > 0 }
        assertEquals(2, exits)
    }

    @Test fun groupedTerminalVideoEosReportsPlayedAfterStoppedAndStaysInGroupWithoutLocalNext() {
        val case = terminalVideoCase("sync-completion-reporting.current-decision")
        val requests = mutableListOf<app.pictureshow.core.sync.SyncRequest>()
        val sync = terminalVideoGroup(requests)
        catalogApi.detail = app.pictureshow.core.ClientResult.Success(episode)
        catalogApi.actionBlock = {
            reports += "catalog:played"
            app.pictureshow.core.ClientResult.Success(app.pictureshow.core.ItemUserData(played = true))
        }
        launch(providedSync = sync)
        releaseLookup.complete(Unit) // A real adjacent episode exists: grouped terminal completion must not select it.
        joinTerminalVideoGroup(sync)
        compose.runOnIdle {
            requests.clear()
            reachedEnd.set(true)
            observedHost.applySyncTimeline(null, true, 1.0)
            assertTerminalVideoAdmission(sync)
            output.onEnded(terminalVideoDecision(case))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            // SYN-08: the terminal entry closes the player but keeps membership; the group stops waiting for it.
            assertEquals(true, sync.participating, "Terminal EOS left the group: ${terminalVideoDiagnostic(sync)}")
            assertEquals(0, requests.count { it.operation == "Leave" }, terminalVideoDiagnostic(sync))
            assertEquals(1, exits, "Admitted terminal completion must exit before asynchronous reports drain")
        }
        try {
            waitUntil { reports.contains("catalog:played") }
        } catch (error: Throwable) {
            throw AssertionError(
                "Terminal EOS already exited; Played is missing. reports=${reports.toList()} actions=${catalogApi.actions.toList()}",
                error
            )
        }
        compose.runOnIdle {
            assertEquals(1, reports.count { it == "/Sessions/Playing/Stopped" })
            assertEquals(1, reports.count { it == "catalog:played" })
            assertTrue(
                reports.indexOf("/Sessions/Playing/Stopped") < reports.indexOf("catalog:played"),
                "The actual session's Stopped request must precede Played despite immediate navigation cleanup"
            )
            assertEquals(listOf(Triple("played", "episode", true)), catalogApi.actions.toList())
            assertEquals(listOf("SetIgnoreWait"), requests.filter { it.operation !in setOf("Ping", "Ready", "Buffering") }.map { it.operation })
            assertEquals(null, sync.state.value.departure, "A stay records no departure")
            assertEquals(true, sync.participating)
            assertEquals(1, exits)
            assertTrue(next.isEmpty(), "The local episode lookup cannot advance a terminal grouped viewing")
            assertTerminalVideoVector(case, sync, requests)
        }
    }

    @Test fun staleGroupedVideoDecisionCannotLeaveReportPlayedOrExitTheCurrentViewing() {
        val case = terminalVideoCase("sync-completion-reporting.obsolete-decision")
        val requests = mutableListOf<app.pictureshow.core.sync.SyncRequest>()
        val sync = terminalVideoGroup(requests)
        catalogApi.detail = app.pictureshow.core.ClientResult.Success(episode)
        launch(providedSync = sync)
        releaseLookup.complete(Unit)
        joinTerminalVideoGroup(sync)
        compose.runOnIdle {
            requests.clear()
            observedHost.applySyncTimeline(null, true, 1.0)
            assertTerminalVideoAdmission(sync)
            output.onEnded(terminalVideoDecision(case))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sync.participating, "A rejected session completion cannot Leave its current group")
            assertEquals(null, sync.state.value.departure)
            assertTrue(requests.none { it.operation in setOf("Leave", "NextItem", "Stop", "SetPlaylistItem") })
            assertEquals(0, reports.count { it == "/Sessions/Playing/Stopped" })
            assertTrue(catalogApi.actions.isEmpty(), "The obsolete decision cannot mark the replacement viewing Played")
            assertEquals(0, exits)
            assertTrue(next.isEmpty())
            assertTrue(output.player.value != null, "Rejecting obsolete EOS must retain the current native output")
            assertTerminalVideoVector(case, sync, requests)
        }
    }

    @Test fun groupedEarlierRepeatNoneCurrentDecisionRequestsNextWithoutLocalCompletion() =
        groupedVideoContinuationCase("sync-completion-reporting.earlier-current-decision")

    @Test fun groupedEarlierRepeatNoneObsoleteDecisionCannotRequestNextOrComplete() =
        groupedVideoContinuationCase("sync-completion-reporting.earlier-obsolete-decision")

    @Test fun groupedRepeatOneCurrentDecisionRequestsRestartWithoutLocalCompletion() =
        groupedVideoContinuationCase("sync-completion-reporting.repeat-one-current-decision")

    @Test fun groupedRepeatOneObsoleteDecisionCannotRequestRestartOrComplete() =
        groupedVideoContinuationCase("sync-completion-reporting.repeat-one-obsolete-decision")

    private fun groupedVideoContinuationCase(id: String) {
        val case = terminalVideoCase(id)
        val requests = mutableListOf<app.pictureshow.core.sync.SyncRequest>()
        val sync = terminalVideoGroup(requests)
        catalogApi.detail = app.pictureshow.core.ClientResult.Success(episode)
        launch(providedSync = sync)
        releaseLookup.complete(Unit)
        joinTerminalVideoGroup(
            sync,
            queueCount = case.getValue("queueCount").jsonPrimitive.int,
            currentIndex = case.getValue("currentIndex").jsonPrimitive.int,
            repeatMode = case.getValue("repeatMode").jsonPrimitive.content
        )
        val native = output.player.value
        val source = decision()
        val queue = sync.membership.queue
        var positionBefore = 0L
        compose.runOnIdle {
            requests.clear()
            observedHost.applySyncTimeline(null, true, 1.0)
            assertTerminalVideoAdmission(sync)
            positionBefore = requireNotNull(native).currentPosition
            output.onEnded(terminalVideoDecision(case))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTerminalVideoVector(case, sync, requests)
            assertTrue(sync.participating, "Continuation and obsolete EOS both retain this group membership")
            assertEquals(null, sync.state.value.departure)
            assertEquals(queue, sync.membership.queue, "Only the server can choose or restart the queue entry")
            assertTrue(output.player.value === native, "A request cannot release or replace the current decoder")
            assertTrue(observedHost.state.decision === source)
            assertEquals(positionBefore, native?.currentPosition, "No optimistic local seek/advance/restart")
            assertEquals(true, native?.playWhenReady)
            assertEquals(0, reports.count { it == "/Sessions/Playing/Stopped" })
            assertTrue(catalogApi.actions.isEmpty(), "Nonterminal or rejected EOS cannot mark Played")
            assertEquals(0, exits)
            assertTrue(next.isEmpty(), "The local episode lookup cannot bypass the authoritative group queue")
            assertTrue(requests.none { it.operation in setOf("Leave", "Stop") })
            if (case.getValue("currentDecision").jsonPrimitive.boolean) {
                val control = requests.single { it.operation !in setOf("Ping", "Ready", "Buffering") }
                assertEquals("terminal-entry", control.body?.get("PlaylistItemId")?.jsonPrimitive?.content)
            }
        }
    }

    private fun terminalVideoCase(id: String): JsonObject {
        val roots = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        val file = roots.map { java.io.File(it, "spec/vectors/sync-completion-reporting.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-completion-reporting", suite.getValue("suite").jsonPrimitive.content)
        val cases = suite.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(6, cases.size, "All portable video completion cases must remain exercised")
        assertEquals(
            setOf(
                "sync-completion-reporting.current-decision",
                "sync-completion-reporting.obsolete-decision",
                "sync-completion-reporting.earlier-current-decision",
                "sync-completion-reporting.earlier-obsolete-decision",
                "sync-completion-reporting.repeat-one-current-decision",
                "sync-completion-reporting.repeat-one-obsolete-decision"
            ),
            cases.map { it.getValue("id").jsonPrimitive.content }.toSet()
        )
        assertEquals(setOf("video-completion"), cases.map { it.getValue("operation").jsonPrimitive.content }.toSet())
        val case = cases.single { it.getValue("id").jsonPrimitive.content == id }
        // The two existing terminal tests keep the fixture's documented one-entry/None defaults.
        if (id in setOf("sync-completion-reporting.current-decision", "sync-completion-reporting.obsolete-decision")) {
            assertEquals(1, case.getValue("queueCount").jsonPrimitive.int)
            assertEquals(0, case.getValue("currentIndex").jsonPrimitive.int)
            assertEquals("RepeatNone", case.getValue("repeatMode").jsonPrimitive.content)
        }
        return case
    }

    private fun terminalVideoDecision(case: JsonObject): PlayDecision {
        val current = decision()
        if (case.getValue("currentDecision").jsonPrimitive.boolean) return current
        // Same negotiated fields, different identity: no manual completion callback or session substitute.
        return PlayDecision(
            current.itemId,
            current.mediaSource,
            current.method,
            current.url,
            current.headers,
            current.playSessionId,
            current.transcodeReasons,
            current.ownedLiveStreamId
        )
    }

    private fun assertTerminalVideoVector(case: JsonObject, sync: SyncPlayHost, requests: List<app.pictureshow.core.sync.SyncRequest>) {
        val id = case.getValue("id").jsonPrimitive.content
        assertEquals(case.getValue("expectedMembership").jsonPrimitive.boolean, sync.participating, id)
        assertEquals(
            case.getValue("expectedWire").jsonArray.map { it.jsonPrimitive.content },
            requests.filter { it.operation !in setOf("Ping", "Ready", "Buffering") }.map { it.operation },
            id
        )
        assertEquals(
            case.getValue("expectedReports").jsonArray.map { it.jsonPrimitive.content },
            reports.filter { it in setOf("/Sessions/Playing/Stopped", "catalog:played") },
            id + " ordered completion reports"
        )
        assertEquals(case.getValue("expectedExits").jsonPrimitive.int, exits, id)
        assertEquals(case.getValue("expectedLocalNext").jsonArray.map { it.jsonPrimitive.content }, next.toList(), id)
    }

    /** Real coordinator/renderer binding; only group HTTP responses are recorded, as in the host tests. */
    private fun terminalVideoGroup(requests: MutableList<app.pictureshow.core.sync.SyncRequest>): SyncPlayHost {
        val epoch = 1_762_776_000_000L
        val scope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        terminalGroupScope = scope
        return SyncPlayHost(
            scope,
            object : SyncPlayGateway {
                override suspend fun groups(account: Account) = app.pictureshow.core.ClientResult.Success(
                    listOf(
                        app.pictureshow.core.sync.SyncGroup(
                            "terminal-group",
                            "Evening",
                            "Playing",
                            listOf("Viewer"),
                            epoch * 10_000
                        )
                    )
                )
                override suspend fun access(account: Account) = app.pictureshow.core.ClientResult.Success("CreateAndJoinGroups")
                override suspend fun time(account: Account) =
                    app.pictureshow.core.ClientResult.Success(app.pictureshow.core.sync.SyncUtcTime(epoch, epoch))
                override suspend fun send(account: Account, request: app.pictureshow.core.sync.SyncRequest): Int {
                    requests += request
                    return 204
                }
            },
            wall = { epoch },
            monotonic = { 0L }
        )
    }

    private fun joinTerminalVideoGroup(sync: SyncPlayHost, queueCount: Int = 1, currentIndex: Int = 0, repeatMode: String = "RepeatNone") {
        require(queueCount in 1..2 && currentIndex == 0 && repeatMode in setOf("RepeatNone", "RepeatOne"))
        val playlist = buildList {
            add("""{"ItemId":"episode","PlaylistItemId":"terminal-entry"}""")
            if (queueCount == 2) add("""{"ItemId":"next","PlaylistItemId":"following-entry"}""")
        }.joinToString(",")
        val updatedAt = app.pictureshow.core.sync.syncUtcTimestamp(1_762_776_000_000L)
        compose.runOnIdle {
            assertTrue(observedHost.sync === sync, "The screen must mount the actual production SyncPlaybackBinding")
            sync.bind(account)
            sync.socket(true)
            sync.refresh()
        }
        waitUntil { sync.state.value.policy == "CreateAndJoinGroups" }
        compose.runOnIdle {
            sync.join("terminal-group")
            sync.receive(
                """{"MessageType":"SyncPlayGroupUpdate","Data":{"GroupId":"terminal-group","Type":"GroupJoined","Data":{"GroupId":"terminal-group","GroupName":"Evening","State":"Playing","Participants":["Viewer"],"LastUpdatedAt":"$updatedAt"}}}"""
            )
            sync.receive(
                """{"MessageType":"SyncPlayGroupUpdate","Data":{"GroupId":"terminal-group","Type":"PlayQueue","Data":{"Reason":"NewPlaylist","LastUpdate":"$updatedAt","Playlist":[$playlist],"PlayingItemIndex":$currentIndex,"StartPositionTicks":420000000,"IsPlaying":true,"RepeatMode":"$repeatMode","ShuffleMode":"Sorted"}}}"""
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sync.participating)
            assertTrue(sync.membership.hasSnapshot)
            assertEquals("terminal-entry", sync.membership.queue?.current?.playlistItemId)
            assertTrue(observedHost.onSyncEnded != null)
            assertEquals(sync.membership.generation, terminalOpening?.generation)
            assertEquals("terminal-entry", terminalOpening?.entryId)
            assertEquals(
                null,
                SyncPlayHost::class.java.getDeclaredField("openingEntry").apply { isAccessible = true }.get(sync),
                "The real binding must acknowledge the actual SyncOpen before EOS"
            )
        }
    }

    /** Read-only fixture diagnostics: never clear a pending opening or replace the production callback. */
    private fun terminalVideoDiagnostic(sync: SyncPlayHost): String {
        val renderer = SyncPlayHost::class.java.getDeclaredField("renderer").apply { isAccessible = true }.get(sync) as? SyncRenderer
        val snapshot = renderer?.snapshot()
        val pending = SyncPlayHost::class.java.getDeclaredField("openingEntry").apply { isAccessible = true }.get(sync)
        return "phase=${sync.membership.phase} generation=${sync.membership.generation} snapshot=${sync.membership.hasSnapshot} " +
            "queueMatch=${sync.membership.queue?.current?.itemId == episode.id} openingPending=${pending != null} " +
            "sourceMatch=${renderer?.sourceIdentity === output.player.value} callback=${observedHost.onSyncEnded != null} " +
            "pwr=${snapshot?.playWhenReady} unpaused=${snapshot?.unpaused} ready=${snapshot?.ready} buffering=${snapshot?.buffering} " +
            "seekable=${snapshot?.seekable} durationTicks=${snapshot?.durationTicks} exits=$exits"
    }

    private fun assertTerminalVideoAdmission(sync: SyncPlayHost) {
        val renderer =
            requireNotNull(
                SyncPlayHost::class.java.getDeclaredField("renderer").apply { isAccessible = true }.get(sync) as? SyncRenderer
            )
        val snapshot = renderer.snapshot()
        val diagnostic = terminalVideoDiagnostic(sync)
        println("TERMINAL_VIDEO_FIXTURE $diagnostic")
        assertTrue(sync.participating && sync.membership.hasSnapshot, diagnostic)
        assertEquals("terminal-entry", sync.membership.queue?.current?.playlistItemId, diagnostic)
        assertEquals(episode.id, snapshot.itemId, diagnostic)
        assertTrue(renderer.sourceIdentity === output.player.value, diagnostic)
        assertTrue(observedHost.state.decision === decision(), diagnostic)
        assertEquals(sync.membership.generation, terminalOpening?.generation, diagnostic)
        assertEquals("terminal-entry", terminalOpening?.entryId, diagnostic)
        assertEquals(null, SyncPlayHost::class.java.getDeclaredField("openingEntry").apply { isAccessible = true }.get(sync), diagnostic)
        assertTrue(snapshot.unpaused && snapshot.playWhenReady == true, diagnostic)
        assertTrue(observedHost.onSyncEnded != null, diagnostic)
    }

    private fun launch(
        failFirst: Boolean = false,
        accessible: Boolean = false,
        noNext: Boolean = false,
        failAlways: Boolean = false,
        offlineEngine: OfflineEngine? = null,
        providedSync: SyncPlayHost? = null
    ) {
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val json = when {
                        path.endsWith("/Episodes") -> {
                            lookups.incrementAndGet()
                            if (failAlways ||
                                (failFirst && !reachedEnd.get())
                            ) {
                                return@addHandler respond("{}", HttpStatusCode.ServiceUnavailable)
                            }
                            releaseLookup.await()
                            if (noNext) {
                                """{"Items":[{"Id":"episode","Type":"Episode"}]}"""
                            } else {
                                """{"Items":[{"Id":"episode","Type":"Episode"},{"Id":"next","Name":"Another episode","Type":"Episode"}]}"""
                            }
                        }
                        path.endsWith("/PlaybackInfo") -> {
                            val count = preparations.incrementAndGet()
                            """{"PlaySessionId":"encode-$count","MediaSources":[{"Id":"source","RunTimeTicks":600000000,
                            "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                            "TranscodingUrl":"/Videos/episode/master.m3u8","DefaultSubtitleStreamIndex":0,
                            "MediaStreams":[{"Type":"Video","Index":2,"Codec":"av1","RealFrameRate":29.97},
                            {"Type":"Audio","Index":1,"Codec":"opus"},{"Type":"Subtitle","Index":0,"Codec":"subrip","IsExternal":true}]}]}"""
                        }
                        path == "/Items/episode" -> {
                            // Keep initial episode metadata stable while independently controlling the adjacency response.
                            metadataGate.await()
                            """{"Id":"episode","Name":"An episode","Type":"Episode","SeriesId":"series"}"""
                        }
                        path.startsWith("/MediaSegments") -> """{"Items":[]}"""
                        else -> {
                            reports += path
                            return@addHandler respond("", HttpStatusCode.NoContent)
                        }
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        startKoin {
            modules(
                module {
                    if (providedSync == null) syncPlayFixture() else single { requireNotNull(providedSync) }
                    single { http }
                    single { DeviceIdentityStore(store) }
                    single { PlaybackPreferencesStore(store) }
                    offlineEngine?.let { engine -> single { engine } }
                }
            )
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val catalog = CatalogSession(catalogApi, accounts)
        output = Media3PlaybackOutput(compose.activity, account.server, upstream = DataSource.Factory { WaitingSource() })
        shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setScreenReader(accessible)
        compose.setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                androidx.compose.runtime.LaunchedEffect(providedSync) {
                    providedSync?.let { sync ->
                        sync.opens.collect { request ->
                            assertEquals(episode.id, request.itemId)
                            if (sync.resolved(request, true)) terminalOpening = request
                        }
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalConfiguration provides configuration,
                    LocalSyncOpen provides terminalOpening
                ) {
                    val version = callbackVersion
                    val host = rememberPlaybackHost(
                        account,
                        episode,
                        42_000,
                        catalog,
                        { next += it.id },
                        { exits += version },
                        providedOutput = output
                    )
                    observedHost = host
                    TouchPlayerContent(account, host)
                }
            }
        }
        try {
            waitUntil { (output.player.value?.duration ?: 0) > 0 && lookups.get() > 0 }
        } catch (error: Throwable) {
            val logs = org.robolectric.shadows.ShadowLog.getLogs()
                .filter { it.tag in setOf("PictureshowPlayback", "NextEpisode") }.map { it.msg }
            throw AssertionError(
                "lookups=${lookups.get()} preparations=${preparations.get()} output=${output.player.value} logs=$logs",
                error
            )
        }
        ready()
    }

    // The screen's finite reporting scope uses Android's Main dispatcher, outside Compose's test scheduler.
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun decision(): PlayDecision = Media3PlaybackOutput::class.java.getDeclaredField("current")
        .apply { isAccessible = true }.get(output) as PlayDecision

    private fun ready() = compose.runOnIdle {
        output.player.value!!.pause()
        output.onReady(decision(), output.position(), output.position().milliseconds)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun reopenAndEnd(enableAccessibilityAtEnd: Boolean = false) {
        if (enableAccessibilityAtEnd) {
            compose.runOnIdle {
                shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setScreenReader(true)
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            lookupsAtEnd = lookups.get()
            reachedEnd.set(true)
            output.player.value!!.seekTo(output.player.value!!.duration)
            output.onEnded(decision())
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
