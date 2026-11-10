/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SettingsPresentationVectorsTest {
    @Test fun protocolNamesBecomeViewerChoicesWithHonestConsequences() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/settings-presentation.json"))
            .bufferedReader().use { it.readText() }
        val suite = PlaybackJson.parseToJsonElement(text).jsonObject
        assertEquals("settings-presentation", suite.getValue("suite").jsonPrimitive.content)
        for (element in suite.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val input = case.getValue("input").jsonObject
            val value = input["value"]?.jsonPrimitive?.content.orEmpty()
            val presentation = PlaybackSettingsPresentation
            val actual: Any = when (case.getValue("operation").jsonPrimitive.content) {
                "passthrough-configurable" -> presentation.canConfigurePassthrough(
                    input.getValue("passthrough").jsonPrimitive.boolean,
                    input.getValue("downmix").jsonPrimitive.boolean,
                    input.getValue("supported").jsonPrimitive.boolean
                )
                "subtitle-label" -> presentation.subtitleValueLabel(value)
                "credits-label" -> presentation.postCreditsLabel(PostCredits.valueOf(value))
                "channel-label" -> presentation.audioChannelLabel(input.getValue("value").takeUnless { it == JsonNull }?.jsonPrimitive?.int)
                "passthrough-label" -> presentation.passthroughLabel(PassthroughEncoding.valueOf(value))
                "capability-label" -> presentation.capabilityLabel(
                    CapabilityOverride.valueOf(value),
                    input.getValue("hdr").jsonPrimitive.boolean
                )
                "capability-consequence" -> presentation.capabilityConsequence(
                    CapabilityOverride.valueOf(value),
                    input.getValue("hdr").jsonPrimitive.boolean
                )
                else -> error("Unhandled settings presentation operation")
            }
            val primitive = when (actual) {
                is Boolean -> JsonPrimitive(actual)
                is String -> JsonPrimitive(actual)
                else -> error("Unsupported presentation result")
            }
            assertEquals(case.getValue("expected"), primitive, case.getValue("id").jsonPrimitive.content)
        }
    }
}
