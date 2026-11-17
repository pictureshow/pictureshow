/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** PLAY-11 shared timing vectors against [SubtitleTiming]. */
class SubtitleTimingVectorsTest {
    @Test fun vectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/subtitle-timing.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 15)
        for (case in cases.map { it.jsonObject }) {
            val input = case.getValue("input").jsonObject
            fun int(key: String) = input.getValue(key).jsonPrimitive.int
            fun bool(key: String) = input.getValue(key).jsonPrimitive.boolean
            val actual: JsonElement = when (val op = case.getValue("operation").jsonPrimitive.content) {
                "label" -> JsonPrimitive(SubtitleTiming.label(int("ms")))
                "step" -> JsonPrimitive(SubtitleTiming.step(int("ms"), int("direction")))
                "can-step" -> JsonPrimitive(SubtitleTiming.canStep(int("ms"), int("direction")))
                "normalize" -> JsonPrimitive(SubtitleTiming.normalize(int("ms")))
                "section" -> JsonPrimitive(SubtitleTiming.section(bool("subtitleOn"), bool("burnedIn")).name)
                "help" -> JsonPrimitive(SubtitleTiming.HELP)
                "burned-in" -> JsonPrimitive(SubtitleTiming.BURNED_IN)
                else -> error("Unknown operation $op")
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }
}
