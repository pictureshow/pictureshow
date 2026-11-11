/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.trackLabel
import app.pictureshow.core.playback.trackLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TrackLabelsVectorsTest {
    @Test fun viewerLabelsMatchEverySharedCase() {
        val document = PlaybackJson.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/track-labels.json"))
                .bufferedReader().use { it.readText() }
        ).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("track-labels", document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        assertEquals(cases.size, cases.map { it.jsonObject.getValue("id") }.toSet().size)
        cases.forEach { entry ->
            val case = entry.jsonObject
            val input = case.getValue("input").jsonObject
            val actual = when (val operation = case.getValue("operation").jsonPrimitive.content) {
                "track-label" -> JsonPrimitive(
                    trackLabel(
                        PlaybackJson.decodeFromJsonElement<MediaStream>(input.getValue("stream")),
                        input["trackNumber"]?.jsonPrimitive?.int,
                        input["showDefault"]?.jsonPrimitive?.boolean ?: true
                    )
                )
                "track-labels" -> JsonArray(
                    trackLabels(
                        PlaybackJson.decodeFromJsonElement<List<MediaStream>>(input.getValue("streams"))
                    ).map(::JsonPrimitive)
                )
                else -> error("Unknown track label operation: $operation")
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }
}
