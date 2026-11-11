/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package app.pictureshow.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.playback.*
import kotlinx.serialization.json.*
import com.sun.jna.*
import com.sun.net.httpserver.HttpServer
import java.awt.Window
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Only platform effects sit behind this seam; controller tests use a deterministic fake. */
interface DesktopPlaybackEngine : AutoCloseable {
    fun profile(settings: DesktopSettings): DeviceProfile = desktopMpvProfile(settings)
    fun attach(window: Window)
    fun open(account: Account, decision: PlayDecision, position: Double, audio: Int, subtitle: Int, volume: Int)
    fun snapshot(): EngineSnapshot
    fun togglePause()
    fun seek(seconds: Double)
    fun volume(value: Int)
    fun fullscreen(enabled: Boolean)
    fun viewport(topRatio: Double, bottomRatio: Double) {}
    fun viewportReceipt(): String? = null
    fun selectedTracks(): DesktopSelectedTracks? = null
    fun selectAudio(index: Int): Boolean = false
    fun selectSubtitle(index: Int): Boolean = false
}
data class DesktopSelectedTracks(
    val audioId: Int?, val subtitleId: Int?, val subtitleText: String, val subtitleVisible: Boolean,
    val nativeVersion: String? = null, val ffmpegVersion: String? = null, val subtitleCodec: String? = null,
    val subtitleStartSeconds: Double? = null, val subtitleEndSeconds: Double? = null,
    val positionSeconds: Double? = null, val initialSubtitleIndex: Int? = null
)

data class EngineSnapshot(val positionSeconds: Double = 0.0, val durationSeconds: Double = 0.0, val paused: Boolean = false,
    val ready: Boolean = false, val ended: Boolean = false, val error: String? = null, val exitRequested: Boolean = false)

/** A per-viewing loopback capability. Every redirect and HLS reference is checked before credentials leave the process. */
internal class DesktopMediaProxy(private val account: Account, private val decision: PlayDecision) : AutoCloseable {
    private val requests = ConcurrentHashMap<String, String>()
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "desktop-media-http").apply { isDaemon = true } }
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).executor(executor).build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val secret = UUID.randomUUID().toString()
    private val bodies = ConcurrentHashMap.newKeySet<java.io.InputStream>()
    init {
        require(PlayDecisions.isSameOrigin(account.server, decision.url))
        server.executor = executor
        server.createContext("/$secret/") { exchange ->
            try {
                val id = exchange.requestURI.path.substringAfterLast('/')
                val target = requests[id] ?: run { exchange.sendResponseHeaders(404, -1); return@createContext }
                if (exchange.requestMethod !in listOf("GET", "HEAD")) { exchange.sendResponseHeaders(405, -1); return@createContext }
                val response = fetch(target, exchange.requestMethod, exchange.requestHeaders.getFirst("Range"), 0)
                val body = response.body(); bodies.add(body)
                try {
                    val type = response.headers().firstValue("Content-Type").orElse("application/octet-stream")
                    listOf("Content-Range", "Accept-Ranges", "Content-Type", "Last-Modified", "ETag").forEach { name -> response.headers().firstValue(name).ifPresent { exchange.responseHeaders.set(name, it) } }
                    val manifest = type.contains("mpegurl", true) || response.uri().path.endsWith(".m3u8", true)
                    if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(response.statusCode(), -1)
                    else if (manifest && response.statusCode() in 200..299) {
                        val bytes = body.readNBytes(4 * 1024 * 1024 + 1)
                        require(bytes.size <= 4 * 1024 * 1024) { "Manifest too large" }
                        val text = rewriteManifest(bytes.toString(Charsets.UTF_8), response.uri().toString())
                        val result = text.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                        exchange.sendResponseHeaders(response.statusCode(), result.size.toLong())
                        exchange.responseBody.write(result)
                    } else {
                        val length = response.headers().firstValueAsLong("Content-Length").orElse(0)
                        exchange.sendResponseHeaders(response.statusCode(), if (length == 0L) 0 else length)
                        body.copyTo(exchange.responseBody, 64 * 1024)
                    }
                } finally { bodies.remove(body); body.close() }
            } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(502, -1) } }
            finally { exchange.close() }
        }
        server.start()
    }
    fun url(url: String = decision.url): String {
        require(PlayDecisions.isSameOrigin(account.server, url)) { "Media URL left the server origin" }
        require(requests.size < 100_000) { "Too many media references" }
        val existing = requests.entries.firstOrNull { it.value == url }?.key
        val id = existing ?: UUID.randomUUID().toString().also { requests[it] = url }
        return "http://127.0.0.1:${server.address.port}/$secret/$id"
    }
    private fun fetch(url: String, method: String, range: String?, redirects: Int): HttpResponse<java.io.InputStream> {
        require(redirects <= 8 && PlayDecisions.isSameOrigin(account.server, url)) { "Unsafe media redirect" }
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody())
        decision.headers.forEach { (name, value) -> request.header(name, value) }
        if (range != null) request.header("Range", range)
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() in listOf(301, 302, 303, 307, 308)) {
            response.body().close()
            val next = URI(url).resolve(response.headers().firstValue("Location").orElseThrow()).toString()
            return fetch(next, method, range, redirects + 1)
        }
        return response
    }
    internal fun rewriteManifest(text: String, origin: String): String = text.lineSequence().joinToString("\n") { line ->
        fun resolve(ref: String): String = url(URI(origin).resolve(ref).toString())
        when {
            line.isBlank() -> line
            line.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${resolve(it.groupValues[1])}\"" }
            else -> resolve(line.trim())
        }
    }
    override fun close() { server.stop(0); bodies.forEach { runCatching { it.close() } }; client.shutdownNow(); executor.shutdownNow() }
}

