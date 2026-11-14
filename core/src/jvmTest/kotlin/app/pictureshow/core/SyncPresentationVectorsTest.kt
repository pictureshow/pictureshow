/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncNavigationAuthority
import app.pictureshow.core.sync.SyncQuality
import app.pictureshow.core.sync.SyncTitleScope
import app.pictureshow.core.sync.syncAcceptTitle
import app.pictureshow.core.sync.syncChipShowsQuality
import app.pictureshow.core.sync.syncDirectionAvailable
import app.pictureshow.core.sync.syncPanelQuality
import app.pictureshow.core.sync.syncPeople
import app.pictureshow.core.sync.syncPhase
import app.pictureshow.core.sync.syncQuality
import app.pictureshow.core.sync.syncTransportAvailable
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
        assertEquals(57, cases.size)
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
                "chip-quality" -> assertEquals(bool("expected"), syncChipShowsQuality(SyncQuality.valueOf(text("quality"))), id)
                "panel-quality" -> assertEquals(
                    text("expected"),
                    syncPanelQuality(SyncQuality.valueOf(text("quality")), bool("recoveryOffered")).name,
                    id
                )
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
                "transport" -> assertEquals(
                    bool("expected"),
                    syncTransportAvailable(bool("usable"), c.getValue("queueEntries").jsonPrimitive.int),
                    id
                )
                "directional-transport" -> {
                    val navigation = c.getValue("navigation").takeUnless { it is JsonNull }?.jsonObject?.let { a ->
                        SyncNavigationAuthority(
                            a.getValue("repeatMode").takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                            a.getValue("entryIds").jsonArray.map { it.jsonPrimitive.content },
                            a.getValue("currentEntryId").takeUnless { it is JsonNull }?.jsonPrimitive?.content
                        )
                    }
                    val entries = c.getValue("queueEntries").jsonPrimitive.int
                    assertEquals(
                        bool("expectedPrevious"),
                        syncDirectionAvailable(bool("usable"), entries, true, navigation),
                        "$id previous"
                    )
                    assertEquals(
                        bool("expectedNext"),
                        syncDirectionAvailable(bool("usable"), entries, false, navigation),
                        "$id next"
                    )
                }
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
