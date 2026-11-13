/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.syncPlayerPrimaryAction
import app.pictureshow.core.sync.syncPlayerSpeedSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every portable case executes the actual production-used core projection, not an inventory. */
class SyncPlayerIntentPresentationVectorsTest {
    @Test fun groupPlayingNativeHold() = verify("group-playing-local-True")
    @Test fun groupPlayingLocalRunning() = verify("group-playing-local-False")
    @Test fun groupPausedNativeHold() = verify("group-paused-local-True")
    @Test fun groupPausedLocalRunning() = verify("group-paused-local-False")
    @Test fun groupWaitingNativeHold() = verify("group-waiting-local-True")
    @Test fun groupWaitingLocalRunning() = verify("group-waiting-local-False")
    @Test fun standaloneNativeHold() = verify("standalone-local-True")
    @Test fun standaloneLocalRunning() = verify("standalone-local-False")
    @Test fun groupUnavailable() = verify("group-unavailable-False-1")
    @Test fun groupQueueEmpty() = verify("group-unavailable-True-0")
    @Test fun groupedSpeedSelection() = verify("speed-selection-grouped-True")
    @Test fun standaloneSpeedSelection() = verify("speed-selection-grouped-False")

    private fun verify(id: String) {
        val resource = requireNotNull(javaClass.classLoader.getResource("sync-vectors/sync-player-intent-presentation.json"))
        val suite = Json.parseToJsonElement(resource.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-player-intent-presentation", suite.getValue("suite").jsonPrimitive.content)
        val case = suite.getValue("cases").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
        val input = case.getValue("input").jsonObject
        val expected = case.getValue("expected").jsonObject
        when (case.getValue("profile").jsonPrimitive.content) {
            "primary-transport" -> {
                val actual = syncPlayerPrimaryAction(
                    participating = input.getValue("participating").jsonPrimitive.boolean,
                    usable = input.getValue("usable").jsonPrimitive.boolean,
                    queueEntries = input.getValue("queueEntries").jsonPrimitive.int,
                    phase = input.getValue("phase").jsonPrimitive.content,
                    localShowPlayButton = input.getValue("localShowPlayButton").jsonPrimitive.boolean
                )
                assertEquals(expected.getValue("action").takeUnless { it is JsonNull }?.jsonPrimitive?.content, actual, id)
            }
            "speed-selection" -> {
                val actual = syncPlayerSpeedSelection(input.getValue("participating").jsonPrimitive.boolean)
                assertEquals(expected.getValue("selectionAllowed").jsonPrimitive.boolean, actual.selectionAllowed, id)
                assertEquals(expected.getValue("explanationRequired").jsonPrimitive.boolean, actual.explanationRequired, id)
            }
            else -> error("No executor for $id")
        }
    }
}