class NativeDesktopPlayback(private val directory: File) : DesktopPlaybackEngine {
    private var window: Window? = null
    private var mpv: Mpv? = null
    private var proxy: DesktopMediaProxy? = null
    private var layer: NativeLayer? = null
    @Volatile private var surface: Pointer? = null
    private var nativeOwner: DesktopNativeOwner<Pointer>? = null
    private val playerRelease = DesktopPlayerRelease { mpv?.close(); mpv = null }
    private val stopped = AtomicBoolean(false)
    private val failure = AtomicReference<String?>(null)
    private val pixels = AtomicReference(Dimension(1280, 800))
    private val viewportMargins = AtomicReference(0.0 to 0.0)
    private val viewportReady = AtomicBoolean(false)
    private val render = Executors.newSingleThreadExecutor { r -> Thread(r, "pictureshow-native-render").apply { isDaemon = true } }
    private var listener: ComponentAdapter? = null
    private var linux: LinuxPlayerWindow? = null
    private var savedTitle: String? = null
    private var offset = 0.0
    private var sourceDecision: PlayDecision? = null
    private var sourceAccount: Account? = null
    private var initialSubtitleIndex: Int? = null
    @Volatile private var pendingExternalSubtitle: String? = null
    override fun profile(settings: DesktopSettings): DeviceProfile {
        // decoder-list is a documented read-only native property; query the loaded bytes,
        // with no media, audio or video surface, before advertising a server profile.
        val probe = Mpv(directory, probe = true)
        try {
            probe.option("vo", "null"); probe.option("ao", "null"); probe.option("terminal", "no")
            probe.initialize()
            val text = requireNotNull(probe.get("decoder-list")) { "libmpv did not report its decoders" }
            val codecs = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject.getValue("codec").jsonPrimitive.content }.toSet()
            require("h264" in codecs && "aac" in codecs) { "This libmpv build cannot play the H.264/AAC conversion fallback" }
            File(directory, "decoder-envelope.json").writeText(buildJsonObject {
                put("source", "actual loaded libmpv decoder-list")
                put("codecs", JsonArray(codecs.sorted().map(::JsonPrimitive)))
            }.toString())
            return desktopMpvProfile(settings, codecs)
        } finally { probe.close() }
    }
    override fun attach(window: Window) { this.window = window }
    override fun open(account: Account, decision: PlayDecision, position: Double, audio: Int, subtitle: Int, volume: Int) {
        check(mpv == null)
        sourceDecision = decision; sourceAccount = account; initialSubtitleIndex = subtitle
        directory.mkdirs()
        val player = Mpv(directory, probe = false); mpv = player
        player.option("vo", "libmpv")
        player.option("loop-file", "no")
        player.option("volume", volume.toString())
        player.option("keep-open", "yes")
        player.option("sub-use-margins", "no")
        player.option("sub-pos", "100")
        player.option("sub-margin-y", "24")
        proxy = DesktopMediaProxy(account, decision)
        if (Platform.isLinux()) {
            val hdr = decision.mediaSource.mediaStreams.any { it.type == MediaStreamType.Video && it.videoRangeType !in listOf(null, VideoRangeType.Unknown, VideoRangeType.SDR) }
            if (hdr) { player.option("target-trc", "pq"); player.option("target-prim", "bt.2020"); player.option("target-peak", "10000"); player.option("fbo-format", "rgba16f"); player.option("dither-depth", "10") }
            player.option("video-timing-offset", "0")
            player.initialize()
            linux = LinuxPlayerWindow(player, directory, hdr, ::togglePause, { seek(snapshot().positionSeconds + it) }, playerRelease::release)
            linux!!.start()
        } else {
            val w = requireNotNull(window) { "The player needs a desktop window" }
            val bridge = Native.load(System.getenv("SPIKE_LAYER_BRIDGE") ?: System.getProperty("spike.layer.bridge") ?: if (Platform.isMac()) "pictureshow_surface" else "pictureshow_layer", NativeLayer::class.java)
            layer = bridge
            player.option("video-timing-offset", if (Platform.isMac()) "0.050" else "0")
            player.option("target-trc", "linear"); player.option("target-prim", "bt.2020"); player.option("target-peak", "10000")
            player.option("fbo-format", if (Platform.isMac()) "rgba16f" else "rgba16hf"); player.option("dither-depth", "no")
            val title = "Pictureshow player ${UUID.randomUUID()}"
            savedTitle = (w as? java.awt.Frame)?.title
            onEdt { (w as? java.awt.Frame)?.title = title }
            val view = if (Platform.isMac()) requireNotNull(bridge.ps_find_window(title)) { "Player window is not visible" } else Native.getWindowPointer(w)
            if (Platform.isMac()) {
                val reference = bridge.ps_edr_reference_white(view)
                check(reference == 100.0 || reference == 203.0)
                val shader = File(directory, "edr-reference.glsl")
                shader.writeText("//!HOOK OUTPUT\n//!BIND HOOKED\nvec4 hook() { vec4 c = HOOKED_tex(HOOKED_pos); return vec4(c.rgb * (203.0 / $reference), c.a); }\n")
                player.option("glsl-shaders", shader.absolutePath)
            } else {
                val shader = File(directory, "scrgb-reference.glsl")
                shader.writeText("//!HOOK OUTPUT\n//!BIND HOOKED\nvec4 hook() { vec4 c = HOOKED_tex(HOOKED_pos); return vec4((mat3(1.660491,-0.124550,-0.018151, -0.587641,1.132900,-0.100579, -0.072850,-0.008349,1.118730) * c.rgb) * (203.0 / 80.0), c.a); }\n")
                player.option("glsl-shaders", shader.absolutePath)
            }
            player.initialize()
            val owner = DesktopNativeOwner(
                create = {
                    check(!stopped.get()) { "The viewing ended before native initialization" }
                    requireNotNull(bridge.ps_create(view, player.handle)) { "Native video surface could not be created" }.also { surface = it }
                },
                frames = { target ->
                    while (!stopped.get()) {
                        val p = pixels.get()
                        check(bridge.ps_frame(target, p.width, p.height) >= 0) { "Native video frame failed" }
                        if (Platform.isWindows()) Thread.sleep(1)
                    }
                },
                finish = { target -> check(bridge.ps_finish(target) >= 0) { "Native GPU drain failed" } },
                destroy = { target -> bridge.ps_destroy(target); surface = null },
                player = playerRelease,
                failed = { if (!stopped.get()) failure.set("The video surface stopped. Return to the title and try again.") }
            )
            nativeOwner = owner
            render.submit(owner)
            val createdSurface = owner.created.get(40, TimeUnit.SECONDS)
            if (Platform.isMac()) File(directory, "native-surface.json").writeText(bridge.ps_receipt(createdSurface))
            fun sync() { if (stopped.get()) return; val t = w.graphicsConfiguration.defaultTransform; pixels.set(Dimension((w.width * t.scaleX).toInt(), (w.height * t.scaleY).toInt())); surface?.let(bridge::ps_sync) }
            onEdt {
                listener = object : ComponentAdapter() { override fun componentMoved(e: ComponentEvent) = sync(); override fun componentResized(e: ComponentEvent) = sync() }
                w.addComponentListener(listener); sync()
            }
        }
        viewportReady.set(true)
        applyViewport()
        // mpv's stream IDs are per-type ordinals, while Jellyfin's indices span all media types.
        val streams = decision.mediaSource.mediaStreams
        fun ordinal(index: Int, type: MediaStreamType) = streams.filter { it.type == type && !it.isExternal }.indexOfFirst { it.index == index }.takeIf { it >= 0 }?.plus(1)
        player.optionIfInitialized("aid", ordinal(audio, MediaStreamType.Audio)?.toString() ?: "auto")
        player.optionIfInitialized("sid", ordinal(subtitle, MediaStreamType.Subtitle)?.toString() ?: "no")
        offset = if (decision.method == PlayMethod.Transcode) position else 0.0
        if (decision.method != PlayMethod.Transcode && position > 0) player.set("start", position.toString())
        player.command("loadfile", proxy!!.url())
        // External subtitle responses receive the same origin checks and headers as media.
        streams.firstOrNull { it.type == MediaStreamType.Subtitle && it.index == subtitle && it.isExternal }?.deliveryUrl?.let { delivery ->
            val url = PlayDecisions.resolve(account.server, delivery)
            require(PlayDecisions.isSameOrigin(account.server, url))
            pendingExternalSubtitle = proxy!!.url(url)
        }
    }
    @Synchronized override fun snapshot(): EngineSnapshot {
        val p = mpv ?: return EngineSnapshot(error = failure.get() ?: linux?.error(), exitRequested = linux?.exitRequested == true)
        val ready = p.get("vo-configured") == "yes" && p.get("time-pos") != null
        if (ready) pendingExternalSubtitle?.let { url ->
            p.command("sub-add", url, "select"); pendingExternalSubtitle = null
        }
        return EngineSnapshot(
            positionSeconds = (p.get("time-pos")?.toDoubleOrNull() ?: 0.0) + offset,
            durationSeconds = (p.get("duration")?.toDoubleOrNull() ?: 0.0) + offset,
            paused = p.get("pause") == "yes", ready = ready,
            ended = p.get("eof-reached") == "yes", error = failure.get() ?: linux?.error(), exitRequested = linux?.exitRequested == true
        )
    }
    @Synchronized override fun selectAudio(index: Int): Boolean {
        val d = sourceDecision ?: return false
        if (d.method == PlayMethod.Transcode) return false
        val ordinal = d.mediaSource.mediaStreams.filter { it.type == MediaStreamType.Audio && !it.isExternal }.indexOfFirst { it.index == index }
        if (ordinal < 0) return false
        mpv?.set("aid", (ordinal + 1).toString()); return true
    }
    @Synchronized override fun selectSubtitle(index: Int): Boolean {
        val d = sourceDecision ?: return false
        if (d.method == PlayMethod.Transcode) return false
        pendingExternalSubtitle = null
        if (index == -1) { mpv?.set("sid", "no"); return true }
        val stream = d.mediaSource.mediaStreams.firstOrNull { it.type == MediaStreamType.Subtitle && it.index == index } ?: return false
        if (stream.isExternal) {
            val account = sourceAccount ?: return false
            val delivery = stream.deliveryUrl ?: return false
            val url = PlayDecisions.resolve(account.server, delivery)
            if (!PlayDecisions.isSameOrigin(account.server, url)) return false
            pendingExternalSubtitle = requireNotNull(proxy).url(url)
        } else {
            val ordinal = d.mediaSource.mediaStreams.filter { it.type == MediaStreamType.Subtitle && !it.isExternal }.indexOfFirst { it.index == index }
            if (ordinal < 0) return false
            mpv?.set("sid", (ordinal + 1).toString())
        }
        return true
    }
    override fun togglePause() { mpv?.command("cycle", "pause") }
    override fun seek(seconds: Double) { mpv?.command("seek", (seconds - offset).coerceAtLeast(0.0).toString(), "absolute+exact") }
    override fun volume(value: Int) { mpv?.set("volume", value.coerceIn(0, 100).toString()) }
    override fun viewport(topRatio: Double, bottomRatio: Double) {
        if (!topRatio.isFinite() || !bottomRatio.isFinite() || topRatio < 0 || bottomRatio < 0 || topRatio + bottomRatio >= 1) return
        viewportMargins.set(topRatio to bottomRatio)
        applyViewport()
    }
    override fun selectedTracks(): DesktopSelectedTracks? {
        val player=mpv ?: return null
        return DesktopSelectedTracks(
            player.get("aid")?.toIntOrNull(), player.get("sid")?.toIntOrNull(),
            player.get("sub-text").orEmpty(), player.get("sub-visibility") == "yes",
            player.get("mpv-version"), player.get("ffmpeg-version"), player.get("sub-codec"),
            player.get("sub-start")?.toDoubleOrNull(), player.get("sub-end")?.toDoubleOrNull(),
            player.get("time-pos")?.toDoubleOrNull(), initialSubtitleIndex
        )
    }
    override fun viewportReceipt(): String? {
        if (Platform.isLinux() || stopped.get() || !viewportReady.get()) return null
        val dimensions=mpv?.get("osd-dimensions")?.let { Json.parseToJsonElement(it) } ?: return null
        val (top,bottom)=viewportMargins.get()
        return buildJsonObject { put("topRatio",top);put("bottomRatio",bottom);put("dimensions",dimensions) }.toString()
    }
    private fun applyViewport() {
        if (Platform.isLinux() || stopped.get() || !viewportReady.get()) return
        val (top,bottom)=viewportMargins.get()
        mpv?.set("video-margin-ratio-top", top.toString())
        mpv?.set("video-margin-ratio-bottom", bottom.toString())
    }
    override fun fullscreen(enabled: Boolean) { if (stopped.get()) return; linux?.fullscreen(enabled); surface?.let { layer?.ps_fullscreen(it, if (enabled) 1 else 0) } }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        var linuxReleased = linux == null
        val errors = runDesktopCleanupActions(
            { window?.let { w -> onEdt { listener?.let(w::removeComponentListener); listener = null } } },
            { linux?.close(); linux = null; linuxReleased = true },
            {
                val owner = nativeOwner
                if (owner != null) {
                    // One worker owns create -> frames -> finish -> destroy -> player release.
                    // A caller timeout never removes that worker's finally path or frees its mpv.
                    owner.completed.get(35, TimeUnit.SECONDS)
                } else if (linuxReleased) playerRelease.release()
                else error("The Wayland owner is still active; player release remains with that owner")
            },
            { window?.let { w -> onEdt { savedTitle?.let { (w as? java.awt.Frame)?.title = it } } } },
            { render.shutdown() },
            { proxy?.close(); proxy = null }
        )
        if (errors.isNotEmpty()) throw IllegalStateException("The player could not complete every native cleanup step", errors.first())
    }
    private fun onEdt(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
}
/** A handle has one release action even if caller and owning native thread finish together. */
internal class DesktopPlayerRelease(private val releaseHandle: () -> Unit) {
    private val released = AtomicBoolean(false)
    fun release() { if (released.compareAndSet(false, true)) releaseHandle() }
}

