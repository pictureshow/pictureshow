/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.control.RemoteCommand
import app.pictureshow.core.control.RemoteControllerEvent
import app.pictureshow.core.control.RemoteControllerState
import app.pictureshow.core.control.RemoteReceiveEvent
import app.pictureshow.core.control.RemoteReceiveState
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.parseRemoteMessage
import app.pictureshow.core.control.reduceRemoteController
import app.pictureshow.core.control.reduceRemoteReceive
import app.pictureshow.core.control.remoteAdvertisedCommands
import app.pictureshow.core.control.remoteCapabilitiesBody
import app.pictureshow.core.control.remoteRelayEnforcesRights
import app.pictureshow.core.control.remoteRequest
import app.pictureshow.core.control.remoteSocketUrl
import app.pictureshow.core.control.remoteTargets
import app.pictureshow.core.control.remoteTargetsPath
import app.pictureshow.core.control.remoteTrackSelected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Frozen remote traces. Apple and web port them; a suite no platform runs is a contract nobody holds. */
class RemoteVectorsTest {
    private val json = Json { encodeDefaults = true }

    @Test fun targets() = run("remote-targets")

    @Test fun wire() = run("remote-wire")

    @Test fun receive() = run("remote-receive")

    @Test fun reconnect() = run("remote-reconnect")

    @Test fun unknownOperationsFail() {
        assertFails { execute("unknown", JsonObject(emptyMap()), JsonObject(emptyMap())) }
        assertFails { reduceRemoteReceive(RemoteReceiveState("s", "v"), RemoteReceiveEvent("unknown")) }
        assertFails { reduceRemoteController(RemoteControllerState("s", "v"), RemoteControllerEvent("unknown")) }
    }

    private fun run(suite: String) {
        val document = json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("remote-vectors/$suite.json")).bufferedReader().use { it.readText() }
        ).jsonObject
        assertEquals(suite, document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        assertEquals(cases.size, cases.map { it.jsonObject.getValue("id") }.toSet().size)
        cases.forEach { value ->
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val operation = case.getValue("operation").jsonPrimitive.content
            var state = case.getValue("state")
            case.getValue("steps").jsonArray.forEachIndexed { index, entry ->
                val step = entry.jsonObject
                val output = execute(operation, state, step.getValue("event").jsonObject)
                val expected = step.getValue("expected").jsonObject
                assertTrue(expected.isNotEmpty(), "$id/$index has no assertions")
                assertProjection(expected, output, "$id/$index")
                state = output
            }
        }
    }

    private fun assertProjection(expected: JsonElement, actual: JsonElement, path: String) {
        if (expected is JsonObject) {
            assertTrue(actual is JsonObject, "$path expected object, was $actual")
            expected.forEach { (key, value) -> assertProjection(value, actual[key] ?: JsonNull, "$path.$key") }
        } else {
            assertEquals(expected, actual, path)
        }
    }

    private fun execute(operation: String, state: JsonElement, event: JsonObject): JsonElement = when (operation) {
        "targets" -> buildJsonObject {
            val o = state.jsonObject
            val rows = remoteTargets(
                o.getValue("sessions") as JsonArray,
                o.getValue("viewer").jsonPrimitive.content,
                o.getValue("selfDevice").jsonPrimitive.content
            )
            put("targets", json.encodeToJsonElement(rows))
        }
        "track-selected" -> buildJsonObject {
            val o = state.jsonObject
            put("selected", remoteTrackSelected(o.getValue("reported").jsonPrimitive.intOrNull, o.getValue("offered").jsonPrimitive.int))
        }
        "request" -> json.encodeToJsonElement(
            remoteRequest(
                json.decodeFromJsonElement<RemoteTarget>(state.jsonObject.getValue("target")),
                json.decodeFromJsonElement<RemoteCommand>(event)
            )
        )
        "parse" -> json.encodeToJsonElement(parseRemoteMessage(event.getValue("text").jsonPrimitive.content))
        "capabilities" -> remoteCapabilitiesBody(
            event.getValue("enabled").jsonPrimitive.boolean,
            event.getValue("commands").jsonArray.map { it.jsonPrimitive.content }
        )
        "advertised" -> buildJsonObject {
            put("commands", json.encodeToJsonElement(remoteAdvertisedCommands(event.getValue("fixedVolume").jsonPrimitive.boolean)))
        }
        "relay-rights" -> buildJsonObject {
            put("enforces", remoteRelayEnforcesRights((event["version"] as? JsonPrimitive)?.contentOrNull))
        }
        "socket-url" -> buildJsonObject {
            put("url", remoteSocketUrl(event.getValue("server").jsonPrimitive.content))
        }
        "targets-path" -> buildJsonObject { put("path", remoteTargetsPath(event.getValue("viewer").jsonPrimitive.content)) }
        "receive" -> json.encodeToJsonElement(
            reduceRemoteReceive(
                json.decodeFromJsonElement<RemoteReceiveState>(state),
                json.decodeFromJsonElement<RemoteReceiveEvent>(event)
            )
        )
        "controller" -> json.encodeToJsonElement(
            reduceRemoteController(
                json.decodeFromJsonElement<RemoteControllerState>(state),
                json.decodeFromJsonElement<RemoteControllerEvent>(event)
            )
        )
        else -> error("Unknown remote vector operation: $operation")
    }
}
