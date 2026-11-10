/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncEvent
import app.pictureshow.core.sync.SyncMembership
import app.pictureshow.core.sync.beginSyncJoin
import app.pictureshow.core.sync.leaveSync
import app.pictureshow.core.sync.parseSyncMessage
import app.pictureshow.core.sync.reduceSyncEvent
import app.pictureshow.core.sync.syncRendererOwnsEntry
import app.pictureshow.core.sync.syncReportsReadiness
import app.pictureshow.core.sync.syncTimestampTicks
import app.pictureshow.core.sync.syncUtcTimestamp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Shared wire traces exercise the production parser and scoped reducer, including failure paths. */
class SyncProtocolTest {
    @Test fun membershipVectors() = runVectors("sync-state")

    @Test fun queueVectors() = runVectors("sync-queue")

    @Test fun vectorRunnerRejectsUnknownOperationsAndAssertions() {
        assertFails { applyEvent(SyncMembership(), JsonObject(mapOf("op" to JsonPrimitive("future-operation")))) }
        assertFails { assertProjection(JsonObject(mapOf("futureAssertion" to JsonPrimitive(true))), SyncMembership(), "unknown") }
        assertFails { checkKeys(JsonObject(mapOf("silentSkip" to JsonPrimitive(true))), setOf("event", "expected")) }
    }

    @Test fun timestampOrderingRetainsOneHundredNanosecondsAndOffsetSigns() {
        val base = assertNotNull(syncTimestampTicks("2026-11-10T12:00:00.0000000Z"))
        assertEquals(base + 1, syncTimestampTicks("2026-11-10T12:00:00.0000001Z"))
        assertEquals(base, syncTimestampTicks("2026-11-10T14:30:00+02:30"))
        assertEquals(base, syncTimestampTicks("2026-11-10T06:30:00-05:30"))
    }

    @Test fun timestampsMatchIndependentCalendarAtEpochLeapAndRangeBoundaries() {
        listOf(
            "0001-01-01T00:00:00.000Z",
            "1900-02-28T23:59:59.999Z",
            "1969-12-31T23:59:59.999Z",
            "1970-01-01T00:00:00.000Z",
            "2000-02-29T00:00:00.001Z",
            "2026-12-31T23:59:59.999Z",
            "9999-12-31T23:59:59.999Z"
        ).forEach { text ->
            val instant = Instant.parse(text)
            assertEquals(instant.epochSecond * 10_000_000L + instant.nano / 100L, syncTimestampTicks(text), text)
            assertEquals(text, syncUtcTimestamp(instant.toEpochMilli()), text)
        }
    }

    @Test fun impossibleAndOverflowingWireNumbersNeverBecomeCommands() {
        val prefix = """
            {"MessageType":"SyncPlayCommand","Data":{
              "GroupId":"g","PlaylistItemId":"e","When":"2026-11-10T12:00:00Z",
              "EmittedAt":"2026-11-10T12:00:00Z","Command":"Seek","PositionTicks":%s
            }}
        """.trimIndent()
        listOf("9223372036854775808", "-1", "1.5", "true", "null", "{}").forEach { value ->
            assertTrue(parseSyncMessage(prefix.format(value)) is SyncEvent.Invalid, value)
        }
        listOf("2026-02-29T00:00:00Z", "2026-11-31T00:00:00Z", "2026-11-10T24:00:00Z", "not-a-time").forEach {
            assertNull(syncTimestampTicks(it), it)
        }
    }

