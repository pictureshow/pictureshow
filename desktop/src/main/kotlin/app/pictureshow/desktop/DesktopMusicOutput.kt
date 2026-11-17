/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.PlayDecision
import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One queue entry as the output holds it: the entry's identity and the negotiated (or downloaded) source. */
data class DesktopMusicSource(val entry: String, val decision: PlayDecision, val local: Boolean = false)

/** What the output reports when asked. [index] is the playing position in what it holds, -1 when it holds nothing playable. */
data class DesktopMusicOutputState(
    val index: Int = -1,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val paused: Boolean = true,
    /** The last held item played to its end and the output stopped there. */
    val ended: Boolean = false,
    val buffering: Boolean = false
)

/**
 * The music output: an ordered list of sources it plays back to back (gapless where the engine can), separate from the
 * video surface. Every call comes from the music host's single step queue.
 */
interface DesktopMusicOutput : AutoCloseable {
    /** The audio codecs this output decodes, for the music device profile (MUS-06). */
    fun decoders(): Set<AudioCodec>
    fun replace(account: Account, sources: List<DesktopMusicSource>, startSeconds: Double, paused: Boolean)
    fun append(account: Account, sources: List<DesktopMusicSource>)
    /** Removes held items [from] until [to] (exclusive). */
    fun remove(from: Int, to: Int)
    fun paused(value: Boolean)
    fun seek(seconds: Double)
    fun volume(value: Int)
    /** Repeat the playing item by itself (Repeat One, or Repeat All over one entry). */
    fun loopCurrent(value: Boolean)
    fun state(): DesktopMusicOutputState
}

/** mpv's FFmpeg decoder names, as the music profile's codecs. PCM needs no decoder beyond FFmpeg's own pcm_* family. */
internal fun desktopMusicDecoders(codecs: Set<String>): Set<AudioCodec> = AudioCodec.entries.filter { codec ->
    codec.serverNames.any { it in codecs } || (codec == AudioCodec.PCM && codecs.any { it.startsWith("pcm_") })
}.toSet()

/** A downloaded track plays from its file; mpv takes a plain path, not a file: URI. Null for anything else. */
internal fun desktopLocalMusicPath(url: String): String? = when {
    url.startsWith("file:", ignoreCase = true) -> runCatching { File(URI(url)).path }.getOrNull()
    url.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(url) -> url
    else -> null
}

/**
 * The audio-only libmpv instance (MUS-07): no video track, no window, its own log directory, one playlist that mpv
 * prefetches and plays with gapless audio. Server requests go through the same loopback proxy as video, so the token
 * never reaches mpv's options or its log.
 */
internal class MpvMusicOutput(private val directory: File, initialVolume: Int) : DesktopMusicOutput {
    private val mpv = Mpv(directory, probe = false)
    private var proxy: DesktopMediaProxy? = null
    private var proxyToken: String? = null
    private var cachedDecoders: Set<AudioCodec>? = null

    init {
        fun soft(name: String, value: String) { runCatching { mpv.option(name, value) } }
        mpv.option("vid", "no")
        soft("vo", "null")
        soft("force-window", "no")
        soft("audio-display", "no")
        soft("idle", "yes")
        soft("gapless-audio", "yes")
        soft("prefetch-playlist", "yes")
        soft("cache", "yes")
        mpv.option("keep-open", "yes")
        mpv.option("loop-file", "no")
        mpv.option("volume", initialVolume.coerceIn(0, 100).toString())
        if (System.getenv("SPIKE_NULL_AUDIO") == "1") soft("ao", "null")
        mpv.initialize()
    }

    override fun decoders(): Set<AudioCodec> = cachedDecoders ?: run {
        val text = mpv.get("decoder-list")
        val names = text?.let { runCatching { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonObject.getValue("codec").jsonPrimitive.content }.toSet() }.getOrNull() }
        // Without a list, claim only what every libmpv build decodes, so the server converts the rest to AAC.
        desktopMusicDecoders(names ?: setOf("aac", "mp3", "flac", "opus", "vorbis", "pcm_s16le")).also { cachedDecoders = it }
    }

    private fun url(account: Account, source: DesktopMusicSource): String {
        desktopLocalMusicPath(source.decision.url)?.let { return it }
        if (proxy == null || proxyToken != account.accessToken) {
            proxy?.close()
            proxy = DesktopMediaProxy(account, source.decision)
            proxyToken = account.accessToken
        }
        return proxy!!.url(source.decision.url)
    }

    override fun replace(account: Account, sources: List<DesktopMusicSource>, startSeconds: Double, paused: Boolean) {
        mpv.set("pause", if (paused) "yes" else "no")
        val first = sources.firstOrNull() ?: run { mpv.command("stop"); return }
        val target = url(account, first)
        if (startSeconds > 0.5) {
            // mpv 0.38+ takes per-file options after the insert index; older builds take them third.
            runCatching { mpv.command("loadfile", target, "replace", "-1", "start=$startSeconds") }
                .recoverCatching { mpv.command("loadfile", target, "replace", "start=$startSeconds") }
                .getOrElse { mpv.command("loadfile", target, "replace") }
        } else {
            mpv.command("loadfile", target, "replace")
        }
        append(account, sources.drop(1))
    }

    override fun append(account: Account, sources: List<DesktopMusicSource>) {
        sources.forEach { mpv.command("loadfile", url(account, it), "append") }
    }

    override fun remove(from: Int, to: Int) { repeat((to - from).coerceAtLeast(0)) { mpv.command("playlist-remove", from.toString()) } }
    override fun paused(value: Boolean) = mpv.set("pause", if (value) "yes" else "no")
    override fun seek(seconds: Double) { runCatching { mpv.command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute") } }
    override fun volume(value: Int) = mpv.set("volume", value.coerceIn(0, 100).toString())
    override fun loopCurrent(value: Boolean) = mpv.set("loop-file", if (value) "inf" else "no")

    override fun state(): DesktopMusicOutputState {
        val index = mpv.get("playlist-pos")?.toIntOrNull() ?: -1
        val count = mpv.get("playlist-count")?.toIntOrNull() ?: 0
        val eof = mpv.get("eof-reached") == "yes"
        return DesktopMusicOutputState(
            index = index,
            positionSeconds = mpv.get("time-pos")?.toDoubleOrNull() ?: 0.0,
            durationSeconds = mpv.get("duration")?.toDoubleOrNull() ?: 0.0,
            paused = mpv.get("pause") == "yes",
            ended = eof && index == count - 1,
            buffering = mpv.get("paused-for-cache") == "yes"
        )
    }

    override fun close() {
        runCatching { mpv.command("stop") }
        mpv.close()
        proxy?.close()
        proxy = null
    }
}
