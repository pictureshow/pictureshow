/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.ConversionNotice
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackInformation
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.PlaybackSpeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
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
            val actual = actual(case)
            assertEquals(case.getValue("expected").jsonPrimitive.content, actual, case.getValue("id").jsonPrimitive.content)
        }
    }

    @Test fun malformedConversionNoticeCasesFailInsteadOfPassing() {
        fun case(input: String) = PlaybackJson.parseToJsonElement(
            """{"id":"bad","operation":"conversion-notice","input":$input,"expected":"none"}"""
        ).jsonObject
        val url = "https://server.example/videos/item/master.m3u8?VideoCodec=h264"
        // Missing reasons, missing method, an unknown method, reasons not an array, and a missing device noun.
        listOf(
            """{"method":"Transcode","url":"$url","device":"this TV"}""",
            """{"url":"$url","reasons":["VideoCodecNotSupported"],"device":"this TV"}""",
            """{"method":"Remux","url":"$url","reasons":[],"device":"this TV"}""",
            """{"method":"Transcode","url":"$url","reasons":"VideoCodecNotSupported","device":"this TV"}""",
            """{"method":"Transcode","url":"$url","reasons":["VideoCodecNotSupported"]}"""
        ).forEach { input -> assertFails(input) { actual(case(input)) } }
        // An unknown operation fails too, so a misspelled case can never silently pass.
        assertFails {
            val misspelled = """{"id":"bad","operation":"conversion-notise","input":{},"expected":"none"}"""
            actual(PlaybackJson.parseToJsonElement(misspelled).jsonObject)
        }
    }

    private fun actual(case: JsonObject): String {
        val input = case.getValue("input").jsonObject
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        return when (case.getValue("operation").jsonPrimitive.content) {
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
            // Expected is "title\nreason\nfix", or "none" when no notice applies.
            "conversion-notice" -> ConversionNotice.of(
                PlayDecision(
                    "item",
                    input["source"]?.let { PlaybackJson.decodeFromJsonElement<MediaSourceInfo>(it) } ?: MediaSourceInfo(),
                    PlayMethod.valueOf(text("method")),
                    text("url"),
                    emptyMap(),
                    null,
                    input.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
                ),
                input["subtitleIndex"]?.jsonPrimitive?.intOrNull ?: -1,
                text("device")
            )?.let { "${it.title}\n${it.reason}\n${it.fix}" } ?: "none"
            else -> error("Unknown operation")
        }
    }
}
