/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class PlaybackReviewVectorsTest {
    private fun cases(suite: String): JsonArray {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/$suite.json"))
            .bufferedReader().use { it.readText() }
        return PlaybackJson.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
    }

    @Test fun automaticFloorAndActualStreamImpact() {
        for (entry in cases("quality-auto-floor")) {
            val case = entry.jsonObject
            val i = case.getValue("input").jsonObject
            if (case["operation"]?.jsonPrimitive?.content == "stream-bitrate") {
                val decision = PlayDecision(
                    "film",
                    PlaybackJson.decodeFromJsonElement<MediaSourceInfo>(i.getValue("source")),
                    PlayMethod.valueOf(i.getValue("method").jsonPrimitive.content),
                    i.getValue("url").jsonPrimitive.content,
                    emptyMap(),
                    null
                )
                val actual = PlaybackInformation.streamBitrate(decision)?.let(::JsonPrimitive) ?: JsonNull
                assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
                continue
            }
            val ceiling = i.getValue("ceiling").jsonPrimitive.int
            val cap = PlaybackTuning.automaticCap(ceiling, i.getValue("estimate").jsonPrimitive.long)
            val stream = i["streamBitrate"]?.jsonPrimitive?.intOrNull
            val actual = buildJsonObject {
                put("cap", cap)
                val reroute = cap < ceiling && PlaybackNetworkPolicy.reroutes(cap, i.getValue("estimate").jsonPrimitive.long, stream)
                put("reroute", reroute)
                put(
                    "notice",
                    PlaybackQualityPresentation.reductionNotice(null, cap, ceiling, stream)?.takeIf { reroute }?.let(::JsonPrimitive) ?: JsonNull
                )
            }
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }

    @Test fun reasonsNameFormatsAndOnlySupplementObservedChanges() {
        for (entry in cases("playback-info-reasons")) {
            val case = entry.jsonObject
            val i = case.getValue("input").jsonObject
            val codes = i.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
            val decision = PlayDecision(
                "film",
                PlaybackJson.decodeFromJsonElement<MediaSourceInfo>(i.getValue("source")),
                PlayMethod.valueOf(i.getValue("method").jsonPrimitive.content),
                "https://example.test/stream",
                emptyMap(),
                null,
                codes
            )
            val received = i.getValue("received").jsonObject
            val lines = (if (codes.isEmpty()) emptyList() else PlaybackInformation.reasons(decision)) + PlaybackInformation.observations(
                decision,
                PlaybackInformation.ReceivedVideo(
                    received["width"]?.jsonPrimitive?.intOrNull,
                    received["height"]?.jsonPrimitive?.intOrNull,
                    received["codec"]?.jsonPrimitive?.content
                ),
                receiverAudio = i.getValue("receiverAudio").jsonPrimitive.boolean
            )
            assertEquals(case.getValue("expected"), JsonArray(lines.map(::JsonPrimitive)), case.getValue("id").jsonPrimitive.content)
        }
    }
}
