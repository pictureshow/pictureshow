/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Recover valid independent fields after a future version or corrupted value introduces an invalid one. */
object PlaybackPreferenceJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun tuning(value: String?): PlaybackTuning = fields(value, PlaybackTuning()).normalized()
    fun style(value: String?): SubtitleStyle = fields(value, SubtitleStyle()).normalized()

    private inline fun <reified T> fields(value: String?, fallback: T): T {
        val objectValue = runCatching { value?.let { json.parseToJsonElement(it) as? JsonObject } }.getOrNull() ?: return fallback
        val accepted = objectValue.filter { (key, field) ->
            runCatching { json.decodeFromJsonElement<T>(JsonObject(mapOf(key to field))) }.isSuccess
        }
        return runCatching { json.decodeFromJsonElement<T>(JsonObject(accepted)) }.getOrDefault(fallback)
    }
}
