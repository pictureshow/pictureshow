/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.control.QueueState
import app.pictureshow.core.music.musicUnreachable
import app.pictureshow.core.offline.OfflineBudget
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.collectionFor
import app.pictureshow.core.offline.collectionPlayOrder
import app.pictureshow.core.offline.collectionProgress
import app.pictureshow.core.offline.collectionRemoval
import app.pictureshow.core.offline.prunedCollections
import app.pictureshow.core.offline.refusal
import app.pictureshow.core.offline.roomBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** MUS-10: what a queue does without its server, and the album/playlist snapshot a download keeps. Exact expected values. */
class MusicOfflineVectorsTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test fun offline() {
        val document = json.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("control-vectors/music-offline.json")).bufferedReader().use {
                it.readText()
            }
        ).jsonObject
        assertEquals("music-offline", document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.size >= 20)
        cases.forEach { value ->
            val case = value.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val actual = execute(case.getValue("operation").jsonPrimitive.content, case.getValue("input").jsonObject)
            assertEquals(case.getValue("expected"), actual, id)
        }
    }

    @Test fun unknownOperationFails() {
        assertFails { execute("unknown", JsonObject(emptyMap())) }
    }

    private fun execute(operation: String, input: JsonObject): JsonElement = when (operation) {
        "unreachable" -> {
            val result = musicUnreachable(
                json.decodeFromJsonElement<QueueState>(input.getValue("queue")),
                input.getValue("local").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                input.getValue("title").jsonPrimitive.content
            )
            buildJsonObject {
                put("step", result.step)
                put("message", result.message)
            }
        }
        "collection" -> {
            val manifest = json.decodeFromJsonElement<OfflineManifest>(input.getValue("manifest"))
            val id = input.getValue("collection").jsonPrimitive.content
            val item = input.getValue("item").jsonPrimitive.content
            val progress = manifest.collectionProgress(id)
            buildJsonObject {
                put("removal", json.encodeToJsonElement(manifest.collectionRemoval(id)))
                put(
                    "progress",
                    progress?.let {
                        buildJsonObject {
                            put("tracks", it.tracks)
                            put("ready", it.ready)
                            put("transferring", it.transferring)
                            put("waiting", it.waiting)
                            put("paused", it.paused)
                            put("failed", it.failed)
                            put("absent", it.absent)
                            put("readyBytes", it.readyBytes)
                            put("phase", it.phase)
                        }
                    } ?: JsonNull
                )
                put("playOrder", json.encodeToJsonElement(manifest.collectionPlayOrder(id)))
                put("collectionFor", manifest.collectionFor(item)?.id?.let(::JsonPrimitive) ?: JsonNull)
                put("prunedCollections", json.encodeToJsonElement(manifest.prunedCollections().collections.map { it.id }))
            }
        }
        "budget" -> {
            val budget = json.decodeFromJsonElement<OfflineBudget>(input.getValue("budget"))
            val bytes = input.getValue("bytes").jsonPrimitive.long
            buildJsonObject {
                put("refusal", budget.refusal(bytes)?.let(::JsonPrimitive) ?: JsonNull)
                put("room", budget.roomBytes()?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        else -> error("Unknown music-offline operation: $operation")
    }
}
