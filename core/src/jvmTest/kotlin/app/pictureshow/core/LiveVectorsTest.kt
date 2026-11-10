/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveGuideCache
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.LiveTvClient
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.livetv.editSchedule
import app.pictureshow.core.livetv.guidePrograms
import app.pictureshow.core.livetv.recordingStatus
import app.pictureshow.core.livetv.sortChannels
import app.pictureshow.core.livetv.timerDraft
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** JSON is the shared contract. Every expected value is checked against production rules and real client calls. */
class LiveVectorsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun guideVectors() = run("live-guide")

    @Test fun dvrVectors() = run("live-dvr")

    private fun run(suite: String) = runTest {
        val resource = requireNotNull(javaClass.classLoader.getResource("live-vectors/$suite.json"))
        val document = json.parseToJsonElement(resource.readText()).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(suite, document.text("suite"))
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        val ids = mutableSetOf<String>()
        cases.forEach { element ->
            val vector = element.jsonObject
            val id = vector.text("id")
            assertTrue(ids.add(id), "Duplicate case $id")
            val input = vector.getValue("input").jsonObject
            val actual = execute(vector.text("operation"), input)
            assertEquals(vector.getValue("expected"), actual, id)
        }
    }

    private suspend fun TestScope.execute(operation: String, input: JsonObject): JsonElement = when (operation) {
        "guide" -> JsonArray(
            guidePrograms(
                json.decodeFromJsonElement(input.getValue("programs")),
                input.getValue("channels").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                input.text("start"),
                input.text("end")
            ).map { JsonPrimitive(it.id) }
        )
        "delete-trace" -> deleteTrace(input)
        "guide-metadata" -> JsonArray(
            guidePrograms(
                json.decodeFromJsonElement(input.getValue("programs")),
                input.getValue("channels").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                input.text("start"),
                input.text("end")
            ).map { program ->
                buildJsonObject {
                    put("id", program.id)
                    put("name", program.name)
                    put("start", program.startDate)
                    put("end", program.endDate)
                }
            }
        )
        "sort" -> JsonArray(
            sortChannels(json.decodeFromJsonElement<List<LiveChannel>>(input.getValue("channels"))).map {
                JsonPrimitive(it.id)
            }
        )
        "tune" -> JsonPrimitive(canTune(json.decodeFromJsonElement(input.getValue("program")), input.getValue("now").jsonPrimitive.long))
        "draft" -> timerDraft(
            LiveSchedule(input.getValue("defaults").jsonObject),
            json.decodeFromJsonElement(input.getValue("program")),
            input.getValue("series").jsonPrimitive.boolean
        ).fields
        "edit" -> try {
            editSchedule(
                LiveSchedule(input.getValue("original").jsonObject),
                input.getValue("edits").jsonObject,
                input.getValue("series").jsonPrimitive.boolean
            ).fields
        } catch (_: IllegalArgumentException) {
            buildJsonObject { put("error", true) }
        }
        "statuses" -> JsonArray(input.getValue("statuses").jsonArray.map { JsonPrimitive(recordingStatus(it.jsonPrimitive.content)) })
        "cache" -> {
            val original = LiveGuideCache(
                input.text("account"),
                input.getValue("generation").jsonPrimitive.long,
                input.getValue("channels").jsonArray.map { it.jsonPrimitive.content }.toSet()
            )
            val after = original.page(
                input.text("responseAccount"),
                input.getValue("responseGeneration").jsonPrimitive.long,
                json.decodeFromJsonElement(input.getValue("programs")),
                input.getValue("fetchedAt").jsonPrimitive.long
            ).copy(
                offline = input.getValue("offline").jsonPrimitive.boolean,
                refreshing = input["refreshing"]?.jsonPrimitive?.boolean ?: false
            )
            buildJsonObject {
                put("ids", JsonArray(after.programs.map { JsonPrimitive(it.id) }))
                val candidate = json.decodeFromJsonElement<LiveProgram>(input.getValue("candidate"))
                put("actionAllowed", after.actionAllowed(candidate, input.getValue("now").jsonPrimitive.long))
            }
        }
        "create-trace" -> createTrace(input)
        else -> error("Unhandled Live TV vector operation $operation")
    }

    private suspend fun TestScope.createTrace(input: JsonObject): JsonObject {
        var posts = 0
        var stored: JsonObject? = input["stored"]?.jsonObject
        val methods = mutableListOf<String>()
        val series = input.getValue("series").jsonPrimitive.boolean
        val path = if (series) "/LiveTv/SeriesTimers" else "/LiveTv/Timers"
        val draft = input.getValue("draft").jsonObject
        val client = LiveTvClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            val route = request.url.encodedPath
                            methods += "${request.method.value} $route"
                            val body = when {
                                route == "/Users/viewer" -> JsonObject(mapOf("Policy" to input.getValue("policy"))).toString()
                                request.method.value == "GET" && route == path -> JsonObject(
                                    mapOf("Items" to JsonArray(listOfNotNull(stored)))
                                ).toString()
                                route == "/LiveTv/Programs/p" -> JsonObject(
                                    draft + mapOf("Id" to JsonPrimitive("p"), "IsSeries" to JsonPrimitive(true)) +
                                        (input["program"]?.jsonObject ?: emptyMap())
                                ).toString()
                                route == "/LiveTv/Channels/c" -> """{"Id":"c","Name":"Channel"}"""
                                request.method.value == "POST" && route == path -> {
                                    posts++
                                    if (input.getValue("applied").jsonPrimitive.boolean) {
                                        stored = JsonObject(draft + mapOf("Id" to JsonPrimitive("saved"), "Status" to JsonPrimitive("New")))
                                    }
                                    if (input.getValue(
                                            "disconnect"
                                        ).jsonPrimitive.boolean
                                    ) {
                                        throw IllegalStateException("Fixture connection lost")
                                    }
                                    return@addHandler respond("", HttpStatusCode.NoContent)
                                }
                                else -> error("Unexpected fixture request ${request.method.value} $route")
                            }
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }
                )
            ),
            ClientIdentity("Fixture", "fixture-device"),
            nowMillis = { 1_893_456_000_000L }
        )
        val account = Account(Server("fixture", "Fixture", "https://media.example.test", "12.0.0"), User("viewer", "Viewer"), "fixture")
        val first = client.createTimer(account, LiveSchedule(draft), series)
        val second = client.createTimer(account, LiveSchedule(draft), series)
        return buildJsonObject {
            put("first", first.outcome)
            put("second", second.outcome)
            put("postCount", posts)
            put("posts", JsonArray(methods.filter { it.startsWith("POST") }.map(::JsonPrimitive)))
            if (input["reportSchedule"]?.jsonPrimitive?.boolean == true) {
                put("firstRuleId", first.schedule?.id)
                put("secondRuleId", second.schedule?.id)
            }
        }
    }

    private suspend fun TestScope.deleteTrace(input: JsonObject): JsonElement {
        val methods = mutableListOf<String>()
        var unlisted = false
        var fileDeleted = false
        val client = LiveTvClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            val route = request.url.encodedPath
                            val method = request.method.value
                            methods += "$method $route"
                            var status = HttpStatusCode.OK
                            val body = when {
                                route == "/Users/viewer" -> JsonObject(mapOf("Policy" to input.getValue("policy"))).toString()
                                method == "DELETE" -> {
                                    unlisted = true
                                    if (route == "/Items/recording" &&
                                        !input.getValue("retainFile").jsonPrimitive.boolean
                                    ) {
                                        fileDeleted = true
                                    }
                                    status = HttpStatusCode.NoContent
                                    ""
                                }
                                route == "/LiveTv/Recordings/recording" || route == "/Items/recording" -> {
                                    if ((route.startsWith("/LiveTv/") && unlisted) || (route.startsWith("/Items/") && fileDeleted)) {
                                        status = HttpStatusCode.NotFound
                                    }
                                    buildJsonObject {
                                        put("Id", "recording")
                                        put("CanDelete", input.getValue("canDelete"))
                                    }.toString()
                                }
                                else -> error("Unexpected delete fixture $method $route")
                            }
                            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }
                )
            ),
            ClientIdentity("Fixture", "fixture-device")
        )
        val account = Account(Server("fixture", "Fixture", "https://media.example.test", "12.0.0"), User("viewer", "Viewer"), "fixture")
        val result = client.deleteRecording(account, "recording")
        return buildJsonObject {
            put("outcome", result.outcome)
            put("fileDeleted", fileDeleted)
            put("requests", JsonArray(methods.map(::JsonPrimitive)))
        }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
