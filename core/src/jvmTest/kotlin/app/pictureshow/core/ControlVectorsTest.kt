/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.control.CommandEvent
import app.pictureshow.core.control.CommandState
import app.pictureshow.core.control.ModeEvent
import app.pictureshow.core.control.ModeState
import app.pictureshow.core.control.OwnerEvent
import app.pictureshow.core.control.OwnerState
import app.pictureshow.core.control.QueueEvent
import app.pictureshow.core.control.QueueState
import app.pictureshow.core.control.QueueStoreEvent
import app.pictureshow.core.control.QueueStoreState
import app.pictureshow.core.control.TemporaryEvent
import app.pictureshow.core.control.TemporaryState
import app.pictureshow.core.control.TransferEvent
import app.pictureshow.core.control.TransferState
import app.pictureshow.core.control.reduceCommand
import app.pictureshow.core.control.reduceMode
import app.pictureshow.core.control.reduceOwner
import app.pictureshow.core.control.reduceQueue
import app.pictureshow.core.control.reduceQueueStore
import app.pictureshow.core.control.reduceTemporary
import app.pictureshow.core.control.reduceTransfer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frozen traces: exact typed values at explicit projections, no record/update mode. */
class ControlVectorsTest {
    private val json = Json { encodeDefaults = true }
    private val suites = setOf("session-owner", "session-command", "queue-edit", "queue-order", "playback-mode")

    @Test fun sessionOwner() = run("session-owner")

    @Test fun sessionCommand() = run("session-command")

    @Test fun queueEdit() = run("queue-edit")

    @Test fun queueOrder() = run("queue-order")

    @Test fun playbackMode() = run("playback-mode")

    @Test fun everyPackagedSuiteHasAnExecutorAndUnknownOperationsFail() {
        val directory = File(requireNotNull(javaClass.classLoader.getResource("control-vectors")).toURI())
        val documents = directory.listFiles()!!.filter { it.extension == "json" }.map { json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(suites, documents.map { it.getValue("suite").jsonPrimitive.content }.toSet())
        val ids = documents.flatMap { it.getValue("cases").jsonArray }.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        assertFails { execute("unknown", JsonObject(emptyMap()), JsonObject(emptyMap())) }
        val identity = """{"server":"s","viewer":"v","device":"d","session":"p","generation":"1"}"""
        val owner = json.decodeFromString<app.pictureshow.core.control.ViewingIdentity>(identity)
        assertFails { reduceOwner(OwnerState(owner), OwnerEvent("unknown")) }
        assertFails { reduceCommand(CommandState(owner), CommandEvent("unknown")) }
        assertFails { reduceQueue(QueueState("s", "v"), QueueEvent("unknown")) }
        assertFails { reduceQueueStore(QueueStoreState(QueueState("s", "v")), QueueStoreEvent("unknown")) }
        assertFails { reduceMode(ModeState(), ModeEvent("unknown")) }
    }

    @Test fun decimalInt64RoundTripsExactlyAndRejectsJsonNumbers() {
        val intent = app.pictureshow.core.control.ControlIntent(ticks = 9_007_199_254_740_993)
        val encoded = json.encodeToString(intent)
        assertEquals(intent, json.decodeFromString<app.pictureshow.core.control.ControlIntent>(encoded))
        assertTrue(encoded.contains("\"ticks\":\"9007199254740993\""))
        assertFails { json.decodeFromString<app.pictureshow.core.control.ControlIntent>("""{"ticks":9007199254740993}""") }
        assertFails { json.decodeFromString<app.pictureshow.core.control.ControlIntent>("""{"ticks":"9223372036854775808"}""") }
    }

    private fun run(suite: String) {
        val document = json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("control-vectors/$suite.json")).bufferedReader().use { it.readText() }
        ).jsonObject
        assertEquals("1", document.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(suite, document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { value ->
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val operation = case.getValue("operation").jsonPrimitive.content
            var state = case.getValue("state")
            val steps = case.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), id)
            steps.forEachIndexed { index, entry ->
                val step = entry.jsonObject
                val output = execute(operation, state, step.getValue("event"))
                val expected = step.getValue("expected").jsonObject
                assertTrue(expected.isNotEmpty(), "$id/$index has no assertions")
                assertProjection(expected, output, "$id/$index")
                state = if (operation == "queue") output.jsonObject.getValue("queue") else output
            }
        }
    }

    private fun assertProjection(expected: JsonElement, actual: JsonElement, path: String) {
        if (expected is JsonObject) {
            assertTrue(actual is JsonObject, path)
            expected.forEach { (key, value) -> assertProjection(value, actual.getValue(key), "$path.$key") }
        } else {
            assertEquals(expected, actual, path)
        }
    }

    private fun execute(operation: String, state: JsonElement, event: JsonElement): JsonElement = when (operation) {
        "owner" -> json.encodeToJsonElement(
            reduceOwner(json.decodeFromJsonElement<OwnerState>(state), json.decodeFromJsonElement<OwnerEvent>(event))
        )
        "temporary" -> json.encodeToJsonElement(
            reduceTemporary(json.decodeFromJsonElement<TemporaryState>(state), json.decodeFromJsonElement<TemporaryEvent>(event))
        )
        "command" -> json.encodeToJsonElement(
            reduceCommand(json.decodeFromJsonElement<CommandState>(state), json.decodeFromJsonElement<CommandEvent>(event))
        )
        "queue" -> json.encodeToJsonElement(
            reduceQueue(json.decodeFromJsonElement<QueueState>(state), json.decodeFromJsonElement<QueueEvent>(event))
        )
        "store" -> json.encodeToJsonElement(
            reduceQueueStore(json.decodeFromJsonElement<QueueStoreState>(state), json.decodeFromJsonElement<QueueStoreEvent>(event))
        )
        "transfer" -> json.encodeToJsonElement(
            reduceTransfer(json.decodeFromJsonElement<TransferState>(state), json.decodeFromJsonElement<TransferEvent>(event))
        )
        "mode" -> json.encodeToJsonElement(
            reduceMode(json.decodeFromJsonElement<ModeState>(state), json.decodeFromJsonElement<ModeEvent>(event))
        )
        else -> error("Unknown control vector operation: $operation")
    }
}
