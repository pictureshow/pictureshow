/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.SubtitleBackground
import app.pictureshow.core.playback.SubtitleSize
import app.pictureshow.mpv.MpvEvent
import app.pictureshow.mpv.MpvNative
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.util.Locale

/** PLAY-60: whether this device can run mpv at all (its ABI has the libraries and they load). */
/**
 * mpv's OpenGL output. Not gpu-next: its hardware-decoding interop on Android (AImageReader) needs the plain GL
 * renderer, and with gpu-next a MediaCodec decoder bound to a surface stalled before the first frame (OBSERVED on the
 * Google TV emulator).
 */
private const val VIDEO_OUTPUT = "gpu"

/**
 * The emulator's MediaCodec decoders take FFmpeg's packets and never return a frame, which holds mpv's core (and the
 * player calls waiting on it) before the first picture (OBSERVED on the Google TV emulator, H.264, with and without a
 * surface). Emulators decode in software; every real device tries MediaCodec first.
 */
private fun isEmulator(): Boolean =
    android.os.Build.HARDWARE.let { it == "ranchu" || it == "goldfish" } || android.os.Build.FINGERPRINT.contains("generic")

internal object MpvAvailability {
    val available: Boolean by lazy { MpvNative.load() }

    /** libass finds fonts through fontconfig, which Android doesn't configure: point it at the system fonts once. */
    fun prepareFonts(context: Context) {
        val directory = File(context.cacheDir, "mpv-fonts").apply { mkdirs() }
        val config = File(directory, "fonts.conf")
        if (!config.exists()) {
            config.writeText(
                """<?xml version="1.0"?>
                |<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                |<fontconfig>
                |  <dir>/system/fonts</dir>
                |  <dir>/product/fonts</dir>
                |  <cachedir>${directory.absolutePath}/cache</cachedir>
                |  <alias><family>sans-serif</family><prefer><family>Roboto</family></prefer></alias>
                |</fontconfig>
                |
                """.trimMargin()
            )
        }
        Os.setenv("FONTCONFIG_FILE", config.absolutePath, true)
    }
}

/** What an mpv viewing needs from the output: where to read, and the viewer's device choices. */
internal data class MpvSource(
    val url: String,
    val title: String?,
    val itemId: String,
    val startMs: Long,
    val durationMs: Long?,
    val streams: List<MediaStream>,
    /** External text subtitles: the server stream index and its loopback URL. */
    val externalSubtitles: List<Pair<MediaStream, String>>,
    val transcoding: Boolean,
    val spdif: List<String>,
    val subtitles: SubtitleAppearance = SubtitleAppearance()
)

/**
 * SET-15's subtitle choices as mpv options. "System" keeps mpv's own look (white text, black outline): mpv can't read
 * Android's caption settings. Styled (ASS) subtitles keep their authored look unless the viewer overrides it, as under
 * Media3; image subtitles take no styling in mpv.
 */
internal fun mpvSubtitleOptions(appearance: SubtitleAppearance): Map<String, String> = buildMap {
    val style = appearance.styling.normalized()
    fun color(name: String, opacity: Int): String {
        val rgb = when (name) {
            "Yellow" -> "FFFF00"
            "Black" -> "000000"
            "Green" -> "00FF00"
            "Cyan" -> "00FFFF"
            else -> "FFFFFF"
        }
        return "#%02X%s".format(Locale.ROOT, (opacity.coerceIn(0, 100) * 255 + 50) / 100, rgb)
    }
    if (style.foreground != "System" || style.textOpacity != 100) put("sub-color", color(style.foreground, style.textOpacity))
    when (style.edge) {
        "None" -> {
            put("sub-border-size", "0")
            put("sub-shadow-offset", "0")
        }
        "Outline" -> {
            put("sub-border-size", "3")
            put("sub-border-color", color(style.edgeColor, 100))
            put("sub-shadow-offset", "0")
        }
        "Shadow" -> {
            put("sub-border-size", "0")
            put("sub-shadow-offset", "2")
            put("sub-shadow-color", color(style.edgeColor, 100))
        }
    }
    when (appearance.background) {
        SubtitleBackground.Box -> {
            put("sub-border-style", "background-box")
            put("sub-back-color", color(style.backgroundColor, style.backgroundOpacity))
        }
        SubtitleBackground.Shadow -> if (style.edge == "System") put("sub-shadow-offset", "2")
        SubtitleBackground.None, SubtitleBackground.System -> Unit
    }
    when (appearance.size) {
        SubtitleSize.Small -> "0.75"
        SubtitleSize.Large -> "1.25"
        SubtitleSize.ExtraLarge -> "1.5"
        SubtitleSize.Normal, SubtitleSize.System -> null
    }?.let { put("sub-scale", it) }
    when (style.position) {
        "Top" -> "top"
        "Middle" -> "center"
        "Bottom" -> "bottom"
        else -> null
    }?.let { put("sub-align-y", it) }
    if (style.bold) put("sub-bold", "yes")
    if (style.italic) put("sub-italic", "yes")
    put("sub-ass-override", if (style.overrideEmbedded) "force" else "scale")
}

