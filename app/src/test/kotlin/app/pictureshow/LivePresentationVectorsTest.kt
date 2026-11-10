/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LivePresentationVectorsTest {
    @Test fun everyPresentationVector() {
        val file = listOf(File("../spec/vectors/live-presentation.json"), File("spec/vectors/live-presentation.json")).first { it.exists() }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(16, cases.size)
        for (element in cases) {
            val c = element.jsonObject
            fun value(key: String) = c.getValue(key).jsonPrimitive.content
            val id = value("id")
            when (value("op")) {
                "tick" -> assertEquals(c.getValue("expected").jsonPrimitive.float, liveGuideTickX(c.getValue("tick").jsonPrimitive.int), id)
                "text" -> assertEquals(
                    c.getValue("expected").jsonPrimitive.boolean,
                    liveGuideShowsText(c.getValue("width").jsonPrimitive.float),
                    id
                )
                else -> {
                    val time =
                        LiveTime(
                            c.getValue("twentyFour").jsonPrimitive.boolean,
                            Locale.US,
                            ZoneId.of("America/New_York"),
                            Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
                        )
                    val actual = if (value("op") == "clock") time.clock(value("instant")) else time.range(value("start"), value("end"))
                    assertEquals(value("expected"), actual.replace('\u202f', ' ').replace('\u00a0', ' '), id)
                }
            }
        }
    }
}
