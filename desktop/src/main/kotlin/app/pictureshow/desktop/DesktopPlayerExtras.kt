/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.net.InetAddress
import java.net.URI

/**
 * One viewing's player extras (PLAY-11/13/14/15/25/30). Speed and subtitle timing are per viewing: they start at 1x and
 * in sync, and survive an internal reopen (track change, downloaded subtitle) but not a new title.
 */
data class DesktopPlayerExtras(
    val chapters: List<Chapter> = emptyList(),
    val trickplay: TrickplayInfo? = null,
    val segments: List<MediaSegment> = emptyList(),
    val speed: Float = 1f,
    val speedMessage: String? = null,
    val subtitleDelayMs: Int = 0,
    /** Segments already skipped automatically, so Automatic skips each once (PLAY-15). */
    val autoSkipped: Set<String> = emptySet(),
    /** PLAY-30's credits card: seconds before the next episode starts, while the credits still play. */
    val creditsSecondsLeft: Int? = null,
    val creditsCancelled: Boolean = false
)

/** What carries over when the same viewing reopens internally. */
internal data class DesktopViewingCarry(val speed: Float, val subtitleDelayMs: Int, val autoSkipped: Set<String>)

internal fun DesktopPlayerExtras.carry() = DesktopViewingCarry(speed, subtitleDelayMs, autoSkipped)

/** PLAY-15: the segment action at this position. */
internal sealed interface DesktopSegmentAction {
    data object None : DesktopSegmentAction
    data class Ask(val segment: MediaSegment) : DesktopSegmentAction
    data class Skip(val segment: MediaSegment) : DesktopSegmentAction
}

internal fun desktopSegmentAction(extras: DesktopPlayerExtras, positionMs: Long, modes: Map<SegmentType, SegmentMode>): DesktopSegmentAction {
    val active = activeSegment(extras.segments, positionMs, modes) ?: return DesktopSegmentAction.None
    return when (modes[active.type] ?: SegmentMode.Ask) {
        SegmentMode.Off -> DesktopSegmentAction.None
        SegmentMode.Automatic -> if (active.id in extras.autoSkipped) DesktopSegmentAction.None else DesktopSegmentAction.Skip(active)
        SegmentMode.Ask -> DesktopSegmentAction.Ask(active)
    }
}

/** The Skip button's words, as on Android. */
internal fun desktopSkipLabel(type: SegmentType): String = "Skip " + when (type) {
    SegmentType.Intro -> "intro"
    SegmentType.Outro -> "credits"
    SegmentType.Recap -> "recap"
    SegmentType.Preview -> "preview"
    SegmentType.Commercial -> "commercial"
    SegmentType.Unknown -> "segment"
}

/** PLAY-13: one row per chapter, "Name · 12:34" (unnamed chapters are numbered). */
internal fun desktopChapterLabel(chapter: Chapter, index: Int): String =
    "${chapter.name.trim().ifEmpty { "Chapter ${index + 1}" }} · ${clock(chapter.startMs / 1000.0)}"

