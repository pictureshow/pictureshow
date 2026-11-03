/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.control

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** All 64-bit control values use canonical decimal strings, including small values. Never use Double. */
object DecimalLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("DecimalInt64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Long {
        val text = if (decoder is JsonDecoder) {
            val value = decoder.decodeJsonElement() as? JsonPrimitive
            require(value != null && value.isString) { "Int64 must be a decimal JSON string" }
            value.content
        } else {
            decoder.decodeString()
        }
        return requireNotNull(exactLong(text)) { "Invalid decimal int64" }
    }
}

fun exactLong(value: String): Long? {
    val parsed = value.toLongOrNull() ?: return null
    return parsed.takeIf { it.toString() == value }
}

internal fun boundedAdd(value: Long, delta: Long, maximum: Long): Long = when {
    delta > 0 && value > Long.MAX_VALUE - delta -> maximum
    delta < 0 && value < Long.MIN_VALUE - delta -> 0
    else -> (value + delta).coerceIn(0, maximum)
}

@Serializable
data class ViewingIdentity(
    val server: String,
    val viewer: String,
    val device: String,
    val session: String,
    @Serializable(with = DecimalLongSerializer::class) val generation: Long,
    val kind: String = "local"
)

internal fun ViewingIdentity.valid(): Boolean = server.isNotBlank() &&
    viewer.isNotBlank() &&
    device.isNotBlank() &&
    session.isNotBlank() &&
    generation >= 0 &&
    kind in listOf("local", "jellyfin", "cast")

@Serializable
data class ControlIntent(
    val entry: String? = null,
    @Serializable(with = DecimalLongSerializer::class) val ticks: Long = 0,
    val paused: Boolean = true,
    val queue: List<String> = emptyList(),
    val repeat: String = "off",
    val shuffle: List<String> = emptyList(),
    val audio: Int? = null,
    val subtitle: Int? = null
)
