/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import java.awt.event.InputEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.jna.Native
import java.util.concurrent.atomic.AtomicReference
import java.awt.event.MouseEvent
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.swing.JPanel
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    if (args.isEmpty() || args.firstOrNull() in setOf("app", "app-smoke")) { desktopMain(args); return }
    if (args.firstOrNull() == "wayland") { waylandMain(args.drop(1).toTypedArray()); return }
    if (args.firstOrNull() == "window") { windowMain(args.drop(1).toTypedArray()); return }
    if (args.firstOrNull() == "canvas") { canvasMain(args.drop(1).toTypedArray()); return }
    if (args.firstOrNull() == "layer") { layerMain(args.drop(1).toTypedArray()); return }
    require(args.size >= 2) { "Usage: <render|wid> <media-file> [evidence-dir] [--auto]" }
    val shape = args[0]
    require(shape in setOf("render", "wid", "canvas"))
    val media = File(args[1]).absolutePath
    val out = File(args.getOrNull(2) ?: "desktop-evidence")
    val auto = args.contains("--auto")
    System.setProperty("compose.interop.blending", "true")
    val mpv = Mpv(out)
    var gl: VideoSurface? = null
    val frame = mutableStateOf<ImageBitmap?>(null)
    val failure = AtomicReference<Throwable?>(null)
    var started = false
    var overlayBounds: androidx.compose.ui.geometry.Rect? = null
    Thread { Thread.sleep(90000); System.err.println("Probe exceeded 90 seconds"); Runtime.getRuntime().halt(3) }.apply { isDaemon = true }.start()
    application {
        val state = rememberWindowState(width = 960.dp, height = 640.dp)
        var status by remember { mutableStateOf("Starting $shape") }
        var clicks by remember { mutableStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        fun stop(code: Int = 0) {
            Thread {
                var result = code
                try { gl?.close(); mpv.close() } catch (e: Throwable) { failure.compareAndSet(null, e); result = 2 }
                failure.get()?.let { File(out, "failure.txt").writeText(it.stackTraceToString()); result = 2 }
                if (result == 0 && auto) File(out, "sequence.txt").writeText("overlayClicks=$clicks\n")
                kotlin.system.exitProcess(result)
            }.start()
        }

        Window(onCloseRequest = { stop() }, title = "Pictureshow · $shape surface probe", state = state) {
            MaterialTheme(colors = darkColors()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF141820))) {
                    Text("Pictureshow desktop · $shape · prototype", Modifier.padding(12.dp), color = Color.White)
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                        if (shape == "render") {
                            frame.value?.let { Image(it, "Locally decoded video", Modifier.fillMaxSize().onSizeChanged { s -> gl?.width = s.width; gl?.height = s.height }, contentScale = ContentScale.Fit) }
                            LaunchedEffect(Unit) {
                                Thread {
                                    try {
                                        mpv.option("vo", "libmpv"); mpv.initialize()
                                        gl = if (System.getenv("SPIKE_D3D_BRIDGE") != null) D3dSurface(mpv, { frame.value = it }, { failure.set(it) }) else GlSurface(mpv, { frame.value = it }, { failure.set(it) })
                                        mpv.command("loadfile", media)
                                        started = true
                                    } catch (e: Throwable) { failure.set(e); status = "FAILED: ${e.message}"; e.printStackTrace() }
                                }.start()
                            }
                        } else if (shape == "canvas") {
                            SwingPanel(factory = { GlCanvasSurface(mpv, media, { failure.set(it) }).also { gl = it; started = true }.panel }, modifier = Modifier.fillMaxSize())
                        } else {
                            SwingPanel(factory = {
                                JPanel(BorderLayout()).apply {
                                    val canvas = Canvas().apply { background = java.awt.Color.BLACK }
                                    add(canvas, BorderLayout.CENTER)
                                    canvas.addHierarchyListener {
                                        if (canvas.isDisplayable && !started) {
                                            started = true
                                            val handle = Native.getComponentPointer(canvas)
                                            Thread {
                                                try {
                                                    mpv.option("wid", com.sun.jna.Pointer.nativeValue(handle).toString())
                                                    mpv.option("vo", "gpu-next")
                                                    mpv.initialize(); mpv.command("loadfile", media)
                                                } catch (e: Throwable) { failure.set(e); status = "FAILED: ${e.message}"; e.printStackTrace() }
                                            }.start()
                                        }
                                    }
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                        if (shape == "canvas") {
                            Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(-16, 16), properties = PopupProperties(focusable = false)) {
                                Button(onClick = { clicks++; mpv.command("cycle", "pause") }) { Text("COMPOSE OVER VIDEO · $clicks") }
                            }
                        }
                        // Intentional same-window overlap: a native child may occlude this.
                        Row(Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color(0xDD174E72)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(modifier = Modifier.onGloballyPositioned { overlayBounds = it.boundsInWindow() }, onClick = { clicks++; mpv.command("cycle", "pause") }) { Text("OVER VIDEO · $clicks") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { paused = !paused; mpv.set("pause", if (paused) "yes" else "no") }) { Text(if (paused) "Play" else "Pause") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { if (com.sun.jna.Platform.isMac()) toggleMacFullscreen(window) else state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen }) { Text("Fullscreen") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { mpv.command("cycle", "sub") }) { Text("Subtitle") }
                        Text(status, Modifier.padding(start = 12.dp), color = Color.White)
                    }
                }
            }
            LaunchedEffect(Unit) {
                Thread {
                    try {
                    Thread.sleep(1000)
                    if (com.sun.jna.Platform.isMac()) SwingUtilities.invokeAndWait {
                        runCatching { java.awt.Desktop.getDesktop().requestForeground(true) }
                        window.toFront(); window.requestFocus()
                    }
                    fun stage(name: String) {
                        failure.get()?.let { throw it }
                        Thread.sleep(1200)
                        SwingUtilities.invokeAndWait {
                            val bounds = window.bounds
                            val location = window.locationOnScreen
                            val capture = Robot().createScreenCapture(java.awt.Rectangle(location.x, location.y, bounds.width, bounds.height))
                            jpeg(capture, File(out, "$name.jpg"))
                            mpv.dump(name, "window=${bounds.width}x${bounds.height}; placement=${state.placement}; screen=${window.graphicsConfiguration.bounds}; undecorated=${window.isUndecorated}; density=${window.graphicsConfiguration.defaultTransform.scaleX}; overlayClicks=$clicks")
                        }
                    }
                    Thread.sleep(5000)
                    status = "${mpv.get("video-format")} · ${mpv.get("hwdec-current")} · ${mpv.get("audio-params/channels")}"
                    if (auto) {
                        stage("01-window")
                        SwingUtilities.invokeAndWait {
                            if (shape == "canvas") {
                                java.awt.Window.getWindows().filter { it !== window && it.isVisible && it.width > 50 }.lastOrNull()?.let { popup ->
                                    val p=popup.locationOnScreen
                                    Robot().apply { mouseMove(p.x+popup.width/2,p.y+popup.height/2);mousePress(InputEvent.BUTTON1_DOWN_MASK);mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                                }
                            } else overlayBounds?.let { b ->
                                val origin = window.contentPane.locationOnScreen
                                val transform = window.graphicsConfiguration.defaultTransform
                                Robot().apply {
                                    mouseMove(origin.x + (b.center.x / transform.scaleX).toInt(), origin.y + (b.center.y / transform.scaleY).toInt())
                                    mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                }
                            }
                        }
                        stage("01-overlay-click")
                        if (clicks == 0 && shape == "render") {
                            // Same-window AWT event delivery also works without macOS global input permission.
                            SwingUtilities.invokeAndWait {
                                overlayBounds?.let { b ->
                                    val transform = window.graphicsConfiguration.defaultTransform
                                    val x = (b.center.x / transform.scaleX).toInt(); val y = (b.center.y / transform.scaleY).toInt()
                                    val target = SwingUtilities.getDeepestComponentAt(window.contentPane, x, y)
                                    if (target != null) {
                                        val point = SwingUtilities.convertPoint(window.contentPane, x, y, target)
                                        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                                            target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), 0, point.x, point.y, 1, false, MouseEvent.BUTTON1))
                                        }
                                    }
                                }
                            }
                            stage("01-local-event-click")
                        }
                        check(mpv.get("vo-configured") == "yes") { "No configured video output" }
                        check(mpv.get("video-params/w")?.toIntOrNull() == 640) { "No decoded fixture video" }
                        mpv.set("pause", "no")
                        SwingUtilities.invokeAndWait { state.size = androidx.compose.ui.unit.DpSize(740.dp, 500.dp) }
                        stage("02-resize")
                        SwingUtilities.invokeAndWait { if (com.sun.jna.Platform.isMac()) toggleMacFullscreen(window) else state.placement = WindowPlacement.Fullscreen }
                        Thread.sleep(2500)
                        stage("03-fullscreen")
                        SwingUtilities.invokeAndWait { if (com.sun.jna.Platform.isMac()) toggleMacFullscreen(window) else state.placement = WindowPlacement.Floating }; Thread.sleep(2000)
                        stage("03-restored")
                        val count = mpv.get("track-list/count")?.toIntOrNull() ?: 0
                        val subtitles = (0 until count).filter { mpv.get("track-list/$it/type") == "sub" }
                        check(subtitles.size == 3) { "Expected SRT, ASS and PGS tracks" }
                        subtitles.forEachIndexed { n, track ->
                            val sid = requireNotNull(mpv.get("track-list/$track/id"))
                            mpv.set("pause", "yes")
                            mpv.set("sid", sid)
                            // Reload avoids a fixture seek/index defect from masquerading as subtitle failure.
                            mpv.command("loadfile", media)
                            Thread.sleep(500)
                            mpv.set("sid", sid)
                            mpv.set("pause", "no")
                            stage("04-sub-${n + 1}")
                            check(mpv.get("track-list/$track/selected") == "yes") { "Subtitle did not select: $sid" }
                        }
                        mpv.dump("final", "completed sequence")
                        stop()
                    }
                    } catch (e: Throwable) {
                        File(out, "failure.txt").writeText(e.stackTraceToString())
                        e.printStackTrace()
                        stop(2)
                    }
                }.apply { isDaemon = true }.start()
            }
        }
    }
}

internal fun jpeg(image: BufferedImage, file: File) {
    val w = minOf(800, image.width)
    val h = image.height * w / image.width
    val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    scaled.createGraphics().apply { drawImage(image, 0, 0, w, h, null); dispose() }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    ImageIO.createImageOutputStream(file).use { output ->
        writer.output = output
        val params = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = .5f }
        writer.write(null, IIOImage(scaled, null, null), params)
    }
    writer.dispose()
}

// Apple's public JDK desktop extensions marshal the operation to AWT's AppKit thread.
// Reflection keeps the same JVM artifact usable on Windows and Linux.
private fun toggleMacFullscreen(window: java.awt.Window) {
    Class.forName("com.apple.eawt.FullScreenUtilities")
        .getMethod("setWindowCanFullScreen", java.awt.Window::class.java, java.lang.Boolean.TYPE)
        .invoke(null, window, true)
    val appClass = Class.forName("com.apple.eawt.Application")
    val app = appClass.getMethod("getApplication").invoke(null)
    appClass.getMethod("requestToggleFullScreen", java.awt.Window::class.java).invoke(app, window)
}
