/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncQueue
import app.pictureshow.core.sync.SyncQueueEntry
import app.pictureshow.core.sync.syncCompletionAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SyncCompletionVectorsTest {
    @Test fun naturalCompletionVectors() {
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("sync-vectors/sync-completion.json"))
        val suite = source.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(setOf("schemaVersion", "suite", "cases"), suite.keys)
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-completion", suite.getValue("suite").jsonPrimitive.content)
        val cases = suite.getValue("cases").jsonArray
        assertEquals(25, cases.size)
        val ids = mutableSetOf<String>()
        for (element in cases) {
            val row = element.jsonObject
            assertEquals(setOf("id", "operation", "input", "expected"), row.keys)
            val id = row.getValue("id").jsonPrimitive.content
            assertTrue(id.isNotBlank() && ids.add(id), id)
            assertEquals("natural-completion", row.getValue("operation").jsonPrimitive.content, id)
            val input = row.getValue("input").jsonObject
            assertEquals(setOf("queueCount", "currentIndex", "repeatMode", "hasSnapshot", "rendererMatches", "unpaused"), input.keys, id)
            fun flag(name: String): Boolean {
                val value = input.getValue(name).jsonPrimitive
                assertFalse(value.isString, "$id.$name")
                return value.boolean
            }
            val queue = SyncQueue(
                List(input.getValue("queueCount").jsonPrimitive.int) { SyncQueueEntry("same-media", "entry-$it") },
                input.getValue("currentIndex").jsonPrimitive.int,
                0,
                true,
                input.getValue("repeatMode").jsonPrimitive.content,
                "Sorted",
                0
            )
            assertEquals(
                row.getValue("expected").jsonPrimitive.content,
                syncCompletionAction(queue, flag("hasSnapshot"), flag("rendererMatches"), flag("unpaused")).name,
                id
            )
        }
    }
}
