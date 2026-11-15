/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.playback.HdrOutput
import app.pictureshow.core.playback.PlaybackFormatInformation
import app.pictureshow.core.playback.VideoRangeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PlaybackFormatsVectorsTest {
    @Test fun sourceDecoderAndDisplayEvidenceVectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/playback-formats.json"))
            .bufferedReader().use { it.readText() }
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 20)
        cases.forEach { value ->
            val c = value.jsonObject
            val input = c.getValue("input").jsonObject
            fun str(k: String) = input[k]?.jsonPrimitive?.contentOrNull
            val actual = when (c.getValue("operation").jsonPrimitive.content) {
                "range" -> PlaybackFormatInformation.range(
                    str("range")?.let(VideoRangeType::valueOf),
                    input["profile"]?.jsonPrimitive?.intOrNull
                )
                "profile" -> PlaybackFormatInformation.profile(str("profile"))
                "decoder" -> PlaybackFormatInformation.decoder(str("hwdec"))
                "display" -> PlaybackFormatInformation.display(str("transfer"), HdrOutput.valueOf(requireNotNull(str("output"))))
                else -> error("Unknown playback format vector")
            }
            assertEquals(c.getValue("expected").jsonPrimitive.content, actual, c.getValue("id").jsonPrimitive.content)
        }
    }
}
