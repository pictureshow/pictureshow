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
    require(args.size >= 2) { "Usage: <render|wid> <media-file> [evidence-dir] [--auto]" }
    val shape = args[0]
    require(shape in setOf("render", "wid"))
    val media = File(args[1]).absolutePath
    val out = File(args.getOrNull(2) ?: "desktop-evidence")
    val auto = args.contains("--auto")
    System.setProperty("compose.interop.blending", "true")
    val mpv = Mpv(out)
    var gl: GlSurface? = null
    val frame = mutableStateOf<ImageBitmap?>(null)
    var started = false
    var overlayBounds: androidx.compose.ui.geometry.Rect? = null
    Thread { Thread.sleep(90000); System.err.println("Probe exceeded 90 seconds"); Runtime.getRuntime().halt(3) }.apply { isDaemon = true }.start()
    application {
        val state = rememberWindowState(width = 960.dp, height = 640.dp)
        var status by remember { mutableStateOf("Starting $shape") }
        var clicks by remember { mutableStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        fun stop() {
            Thread {
                gl?.close()
                mpv.close()
                SwingUtilities.invokeLater { exitApplication() }
            }.start()
        }
        Window(onCloseRequest = { stop() }, title = "Pictureshow · $shape surface probe", state = state) {
            MaterialTheme(colors = darkColors()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF141820))) {
                    Text("Pictureshow desktop · $shape · prototype", Modifier.padding(12.dp))
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                        if (shape == "render") {
                            frame.value?.let { Image(it, "Locally decoded video", Modifier.fillMaxSize().onSizeChanged { s -> gl?.width = s.width; gl?.height = s.height }, contentScale = ContentScale.Fit) }
                            LaunchedEffect(Unit) {
                                Thread {
                                    try {
                                        mpv.option("vo", "libmpv"); mpv.initialize()
                                        gl = GlSurface(mpv) { frame.value = it }
                                        mpv.command("loadfile", media)
                                        started = true
                                    } catch (e: Throwable) { status = "FAILED: ${e.message}"; e.printStackTrace() }
                                }.start()
                            }
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
                                                } catch (e: Throwable) { status = "FAILED: ${e.message}"; e.printStackTrace() }
                                            }.start()
                                        }
                                    }
                                }
                            }, modifier = Modifier.fillMaxSize())
                        }
                        // Intentional same-window overlap: a native child may occlude this.
                        Row(Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color(0xDD174E72)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(modifier = Modifier.onGloballyPositioned { overlayBounds = it.boundsInWindow() }, onClick = { clicks++; mpv.command("cycle", "pause") }) { Text("OVER VIDEO · $clicks") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { paused = !paused; mpv.set("pause", if (paused) "yes" else "no") }) { Text(if (paused) "Play" else "Pause") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen }) { Text("Fullscreen") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { mpv.command("cycle", "sub") }) { Text("Subtitle") }
                        Text(status, Modifier.padding(start = 12.dp))
                    }
                }
            }
            LaunchedEffect(Unit) {
                Thread {
                    fun stage(name: String) {
                        Thread.sleep(1200)
                        SwingUtilities.invokeAndWait {
                            val bounds = window.bounds
                            val location = window.locationOnScreen
                            val capture = Robot().createScreenCapture(java.awt.Rectangle(location.x, location.y, bounds.width, bounds.height))
                            jpeg(capture, File(out, "$name.jpg"))
                            mpv.dump(name, "window=${bounds.width}x${bounds.height}; placement=${state.placement}; overlayClicks=$clicks")
                        }
                    }
                    Thread.sleep(5000)
                    status = "${mpv.get("video-format")} · ${mpv.get("hwdec-current")} · ${mpv.get("audio-params/channels")}"
                    if (auto) {
                        stage("01-window")
                        SwingUtilities.invokeAndWait {
                            overlayBounds?.let { b ->
                                val origin = window.contentPane.locationOnScreen
                                Robot().apply {
                                    mouseMove(origin.x + b.center.x.toInt(), origin.y + b.center.y.toInt())
                                    mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                                }
                            }
                        }
                        stage("01-overlay-click")
                        mpv.set("pause", "no")
                        SwingUtilities.invokeAndWait { state.size = androidx.compose.ui.unit.DpSize(740.dp, 500.dp) }
                        stage("02-resize")
                        SwingUtilities.invokeAndWait { state.placement = WindowPlacement.Fullscreen }
                        stage("03-fullscreen")
                        SwingUtilities.invokeAndWait { state.placement = WindowPlacement.Floating }
                        for (sid in 1..3) { mpv.set("sid", sid.toString()); mpv.command("seek", "2", "absolute+exact"); stage("04-sub-$sid") }
                        mpv.dump("final", "completed sequence")
                        File(out, "sequence.txt").writeText("overlayClicks=$clicks\n")
                        stop()
                    }
                }.apply { isDaemon = true }.start()
            }
        }
    }
}

private fun jpeg(image: BufferedImage, file: File) {
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