/** All native context operations, including deferred teardown, stay on the thread that created it. */
internal class DesktopNativeOwner<T : Any>(
    private val create: () -> T,
    private val frames: (T) -> Unit,
    private val finish: (T) -> Unit,
    private val destroy: (T) -> Unit,
    private val player: DesktopPlayerRelease,
    private val failed: (Throwable) -> Unit = {}
) : Runnable {
    val created = CompletableFuture<T>()
    val completed = CompletableFuture<Unit>()
    private val started = AtomicBoolean(false)
    override fun run() {
        check(started.compareAndSet(false, true)) { "A native owner can only run once" }
        var context: T? = null
        var failure: Throwable? = null
        try {
            context = create()
            created.complete(context)
            frames(context)
        } catch (error: Throwable) {
            failure = error
            created.completeExceptionally(error)
            failed(error)
        } finally {
            val target = context
            var contextReleased = target == null
            val cleanup = runDesktopCleanupActions(
                { if (target != null) finish(target) },
                { if (target != null) destroy(target); contextReleased = true },
                { if (contextReleased) player.release() else error("Native context destruction was not confirmed; player retained") }
            )
            val error = failure ?: cleanup.firstOrNull()
            if (error == null) completed.complete(Unit) else completed.completeExceptionally(error)
        }
    }
}