/** The chapter playing now: the last one that has started. */
internal fun desktopCurrentChapter(chapters: List<Chapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

/** PLAY-11: whether the selected subtitle is drawn into the video by the server. */
internal fun desktopSubtitleBurnedIn(decision: PlayDecision, subtitleIndex: Int): Boolean =
    subtitleIndex >= 0 && decision.method == PlayMethod.Transcode &&
        decision.mediaSource.mediaStreams.any { it.type == MediaStreamType.Subtitle && it.index == subtitleIndex && it.deliveryMethod == SubtitleDeliveryMethod.Encode }

/** mpv's names for the encoded formats it can pass through (`--audio-spdif`). */
internal fun desktopSpdifCodecs(prefs: PlaybackPreferences): List<String> {
    if (!prefs.passthrough || prefs.tuning.downmix) return emptyList()
    // mpv can't tell Atmos from plain Dolby Digital Plus, so E-AC-3 passes only while both are allowed.
    val off = prefs.tuning.disabledPassthrough
    return listOfNotNull(
        "ac3".takeIf { PassthroughEncoding.AC3 !in off },
        "eac3".takeIf { PassthroughEncoding.EAC3 !in off && PassthroughEncoding.EAC3_JOC !in off },
        "dts".takeIf { PassthroughEncoding.DTS !in off },
        "dts-hd".takeIf { PassthroughEncoding.DTS_HD !in off },
        "truehd".takeIf { PassthroughEncoding.TRUEHD !in off }
    )
}

private fun mpvColor(name: String, opacity: Int): String? {
    val rgb = when (name) {
        "White" -> "FFFFFF"; "Yellow" -> "FFFF00"; "Black" -> "000000"; "Green" -> "00FF00"; "Cyan" -> "00FFFF"
        else -> return null
    }
    return "#%02X%s".format((opacity.coerceIn(0, 100) * 255 + 50) / 100, rgb)
}

/**
 * SET-02/03/04/15/19 as mpv options. Nothing is emitted for defaults, so a viewer who never opens Settings plays
 * exactly as before. Image subtitles keep their authored pixels (mpv's sub-* text options don't touch them).
 */
internal fun desktopMpvOptions(prefs: PlaybackPreferences): List<Pair<String, String>> = buildList {
    val style = prefs.subtitleStyle.normalized()
    when (prefs.subtitleSize) {
        SubtitleSize.Small -> add("sub-scale" to "0.75")
        SubtitleSize.Normal -> add("sub-scale" to "1")
        SubtitleSize.Large -> add("sub-scale" to "1.25")
        SubtitleSize.ExtraLarge -> add("sub-scale" to "1.5")
        SubtitleSize.System -> {}
    }
    when (prefs.subtitleBackground) {
        SubtitleBackground.None -> { add("sub-border-size" to "0"); add("sub-shadow-offset" to "0") }
        SubtitleBackground.Shadow -> { add("sub-border-size" to "0"); add("sub-shadow-offset" to "2"); add("sub-shadow-color" to "#CC000000") }
        SubtitleBackground.Box -> {
            add("sub-border-style" to "background-box")
            add("sub-back-color" to (mpvColor(style.backgroundColor, style.backgroundOpacity) ?: "#CC000000"))
        }
        SubtitleBackground.System -> {}
    }
    mpvColor(style.foreground, style.textOpacity)?.let { add("sub-color" to it) }
        ?: if (style.textOpacity != 100) add("sub-color" to mpvColor("White", style.textOpacity)!!) else Unit
    when (style.edge) {
        "None" -> add("sub-border-size" to "0")
        "Outline" -> { add("sub-border-size" to "3"); add("sub-border-color" to mpvColor(style.edgeColor, 100)!!) }
        "Shadow" -> { add("sub-shadow-offset" to "2"); add("sub-shadow-color" to mpvColor(style.edgeColor, 80)!!) }
    }
    when (style.position) {
        "Bottom" -> add("sub-pos" to "100")
        "Middle" -> add("sub-pos" to "55")
        "Top" -> add("sub-pos" to "10")
    }
    if (style.bold) add("sub-bold" to "yes")
    if (style.italic) add("sub-italic" to "yes")
    if (style.overrideEmbedded) add("sub-ass-override" to "force")
    desktopSpdifCodecs(prefs).takeIf { it.isNotEmpty() }?.let { add("audio-spdif" to it.joinToString(",")) }
    when {
        prefs.tuning.downmix || prefs.tuning.maxAudioChannels == 2 -> add("audio-channels" to "stereo")
        prefs.tuning.maxAudioChannels == 6 -> add("audio-channels" to "5.1,stereo,mono")
        prefs.tuning.maxAudioChannels == 8 -> add("audio-channels" to "7.1,5.1,stereo,mono")
    }
}

/**
 * PLAY-30 on desktop: when the viewer last pressed a key or clicked, and how many episodes started by themselves since.
 * The controller's window listener reports input; the end countdown reports each automatic start.
 */
internal class DesktopViewerInput(private val now: () -> Long = { System.nanoTime() }) {
    @Volatile private var last = now()
    @Volatile var automaticAdvances = 0; private set
    fun input() { last = now(); automaticAdvances = 0 }
    fun automaticallyAdvanced() { automaticAdvances++ }
    fun due(policy: StillWatching): Boolean = stillWatchingDue(policy, automaticAdvances + 1, (now() - last).coerceAtLeast(0) / 60_000_000_000L)
}

/** SET-13: a server this computer reaches on a private, loopback or link-local address is Home; anything else Remote. */
internal fun desktopPlaybackNetwork(serverUrl: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): PlaybackNetwork {
    val addresses = runCatching { resolve(requireNotNull(URI(serverUrl).host)) }.getOrDefault(emptyList())
    val local = addresses.isNotEmpty() && addresses.all {
        it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || PlaybackNetworkPolicy.isUniqueLocalAddress(it.address)
    }
    // Desktop operating systems don't say whether a connection is metered, so Metered is never inferred.
    return PlaybackNetworkPolicy.classify(metered = false, localEndpoint = local)
}

/** The extras' network side: chapters, trickplay and segments for a viewing, and trickplay sheets for the preview. */
internal class DesktopPlayerExtrasLoader(private val http: HttpClient, private val identity: ClientIdentity) {
    private val client = PlaybackExtrasClient(http, identity)

    /** Failures leave the extras empty: a viewing never fails because chapters or segments couldn't load (PLAY-14). */
    suspend fun load(account: Account, item: MediaItem, mediaSourceId: String?): DesktopPlayerExtras {
        val full = (client.item(account, item.id) as? ClientResult.Success)?.value
        val segments = (client.segments(account, item.id) as? ClientResult.Success)?.value.orEmpty()
        return DesktopPlayerExtras(
            chapters = full?.chapters.orEmpty().sortedBy { it.startTicks },
            trickplay = full?.let { chooseTrickplay(it, mediaSourceId, 320) },
            segments = segments
        )
    }

    /** One trickplay sheet's JPEG bytes, or null. Same origin as the server, with the viewer's token. */
    suspend fun sheet(account: Account, itemId: String, mediaSourceId: String, width: Int, sheet: Int): ByteArray? = runCatching {
        val response = http.get(trickplaySheetUrl(account.server, itemId, mediaSourceId, width, sheet)) {
            header("Authorization", AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
        }
        if (response.status.isSuccess()) response.readRawBytes() else null
    }.getOrNull()
}

/** SET-13/19: the profile carries the effective cap for this network, and the audio channel limit for conversions. */
internal fun DeviceProfile.forDesktopPreferences(prefs: PlaybackPreferences, maxBitrate: Int): DeviceProfile {
    val channels = if (prefs.tuning.downmix) 2 else prefs.tuning.normalized().maxAudioChannels
    return copy(
        maxStreamingBitrate = maxBitrate, maxStaticBitrate = maxBitrate,
        transcodingProfiles = transcodingProfiles.map { if (channels != null) it.copy(maxAudioChannels = channels.toString()) else it }
    )
}
