/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncTitleScope
import app.pictureshow.core.sync.syncAcceptTitle
import app.pictureshow.core.sync.syncPeople
import app.pictureshow.core.sync.syncPhase
import app.pictureshow.core.sync.syncQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** These portable projections make UI estimates and names-only roster limitations explicit. */
class SyncPresentationVectorsTest {
    @Test fun allPresentationRules() {
        val resource = requireNotNull(javaClass.classLoader.getResource("sync-vectors/sync-presentation.json"))
        val cases = Json.parseToJsonElement(resource.readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(24, cases.size)
        cases.forEach { element ->
            val c = element.jsonObject
            val id = c.getValue("id").jsonPrimitive.content
            fun text(key: String) = c.getValue(key).jsonPrimitive.content
            fun bool(key: String) = c.getValue(key).jsonPrimitive.boolean
            fun number(key: String) = c.getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.long
            fun strings(key: String) = c.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            fun scope(key: String): SyncTitleScope {
                val a = c.getValue(key).jsonArray
                return SyncTitleScope(a[0].jsonPrimitive.content, a[1].jsonPrimitive.long, a[2].jsonPrimitive.long)
            }
            when (text("op")) {
                "quality" -> assertEquals(
                    text("expected"),
                    syncQuality(bool("usableClock"), bool("ready"), bool("applied"), number("errorTicks"), number("uncertaintyTicks")).name,
                    id
                )
                "title" -> assertEquals(
                    bool("expected"),
                    syncAcceptTitle(scope("request"), scope("current"), strings("entries").toSet(), strings("asked").toSet()),
                    id
                )
                "phase" -> assertEquals(
                    text("expected"),
                    syncPhase(
                        text("phase"),
                        c["reason"]?.takeUnless {
                            it is JsonNull
                        }?.jsonPrimitive?.content
                    ).name,
                    id
                )
                "people" -> {
                    val people = syncPeople(strings("names"))
                    assertEquals(strings("visible"), people.names, id)
                    assertEquals(c.getValue("remaining").jsonPrimitive.int, people.remaining, id)
                }
                else -> error("Unimplemented presentation vector $id")
            }
        }
    }
}
