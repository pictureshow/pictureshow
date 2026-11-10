/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class FrameRateMatchVectorsTest {
    @Test fun frameModesAndStartProvenanceMatchPortableRules() {
        for (suite in listOf("frame-rate-match", "playback-start")) {
            val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/$suite.json"))
                .bufferedReader().use { it.readText() }
            val document = PlaybackJson.parseToJsonElement(text).jsonObject
            assertEquals(suite, document.getValue("suite").jsonPrimitive.content)
            for (element in document.getValue("cases").jsonArray) {
                val case = element.jsonObject
                val input = case.getValue("input").jsonObject
                val actual = when (case.getValue("operation").jsonPrimitive.content) {
                    "frame-rate-mode" -> FrameRateMatch.select(
                        input.getValue("receivedFrameRate").jsonPrimitive.float,
                        mode(input.getValue("current").jsonObject),
                        input.getValue("supported").jsonArray.map { mode(it.jsonObject) }
                    )?.let { JsonPrimitive(it.id) } ?: JsonNull
                    "start-position" -> JsonPrimitive(
                        PlaybackStartPosition.of(
                            input.getValue("positionMs").jsonPrimitive.long,
                            PlaybackStartOrigin.valueOf(input.getValue("origin").jsonPrimitive.content),
                            PlaybackTuning(rewindSeconds = input.getValue("rewindSeconds").jsonPrimitive.int)
                        )
                    )
                    else -> error("Unhandled frame/start vector operation")
                }
                assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
            }
        }
    }

    private fun mode(input: JsonObject) = FrameRateDisplayMode(
        input.getValue("id").jsonPrimitive.int,
        input.getValue("width").jsonPrimitive.int,
        input.getValue("height").jsonPrimitive.int,
        input.getValue("refreshRate").jsonPrimitive.float,
        input["hdrFormats"]?.jsonArray?.map { HdrFormat.valueOf(it.jsonPrimitive.content) }?.toSet() ?: emptySet()
    )
}
