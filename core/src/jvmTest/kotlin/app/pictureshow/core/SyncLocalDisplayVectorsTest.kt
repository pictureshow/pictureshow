/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.syncShowsLocalPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SyncLocalDisplayVectorsTest {
    @Test fun authoritativeQueueControlsWhetherActualLocalFactExists() {
        val source = requireNotNull(javaClass.classLoader.getResource("sync-vectors/sync-local-display.json"))
        val cases = Json.parseToJsonElement(source.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(4, cases.size)
        cases.forEach { row ->
            val c = row.jsonObject
            assertEquals(
                c.getValue("expectedVisible").jsonPrimitive.boolean,
                syncShowsLocalPlayback(c.getValue("queueEntries").jsonPrimitive.int),
                c.getValue("id").jsonPrimitive.content + ": " + c.getValue("localState").jsonPrimitive.content
            )
        }
    }
}
