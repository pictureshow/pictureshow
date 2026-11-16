/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** PLAY-60/61 vectors: which engine plays, whether the server is asked with mpv's profile, and the first-frame watchdog. */
class PlaybackEngineVectorsTest {
    @Test fun engineChoiceMatchesPortableRule() {
        val text = requireNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/playback-engine.json"))
            .bufferedReader().use { it.readText() }
        val document = PlaybackJson.parseToJsonElement(text).jsonObject
        assertEquals("playback-engine", document.getValue("suite").jsonPrimitive.content)
        val cases = document.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        assertTrue(cases.any { it.jsonObject.getValue("operation").jsonPrimitive.content == "first-frame" })
        for (element in cases) {
            val case = element.jsonObject
            val input = case.getValue("input").jsonObject
            if (case.getValue("operation").jsonPrimitive.content == "first-frame") {
                val action = EngineFirstFrame.action(
                    loaded = input.flag("loaded"),
                    firstFrame = input.flag("firstFrame"),
                    dataReadyForMs = input["dataReadyForMs"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toLong(),
                    loadedForMs = input.text("loadedForMs").toLong(),
                    passthrough = input.flag("passthrough")
                )
                assertEquals(case.getValue("expected"), JsonObject(mapOf("action" to JsonPrimitive(action.name))), case.getValue("id").jsonPrimitive.content)
                continue
            }
            assertEquals("engine", case.getValue("operation").jsonPrimitive.content)
            val context = PlaybackEngineContext(
                choice = PlaybackEngineChoice.valueOf(input.text("choice")),
                mpvAvailable = input.flag("mpvAvailable"),
                live = input.flag("live"),
                offline = input.flag("offline"),
                syncPlay = input.flag("syncPlay"),
                audio = input.flag("audio"),
                mpvFailed = input["mpvFailed"]?.jsonPrimitive?.boolean ?: false
            )
            val method = PlayMethod.valueOf(input.text("platformMethod"))
            val reasons = input.getValue("platformReasons").jsonArray.map { it.jsonPrimitive.content }
            val mpvMethod = input["mpvMethod"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let(PlayMethod::valueOf)
            val asked = PlaybackEngineRule.first(context) == PlaybackEngine.Mpv || PlaybackEngineRule.retryWithMpv(context, method, reasons)
            val actual = JsonObject(
                mapOf(
                    "engine" to JsonPrimitive(PlaybackEngineRule.choose(context, method, reasons, mpvMethod.takeIf { asked }).name),
                    "askedMpv" to JsonPrimitive(asked)
                )
            )
            assertEquals(case.getValue("expected"), actual, case.getValue("id").jsonPrimitive.content)
        }
    }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.flag(name: String) = getValue(name).jsonPrimitive.boolean
}
