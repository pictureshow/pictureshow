/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.io.File
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class PlayerHudClocksTest {
    @Test fun elapsedAndRemainingUsePortableClocksWithoutInventingDuration() {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "spec/vectors/player-hud-clocks.json") }.first { it.isFile }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(7, cases.size)
        for (row in cases) {
            val c = row.jsonObject
            val input = c.getValue("input").jsonObject
            val expected = c.getValue("expected").jsonObject
            val position = input.getValue("positionMs").jsonPrimitive.long
            val duration = input.getValue("durationMs").jsonPrimitive.long
            val id = c.getValue("id").jsonPrimitive.content
            assertEquals(expected.getValue("elapsed").jsonPrimitive.content, playerElapsedClock(position, duration), id)
            assertEquals(expected.getValue("remaining").jsonPrimitive.contentOrNull, playerRemainingClock(position, duration), id)
        }
    }
}
