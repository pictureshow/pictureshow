/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath

/** A reduced-rate hardware encode can have longer GOPs than Jellyfin's synthetic HLS timeline. */
object TranscodeSeek {
    fun required(decision: PlayDecision): Boolean {
        val source = decision.mediaSource
        if (decision.method != PlayMethod.Transcode ||
            source.isInfiniteStream ||
            source.liveStreamId != null ||
            source.runTimeTicks == null ||
            source.runTimeTicks <= 0 ||
            source.transcodingSubProtocol != MediaStreamProtocol.Hls ||
            !source.transcodingContainer.equals("ts", true)
        ) {
            return false
        }
        val uri = runCatching { Url(decision.url) }.getOrNull() ?: return false
        if (!uri.encodedPath.endsWith("/master.m3u8", true)) return false
        if (parameter(uri, "VideoCodec").equals("copy", true)) return false
        val video = source.mediaStreams.firstOrNull { it.type == MediaStreamType.Video } ?: return false
        val rate = video.realFrameRate ?: video.averageFrameRate ?: return false
        val cap = parameter(uri, "Framerate")?.toDoubleOrNull() ?: parameter(uri, "MaxFramerate")?.toDoubleOrNull() ?: return false
        return rate.isFinite() && cap.isFinite() && rate > 30 && cap > 0 && cap + 0.001 < rate && audioAlreadyEncodes(decision, uri)
    }

    // Progressive audio copy can include packets before the seek target. Only switch transport when
    // the negotiated output already requires audio encoding; changing copy to a lossy encode needs a viewer choice.
    private fun audioAlreadyEncodes(decision: PlayDecision, uri: Url): Boolean {
        val streams = decision.mediaSource.mediaStreams.filter { it.type == MediaStreamType.Audio }
        val index = parameter(uri, "AudioStreamIndex")?.toIntOrNull() ?: decision.mediaSource.defaultAudioStreamIndex
        if (streams.isEmpty() || index == -1) return true
        val audio = if (index != null) streams.firstOrNull { it.index == index } else streams.firstOrNull()
        if (audio == null) return false
        val targets = parameter(uri, "AudioCodec")?.split(',')?.map { it.trim() }
        if (targets?.any { it.equals("copy", true) } == true) return false
        if (parameter(uri, "AllowAudioStreamCopy").equals("false", true) ||
            parameter(uri, "EnableAutoStreamCopy").equals("false", true)
        ) {
            return true
        }
        // PlaybackInfo can rewrite the selected audio stream to the target codec/channel count.
        // These server reasons preserve the source incompatibility after that rewrite.
        if ("AudioChannelsNotSupported" in decision.transcodeReasons) return true
        val codec = audio.codec?.takeIf { it.isNotBlank() } ?: return false
        if (targets.isNullOrEmpty() || targets.any { it.isBlank() }) return false
        if (targets.none { it.equals(codec, true) }) return true
        val channels = audio.channels ?: return false
        val maxChannels = listOf("$codec-audiochannels", "MaxAudioChannels", "AudioChannels", "TranscodingMaxAudioChannels")
            .firstNotNullOfOrNull { parameter(uri, it)?.toIntOrNull() }
        return maxChannels != null && channels > maxChannels
    }

    /** The server clamps fast seeks to five seconds before the end; keep the residual seek in the player. */
    fun startPositionMs(decision: PlayDecision, targetMs: Long): Long =
        targetMs.coerceIn(0, ((decision.mediaSource.runTimeTicks ?: 0) / Ticks.PER_MILLISECOND - 5_000).coerceAtLeast(0))

    /** Progressive TS uses the encode's real media clock and supports server byte-based throttling. */
    fun url(decision: PlayDecision, startMs: Long): String {
        val uri = Url(Redaction.stripApiKey(decision.url))
        return URLBuilder(uri).apply {
            encodedPath = uri.encodedPath.substringBeforeLast('/') + "/stream.ts"
            listOf("StartTimeTicks", "Static", "CopyTimestamps", "AllowAudioStreamCopy", "SubtitleMethod").forEach { name ->
                parameters.names().filter { it.equals(name, true) }.forEach(parameters::remove)
            }
            parameters.append("StartTimeTicks", Ticks.fromMillis(startMs.coerceAtLeast(0)).toString())
            parameters.append("Static", "false")
            parameters.append("CopyTimestamps", "false")
            // Eligibility already requires audio encoding; make the local-clock requirement explicit.
            parameters.append("AllowAudioStreamCopy", "false")
            parameters.append("SubtitleMethod", parameter(uri, "SubtitleMethod") ?: "External")
        }.buildString()
    }

    private fun parameter(uri: Url, name: String): String? = uri.parameters.entries()
        .firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}