/**
 * A Media3 [Player] over libmpv, so the existing output, session, controls, media session and frame-rate matching
 * drive mpv as they drive ExoPlayer. mpv's events arrive on a reader thread and are applied on the main looper; the
 * reader thread owns the handle's destruction, after mpv says it shut down.
 */
@UnstableApi
internal class MpvPlayer(context: Context, private val source: MpvSource) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val handle: Long
    private var released = false
    private var prepared = false

    /** Prepared before a Surface arrived: mpv loads once it has one, or its video output would fail to start. */
    private var loadPending = false
    private var loaded = false
    private var restarted = false
    private var ended = false
    private var buffering = false
    private var paused = true
    private var playWhenReady = false
    private var positionMs = source.startMs
    private var durationMs = source.durationMs ?: C.TIME_UNSET
    private var cacheMs = 0L
    private var seekable = true
    private var width = 0
    private var height = 0
    private var frameRate = -1f
    private var firstFrame = false
    private var firstFramePending = false
    private var speed = 1f
    private var error: PlaybackException? = null
    private var pendingSeek: SettableFuture<*>? = null
    private var surface: Surface? = null
    private var holder: SurfaceHolder? = null
    private var tracksRevision = 0L
    private var trackList: List<MpvTrack> = emptyList()
    private val externalIds = mutableMapOf<Int, Int>()

    /** Server stream index the viewer chose, applied once mpv has published its tracks. */
    private var wantedAudio: Int? = null
    private var wantedSubtitle: Int? = null

    /** For Playback info: what mpv decodes with, and the video's transfer. */
    var hwdec: String? = null
        private set
    var videoCodec: String? = null
        private set
    var audioCodec: String? = null
        private set
    var gamma: String? = null
        private set

    init {
        MpvAvailability.prepareFonts(context)
        handle = MpvNative.create(context.applicationContext)
        check(handle != 0L) { "mpv could not be created" }
        val options = linkedMapOf(
            "config" to "no",
            "terminal" to "no",
            "idle" to "yes",
            "keep-open" to "yes",
            "force-window" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "ytdl" to "no",
            "load-scripts" to "no",
            "vo" to VIDEO_OUTPUT,
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to if (isEmulator()) "no" else "mediacodec,mediacodec-copy",
            "hwdec-codecs" to "all",
            "ao" to "audiotrack",
            "audio-spdif" to source.spdif.joinToString(","),
            "cache" to "yes",
            "demuxer-max-bytes" to "64MiB",
            "demuxer-max-back-bytes" to "32MiB",
            "network-timeout" to "30",
            "embeddedfonts" to "yes",
            "sub-auto" to "no",
            "audio-file-auto" to "no",
            "alang" to Locale.getDefault().language,
            "slang" to Locale.getDefault().language,
            "msg-level" to "all=warn"
        )
        options.putAll(mpvSubtitleOptions(source.subtitles))
        options.forEach { (name, value) -> MpvNative.setOptionString(handle, name, value) }
        val result = MpvNative.initialize(handle)
        check(result >= 0) { "mpv could not start ($result)" }
        MpvNative.requestLogMessages(handle, "warn")
        OBSERVED.forEachIndexed { id, (name, format) -> MpvNative.observeProperty(handle, id.toLong(), name, format) }
        Thread({ readEvents() }, "mpv-events").apply { isDaemon = true }.start()
    }

    private fun readEvents() {
        while (true) {
            val event = MpvNative.waitEvent(handle, -1.0) ?: continue
            if (event.id == MpvEvent.SHUTDOWN) break
            if (event.id == MpvEvent.LOG_MESSAGE) {
                // Never log URLs: mpv's messages name the loopback path, which carries the proxy secret.
                Log.w(TAG, event.text.orEmpty().replace(Regex("""http://127\.0\.0\.1:\d+/\S*"""), "<stream>").trimEnd())
                continue
            }
            main.post { if (!released) apply(event) }
        }
        MpvNative.destroy(handle)
    }

    private fun apply(event: MpvEvent) {
        when (event.id) {
            MpvEvent.START_FILE -> {
                buffering = true
                restarted = false
            }
            MpvEvent.FILE_LOADED -> {
                loaded = true
                refreshTracks()
                applyWantedTracks()
            }
            MpvEvent.PLAYBACK_RESTART -> {
                restarted = true
                buffering = false
                pendingSeek?.set(null)
                pendingSeek = null
                if (!firstFrame && width > 0) {
                    firstFrame = true
                    firstFramePending = true
                }
            }
            MpvEvent.SEEK -> buffering = true
            MpvEvent.VIDEO_RECONFIG -> refreshVideo()
            MpvEvent.END_FILE -> when (event.endReason) {
                MpvEvent.END_ERROR -> fail(event.error)
                MpvEvent.END_EOF -> ended = true
                else -> Unit
            }
            MpvEvent.PROPERTY_CHANGE -> property(event)
            else -> return
        }
        invalidateState()
    }

    private fun property(event: MpvEvent) {
        val none = event.format == MpvNative.FORMAT_NONE
        when (event.name) {
            "time-pos" -> if (!none && !event.doubleValue.isNaN()) positionMs = (event.doubleValue * 1000).toLong()
            "duration" -> if (!none && !event.doubleValue.isNaN()) durationMs = (event.doubleValue * 1000).toLong()
            "pause" -> paused = event.longValue != 0L
            "paused-for-cache" -> buffering = event.longValue != 0L && !none
            "eof-reached" -> if (event.longValue != 0L && !none) ended = true
            "seekable" -> if (!none) seekable = event.longValue != 0L
            "demuxer-cache-duration" -> cacheMs = if (none || event.doubleValue.isNaN()) 0 else (event.doubleValue * 1000).toLong()
            "track-list/count" -> refreshTracks()
            "hwdec-current" -> hwdec = event.stringValue
            "video-params/gamma" -> gamma = event.stringValue
            "estimated-vf-fps", "container-fps" -> if (!none && event.doubleValue > 0) {
                val rate = event.doubleValue.toFloat()
                if (event.name == "container-fps" || frameRate <= 0f) {
                    frameRate = rate
                    tracksRevision++
                }
            }
            "dwidth", "dheight" -> refreshVideo()
        }
    }

    private fun refreshVideo() {
        val w = MpvNative.getPropertyLong(handle, "dwidth")
        val h = MpvNative.getPropertyLong(handle, "dheight")
        if (w > 0 && h > 0 && (w.toInt() != width || h.toInt() != height)) {
            width = w.toInt()
            height = h.toInt()
            tracksRevision++
        }
        if (!firstFrame && width > 0 && restarted) {
            firstFrame = true
            firstFramePending = true
        }
    }

    private fun fail(code: Int) {
        val playbackCode = if (code == MPV_ERROR_LOADING_FAILED || code == MPV_ERROR_UNKNOWN_FORMAT) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        } else {
            PlaybackException.ERROR_CODE_DECODING_FAILED
        }
        error = PlaybackException("mpv stopped ($code)", null, playbackCode)
        pendingSeek?.set(null)
        pendingSeek = null
    }

    private fun refreshTracks() {
        val count = MpvNative.getPropertyLong(handle, "track-list/count").coerceAtLeast(0)
        trackList = (0 until count).map { i ->
            fun text(name: String) = MpvNative.getPropertyString(handle, "track-list/$i/$name")
            MpvTrack(
                id = MpvNative.getPropertyLong(handle, "track-list/$i/id").toInt(),
                type = text("type").orEmpty(),
                external = MpvNative.getPropertyFlag(handle, "track-list/$i/external") == 1,
                selected = MpvNative.getPropertyFlag(handle, "track-list/$i/selected") == 1,
                ffIndex = MpvNative.getPropertyLong(handle, "track-list/$i/ff-index").takeIf { it >= 0 }?.toInt(),
                codec = text("codec")
            )
        }
        videoCodec = trackList.firstOrNull { it.type == "video" && it.selected }?.codec
        audioCodec = trackList.firstOrNull { it.type == "audio" && it.selected }?.codec
        tracksRevision++
    }

    /**
     * Server stream index to mpv track id. Embedded streams pair by their order within a type (the server can number
     * external subtitles before embedded streams, so its index isn't the container's); external subtitles by the id
     * mpv gave them when they were added.
     */
    private fun trackId(type: MediaStreamType, index: Int): Int? {
        if (type == MediaStreamType.Subtitle) externalIds[index]?.let { return it }
        val mpvType = if (type == MediaStreamType.Audio) "audio" else "sub"
        val embedded = source.streams.filter { it.type == type && !PlaybackMedia.deliveredExternally(it) }.sortedBy { it.index }
        val position = embedded.indexOfFirst { it.index == index }.takeIf { it >= 0 } ?: return null
        val tracks = trackList.filter { it.type == mpvType && !it.external }.sortedBy { it.ffIndex ?: it.id }
        return tracks.getOrNull(position)?.id
    }

    /** Selects a server stream; false when mpv has no matching track (the session then asks the server instead). */
    fun selectStream(type: MediaStreamType, index: Int): Boolean {
        if (released) return false
        if (type == MediaStreamType.Audio) wantedAudio = index else wantedSubtitle = index
        if (!loaded) return true
        val property = if (type == MediaStreamType.Audio) "aid" else "sid"
        if (index < 0) return MpvNative.setPropertyString(handle, property, "no") >= 0
        // A transcode carries one audio stream: another one is the server's job.
        if (source.transcoding && type == MediaStreamType.Audio) return false
        val id = trackId(type, index) ?: return false
        return MpvNative.setPropertyLong(handle, property, id.toLong()) >= 0
    }

    /** Subtitle delay (PLAY-31): positive shows subtitles later, as mpv's sub-delay does. */
    fun subtitleDelayMs(milliseconds: Int) {
        if (!released) MpvNative.setPropertyDouble(handle, "sub-delay", milliseconds / 1000.0)
    }

    private fun applyWantedTracks() {
        source.externalSubtitles.forEach { (stream, url) ->
            if (stream.index in externalIds) return@forEach
            val before = trackList.filter { it.type == "sub" }.map { it.id }.toSet()
            val title = stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}"
            MpvNative.command(handle, arrayOf("sub-add", url, "auto", title, stream.language ?: ""))
            refreshTracks()
            trackList.firstOrNull { it.type == "sub" && it.id !in before }?.let { externalIds[stream.index] = it.id }
        }
        wantedAudio?.let { selectStream(MediaStreamType.Audio, it) }
        wantedSubtitle?.let { selectStream(MediaStreamType.Subtitle, it) } ?: MpvNative.setPropertyString(handle, "sid", "no")
    }

    override fun getState(): State {
        val playbackState = when {
            error != null || !prepared -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            !loaded || !restarted || buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        // mpv reports time-pos on every frame, so the position is what mpv last said. Extrapolating from when the
        // state was built ran ahead of a CPU-starved decoder and then jumped back (OBSERVED on the emulator).
        val position = PositionSupplier.getConstant(positionMs)
        val item = MediaItemData.Builder(source.itemId)
            .setMediaItem(MediaItem.Builder().setMediaId(source.itemId).setUri(PLACEHOLDER_URI).build())
            .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
            .setIsSeekable(seekable)
            .setIsPlaceholder(false)
            .setTracks(tracks())
            .build()
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(error)
            .setIsLoading(buffering)
            .setContentPositionMs(position)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(positionMs + cacheMs))
            .setTotalBufferedDurationMs(PositionSupplier.getConstant(cacheMs))
            .setPlaybackParameters(PlaybackParameters(speed))
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setVideoSize(if (width > 0 && height > 0) VideoSize(width, height) else VideoSize.UNKNOWN)
        if (firstFramePending) {
            firstFramePending = false
            builder.setNewlyRenderedFirstFrame(true)
        }
        return builder.build()
    }

    private var cachedTracks: Pair<Long, Tracks>? = null

    /** A video group carrying the frame rate (frame-rate matching reads it) and size; audio/text come from the server. */
    private fun tracks(): Tracks {
        cachedTracks?.takeIf { it.first == tracksRevision }?.let { return it.second }
        val video = Format.Builder()
            .setId("mpv-video")
            .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
            .setCodecs(videoCodec)
            .setWidth(width.takeIf { it > 0 } ?: Format.NO_VALUE)
            .setHeight(height.takeIf { it > 0 } ?: Format.NO_VALUE)
            .setFrameRate(frameRate)
            .build()
        val groups = if (loaded) {
            listOf(Tracks.Group(TrackGroup("mpv-video", video), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
        } else {
            emptyList()
        }
        return Tracks(groups).also { cachedTracks = tracksRevision to it }
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (prepared) return Futures.immediateVoidFuture()
        prepared = true
        error = null
        if (surface == null) loadPending = true else load()
        return Futures.immediateVoidFuture()
    }

    private fun load() {
        loadPending = false
        MpvNative.setPropertyFlag(handle, "pause", !playWhenReady)
        if (source.startMs > 0) MpvNative.setPropertyString(handle, "start", "%.3f".format(Locale.ROOT, source.startMs / 1000.0))
        source.title?.let { MpvNative.setPropertyString(handle, "force-media-title", it) }
        MpvNative.command(handle, arrayOf("loadfile", source.url, "replace"))
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (!released) MpvNative.setPropertyFlag(handle, "pause", !playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (released || !prepared) return Futures.immediateVoidFuture()
        val target = positionMs.coerceAtLeast(0)
        this.positionMs = target
        ended = false
        restarted = false
        pendingSeek?.set(null)
        val done = SettableFuture.create<Any?>()
        pendingSeek = done
        MpvNative.command(handle, arrayOf("seek", "%.3f".format(Locale.ROOT, target / 1000.0), "absolute"))
        return done
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        if (!released) MpvNative.setPropertyDouble(handle, "speed", speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!released) MpvNative.command(handle, arrayOf("stop"))
        prepared = false
        loadPending = false
        loaded = false
        restarted = false
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        detach()
        released = true
        pendingSeek?.set(null)
        pendingSeek = null
        holder?.removeCallback(surfaceCallback)
        holder = null
        // The reader thread destroys the handle once mpv reports its shutdown.
        MpvNative.command(handle, arrayOf("quit"))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> useHolder(videoOutput.holder)
            is SurfaceHolder -> useHolder(videoOutput)
            is Surface -> attach(videoOutput)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        holder?.removeCallback(surfaceCallback)
        holder = null
        detach()
        return Futures.immediateVoidFuture()
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = attach(holder.surface)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (!released) MpvNative.setPropertyString(handle, "android-surface-size", "${width}x$height")
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
    }

    private fun useHolder(next: SurfaceHolder) {
        if (holder === next) return
        holder?.removeCallback(surfaceCallback)
        holder = next
        next.addCallback(surfaceCallback)
        next.surface?.takeIf { it.isValid }?.let { valid ->
            attach(valid)
            val frame = next.surfaceFrame
            if (frame.width() > 0) MpvNative.setPropertyString(handle, "android-surface-size", "${frame.width()}x${frame.height()}")
        }
    }

    private fun attach(next: Surface) {
        if (released || surface === next) return
        if (surface != null) detach()
        surface = next
        MpvNative.attachSurface(handle, next)
        MpvNative.setOptionString(handle, "force-window", "yes")
        MpvNative.setPropertyString(handle, "vo", VIDEO_OUTPUT)
        if (loadPending && prepared) load()
    }

    private fun detach() {
        if (released || surface == null) return
        // mpv must stop drawing before the Surface goes away.
        MpvNative.setPropertyString(handle, "vo", "null")
        MpvNative.setOptionString(handle, "force-window", "no")
        MpvNative.detachSurface(handle)
        surface = null
    }

    private data class MpvTrack(
        val id: Int,
        val type: String,
        val external: Boolean,
        val selected: Boolean,
        val ffIndex: Int?,
        val codec: String?
    )

    companion object {
        private const val TAG = "PictureshowMpv"
        private const val SEEK_STEP_MS = 10_000L
        private const val MPV_ERROR_LOADING_FAILED = -13
        private const val MPV_ERROR_UNKNOWN_FORMAT = -17
        private const val PLACEHOLDER_URI = "about:mpv"
        private val OBSERVED = listOf(
            "time-pos" to MpvNative.FORMAT_DOUBLE,
            "duration" to MpvNative.FORMAT_DOUBLE,
            "pause" to MpvNative.FORMAT_FLAG,
            "paused-for-cache" to MpvNative.FORMAT_FLAG,
            "eof-reached" to MpvNative.FORMAT_FLAG,
            "seekable" to MpvNative.FORMAT_FLAG,
            "demuxer-cache-duration" to MpvNative.FORMAT_DOUBLE,
            "track-list/count" to MpvNative.FORMAT_INT64,
            "hwdec-current" to MpvNative.FORMAT_STRING,
            "video-params/gamma" to MpvNative.FORMAT_STRING,
            "container-fps" to MpvNative.FORMAT_DOUBLE,
            "estimated-vf-fps" to MpvNative.FORMAT_DOUBLE,
            "dwidth" to MpvNative.FORMAT_INT64,
            "dheight" to MpvNative.FORMAT_INT64
        )
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_VIDEO_SURFACE
        ).build()
    }
}
