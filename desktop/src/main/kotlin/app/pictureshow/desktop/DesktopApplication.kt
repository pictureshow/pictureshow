/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import app.pictureshow.ui.PictureshowTheme
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

fun desktopMain(args: Array<String>) {
    val smoke = args.firstOrNull() == "app-smoke"
    if(smoke) require(args.size == 5) { "Usage: app-smoke SERVER USER PASSWORD EVIDENCE" }
    // Disposable smoke confines the real UDP discovery implementation to its
    // owned loopback responder. Installed viewers retain normal LAN broadcast.
    val discoveryPort=if(smoke) System.getenv("PICTURESHOW_SMOKE_DISCOVERY_PORT")?.toInt() else null
    val discovery=if(discoveryPort!=null) app.pictureshow.core.JvmServerDiscovery(port=discoveryPort,targets={setOf(java.net.InetAddress.getByName("127.0.0.1"))}) else app.pictureshow.core.JvmServerDiscovery()
    val controller = DesktopController(discovery=discovery)
    application {
        val state = rememberWindowState(width=1280.dp,height=800.dp,position=if(smoke && System.getenv("PICTURESHOW_SMOKE_WINDOW_X")!=null) WindowPosition.Absolute(System.getenv("PICTURESHOW_SMOKE_WINDOW_X").toInt().dp,(System.getenv("PICTURESHOW_SMOKE_WINDOW_Y") ?: "0").toInt().dp) else WindowPosition.PlatformDefault)
        var closing by remember { mutableStateOf(false) }
        var full by remember { mutableStateOf(false) }
        var macChrome by remember { mutableStateOf(false) }
        val keys = remember { java.util.concurrent.atomic.AtomicReference<(androidx.compose.ui.input.key.KeyEvent) -> Boolean>({ false }) }
        var restore by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        fun close(source: String = "window") {
            if(closing) return
            closing=true
            fun receipt(complete: Boolean) { if(smoke) java.io.File(args[4], "close-lifecycle.json").writeText("{\"source\":\"$source\",\"shutdownComplete\":$complete}\n") }
            receipt(false)
            Thread {
                runBlocking { controller.shutdown() }
                receipt(true)
                SwingUtilities.invokeLater { exitApplication() }
            }.start()
        }
        Window(onCloseRequest={ close("window") },state=state,title="Pictureshow",undecorated=true,transparent=true,resizable=true,onPreviewKeyEvent={keys.get().invoke(it)}) {
            fun fullscreen() {
                val bounds=if(!full) { restore=java.awt.Rectangle(window.bounds);window.graphicsConfiguration.bounds } else restore ?: window.bounds
                state.placement=WindowPlacement.Floating
                state.position=WindowPosition.Absolute(bounds.x.dp,bounds.y.dp)
                state.size=DpSize(bounds.width.dp,bounds.height.dp)
                full=!full
                controller.fullscreen(full)
            }
            LaunchedEffect(Unit) {
                if(com.sun.jna.Platform.isMac()) java.awt.Desktop.getDesktop().requestForeground(true)
                window.minimumSize=Dimension(800,600)
                if(com.sun.jna.Platform.isMac()) {
                    macChrome = runCatching { configureDesktopMacChrome() }.getOrElse { recordDesktopPlayerFailure("window-chrome", it); false }
                    if(smoke) {
                        java.io.File(args[4]).mkdirs()
                        java.io.File(args[4], "window-chrome.json").writeText("{\"nativeMacChrome\":$macChrome}\n")
                    }
                }
                controller.attachWindow(window)
                controller.start()
                if(smoke) Thread { runDesktopSmoke(controller,window,state,args.drop(1)) { close("smoke") } }.apply { name="desktop-ui-smoke";isDaemon=true;start() }
            }
            DisposableEffect(window) {
                val listener=object:WindowAdapter() {
                    override fun windowIconified(e:WindowEvent) { controller.pauseForBackground() }
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener);controller.detachWindow() }
            }
            PictureshowTheme {
                DesktopApp(controller, ::fullscreen, full, { keys.set(it) }, macChrome,
                    chrome = { content -> WindowDraggableArea(Modifier.fillMaxWidth()) { content() } },
                    windowControls = {
                        if (!macChrome) {
                            TextButton(onClick={state.isMinimized=true},contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.widthIn(min=40.dp)) { Text("—",Modifier.clearAndSetSemantics { contentDescription="Minimize window" }) }
                            TextButton(onClick={state.placement=if(state.placement==WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized},contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.widthIn(min=40.dp)) { Text("□",Modifier.clearAndSetSemantics { contentDescription="Maximize or restore window" }) }
                            TextButton(onClick={ close("window-button") },contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.widthIn(min=40.dp)) { Text("×",Modifier.clearAndSetSemantics { contentDescription="Close window" }) }
                        }
                    })
            }
        }
    }
}

private interface DesktopMacChrome : com.sun.jna.Library {
    fun ps_window_find(title: String): com.sun.jna.Pointer?
    fun ps_window_desktop_chrome(window: com.sun.jna.Pointer): Int
    fun ps_window_info(window: com.sun.jna.Pointer): String
}
private fun configureDesktopMacChrome(): Boolean {
    val layer = System.getenv("SPIKE_LAYER_BRIDGE") ?: System.getProperty("spike.layer.bridge")
    val path = layer?.let { java.io.File(java.io.File(it).parentFile, "libpictureshow_window.dylib").absolutePath } ?: "pictureshow_window"
    val bridge = com.sun.jna.Native.load(path, DesktopMacChrome::class.java)
    return bridge.ps_window_find("Pictureshow")?.let { bridge.ps_window_desktop_chrome(it) == 1 } ?: false
}

internal fun recordDesktopMacChrome(file: java.io.File, window: java.awt.Window) {
    val layer = System.getenv("SPIKE_LAYER_BRIDGE") ?: System.getProperty("spike.layer.bridge") ?: return
    val path = java.io.File(java.io.File(layer).parentFile, "libpictureshow_window.dylib").absolutePath
    val bridge = com.sun.jna.Native.load(path, DesktopMacChrome::class.java)
    bridge.ps_window_find((window as? java.awt.Frame)?.title ?: "Pictureshow")?.let { file.writeText(bridge.ps_window_info(it)) }
}