    private fun runVectors(suite: String) {
        val document = Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("sync-vectors/$suite.json")) {
                "Missing executable SyncPlay suite $suite"
            }.bufferedReader().use { it.readText() }
        ).jsonObject
        checkKeys(document, setOf("schemaVersion", "suite", "cases"))
        assertEquals(JsonPrimitive(1), document.getValue("schemaVersion"))
        assertEquals(suite, document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty(), "$suite has no cases")
        assertEquals(cases.size, cases.map { it.jsonObject.getValue("id") }.toSet().size)
        cases.forEach { value ->
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            if (case.getValue("operation").jsonPrimitive.content == "readiness") {
                runReadinessCase(case, id)
                return@forEach
            }
            if (case.getValue("operation").jsonPrimitive.content == "renderer-readiness") {
                runRendererReadinessCase(case, id)
                return@forEach
            }
            checkKeys(case, setOf("id", "operation", "state", "steps"))
            assertEquals("membership", case.getValue("operation").jsonPrimitive.content, id)
            val initial = case.getValue("state").jsonObject
            checkKeys(initial, setOf("generation", "groupId", "phase"))
            var state = SyncMembership(
                generation = initial.getValue("generation").jsonPrimitive.long,
                groupId = initial["groupId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                phase = initial.getValue("phase").jsonPrimitive.content
            )
            val steps = case.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), "$id has no steps")
            steps.forEachIndexed { index, stepValue ->
                val step = stepValue.jsonObject
                checkKeys(step, setOf("event", "expected"))
                state = applyEvent(state, step.getValue("event").jsonObject)
                assertProjection(step.getValue("expected").jsonObject, state, "$id/$index")
            }
        }
    }

    private fun runReadinessCase(case: JsonObject, id: String) {
        checkKeys(case, setOf("id", "operation", "phase", "currentEntryId", "stoppedEntryId", "expected"))
        val actual = syncReportsReadiness(
            case.getValue("phase").jsonPrimitive.content,
            case.getValue("currentEntryId").jsonPrimitive.content,
            case.getValue("stoppedEntryId").takeUnless { it == JsonNull }?.jsonPrimitive?.content
        )
        assertEquals(case.getValue("expected"), JsonPrimitive(actual), id)
    }

    private fun runRendererReadinessCase(case: JsonObject, id: String) {
        checkKeys(case, setOf("id", "operation", "pendingEntryId", "renderedEntryId", "currentEntryId", "expected"))
        val actual = syncRendererOwnsEntry(
            case.getValue("pendingEntryId").takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            case.getValue("renderedEntryId").takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            case.getValue("currentEntryId").jsonPrimitive.content
        )
        assertEquals(case.getValue("expected"), JsonPrimitive(actual), id)
    }

    private fun applyEvent(state: SyncMembership, event: JsonObject): SyncMembership = when (event.getValue("op").jsonPrimitive.content) {
        "join" -> {
            checkKeys(event, setOf("op", "groupId"))
            beginSyncJoin(state, event["groupId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
        }

        "leave" -> {
            checkKeys(event, setOf("op"))
            leaveSync(state)
        }

        "message" -> {
            checkKeys(event, setOf("op", "envelope", "generation"))
            val parsed = parseSyncMessage(event.getValue("envelope").toString())
            if (parsed == null) {
                state
            } else {
                reduceSyncEvent(
                    state,
                    parsed,
                    event["generation"]?.jsonPrimitive?.long ?: state.generation
                )
            }
        }

        else -> error("Unknown SyncPlay vector operation")
    }

    private fun assertProjection(expected: JsonObject, state: SyncMembership, path: String) {
        val actual = summarize(state)
        assertTrue(expected.isNotEmpty(), "$path has no assertions")
        expected.forEach { (key, value) ->
            when (key) {
                "noticeContains" -> assertTrue(state.notice?.contains(value.jsonPrimitive.content) == true, "$path.$key: ${state.notice}")

                else -> {
                    require(key in actual) { "Unknown SyncPlay assertion: $key" }
                    assertEquals(value, actual[key], "$path.$key")
                }
            }
        }
    }

    private fun summarize(state: SyncMembership): JsonObject = buildJsonObject {
        put("generation", state.generation)
        put("groupId", state.groupId?.let(::JsonPrimitive) ?: JsonNull)
        put("phase", state.phase)
        put("hasSnapshot", state.hasSnapshot)
        put("currentEntry", state.queue?.current?.playlistItemId?.let(::JsonPrimitive) ?: JsonNull)
        put("entries", JsonArray(state.queue?.entries.orEmpty().map { JsonPrimitive(it.playlistItemId) }))
        put("items", JsonArray(state.queue?.entries.orEmpty().map { JsonPrimitive(it.itemId) }))
        put("participants", JsonArray(state.group?.participants.orEmpty().map(::JsonPrimitive)))
        put("command", state.command?.command?.let(::JsonPrimitive) ?: JsonNull)
        put("commandTicks", state.command?.positionTicks?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("startTicks", state.queue?.startPositionTicks?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("repeat", state.queue?.repeatMode?.let(::JsonPrimitive) ?: JsonNull)
        put("shuffle", state.queue?.shuffleMode?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun checkKeys(value: JsonObject, allowed: Set<String>) {
        require(value.keys.all { it in allowed }) { "Unknown SyncPlay vector fields: ${value.keys - allowed}" }
    }
}
