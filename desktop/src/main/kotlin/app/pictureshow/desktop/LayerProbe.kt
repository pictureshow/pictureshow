/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.sun.jna.*
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

interface NativeLayer : Library {
    fun ps_create(view: Pointer, mpv: Pointer): Pointer?
    fun ps_frame(surface: Pointer, width: Int, height: Int): Int
    fun ps_receipt(surface: Pointer): String
    fun ps_sync(surface: Pointer)
    fun ps_fullscreen(surface: Pointer, enabled: Int)
    fun ps_destroy(surface: Pointer)
}

/** Separate SDR Compose controls and native HDR video presentation, joined by the OS compositor.
 * No decoded frame traverses BufferedImage, Compose ImageBitmap or JVM memory.
 */
fun layerMain(args: Array<String>) {
    require(args.size >= 2) { "Usage: layer MEDIA EVIDENCE [--sustain=125]" }
    val media=File(args[0]).absolutePath
    val out=File(args[1]).apply { mkdirs() }
    val sustain=args.firstOrNull { it.startsWith("--sustain=") }?.substringAfter('=')?.toInt() ?: 0
    val bridge=Native.load(requireNotNull(System.getenv("SPIKE_LAYER_BRIDGE")),NativeLayer::class.java)
    val mpv=Mpv(out)
    val executor=Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"native-video") }
    val failure=AtomicReference<Throwable?>(null)
    val stopping=AtomicBoolean(false)
    var surface:Pointer?=null
    var tick:java.util.concurrent.ScheduledFuture<*>?=null
    var pixelWidth=960; var pixelHeight=640
    var overlayBounds:androidx.compose.ui.geometry.Rect?=null
    Thread { Thread.sleep((sustain+120L)*1000); Runtime.getRuntime().halt(3) }.apply {isDaemon=true}.start()
    application {
        val state=rememberWindowState(width=960.dp,height=640.dp)
        var clicks by remember { mutableStateOf(0) }
        fun stop(code:Int=0) {
            if(!stopping.compareAndSet(false,true)) return
            Thread {
                var rc=code
                try {
                    tick?.cancel(false)
                    executor.submit { surface?.let(bridge::ps_destroy); surface=null }.get(30,TimeUnit.SECONDS)
                    executor.shutdown(); mpv.close()
                    failure.get()?.let { throw it }
                    File(out,"teardown.txt").writeText("native render freed before mpv destroy; executor stopped\n")
                    if(rc==0) File(out,"sequence.txt").writeText("overlayClicks=$clicks\n")
                } catch(e:Throwable) { File(out,"failure.txt").writeText(e.stackTraceToString()); rc=2 }
                kotlin.system.exitProcess(rc)
            }.start()
        }
        Window(onCloseRequest={stop()},state=state,title="Pictureshow native HDR surface",undecorated=true,transparent=true) {
            MaterialTheme(colors=darkColors()) {
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.align(Alignment.TopEnd).padding(18.dp).background(Color(0xE0174E72)).padding(8.dp)) {
                        Button(modifier=Modifier.onGloballyPositioned { overlayBounds=it.boundsInWindow() },onClick={ clicks++; mpv.command("cycle","pause") }) { Text("COMPOSE OVER VIDEO · $clicks") }
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD141820)).padding(8.dp)) {
                        Button(onClick={mpv.command("cycle","pause")}) {Text("Pause / play")}
                        Spacer(Modifier.width(8.dp))
                        Button(onClick={state.placement=if(state.placement==WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen}) {Text("Fullscreen")}
                        Spacer(Modifier.width(8.dp))
                        Button(onClick={stop()}) {Text("Close")}
                    }
                }
            }
            LaunchedEffect(Unit) {
                Thread {
                    try {
                        Thread.sleep(1800)
                        SwingUtilities.invokeAndWait { java.awt.Desktop.getDesktop().requestForeground(true); window.toFront(); window.requestFocus() }
                        mpv.option("vo","libmpv")
                        // Render linear BT.2020 into a float target. 1.0 is the SDR reference white.
                        mpv.option("target-prim",if(Platform.isMac()) "bt.2020" else "bt.709"); mpv.option("target-trc","linear"); mpv.option("target-peak","1000")
                        mpv.initialize()
                        val view=Native.getWindowPointer(window)
                        executor.submit { surface=requireNotNull(bridge.ps_create(view,mpv.handle)) {"Native layer creation failed"} }.get(40,TimeUnit.SECONDS)
                        fun sync() { SwingUtilities.invokeAndWait {
                            val transform=window.graphicsConfiguration.defaultTransform
                            pixelWidth=(window.width*transform.scaleX).toInt(); pixelHeight=(window.height*transform.scaleY).toInt()
                            bridge.ps_sync(surface!!)
                        } }
                        sync()
                        tick=executor.scheduleWithFixedDelay({try {
                            check(bridge.ps_frame(surface!!,pixelWidth,pixelHeight)>=0) {"Native frame failed"}
                        }catch(e:Throwable){failure.compareAndSet(null,e); tick?.cancel(false)}},0,1,TimeUnit.MILLISECONDS)
                        mpv.command("loadfile",media)
                        Thread.sleep(4500)
                        fun stage(name:String) {
                            sync(); Thread.sleep(1000); failure.get()?.let {throw it}
                            File(out,"$name-native.json").writeText(bridge.ps_receipt(surface!!))
                            SwingUtilities.invokeAndWait {
                                val p=window.locationOnScreen; val b=window.bounds; val screen=window.graphicsConfiguration.bounds
                                jpeg(Robot().createScreenCapture(java.awt.Rectangle(p.x,p.y,b.width,b.height)),File(out,"$name.jpg"))
                                mpv.dump(name,"window=$b; screen=$screen; undecorated=${window.isUndecorated}; placement=${state.placement}; overlayClicks=$clicks")
                            }
                        }
                        stage("01-window")
                        SwingUtilities.invokeAndWait {overlayBounds?.let { b ->
                            val origin=window.contentPane.locationOnScreen; val d=window.graphicsConfiguration.defaultTransform
                            Robot().apply { mouseMove(origin.x+(b.center.x/d.scaleX).toInt(),origin.y+(b.center.y/d.scaleY).toInt()); mousePress(InputEvent.BUTTON1_DOWN_MASK); mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
                        }}
                        stage("02-global-click")
                        if(clicks==0) {
                            SwingUtilities.invokeAndWait {overlayBounds?.let { b ->
                                val d=window.graphicsConfiguration.defaultTransform; val x=(b.center.x/d.scaleX).toInt(); val y=(b.center.y/d.scaleY).toInt()
                                val target=SwingUtilities.getDeepestComponentAt(window.contentPane,x,y)!!
                                val p=SwingUtilities.convertPoint(window.contentPane,x,y,target)
                                for(id in listOf(MouseEvent.MOUSE_PRESSED,MouseEvent.MOUSE_RELEASED)) target.dispatchEvent(MouseEvent(target,id,System.currentTimeMillis(),0,p.x,p.y,1,false,MouseEvent.BUTTON1))
                            }}
                            stage("02-local-click")
                        }
                        check(clicks>0) {"Compose overlay did not receive input"}
                        mpv.set("pause","no")
                        SwingUtilities.invokeAndWait {state.size=androidx.compose.ui.unit.DpSize(740.dp,500.dp)}
                        stage("03-resize")
                        SwingUtilities.invokeAndWait {state.placement=WindowPlacement.Fullscreen; bridge.ps_fullscreen(surface!!,1)}
                        Thread.sleep(2500); stage("04-fullscreen")
                        SwingUtilities.invokeAndWait {state.placement=WindowPlacement.Floating; bridge.ps_fullscreen(surface!!,0)}
                        Thread.sleep(2000); stage("05-restored")
                        val tracks=mpv.get("track-list/count")!!.toInt()
                        val subtitles=(0 until tracks).filter {mpv.get("track-list/$it/type")=="sub"}
                        for((n,track) in subtitles.withIndex()) {
                            mpv.command("loadfile",media); Thread.sleep(500)
                            mpv.set("sid",mpv.get("track-list/$track/id")!!); mpv.set("pause","no")
                            stage("06-sub-${n+1}")
                        }
                        if(sustain>0) {
                            mpv.set("pause","no"); stage("07-sustain-start")
                            val started=System.nanoTime()
                            repeat(sustain) {Thread.sleep(1000); failure.get()?.let{throw it}}
                            File(out,"sustained-seconds.txt").writeText(((System.nanoTime()-started)/1e9).toString())
                            stage("08-sustain-end")
                        }
                        stop()
                    } catch(e:Throwable) {failure.compareAndSet(null,e); e.printStackTrace(); stop(2)}
                }.start()
            }
        }
    }
}
