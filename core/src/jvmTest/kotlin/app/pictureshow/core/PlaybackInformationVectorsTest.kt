/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackInformation
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.PlaybackSpeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PlaybackInformationVectorsTest {
    @Test fun sharedInformationAndSpeedVectorsMatchProduction() {
        val document = PlaybackJson.parseToJsonElement(
            requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/playback-information.json")).bufferedReader().use {
                it.readText()
            }
        ).jsonObject
        assertEquals("1", document.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("playback-information", document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        assertEquals(cases.size, cases.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet().size)
        cases.forEach { entry ->
            val case = entry.jsonObject
            val input = case.getValue("input").jsonObject
            fun text(key: String) = input.getValue(key).jsonPrimitive.content
            val actual = when (case.getValue("operation").jsonPrimitive.content) {
                "reason" -> PlaybackInformation.reason(text("reason"), input["qualityMbps"]?.jsonPrimitive?.intOrNull)
                "method" -> PlaybackInformation.method(
                    PlayDecision("item", MediaSourceInfo(), PlayMethod.valueOf(text("method")), text("url"), emptyMap(), null)
                )
                "subtitle" -> PlaybackInformation.subtitle(
                    PlaybackJson.decodeFromJsonElement(input.getValue("source")),
                    requireNotNull(input.getValue("index").jsonPrimitive.intOrNull)
                )
                "bitrate" -> PlaybackInformation.bitrate(input["bitrate"]?.jsonPrimitive?.intOrNull)
                "speed" -> PlaybackSpeeds.allowed(input.getValue("speed").jsonPrimitive.float, text("encoded") == "true").toString()
                else -> error("Unknown operation")
            }
            assertEquals(case.getValue("expected").jsonPrimitive.content, actual, case.getValue("id").jsonPrimitive.content)
        }
    }
}
