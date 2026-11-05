/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

@Serializable
data class CastCapabilityInput(
    /** touch or tv; browser for the web sender. */
    val surface: String = "touch",
    val osSupported: Boolean = true,
    val browserSupported: Boolean = true,
    /** ok, missing or update-required: whether the platform's Google Cast support can run at all. */
    val services: String = "ok",
    val viewerEnabled: Boolean = true,
    val localNetworkAllowed: Boolean = true,
    /** idle, searching or finished. */
    val discovery: String = "idle",
    val targets: Int = 0
)

@Serializable
data class CastCapability(
    val state: String,
    /** Whether Google Cast belongs in this surface's chooser at all. */
    val visible: Boolean,
    /** Whether a device search may run in this state. */
    val canDiscover: Boolean,
    /** Jellyfin sessions don't depend on Google Cast, so they stay usable in every state. */
    val jellyfinRemote: Boolean = true
)

/** Each state has its own recovery; no state is a crash, and none of them touches the sender SDK. */
fun castCapability(input: CastCapabilityInput): CastCapability {
    fun blocked(state: String, visible: Boolean = true) = CastCapability(state, visible, canDiscover = false)
    return when {
        input.surface == "tv" -> blocked("tv-interface", visible = false)
        input.surface == "browser" && !input.browserSupported -> blocked("browser-unsupported")
        !input.osSupported -> blocked("os-too-old")
        input.services == "missing" -> blocked("services-missing")
        input.services != "ok" -> blocked("services-update-required")
        !input.viewerEnabled -> blocked("viewer-disabled")
        !input.localNetworkAllowed -> blocked("permission-denied")
        input.targets > 0 -> CastCapability("ready", visible = true, canDiscover = true)
        input.discovery == "searching" -> CastCapability("searching", visible = true, canDiscover = true)
        input.discovery == "finished" -> CastCapability("no-targets", visible = true, canDiscover = true)
        else -> CastCapability("idle", visible = true, canDiscover = true)
    }
}

/** A subtitle or audio track the sender may offer. Only an observed active id can make it read as selected. */
@Serializable
data class CastTrack(val id: Int, val type: String, val label: String, val available: Boolean = true)

@Serializable
data class CastControlsInput(
    val commands: List<String> = emptyList(),
    val durationTicks: Long? = null,
    val live: Boolean = false,
    val seekableStartTicks: Long? = null,
    val seekableEndTicks: Long? = null,
    val tracks: List<CastTrack> = emptyList(),
    val activeTracks: List<Int> = emptyList()
)

@Serializable
data class CastTrackControl(val id: Int, val type: String, val label: String, val selectable: Boolean, val selected: Boolean)

@Serializable
data class CastControls(
    val pause: Boolean,
    val seek: Boolean,
    val stop: Boolean,
    val volume: Boolean,
    val volumeReason: String?,
    /** vod or live. An unknown length is live; it never reads as a zero-length video. */
    val timeline: String,
    val seekStartTicks: Long?,
    val seekEndTicks: Long?,
    val tracks: List<CastTrackControl>
)

fun castControls(input: CastControlsInput): CastControls {
    val known = input.durationTicks?.takeIf { it > 0 }
    val live = input.live || known == null
    val window = input.seekableStartTicks != null && input.seekableEndTicks != null && input.seekableEndTicks > input.seekableStartTicks
    val canSeek = "seek" in input.commands && if (live) window else true
    val volume = "volume" in input.commands
    return CastControls(
        pause = "pause" in input.commands,
        seek = canSeek,
        stop = "stop" in input.commands,
        volume = volume,
        volumeReason = if (volume) null else "receiver-fixed-volume",
        timeline = if (live) "live" else "vod",
        seekStartTicks = if (!canSeek) null else if (live) input.seekableStartTicks else 0,
        seekEndTicks = if (!canSeek) null else if (live) input.seekableEndTicks else known,
        tracks = input.tracks.map { track ->
            CastTrackControl(
                track.id,
                track.type,
                track.label,
                selectable = track.available && "tracks" in input.commands,
                selected = track.available && track.id in input.activeTracks
            )
        }
    )
}

@Serializable
data class CastSenderVpn(
    val active: Boolean = false,
    /** CIDR ranges routed through the sender's VPN. */
    val ranges: List<String> = emptyList()
)

@Serializable
data class CastReachInput(val baseUrl: String, val vpn: CastSenderVpn = CastSenderVpn())

@Serializable
data class CastReach(
    /** invalid, loopback, link-local, sender-vpn, vpn-possible, private-http, private-https, public-http or public-https. */
    val category: String,
    /** False stops the attempt: the receiver has no way to fetch this address. */
    val allowed: Boolean
)

/**
 * Whether the receiver can fetch from this address, judged only from the address and the sender's VPN. This can rule
 * an address out; only a receiver loading it proves it. It never exposes, relays or proxies anything to make one work.
 */
fun castReach(input: CastReachInput): CastReach {
    val scheme = input.baseUrl.substringBefore("://", "").lowercase()
    val authority = input.baseUrl.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }.lowercase()
    if (scheme !in listOf("http", "https") || host.isEmpty()) return CastReach("invalid", false)
    val v4 = parseIpv4(host)
    if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || (v4 != null && v4 ushr 24 == 127L)) {
        return CastReach("loopback", false)
    }
    if ((v4 != null && v4 ushr 16 == 0xA9FEL) || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")) {
        return CastReach("link-local", false)
    }
    if (input.vpn.active) {
        if (v4 != null && input.vpn.ranges.any { inRange(v4, it) }) return CastReach("sender-vpn", false)
        // A CGNAT address is an overlay network the receiver isn't on, even when the OS doesn't list it as a VPN route.
        if (v4 != null && v4 ushr 22 == (100L shl 2 or 1L)) return CastReach("sender-vpn", false)
        if (v4 == null) return CastReach("vpn-possible", true)
    }
    val private = host.endsWith(".local") ||
        host.endsWith(".lan") ||
        host.endsWith(".home.arpa") ||
        '.' !in host && ':' !in host ||
        host.startsWith("fc") ||
        host.startsWith("fd") ||
        (v4 != null && isPrivateV4(v4))
    return CastReach((if (private) "private-" else "public-") + scheme, true)
}

private fun parseIpv4(host: String): Long? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    var value = 0L
    parts.forEach { part ->
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return null
        value = value shl 8 or octet.toLong()
    }
    return value
}

private fun isPrivateV4(v: Long) = v ushr 24 == 10L || v ushr 20 == 0xAC1L || v ushr 16 == 0xC0A8L

private fun inRange(v: Long, cidr: String): Boolean {
    val network = parseIpv4(cidr.substringBefore('/')) ?: return false
    val prefix = cidr.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return false
    if (prefix == 0) return true
    val shift = 32 - prefix
    return v ushr shift == network ushr shift
}