/** Run every resource release; one native failure cannot leave an authenticated HTTP capability alive. */
internal fun runDesktopCleanupActions(vararg actions: () -> Unit): List<Throwable> = buildList {
    actions.forEach { action ->
        try { action() }
        catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            add(e)
        }
    }
}

private fun Mpv.optionIfInitialized(name: String, value: String) = set(name, value)

private interface DesktopWaylandApi : Library {
    fun ps_wayland_preflight(hdr: Int): Int
    fun ps_wayland_create(mpv: Pointer, receipt: String, hdr: Int): Pointer
    fun ps_wayland_pump(ctx: Pointer): Int
    fun ps_wayland_width(ctx: Pointer): Int
    fun ps_wayland_height(ctx: Pointer): Int
    fun ps_wayland_fullscreen(ctx: Pointer): Int
    fun ps_wayland_fullscreen_set(ctx: Pointer, value: Int)
    fun ps_wayland_title(ctx: Pointer, title: String)
    fun ps_wayland_ui(ctx: Pointer, data: ByteArray, width: Int, height: Int)
    fun ps_wayland_input(ctx: Pointer, event: IntArray): Int
    fun ps_wayland_close(ctx: Pointer)
}

/** A native Wayland video window. Its changing controls use the qualified SDR shm overlay; video stays on the GPU. */
private class LinuxPlayerWindow(
    private val mpv: Mpv, private val out: File, private val hdr: Boolean,
    private val pause: () -> Unit, private val skip: (Double) -> Unit, private val releasePlayer: () -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pictureshow-wayland-ui").apply { isDaemon = true } }
    private val running = AtomicBoolean(true)
    private val failure = AtomicReference<String?>(null)
    private val fullscreenRequest = AtomicReference<Boolean?>(null)
    private var job: Future<*>? = null
    private val initialized = CompletableFuture<Unit>()
    @Volatile var exitRequested = false
        private set
    fun start() {
        job = executor.submit {
            var context: Pointer? = null
            var scene: androidx.compose.ui.ImageComposeScene? = null
            try {
                require(!System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) { "This player requires a Wayland desktop" }
                val native = Native.load(System.getenv("SPIKE_WAYLAND_BRIDGE") ?: "pictureshow_wayland", DesktopWaylandApi::class.java)
                check(native.ps_wayland_preflight(if (hdr) 1 else 0) == 0) { "The compositor does not support this video output" }
                context = native.ps_wayland_create(mpv.handle, File(out, "native-wayland.txt").absolutePath, if (hdr) 1 else 0)
                native.ps_wayland_title(context, "Pictureshow — player")
                initialized.complete(Unit)
                var width = 0; var height = 0
                val paused = androidx.compose.runtime.mutableStateOf(false)
                var dirty = true
                var lastUpdate = System.nanoTime()
                val fakeComponent = java.awt.Canvas()
                val event = IntArray(3)
                while (running.get()) {
                    val pumped = native.ps_wayland_pump(context)
                    if (pumped == -3) { exitRequested = true; break }
                    check(pumped >= 0)
                    fullscreenRequest.getAndSet(null)?.let { native.ps_wayland_fullscreen_set(context, if (it) 1 else 0) }
                    val w = native.ps_wayland_width(context); val h = native.ps_wayland_height(context)
                    if (width != w || height != h) {
                        scene?.close(); width = w; height = h
                        scene = androidx.compose.ui.ImageComposeScene(width = w, height = h) {
                            androidx.compose.material.MaterialTheme(colors = androidx.compose.material.darkColors()) {
                                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xEE141820)).padding(12.dp)) {
                                        androidx.compose.material.Text("Playback controls · Space pause · ←/→ seek · F fullscreen · Esc return", color = androidx.compose.ui.graphics.Color.White)
                                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                            androidx.compose.material.OutlinedButton(onClick = { exitRequested = true }) { androidx.compose.material.Text("Back to title") }
                                            androidx.compose.material.Button(onClick = pause) { androidx.compose.material.Text(if (paused.value) "Play" else "Pause") }
                                            androidx.compose.material.OutlinedButton(onClick = { skip(-10.0) }) { androidx.compose.material.Text("Back 10s") }
                                            androidx.compose.material.OutlinedButton(onClick = { skip(10.0) }) { androidx.compose.material.Text("Forward 10s") }
                                            androidx.compose.material.OutlinedButton(onClick = { fullscreenRequest.set(native.ps_wayland_fullscreen(context) == 0) }) { androidx.compose.material.Text("Fullscreen") }
                                        }
                                        androidx.compose.material.Text("Choose audio, subtitles or the next episode in the main Pictureshow window.")
                                    }
                                }
                            }
                        }
                        dirty = true
                    }
                    while (native.ps_wayland_input(context, event) > 0) {
                        if (event[0] in 1..2) scene!!.sendPointerEvent(if (event[0] == 1) androidx.compose.ui.input.pointer.PointerEventType.Press else androidx.compose.ui.input.pointer.PointerEventType.Release,
                            androidx.compose.ui.geometry.Offset(event[1].toFloat(), event[2].toFloat()))
                        else {
                            val code = when (event[1]) { 1 -> java.awt.event.KeyEvent.VK_ESCAPE; 15 -> java.awt.event.KeyEvent.VK_TAB; 28 -> java.awt.event.KeyEvent.VK_ENTER; 33 -> java.awt.event.KeyEvent.VK_F; 57 -> java.awt.event.KeyEvent.VK_SPACE; 103 -> java.awt.event.KeyEvent.VK_UP; 105 -> java.awt.event.KeyEvent.VK_LEFT; 106 -> java.awt.event.KeyEvent.VK_RIGHT; 108 -> java.awt.event.KeyEvent.VK_DOWN; else -> 0 }
                            if (code != 0) {
                                val pressed = event[0] == 3
                                val awt = java.awt.event.KeyEvent(fakeComponent, if (pressed) java.awt.event.KeyEvent.KEY_PRESSED else java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                                val consumed = scene!!.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(awt))
                                if (pressed && !consumed) when (code) {
                                    java.awt.event.KeyEvent.VK_ESCAPE -> exitRequested = true
                                    java.awt.event.KeyEvent.VK_SPACE -> pause()
                                    java.awt.event.KeyEvent.VK_F -> fullscreenRequest.set(native.ps_wayland_fullscreen(context) == 0)
                                    java.awt.event.KeyEvent.VK_LEFT -> skip(-10.0)
                                    java.awt.event.KeyEvent.VK_RIGHT -> skip(10.0)
                                }
                            }
                        }
                        dirty = true
                    }
                    if (System.nanoTime() - lastUpdate > 500_000_000) { lastUpdate = System.nanoTime(); paused.value = mpv.get("pause") == "yes"; dirty = true }
                    val sc = scene!!
                    if (dirty || sc.hasInvalidations()) {
                        sc.render(System.nanoTime()).use { image ->
                            val bytes = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { it.bytes }
                            val bitmap = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                            val ints = bitmap.getRGB(0, 0, w, h, null, 0, w); val pixels = ByteArray(w * h * 4)
                            ints.forEachIndexed { n, argb -> val a = (argb ushr 24) and 255; val k = n * 4
                                pixels[k] = ((argb and 255) * a / 255).toByte(); pixels[k+1] = (((argb ushr 8) and 255) * a / 255).toByte(); pixels[k+2] = (((argb ushr 16) and 255) * a / 255).toByte(); pixels[k+3] = a.toByte()
                            }
                            native.ps_wayland_ui(context, pixels, w, h)
                        }; dirty = false
                    }
                    Thread.sleep(5)
                }
            } catch (_: Exception) { failure.set("The native Wayland player could not continue. Check libmpv and compositor support."); initialized.completeExceptionally(IllegalStateException("Wayland player could not initialize")) }
            finally {
                scene?.close()
                context?.let { Native.load(System.getenv("SPIKE_WAYLAND_BRIDGE") ?: "pictureshow_wayland", DesktopWaylandApi::class.java).ps_wayland_close(it) }
                // A timed-out caller must not free mpv while this native context is live.
                // Its owning thread completes the deferred release after context destruction.
                if (!running.get()) releasePlayer()
            }
        }
        initialized.get(40, TimeUnit.SECONDS)
    }
    fun fullscreen(enabled: Boolean) { fullscreenRequest.set(enabled) }
    fun error(): String? = failure.get()
    override fun close() { running.set(false); job?.get(40, TimeUnit.SECONDS); executor.shutdown() }
}
