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
            val envelope = element.jsonObject
            val c = envelope.getValue("input").jsonObject
            fun value(key: String) = c.getValue(key).jsonPrimitive.content
            val id = envelope.getValue("id").jsonPrimitive.content
            when (envelope.getValue("operation").jsonPrimitive.content) {
                "tick" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.float,
                    liveGuideTickX(c.getValue("tick").jsonPrimitive.int),
                    id
                )
                "text" -> assertEquals(
                    envelope.getValue("expected").jsonPrimitive.boolean,
                    liveGuideShowsText(c.getValue("width").jsonPrimitive.float),
                    id
                )
                else -> {
                    val time =
                        LiveTime(
                            c.getValue("twentyFour").jsonPrimitive.boolean,
                            Locale.forLanguageTag(value("locale")),
                            ZoneId.of(value("zone")),
                            Instant.parse(value("now")).toEpochMilli()
                        )
                    val actual = if (envelope.getValue("operation").jsonPrimitive.content ==
                        "clock"
                    ) {
                        time.clock(value("instant"))
                    } else {
                        time.range(value("start"), value("end"))
                    }
                    assertEquals(
                        envelope.getValue("expected").jsonPrimitive.content,
                        actual.replace('\u202f', ' ').replace('\u00a0', ' '),
                        id
                    )
                }
            }
        }
    }
}
