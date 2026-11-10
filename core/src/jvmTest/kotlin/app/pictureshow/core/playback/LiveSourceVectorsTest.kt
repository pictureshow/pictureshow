/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class LiveSourceVectorsTest {
    @Test fun allLiveTimelineVectors() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("live-vectors/live-source.json")).bufferedReader().use {
            it.readText()
        }
        for (entry in Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray) {
            val case = entry.jsonObject
            val input = case.getValue("window").jsonObject
            val window = ObservedLiveWindow(
                input.getValue("offsetMs").jsonPrimitive.long,
                input.getValue("durationMs").jsonPrimitive.long,
                input.getValue("defaultPositionMs").jsonPrimitive.long,
                input.getValue("seekable").jsonPrimitive.boolean
            )
            val expected = case.getValue("expected").jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            assertEquals(expected.getValue("canTimeShift").jsonPrimitive.boolean, window.canTimeShift, id)
            assertEquals(
                expected.getValue("contains").jsonPrimitive.boolean,
                window.contains(case.getValue("positionMs").jsonPrimitive.long),
                id
            )
            assertEquals(
                expected.getValue("expired").jsonPrimitive.boolean,
                window.pausedPositionExpired(case.getValue("pausedAbsoluteMs").jsonPrimitive.longOrNull),
                id
            )
            assertEquals(expected.getValue("goLiveMs").jsonPrimitive.longOrNull, window.goLivePosition(), id)
        }
    }
}
