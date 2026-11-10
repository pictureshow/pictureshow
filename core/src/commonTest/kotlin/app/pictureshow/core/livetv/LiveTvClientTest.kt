/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.livetv

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LiveTvClientTest {
    private val account = Account(Server("s", "Fixture", "https://media.example.test", "12.0.0"), User("viewer", "Viewer"), "fixture-token")
    private val draft = LiveSchedule(
        Json.parseToJsonElement(
            """{"ProgramId":"p","ChannelId":"c","Name":"Program","StartDate":"2030-01-01T00:00:00Z","EndDate":"2030-01-01T01:00:00Z","Priority":1,"PrePaddingSeconds":0,"ProviderField":"kept"}"""
        ) as JsonObject
    )
    private var management = true
    private var access = true
    private var userData = true
    private var deletion = true
    private var writeStatus = HttpStatusCode.NoContent
    private var failAfterWrite = false
    private var applyCreate = true
    private var cancelAfterWrite = false
    private var ignoreUpdate = false
    private var expiredProgram = false
    private var linkedSeriesId: String? = null
    private var deniedChannel = false
    private var itemCanDelete = true
    private var deletedRecording = false
    private var deletedFile = false
    private var ignoreFileDelete = false
    private var stored: JsonObject? = null
    private var recordedFields: JsonObject? = null
    private val requests = mutableListOf<String>()
    private val query = mutableListOf<Map<String, List<String>>>()

    private fun TestScope.client(pending: LivePendingWrites = MemoryLivePendingWrites()) = LiveTvClient(
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val path = request.url.encodedPath
                        val method = request.method.value
                        requests += "$method $path"
                        query += request.url.parameters.entries().associate { it.key to it.value }
                        assertEquals("viewer", request.url.parameters["userId"])
                        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().contains("fixture-token"))
                        var code = HttpStatusCode.OK
                        val body = when {
                            path == "/Users/viewer" ->
                                """{"Policy":{"EnableLiveTvAccess":$access,"EnableLiveTvManagement":$management,""" +
                                    """"EnableContentDeletion":$deletion,"EnableUserPreferenceAccess":$userData}}"""
                            method == "GET" && path in setOf("/LiveTv/Timers", "/LiveTv/SeriesTimers") ->
                                "{\"Items\":[${stored?.toString().orEmpty()}]}"
                            method == "GET" && path.endsWith("/saved") -> stored?.toString() ?: "{}"
                            method == "POST" && path in setOf("/LiveTv/Timers", "/LiveTv/SeriesTimers") -> {
                                recordedFields = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                                if (applyCreate) {
                                    stored =
                                        JsonObject(
                                            recordedFields!! + mapOf("Id" to JsonPrimitive("saved"), "Status" to JsonPrimitive("New"))
                                        )
                                }
                                code = writeStatus
                                if (cancelAfterWrite) throw CancellationException("cancelled after submission")
                                if (failAfterWrite) throw IllegalStateException("connection gone after submission")
                                ""
                            }
                            method == "POST" && path.endsWith("/saved") -> {
                                recordedFields = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                                if (!ignoreUpdate) stored = recordedFields
                                code = writeStatus
                                ""
                            }
                            method == "DELETE" -> {
                                // Jellyfin's Live TV delete can remove metadata without touching the file.
                                if (path.startsWith("/LiveTv/Recordings/")) deletedRecording = true
                                if (path == "/Items/recording") {
                                    deletedRecording = true
                                    if (!ignoreFileDelete) deletedFile = true
                                }
                                code = writeStatus
                                if (writeStatus.value in 200..299) stored = null
                                ""
                            }
                            path == "/LiveTv/Programs/p" -> JsonObject(
                                draft.fields + mapOf(
                                    "Id" to JsonPrimitive("p"),
                                    "IsSeries" to JsonPrimitive(true),
                                    "SeriesTimerId" to JsonPrimitive(linkedSeriesId),
                                    "EndDate" to
                                        (if (expiredProgram) JsonPrimitive("2020-01-01T01:00:00Z") else draft.fields.getValue("EndDate"))
                                )
                            ).toString()
                            path == "/LiveTv/Channels/c" -> {
                                if (deniedChannel) code = HttpStatusCode.Forbidden
                                """{"Id":"c","Name":"Channel","UserData":{"IsFavorite":true}}"""
                            }
                            path == "/LiveTv/Recordings/recording" -> {
                                if (deletedRecording) code = HttpStatusCode.NotFound
                                """{"Id":"recording","CanDelete":$itemCanDelete}"""
                            }
                            path == "/Items/recording" -> {
                                if (deletedFile) code = HttpStatusCode.NotFound
                                """{"Id":"recording","CanDelete":$itemCanDelete}"""
                            }
                            path == "/LiveTv/Channels" ->
                                """{"Items":[{"Id":"c","Name":"Channel","ChannelNumber":"2.10"}],""" +
                                    """"TotalRecordCount":1,"StartIndex":200}"""
                            path == "/LiveTv/Programs" -> """{"Items":[],"TotalRecordCount":0}"""
                            path == "/LiveTv/Info" -> """{"IsEnabled":false,"Services":[]}"""
                            else -> "{}"
                        }
                        respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        ),
        ClientIdentity("Fixture", "device"),
        pending,
        nowMillis = { 1_893_456_000_000L }
    )

    @Test fun guideWireUsesOverlapBoundsAndTheCurrentViewerAndPaging() = runTest {
        val live = client()
        val channels = assertIs<ClientResult.Success<LivePage<LiveChannel>>>(live.channels(account, 200, 100, null, true)).value
        assertEquals("2.10", channels.items.single().number)
        assertEquals(200, channels.startIndex)
        assertFalse("searchTerm" in query.single())
        assertEquals(listOf("true"), query.single()["isFavorite"])
        live.programs(account, listOf("c"), "2030-01-01T00:00:00Z", "2030-01-01T02:00:00Z", 100, 100)
        assertEquals(listOf("2030-01-01T00:00:00Z"), query.last()["minEndDate"])
        assertEquals(listOf("2030-01-01T02:00:00Z"), query.last()["maxStartDate"])
        assertFalse("minStartDate" in query.last())
        assertEquals(listOf("100"), query.last()["startIndex"])
    }

    @Test fun channelSearchIsAppliedToAuthorizedPagesInsteadOfAnUnsupportedServerQuery() = runTest {
        val page = assertIs<ClientResult.Success<LivePage<LiveChannel>>>(client().channels(account, search = "chan")).value
        assertEquals(listOf("c"), page.items.map { it.id })
        assertEquals(1, page.totalRecordCount)
        assertFalse(query.any { "searchTerm" in it })
    }

    @Test fun disabledInfoRemainsDifferentFromAnEmptyGuideAndAPermissionDenial() = runTest {
        assertFalse(assertIs<ClientResult.Success<LiveInfo>>(client().info(account)).value.enabled)
        access = false
        assertEquals("denied", client().createTimer(account, draft).outcome)
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun readonlyViewerCannotSubmitAnyRecordingWritesEvenWithAnOldForm() = runTest {
        management = false
        val live = client()
        assertEquals("denied", live.createTimer(account, draft).outcome)
        assertEquals("denied", live.updateTimer(account, LiveSchedule(JsonObject(draft.fields + ("Id" to JsonPrimitive("saved"))))).outcome)
        assertEquals("denied", live.cancelTimer(account, "saved").outcome)
        assertFalse(requests.any { it.startsWith("POST") || it.startsWith("DELETE") })
        assertEquals(3, requests.count { it == "GET /Users/viewer" })
    }

    @Test fun ambiguousCreateIsReconciledWithoutDuplicatingTheAcceptedTimer() = runTest {
        failAfterWrite = true
        val live = client()
        assertEquals("ok", live.createTimer(account, draft).outcome)
        assertEquals("ok", live.createTimer(account, draft).outcome)
        assertEquals(1, requests.count { it == "POST /LiveTv/Timers" })
        assertEquals("kept", (recordedFields!!["ProviderField"] as JsonPrimitive).content)
    }

    @Test fun anUnresolvedCreateCannotBeBlindlyRetriedEvenByANewClientUsingItsJournal() = runTest {
        val pending = MemoryLivePendingWrites()
        failAfterWrite = true
        applyCreate = false
        assertEquals("unknown", client(pending).createTimer(account, draft).outcome)
        assertEquals("unknown", client(pending).createTimer(account, draft).outcome)
        assertEquals(1, requests.count { it == "POST /LiveTv/Timers" })
    }

    @Test fun cancellationAlsoLeavesAnUnresolvedSubmissionJournaled() = runTest {
        val pending = MemoryLivePendingWrites()
        cancelAfterWrite = true
        applyCreate = false
        try {
            client(pending).createTimer(account, draft)
            error("Cancellation must propagate")
        } catch (_: CancellationException) {
            // A parent navigation cancellation must not be translated into a success or an ordinary API failure.
        }
        cancelAfterWrite = false
        assertEquals("unknown", client(pending).createTimer(account, draft).outcome)
        assertEquals(1, requests.count { it == "POST /LiveTv/Timers" })
    }

    @Test fun anExplicitRefusalMayBeCorrectedAndRetriedAfterRefreshingPolicy() = runTest {
        writeStatus = HttpStatusCode.Forbidden
        applyCreate = false
        val live = client()
        assertEquals("denied", live.createTimer(account, draft).outcome)
        writeStatus = HttpStatusCode.NoContent
        applyCreate = true
        assertEquals("ok", live.createTimer(account, draft).outcome)
        assertEquals(2, requests.count { it == "POST /LiveTv/Timers" })
    }

    @Test fun seriesLinkReusesTheExactRuleCreatedFromAnotherEpisodeWithoutPosting() = runTest {
        linkedSeriesId = "saved"
        stored = JsonObject(
            draft.fields + mapOf(
                "Id" to JsonPrimitive("saved"),
                "ProgramId" to JsonPrimitive("different-episode"),
                "ChannelId" to JsonPrimitive("original-channel"),
                "StartDate" to JsonPrimitive("2029-01-01T00:00:00Z"),
                "EndDate" to JsonPrimitive("2029-01-01T01:00:00Z")
            )
        )
        val result = client().createTimer(account, draft, series = true)
        assertEquals("ok", result.outcome)
        assertEquals("saved", result.schedule!!.id)
        assertEquals("different-episode", result.schedule!!.programId)
        assertTrue(requests.contains("GET /LiveTv/Programs/p"))
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun seriesLinkWithDifferentOptionsReturnsTheReadableRuleWithoutCreatingAnother() = runTest {
        linkedSeriesId = "saved"
        stored = JsonObject(
            draft.fields + mapOf(
                "Id" to JsonPrimitive("saved"),
                "ProgramId" to JsonPrimitive("different-episode"),
                "Priority" to JsonPrimitive(99)
            )
        )
        val result = client().createTimer(account, draft, series = true)
        assertEquals("failed", result.outcome)
        assertEquals("saved", result.schedule!!.id)
        assertTrue(result.message.orEmpty().contains("series"))
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun anUnreadableLinkedSeriesRuleCannotBeBlindlyRecreated() = runTest {
        linkedSeriesId = "not-returned"
        val unrelated = JsonObject(draft.fields + mapOf("Id" to JsonPrimitive("unrelated")))
        for (readback in listOf(null, unrelated)) {
            stored = readback
            val result = client().createTimer(account, draft, series = true)
            assertEquals("failed", result.outcome)
            assertEquals(null, result.schedule)
            assertTrue(result.message.orEmpty().contains("existing series rule"))
            assertFalse(requests.any { it.startsWith("POST") })
        }
    }

    @Test fun seriesCreationAndOccurrenceCancellationUseDifferentResources() = runTest {
        val live = client()
        assertEquals("ok", live.createTimer(account, draft, series = true).outcome)
        assertTrue(requests.contains("POST /LiveTv/SeriesTimers"))
        assertEquals("ok", live.cancelTimer(account, "saved", series = false).outcome)
        assertTrue(requests.contains("DELETE /LiveTv/Timers/saved"))
        assertFalse(requests.contains("DELETE /LiveTv/SeriesTimers/saved"))
    }

    @Test fun editRebasesOnlyDirtyFieldsOnFreshServerValuesAndReadsBack() = runTest {
        val original = LiveSchedule(JsonObject(draft.fields + ("Id" to JsonPrimitive("saved"))))
        val desired = editSchedule(original, JsonObject(mapOf("Priority" to JsonPrimitive(9))), false)
        stored = JsonObject(original.fields + ("ProviderField" to JsonPrimitive("changed elsewhere")))
        val result = client().updateTimer(account, desired)
        assertEquals("ok", result.outcome)
        assertEquals(JsonPrimitive("changed elsewhere"), recordedFields!!["ProviderField"])
        assertEquals(JsonPrimitive(9), result.schedule!!.fields["Priority"])
        assertEquals(2, requests.count { it == "GET /LiveTv/Timers/saved" })
    }

    @Test fun serverIgnoringAnEditIsNotReportedAsSaved() = runTest {
        val original = LiveSchedule(JsonObject(draft.fields + ("Id" to JsonPrimitive("saved"))))
        stored = original.fields
        ignoreUpdate = true
        val desired = editSchedule(original, JsonObject(mapOf("Priority" to JsonPrimitive(9))), false)
        val result = client().updateTimer(account, desired)
        assertEquals("failed", result.outcome)
        assertEquals(JsonPrimitive(1), result.schedule!!.fields["Priority"])
    }

    @Test fun aFailedWriteLeavesTheOriginalScheduleReadableAndRetryable() = runTest {
        val original = LiveSchedule(JsonObject(draft.fields + ("Id" to JsonPrimitive("saved"))))
        stored = original.fields
        ignoreUpdate = true
        writeStatus = HttpStatusCode.BadRequest
        val desired = editSchedule(original, JsonObject(mapOf("Priority" to JsonPrimitive(9))), false)
        val live = client()
        assertEquals("failed", live.updateTimer(account, desired).outcome)
        assertEquals(original.fields, assertIs<ClientResult.Success<List<LiveSchedule>>>(live.timers(account)).value.single().fields)
    }

    @Test fun favoriteWritesRefreshTheAuthoritativeChannelInsteadOfOptimisticallyToggling() = runTest {
        val result = assertIs<ClientResult.Success<LiveChannel>>(client().favorite(account, "c", true)).value
        assertTrue(result.userData.isFavorite)
        assertEquals(listOf("GET /Users/viewer", "POST /Users/viewer/FavoriteItems/c", "GET /LiveTv/Channels/c"), requests)
    }

    @Test fun changedUserDataRightsRefuseFavoritesBeforeMutation() = runTest {
        userData = false
        assertIs<ClientResult.Failure>(client().favorite(account, "c", true))
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun freshProgramOrChannelRevocationPreventsSubmittingAStaleEditor() = runTest {
        expiredProgram = true
        assertEquals("failed", client().createTimer(account, draft).outcome)
        expiredProgram = false
        deniedChannel = true
        assertEquals("denied", client().createTimer(account, draft).outcome)
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun anExistingTimerWithDifferentOptionsIsReadableButNotReportedAsSaved() = runTest {
        stored = JsonObject(draft.fields + mapOf("Id" to JsonPrimitive("saved"), "Priority" to JsonPrimitive(99)))
        val result = client().createTimer(account, draft)
        assertEquals("failed", result.outcome)
        assertEquals("saved", result.schedule!!.id)
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test fun perItemDeletionPermissionAllowsAFolderGrantWithoutGlobalDeleteRights() = runTest {
        deletion = false
        itemCanDelete = true
        assertEquals("ok", client().deleteRecording(account, "recording").outcome)
        assertTrue(requests.contains("DELETE /Items/recording"))
    }

    @Test fun aPerItemDeleteRefusalOverridesGlobalDeletionRights() = runTest {
        deletion = true
        itemCanDelete = false
        assertEquals("denied", client().deleteRecording(account, "recording").outcome)
        assertFalse(requests.any { it.startsWith("DELETE") })
    }

    @Test fun deletingARecordingCannotAccidentallyCancelATimer() = runTest {
        assertEquals("ok", client().deleteRecording(account, "recording").outcome)
        assertTrue(requests.contains("DELETE /Items/recording"))
        assertFalse(requests.any { it.contains("/Timers") })
    }

    @Test fun recordingDeletionMustRemoveTheContentFileInsteadOfOnlyUnlistingTheRecording() = runTest {
        management = false // Content deletion permission is separate from DVR management.
        assertEquals("ok", client().deleteRecording(account, "recording").outcome)
        assertTrue(deletedFile, "Live TV metadata deletion is not recorded-file deletion")
        assertTrue(requests.contains("DELETE /Items/recording"))
        assertTrue(requests.contains("GET /Items/recording"))
        assertFalse(requests.contains("DELETE /LiveTv/Recordings/recording"))
    }

    @Test fun acceptedContentDeleteThatLeavesTheItemCannotBeCalledDeleted() = runTest {
        ignoreFileDelete = true
        assertEquals("unknown", client().deleteRecording(account, "recording").outcome)
        assertFalse(deletedFile)
    }
}
