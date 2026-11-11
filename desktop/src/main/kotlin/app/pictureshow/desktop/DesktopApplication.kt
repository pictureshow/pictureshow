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
    val controller = DesktopController()
    application {
        val state = rememberWindowState(width=1280.dp,height=800.dp,position=if(smoke && System.getenv("PICTURESHOW_SMOKE_WINDOW_X")!=null) WindowPosition.Absolute(System.getenv("PICTURESHOW_SMOKE_WINDOW_X").toInt().dp,(System.getenv("PICTURESHOW_SMOKE_WINDOW_Y") ?: "0").toInt().dp) else WindowPosition.PlatformDefault)
        var closing by remember { mutableStateOf(false) }
        var full by remember { mutableStateOf(false) }
        val keys = remember { java.util.concurrent.atomic.AtomicReference<(androidx.compose.ui.input.key.KeyEvent) -> Boolean>({ false }) }
        var restore by remember { mutableStateOf<java.awt.Rectangle?>(null) }
        fun close() {
            if(closing) return
            closing=true
            Thread {
                runBlocking { controller.shutdown() }
                SwingUtilities.invokeLater { exitApplication() }
            }.start()
        }
        Window(onCloseRequest=::close,state=state,title="Pictureshow",undecorated=true,transparent=true,resizable=true,onPreviewKeyEvent={keys.get().invoke(it)}) {
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
                controller.attachWindow(window)
                controller.start()
                if(smoke) Thread { runDesktopSmoke(controller,window,state,args.drop(1),::close) }.apply { name="desktop-ui-smoke";isDaemon=true;start() }
            }
            DisposableEffect(window) {
                val listener=object:WindowAdapter() {
                    override fun windowIconified(e:WindowEvent) { controller.pauseForBackground() }
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener);controller.detachWindow() }
            }
            PictureshowTheme {
                Column(Modifier.fillMaxSize()) {
                    WindowDraggableArea(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.fillMaxWidth().height(32.dp).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("Pictureshow",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick={state.isMinimized=true},contentPadding=PaddingValues(horizontal=16.dp),modifier=Modifier.height(32.dp)) { Text("—",Modifier.clearAndSetSemantics { contentDescription="Minimize window" }) }
                            TextButton(onClick={state.placement=if(state.placement==WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized},contentPadding=PaddingValues(horizontal=16.dp),modifier=Modifier.height(32.dp)) { Text("□",Modifier.clearAndSetSemantics { contentDescription="Maximize or restore window" }) }
                            TextButton(onClick=::close,contentPadding=PaddingValues(horizontal=16.dp),modifier=Modifier.height(32.dp)) { Text("×",Modifier.clearAndSetSemantics { contentDescription="Close window" }) }
                        }
                    }
                    Box(Modifier.weight(1f)) { DesktopApp(controller,::fullscreen,full) { keys.set(it) } }
                }
            }
        }
    }
}
