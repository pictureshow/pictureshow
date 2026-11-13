/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class PlaybackQualityPresentationVectorsTest {
    @Test fun playerQualityChoicesAndWordingMatchPortableContract() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/player-quality.json"))
            .bufferedReader().use { it.readText() }
        val document = PlaybackJson.parseToJsonElement(text).jsonObject
        assertEquals("player-quality", document.getValue("suite").jsonPrimitive.content)
        for (element in document.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val input = case.getValue("input").jsonObject
            val override = input["overrideMbps"]?.jsonPrimitive?.intOrNull
            val actual = when (case.getValue("operation").jsonPrimitive.content) {
                "choose" -> PlaybackQualitySelection(override, input["estimatedBandwidth"]?.jsonPrimitive?.longOrNull)
                    .choose(input["chooseMbps"]?.jsonPrimitive?.intOrNull).let { selection ->
                        buildJsonObject {
                            put("overrideMbps", selection.overrideMbps?.let(::JsonPrimitive) ?: JsonNull)
                            put("estimatedBandwidth", selection.estimatedBandwidth?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    }
                "choices" -> JsonArray(
                    PlaybackQualityPresentation.choices.map {
                        JsonPrimitive(PlaybackQualityPresentation.choiceLabel(it))
                    }
                )
                "label" -> JsonPrimitive(PlaybackQualityPresentation.choiceLabel(override))
                "context" -> JsonPrimitive(
                    PlaybackQualityPresentation.automaticContext(
                        PlaybackNetwork.valueOf(input.getValue("network").jsonPrimitive.content),
                        input.getValue("configuredBitrate").jsonPrimitive.int
                    )
                )
                "notice" -> PlaybackQualityPresentation.reductionNotice(
                    override,
                    input.getValue("activeBitrate").jsonPrimitive.int,
                    input.getValue("configuredBitrate").jsonPrimitive.int
                )?.let(::JsonPrimitive) ?: JsonNull
                else -> error("Unhandled player quality operation")
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }
}
